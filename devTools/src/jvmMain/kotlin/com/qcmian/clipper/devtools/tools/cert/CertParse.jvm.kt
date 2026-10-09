package com.qcmian.clipper.devtools.tools.cert

import com.qcmian.clipper.devtools.tools.apksign.storeTypeFromMagic
import com.qcmian.clipper.devtools.tools.apksign.toCertInfo
import com.qcmian.clipper.devtools.tools.apksign.toHex
import com.qcmian.clipper.devtools.tools.base64.Base64Format
import java.io.ByteArrayInputStream
import java.io.File
import java.io.StringReader
import java.security.Key
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.CRLReason
import java.security.cert.CertificateFactory
import java.security.cert.X509CRL
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import javax.crypto.EncryptedPrivateKeyInfo
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.Extensions
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo

/**
 * 证书工具的**平台**那一半：把 PEM / DER / PKCS#7 / PKCS#12 / JKS，以及证书请求 / 吊销列表 /
 * 密钥，解析成一条条 [CertItem]。
 *
 * 分工是刻意的：**证书、PKCS#7、CRL 走 JDK**（`CertificateFactory`）——它给得出 `SHA256withRSA`
 * 这种可读的算法名与 keytool 那套扩展排版；**证书请求与密钥走 bouncycastle**——PKCS#10 与
 * PKCS#1 / SEC1 / 加密 PKCS#8 这些 JDK 根本没有公开 API（`sun.security` 那几套 JDK 17 起连反射
 * 都进不去）。两边的 `CertInfo` 转换是同一处实现（`toCertInfo`），`apksign` 那边也在用。
 *
 * 一行密码学也没有：ASN.1、X.509、PKCS#7、PKCS#10 全交给库，这里只把它们接起来。
 */

/** 单个文件读入内存的上限：证书与密钥库都不该有这么大，超过就不是本工具该接的输入。 */
private const val MaxCertFileBytes = 16L * 1024 * 1024

/** 判「这是不是文本」时只看开头这一小段。 */
private const val TextSniffBytes = 8_000

/** 裸 Base64 至少要这么长：再短的解出来也不可能是一份证书类对象。 */
private const val MinBareBase64Chars = 64

internal actual fun parseCertInput(input: CertInput): CertParseResult = runCatching {
    when (input) {
        is CertInput.Text -> parseText(input.text, input.password)
        is CertInput.File -> parseFile(input.path, input.password)
    }
}.getOrElse { CertParseResult.Failed(it.rootMessage().ifBlank { "无法解析这段输入" }) }

/** 一次 PEM 块解析的三种下场：解出了条目 / 这个 label 本工具不认 / 解的过程中出错。 */
private sealed interface BlockResult {
    class Items(val items: List<CertItem>) : BlockResult

    class Unknown(val label: String) : BlockResult

    class Failure(val message: String, val needsPassword: Boolean = false) : BlockResult
}

/**
 * 粘贴进来的一段文本：先按 PEM 块逐块解，找不到 PEM 块再当成一整段裸 Base64（DER）。
 *
 * 一份 `.pem` 里常混着好几种块（证书 + 私钥 + 中间证书），因此**逐块攒起来**而不是「解出第一张
 * 就收工」；认不出的块只记下 label，别的地方解出来了照样显示。
 */
private fun parseText(text: String, password: String?): CertParseResult {
    val blocks = CertFormat.extractPemBlocks(text)
    if (blocks.isNotEmpty()) {
        val items = mutableListOf<CertItem>()
        val unknownLabels = mutableListOf<String>()
        blocks.forEach { block ->
            when (val parsed = parsePemBlock(block, password)) {
                is BlockResult.Items -> items.addAll(parsed.items)
                is BlockResult.Unknown -> unknownLabels.add(parsed.label)
                is BlockResult.Failure -> return CertParseResult.Failed(parsed.message, parsed.needsPassword)
            }
        }
        if (items.isNotEmpty()) return CertParseResult.Ok(CertDocument("PEM", items))
        if (unknownLabels.isNotEmpty()) return CertParseResult.Failed(CertFormat.unrecognizedMessage(unknownLabels))
    }

    val compact = text.filterNot { it.isWhitespace() }
    if (compact.length >= MinBareBase64Chars) {
        Base64Format.decode(compact).getOrNull()?.let { der ->
            documentFromDer(der, password)?.let { return it }
        }
    }
    return CertParseResult.Failed(CertFormat.notACertificateHint())
}

/** 一个文件：文本的按文本读，二进制的依次当 DER 对象 / PKCS#7 / 密钥库试。 */
private fun parseFile(path: String, password: String?): CertParseResult {
    val bytes = readAllBytes(path)
        ?: return CertParseResult.Failed("读不到这个文件：它不存在、是目录、无权限，或超过 16 MB。")
    val text = bytes.takeIf(::looksTextual)?.decodeToString()
    // 文本类先走文本：PEM / Base64 的判据是确定性的，比「按 DER 试一遍」准。
    if (text != null && CertFormat.looksLikeCertificate(text)) return parseText(text, password)
    documentFromDer(bytes, password)?.let { return it }
    if (text != null) return parseText(text, password)
    return parseKeyStore(bytes, password)
}

/** 一段 PEM 块按 label 分派。 */
private fun parsePemBlock(block: CertFormat.PemBlock, password: String?): BlockResult {
    val label = block.label.uppercase()
    val der = Base64Format.decode(block.base64).getOrElse {
        return BlockResult.Failure("PEM 里的 Base64 解不开（${block.label}）：${it.message.orEmpty()}")
    }

    // 证书与 PKCS#7 走 JDK：算法名可读、扩展的排版与 `keytool -printcert` 一字不差。
    when {
        label in CertFormat.CertificateLabels -> {
            val certificate = readCertificate(der)
                ?: return BlockResult.Failure("PEM 里这张证书读不出来（${block.label}）")
            return BlockResult.Items(listOf(certificateItem(certificate)))
        }

        label == CertFormat.Pkcs7Label -> {
            val certificates = readCertificates(der)
                ?: return BlockResult.Failure(CertFormat.emptyBlockMessage(block.label))
            return BlockResult.Items(certificates.map(::certificateItem))
        }

        label == CrlLabel -> {
            val crl = readCrl(der) ?: return BlockResult.Failure(CertFormat.emptyBlockMessage(block.label))
            return BlockResult.Items(listOf(crlItem(crl, CertFormat.pemBlock(block.label, block.base64))))
        }
    }

    // 余下的（证书请求 / 密钥）交给 bouncycastle：PKCS#10 与 PKCS#1 / SEC1 / 加密 PKCS#8 都由它认，
    // 自己按 label 分派反而要为重编码各写一支。PEM 文字用原始块重建，左栏因此显示的是原样。
    return parsePemObject(block, password)
}

/**
 * 用 bouncycastle 解一块 PEM。
 *
 * 交给 `PEMParser` 而不是自带 label→类型表：它认识 PKCS#1 / SEC1 / PKCS#8 / 加密 PKCS#8 /
 * PKCS#10 各自的标准写法，并统一还回对应的对象；自己维护那张表只会跟着新写法漂。
 */
private fun parsePemObject(block: CertFormat.PemBlock, password: String?): BlockResult {
    val pem = CertFormat.pemBlock(block.label, block.base64)
    val parsed = runCatching { PEMParser(StringReader(pem)).use { it.readObject() } }.getOrNull()
        ?: return BlockResult.Unknown(block.label)
    val encoding = encodingOf(block.label)
    return when (parsed) {
        is PKCS10CertificationRequest -> BlockResult.Items(listOf(requestItem(parsed, pem)))

        is PEMKeyPair -> {
            val key = runCatching { converter().getKeyPair(parsed).private }.getOrNull()
                ?: return BlockResult.Failure("这把私钥读不出来（${block.label}）")
            BlockResult.Items(listOf(privateKeyItem(key, encoding, pem)))
        }

        is PrivateKeyInfo -> {
            val key = runCatching { converter().getPrivateKey(parsed) }.getOrNull()
                ?: return BlockResult.Failure("这把私钥读不出来（${block.label}）")
            BlockResult.Items(listOf(privateKeyItem(key, encoding, pem)))
        }

        is PKCS8EncryptedPrivateKeyInfo -> encryptedKeyResult(parsed, block, pem, password)

        is SubjectPublicKeyInfo -> {
            val key = runCatching { converter().getPublicKey(parsed) }.getOrNull()
                ?: return BlockResult.Failure("这把公钥读不出来（${block.label}）")
            BlockResult.Items(listOf(publicKeyItem(key, encoding, pem)))
        }

        // `RSA PUBLIC KEY`（PKCS#1 那种）在 BC 里还回裸的 `SEQUENCE{modulus, exponent}`，不是 SPKI；
        // 按 rsaEncryption 包一层再交给转换器。
        is org.bouncycastle.asn1.pkcs.RSAPublicKey -> {
            val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.rsaEncryption, DERNull.INSTANCE)
            val key = runCatching { converter().getPublicKey(SubjectPublicKeyInfo(algorithm, parsed)) }.getOrNull()
                ?: return BlockResult.Failure("这把公钥读不出来（${block.label}）")
            BlockResult.Items(listOf(publicKeyItem(key, encoding, pem)))
        }

        else -> BlockResult.Unknown(block.label)
    }
}

/** 加密私钥：没给口令就说「要口令」，给了口令就解开（解不开说明口令不对）。 */
private fun encryptedKeyResult(
    encrypted: PKCS8EncryptedPrivateKeyInfo,
    block: CertFormat.PemBlock,
    pem: String,
    password: String?,
): BlockResult {
    val algorithmName = encryptionAlgorithmName(encrypted)
    if (password.isNullOrEmpty()) {
        // 读得出「这是加密的 PKCS#8、用的什么算法」，但算法与位数要解密后才知道。
        return BlockResult.Items(listOf(encryptedKeyItem(algorithmName, pem)))
    }
    val key = runCatching {
        val provider = BouncyCastleProvider()
        val decryptor = JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider(provider).build(password.toCharArray())
        JcaPEMKeyConverter().setProvider(provider).getPrivateKey(encrypted.decryptPrivateKeyInfo(decryptor))
    }.getOrNull() ?: return BlockResult.Failure(CertFormat.wrongPrivateKeyPasswordMessage(), needsPassword = true)
    return BlockResult.Items(listOf(privateKeyItem(key, encodingOf(block.label), pem)))
}

/** 一段 DER 能当什么：按「越具体越靠前」的顺序试；都不是返回 `null`。 */
private fun documentFromDer(der: ByteArray, password: String?): CertParseResult? {
    readCertificate(der)?.let { return CertParseResult.Ok(CertDocument("DER", listOf(certificateItem(it)))) }
    readCertificates(der)?.let { return CertParseResult.Ok(CertDocument("PKCS#7", it.map(::certificateItem))) }
    readCrl(der)?.let {
        return CertParseResult.Ok(CertDocument("DER", listOf(crlItem(it, CertFormat.pemBlock(CrlLabel, der.base64())))))
    }
    readRequest(der)?.let { request ->
        val pem = CertFormat.pemBlock(RequestLabel, der.base64())
        return CertParseResult.Ok(CertDocument("DER", listOf(requestItem(request, pem))))
    }
    readPrivateKeyInfo(der)?.let { info ->
        val key = runCatching { converter().getPrivateKey(info) }.getOrNull()
        // 裸 DER 的私钥是 PKCS#8（PKCS#1 / SEC1 只在 PEM 里以传统 label 出现）。
        if (key != null) {
            return CertParseResult.Ok(
                CertDocument("DER", listOf(privateKeyItem(key, "PKCS#8", CertFormat.pemBlock("PRIVATE KEY", der.base64())))),
            )
        }
    }
    readPublicKeyInfo(der)?.let { info ->
        val key = runCatching { converter().getPublicKey(info) }.getOrNull()
        if (key != null) {
            return CertParseResult.Ok(
                CertDocument("DER", listOf(publicKeyItem(key, "X.509 (SPKI)", CertFormat.pemBlock("PUBLIC KEY", der.base64())))),
            )
        }
    }
    return null
}

/**
 * 把这段字节当密钥库读。
 *
 * 容器类型**按魔数认**（`storeTypeFromMagic`：只有 JKS 有固定魔数，其余一律先当 PKCS#12，让
 * `KeyStore.load` 去说它不是），与 `apksign` 那边是同一套判据。
 *
 * 「读不出证书」有两种：口令挡着（`needsPassword`），或者确实没有证书——这两种对用户是两件事，
 * 分开说（PKCS#12 不给口令时 `getCertificate` 直接返回 `null`，实测如此）。
 */
private fun parseKeyStore(bytes: ByteArray, password: String?): CertParseResult {
    val type = storeTypeFromMagic(bytes.copyOf(minOf(8, bytes.size)))
    val store = runCatching {
        KeyStore.getInstance(type).apply {
            ByteArrayInputStream(bytes).use { load(it, password?.toCharArray()) }
        }
    }.getOrElse { return keyStoreFailure(it, password, type) }

    val seen = mutableSetOf<String>()
    val certificates = mutableListOf<X509Certificate>()
    store.aliases().toList().sorted().forEach { alias ->
        store.certificatesOf(alias).forEach { certificate ->
            // 链上后几张会在别的别名下重复出现（同一条根、中间证书），按 DER 去重。
            if (seen.add(Base64Format.encode(certificate.encoded, urlSafe = false))) {
                certificates.add(certificate)
            }
        }
    }

    if (certificates.isEmpty()) {
        return if (password.isNullOrEmpty()) {
            CertParseResult.Failed(CertFormat.lockedKeyStoreMessage(type), needsPassword = true)
        } else {
            CertParseResult.Failed("这个密钥库里没有可读的证书。")
        }
    }
    return CertParseResult.Ok(
        CertDocument(if (type.equals("JKS", ignoreCase = true)) "JKS" else "PKCS#12", certificates.map(::certificateItem)),
    )
}

/**
 * 一个别名下的整条证书链。
 *
 * 必须读**整条**而不是 `getCertificate`：后者只给第一张，用它就等于把中间证书与根证书扔掉，而
 * 「链完不完整」正是看证书时最要紧的一件事。`getCertificateChain` 对**只放证书的条目**给 `null`，
 * 那时退回单张。
 */
private fun KeyStore.certificatesOf(alias: String): List<X509Certificate> {
    val fromChain = runCatching { getCertificateChain(alias) }.getOrNull()
    val list = fromChain?.toList() ?: listOfNotNull(runCatching { getCertificate(alias) }.getOrNull())
    return list.filterIsInstance<X509Certificate>()
}

// ---------------------------------------------------------------- 条目构造

private fun certificateItem(certificate: X509Certificate): CertificateItem =
    CertificateItem(certificate.toCertInfo(), certificate.pemOf())

private fun crlItem(crl: X509CRL, pem: String): CrlItem = CrlItem(crlInfoOf(crl), pem)

private fun requestItem(request: PKCS10CertificationRequest, pem: String): RequestItem =
    RequestItem(requestInfoOf(request), pem)

private fun privateKeyItem(key: PrivateKey, encoding: String, pem: String): KeyItem =
    KeyItem(
        KeyInfo(true, keyAlgorithmName(key), key.keySizeBits(), encoding, encrypted = false, encryptionAlgorithm = null),
        pem,
    )

private fun publicKeyItem(key: PublicKey, encoding: String, pem: String): KeyItem =
    KeyItem(
        KeyInfo(false, keyAlgorithmName(key), key.keySizeBits(), encoding, encrypted = false, encryptionAlgorithm = null),
        pem,
    )

/**
 * 密钥算法名统一成与证书那边一致的写法。
 *
 * bouncycastle 对 EC 密钥报的是 `ECDSA`（`BCECPrivateKey.getAlgorithm()`），而证书里的公钥、JDK
 * 的 `ECPrivateKey` 报的都是 `EC`。同一份输出里两种写法并存，读的人会以为不是一回事。
 */
private fun keyAlgorithmName(key: Key): String = when (val name = key.algorithm) {
    "ECDSA" -> "EC"
    else -> name
}

/** 加密私钥还没解开时的那一项：算法与位数读不出来，但「加密了」与加密算法读得出来。 */
private fun encryptedKeyItem(encryptionAlgorithm: String?, pem: String): KeyItem =
    KeyItem(KeyInfo(true, "", 0, "PKCS#8", encrypted = true, encryptionAlgorithm = encryptionAlgorithm), pem)

// ---------------------------------------------------------------- 平台对象 → 信息

/** 一张证书的 PEM 原文，逐行折到 64 列。 */
private fun X509Certificate.pemOf(): String =
    CertFormat.pemBlock("CERTIFICATE", Base64Format.encode(encoded, urlSafe = false))

@Suppress("DEPRECATION") // 与证书那边同一条理由：要的就是 keytool 打的那一串（见 `ApkSign.jvm.kt`）。
private fun crlInfoOf(crl: X509CRL): CrlInfo = CrlInfo(
    issuer = crl.issuerDN.name,
    thisUpdateMillis = crl.thisUpdate.time,
    nextUpdateMillis = crl.nextUpdate?.time,
    signatureAlgorithm = crl.sigAlgName,
    version = crl.version,
    // 按序列号排：`getRevokedCertificates()` 给的是 `Set`，顺序不定，同一张 CRL 两次读出来不一样。
    revoked = crl.revokedCertificates.orEmpty()
        .sortedBy { it.serialNumber }
        .map { entry ->
            RevokedEntry(
                serial = entry.serialNumber.toString(16),
                revokedAtMillis = entry.revocationDate.time,
                reason = entry.revocationReason?.let(::crlReasonText),
            )
        },
)

/**
 * 一个证书请求上的信息。
 *
 * 主体经 JDK 的 [X500Principal] 过一道再取 `.name`：bouncycastle 的 `X500Name.toString()` 打的是
 * `CN=demo,O=Demo,C=CN`（逗号后**没有**空格），而证书那边用的是 JDK 那一份（**带**空格）。同一份
 * 输出里两种写法并存，读的人会以为不是一回事。
 */
private fun requestInfoOf(request: PKCS10CertificationRequest): RequestInfo {
    val publicKey = runCatching { converter().getPublicKey(request.subjectPublicKeyInfo) }.getOrNull()
    return RequestInfo(
        subject = runCatching { X500Principal(request.subject.encoded).name }.getOrDefault(request.subject.toString()),
        publicKeyAlgorithm = publicKey?.algorithm.orEmpty(),
        publicKeyBits = publicKey?.keySizeBits() ?: 0,
        signatureAlgorithm = signatureAlgorithmName(request.signatureAlgorithm.algorithm.id),
        selfSigned = requestSelfSigned(request),
        extensionsText = requestExtensionsText(request),
    )
}

/** 请求自带的签名验过没有（它签的是自己的主体+公钥，所以用请求里的公钥验）。 */
private fun requestSelfSigned(request: PKCS10CertificationRequest): Boolean = runCatching {
    val provider = JcaContentVerifierProviderBuilder().setProvider(BouncyCastleProvider())
        .build(request.subjectPublicKeyInfo)
    request.isSignatureValid(provider)
}.getOrDefault(false)

/**
 * 请求里请求的那些扩展。
 *
 * 排法与证书那一段刻意不同：证书走 JDK 的 `toString()`（公共 API 拿不到扩展正文，只能切它的），
 * 而请求没有那道现成的排版，于是自己排——`OID (critical=…)` 一行，认得出来的（SAN）解码成人看的
 * 名字，认不出的给一行十六进制（`openssl req -text` 也是这么干的）。
 */
private fun requestExtensionsText(request: PKCS10CertificationRequest): String {
    val extensionRequest = request.getAttributes(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest)
        .firstOrNull() ?: return ""
    val extensions = runCatching {
        Extensions.getInstance(extensionRequest.attrValues.getObjectAt(0))
    }.getOrNull() ?: return ""
    val oids = extensions.extensionOIDs
    if (oids.isEmpty()) return ""
    return buildString {
        oids.forEachIndexed { index, oid ->
            val extension = extensions.getExtension(oid)
            appendLine("#${index + 1}: ${oid.id} (critical=${extension.isCritical})")
            val names = runCatching {
                if (oid == Extension.subjectAlternativeName) {
                    GeneralNames.getInstance(extension.parsedValue).names.joinToString("\n  ") { generalNameText(it) }
                } else {
                    null
                }
            }.getOrNull()
            if (names != null) {
                appendLine("  $names")
            } else {
                appendLine("  " + extension.extnValue.octets.toHex(separator = " ", upperCase = true))
            }
        }
    }
}

private fun generalNameText(name: GeneralName): String {
    val value = name.name?.toString().orEmpty()
    return when (name.tagNo) {
        GeneralName.dNSName -> "DNS: $value"
        GeneralName.iPAddress -> "IP: $value"
        GeneralName.rfc822Name -> "Email: $value"
        GeneralName.uniformResourceIdentifier -> "URI: $value"
        GeneralName.directoryName -> "目录名: $value"
        else -> value
    }
}

/** CRL 的吊销原因。JDK 给的是枚举，这里翻成 keytool 那套中文说法。 */
private fun crlReasonText(reason: CRLReason): String = when (reason) {
    CRLReason.UNSPECIFIED -> "未指定"
    CRLReason.KEY_COMPROMISE -> "密钥泄露"
    CRLReason.CA_COMPROMISE -> "CA 泄露"
    CRLReason.AFFILIATION_CHANGED -> "隶属关系变更"
    CRLReason.SUPERSEDED -> "被取代"
    CRLReason.CESSATION_OF_OPERATION -> "停止使用"
    CRLReason.CERTIFICATE_HOLD -> "证书挂起"
    CRLReason.UNUSED -> "未使用"
    CRLReason.REMOVE_FROM_CRL -> "从 CRL 移除"
    CRLReason.PRIVILEGE_WITHDRAWN -> "权限撤销"
    CRLReason.AA_COMPROMISE -> "属性机构泄露"
}

/**
 * 公钥 / 私钥的位数。
 *
 * 只认签名实际会用的那两种（RSA / EC）。Ed25519 这类**刻意返回 0**：它的强度不是「多少位模数」
 * 那回事，报一个数字出来只会误导，界面那边见 0 就只打算法名。
 */
private fun Key.keySizeBits(): Int = when (this) {
    is RSAPublicKey -> modulus.bitLength()
    is RSAPrivateKey -> modulus.bitLength()
    is ECPublicKey -> params.order.bitLength()
    is ECPrivateKey -> params.order.bitLength()
    else -> 0
}

/**
 * PEM label → 编码方式的说法。
 *
 * 这是**给用户看的话**，所以不直接用 label 原文：`RSA PRIVATE KEY` 这种写法要懂 PKCS#1 才看得懂，
 * 说成编码名反而更清楚。
 */
private fun encodingOf(label: String): String = when (label.uppercase()) {
    "RSA PRIVATE KEY" -> "PKCS#1"
    "EC PRIVATE KEY" -> "SEC1"
    "DSA PRIVATE KEY" -> "传统 DSA"
    "ENCRYPTED PRIVATE KEY", "PRIVATE KEY" -> "PKCS#8"
    "RSA PUBLIC KEY" -> "PKCS#1"
    "PUBLIC KEY" -> "X.509 (SPKI)"
    else -> label
}

/** 加密私钥用的是什么算法：先问 JDK（它给可读的名字），问不出来退回 BC 的 OID。 */
private fun encryptionAlgorithmName(encrypted: PKCS8EncryptedPrivateKeyInfo): String? =
    runCatching { EncryptedPrivateKeyInfo(encrypted.encoded).algName }.getOrNull()
        ?: encrypted.encryptionAlgorithm.algorithm.id

/** 签名算法的 OID → 名字。表里没有就原样给 OID——编不出名字时，OID 至少是准确的。 */
private fun signatureAlgorithmName(oid: String): String = when (oid) {
    "1.2.840.113549.1.1.5" -> "SHA1withRSA"
    "1.2.840.113549.1.1.11" -> "SHA256withRSA"
    "1.2.840.113549.1.1.12" -> "SHA384withRSA"
    "1.2.840.113549.1.1.13" -> "SHA512withRSA"
    "1.2.840.113549.1.1.4" -> "MD5withRSA"
    "1.2.840.10045.4.1" -> "SHA1withECDSA"
    "1.2.840.10045.4.3.2" -> "SHA256withECDSA"
    "1.2.840.10045.4.3.3" -> "SHA384withECDSA"
    "1.2.840.10045.4.3.4" -> "SHA512withECDSA"
    "1.3.101.112" -> "Ed25519"
    "1.3.101.113" -> "Ed448"
    else -> oid
}

// ---------------------------------------------------------------- 读字节

private fun readCertificate(der: ByteArray): X509Certificate? = runCatching {
    CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as? X509Certificate
}.getOrNull()

private fun readCertificates(der: ByteArray): List<X509Certificate>? = runCatching {
    CertificateFactory.getInstance("X.509")
        .generateCertificates(ByteArrayInputStream(der))
        .filterIsInstance<X509Certificate>()
}.getOrNull()?.takeIf { it.isNotEmpty() }

private fun readCrl(der: ByteArray): X509CRL? = runCatching {
    CertificateFactory.getInstance("X.509").generateCRL(ByteArrayInputStream(der)) as? X509CRL
}.getOrNull()

private fun readRequest(der: ByteArray): PKCS10CertificationRequest? =
    runCatching { PKCS10CertificationRequest(der) }.getOrNull()

private fun readPrivateKeyInfo(der: ByteArray): PrivateKeyInfo? =
    runCatching { PrivateKeyInfo.getInstance(der) }.getOrNull()

private fun readPublicKeyInfo(der: ByteArray): SubjectPublicKeyInfo? =
    runCatching { SubjectPublicKeyInfo.getInstance(der) }.getOrNull()

private fun ByteArray.base64(): String = Base64Format.encode(this, urlSafe = false)

private fun readAllBytes(path: String): ByteArray? = runCatching {
    val file = File(path)
    if (!file.isFile || file.length() > MaxCertFileBytes) return@runCatching null
    file.readBytes()
}.getOrNull()

/**
 * 这段字节像不像文本。
 *
 * 判据是**「每一个字节都是可见 ASCII 或空白」**，而不是只看有没有 NUL。只看 NUL 会漏：一小段
 * PKCS#12 完全可能一个 NUL 都不含（实测一个只放证书的 `.p12` 就撞过），于是被当成文本送去解析，
 * 报出一句牛头不对马嘴的「看不出这是证书」。PEM 与 Base64 都只用 ASCII，收严一点不会误伤，
 * DER / 密钥库这类二进制则必定踩到不可见字节。
 */
private fun looksTextual(bytes: ByteArray): Boolean {
    val limit = minOf(bytes.size, TextSniffBytes)
    for (index in 0 until limit) {
        val value = bytes[index].toInt() and 0xFF
        if (value == 0x09 || value == 0x0A || value == 0x0D) continue
        if (value in 0x20..0x7E) continue
        return false
    }
    return true
}

// ---------------------------------------------------------------- 平台适配

/** 转 JCA 对象时统一的转换器。**不注册全局 JCA 提供方**：BC 只以实例形式给这几个转换器用。 */
private fun converter(): JcaPEMKeyConverter = JcaPEMKeyConverter().setProvider(BouncyCastleProvider())

/**
 * 读密钥库失败时说什么。
 *
 * 用户最卡的正是分不清「口令不对」「这东西根本不是密钥库」这两件事，所以先扒到最里层的 `cause`
 * 再判（外层常常只是一句泛泛的 `IOException`）。判据是**异常里说了什么**而不是异常类型：同一件事
 * JDK 在不同版本里给的异常类型并不一样。
 */
private fun keyStoreFailure(error: Throwable, password: String?, type: String): CertParseResult.Failed {
    val message = error.rootMessage()
    val text = message.lowercase()
    val passwordProblem = text.contains("password") || text.contains("integrity") ||
        text.contains("tampered") || text.contains("decrypt")
    return when {
        passwordProblem && password.isNullOrEmpty() ->
            CertParseResult.Failed(CertFormat.lockedKeyStoreMessage(type), needsPassword = true)

        passwordProblem ->
            CertParseResult.Failed("口令不正确：请检查这个密钥库的口令。", needsPassword = true)

        // 既不是 DER 证书类对象（上面已排除），也不是能读的密钥库——多半用户拖错了文件。
        text.contains("invalid keystore") || text.contains("unrecognized") ||
            text.contains("der") || text.contains("tag") ->
            CertParseResult.Failed("这份文件既不是证书类对象，也不是密钥库（PKCS#12 / JKS）。")

        text.contains("no such file") || text.contains("not found") ->
            CertParseResult.Failed("文件不存在。")

        else -> CertParseResult.Failed(message.ifBlank { "无法读取这个密钥库。" })
    }
}

/** 最里层的原因，最多扒 8 层（防自引用的异常链把界面拖死）。 */
private fun Throwable.rootMessage(): String {
    var current: Throwable = this
    repeat(8) {
        current = current.cause ?: return current.message.orEmpty()
    }
    return current.message.orEmpty()
}

/** PEM 里表示吊销列表的 label。 */
private const val CrlLabel = "X509 CRL"

/** 重建 DER 输入那一栏的 PEM 时用的 label（这些 JSON/openssl 的标准写法）。 */
private const val RequestLabel = "CERTIFICATE REQUEST"
