package com.qcmian.clipper.devtools.tools.apksign

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import com.android.apksig.KeyConfig
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.UnrecoverableKeyException
import java.security.cert.Certificate
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.ECGenParameterSpec
import java.text.DateFormat
import java.util.Date
import java.util.zip.ZipException
import javax.security.auth.x500.X500Principal
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

/**
 * Android 签名工具的**平台**那一半：JDK 的 `KeyStore` 加 AOSP 的 apksig。
 *
 * 收得很小——读、验、签三件事，别的什么都不做。指纹排版、日期显示、文案全在 `ApkSign.kt` 那一侧，
 * 因此这里没有一行字符串是「给用户看的话」，只有**把异常翻成人话**这一处例外（JDK 与 apksig 抛
 * 出来的东西没有人看得懂，而这层是唯一能同时看到异常类型与上下文的地方）。
 *
 * apksig 就是 `apksigner` 的内核，纯 Java、零传递依赖：桌面端因此不必装 Android SDK 也能签名，
 * 而且**签出来的包与 `apksigner` 命令行签的完全一样**（同一个实现）。
 */

/** 读一个密钥库（见 `ApkSign.kt` 里对 `password` 为 `null` 的说明）。 */
internal actual fun readKeyStore(
    path: String,
    storeType: String?,
    password: CharArray?,
): KeyStoreOutcome = runCatching {
    val actualType = storeType ?: storeTypeFromMagic(headOf(path))
    val store = loadKeyStore(path, actualType, password)
    KeyStoreOutcome.Ready(
        storeType = actualType,
        providerName = store.provider.name,
        // 口令为 `null` 就是「这次没给口令」。界面靠它把「证书被锁住」与「压根没有证书」分开说：
        // PKCS#12 不给口令时 `getCertificate` 会返回 `null`（证书也在加密的 safe bag 里），照着
        // 「读不到就是没有」去说，会对着一个好好的密钥库喊「这个条目没有证书」（实测见类注释）。
        readWithoutPassword = password == null,
        entries = store.aliases().toList().map { alias ->
            // 读**整条**链，而不是 `getCertificate`（那个只给第一张，后面几张直接扔了）。
            // 每张各自带一套摘要与扩展正文（`toCertInfo` 里取）。
            val chain = store.certificateChainOf(alias)
            KeyStoreEntry(
                alias = alias,
                isKeyEntry = store.isKeyEntry(alias),
                chain = chain.mapNotNull { (it as? X509Certificate)?.toCertInfo() },
                creationDateMillis = store.creationDateOf(alias),
            )
        },
    )
}.getOrElse { KeyStoreOutcome.Failed(it.readableKeyStoreMessage()) }

/**
 * 新建一个密钥库。
 *
 * 三步都是现成的东西：JDK 生成密钥对 → bcpkix 签一张自签证书 → JDK 写进密钥库。**自己没有一行
 * 密码学**：ASN.1、X.509 结构、签名算法都交给库，我们只负责把它们串起来。
 *
 * 为什么非得引 bcpkix：JDK **没有**公开的「造一张 X.509 证书」API——能干这事儿的 `CertAndKeyGen`
 * 与 `X509CertImpl` 都在不导出的 `sun.security.x509` 里，JDK 17 起连反射都进不去
 * （`InaccessibleObjectException`），要走它就得给用户的应用加 `--add-exports`。
 *
 * **不注册全局 JCA 提供方**：BC 只以实例形式传给那两处用它的构建器（`setProvider(bc)`），进程里
 * 其它地方的 `Security.getProvider` 一行都不改——为一个功能改全局的算法解析顺序不划算。
 */
internal actual fun createKeyStore(request: KeyStoreRequest): CreateOutcome = runCatching {
    // 已有文件一律不盖：里面可能是一把**再也拿不回来**的私钥。系统保存对话框自己会问一次「要覆盖吗」，
    // 但这一页的路径框是能**手敲 / 粘贴**的——那条路不过对话框，也就没人问过。
    if (File(request.outPath).exists()) {
        return CreateOutcome.Failed(
            "这个位置已经有文件了：${request.outPath}。" +
                "密钥库盖掉就找不回来，换个名字，或先把旧文件挪走",
        )
    }

    val keyPair = generateKeyPair(request.algorithm)
    val now = System.currentTimeMillis()
    // 主题**必须**借 JDK 的 `X500Principal` 过一道，不能直接 `X500Name(字符串)`。
    //
    // 坑在顺序上：RFC 2253 的字符串写法是**倒着**写的（最具体的在前：`CN, O, C`），而 DER 里的
    // SEQUENCE 是正序（`C, O, CN`）。JDK 按规范两头都倒，所以 `keytool -dname "CN=…, O=…, C=…"`
    // 出来的证书显示回去仍是 `CN=…, O=…, C=…`；而 `X500Name(String)` 是**照给的顺序**直接编码成
    // `CN, O, C` 的——结果就是证书里顺序反了，JDK 显示出来成了 `C=CN, O=Acme, CN=demo`。实测踩到过。
    //
    // 用 `X500Principal(字符串).encoded` 就把这一步交回给 JDK（它按规范编码），既修了顺序，也顺带
    // 让写错的主题在这一行就抛出来（`IllegalArgumentException`，由 `readableCreateMessage` 翻成人话）。
    val subject = X500Name.getInstance(X500Principal(request.subject).encoded)

    val builder = X509v3CertificateBuilder(
        subject,
        // 序列号随机 64 位。自己签自己，没有「谁给的」这回事，但它**不能是常数**：各家平台拿它
        // 当这张证书的标识（吊销列表、日志、`--print-certs` 里的那一行都是它）。
        BigInteger(64, SecureRandom()),
        Date(now),
        Date(validityEndMillis(now, request.validityDays)),
        subject,
        SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
    ).apply {
        // 这三条跟 keytool `-genkeypair` 默认写的一致：不是 CA、用途是数字签名、带自己的
        // SubjectKeyIdentifier。Android 那边认的就是这一组。
        addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
        addExtension(
            Extension.subjectKeyIdentifier,
            false,
            JcaX509ExtensionUtils().createSubjectKeyIdentifier(keyPair.public),
        )
    }

    val bc = BouncyCastleProvider()
    val signer = JcaContentSignerBuilder(request.algorithm.signatureAlgorithm)
        .setProvider(bc)
        .build(keyPair.private)
    val certificate = JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(signer))

    val store = KeyStore.getInstance(request.storeType)
    store.load(null, null)
    // store 与 key 同一个口令：`PKCS#12` 格式上就要求如此（见 `KeyStoreRequest` 的说明）。
    store.setKeyEntry(request.alias, keyPair.private, request.password, arrayOf(certificate))
    File(request.outPath).outputStream().use { store.store(it, request.password) }
    CreateOutcome.Done(request.outPath)
}.getOrElse { CreateOutcome.Failed(it.readableCreateMessage()) }

/** 密钥对：RSA 给长度，EC 给曲线名（`secp256r1` 等）。用 JDK 的 `KeyPairGenerator`，不劳 BC。 */
private fun generateKeyPair(algorithm: KeyAlgorithm): KeyPair {
    val generator = KeyPairGenerator.getInstance(algorithm.keyPairAlgorithm)
    val curve = algorithm.curve
    if (curve == null) {
        generator.initialize(algorithm.keySize)
    } else {
        generator.initialize(ECGenParameterSpec(curve))
    }
    return generator.generateKeyPair()
}

/**
 * 建密钥库失败时的说明。
 *
 * 这一步的坑很集中：写不进去（目录不在 / 没权限 / 那是个目录）、主题写不成 DN。前者 JDK 给的原话
 * 还看得懂，后者是一条 `IllegalArgumentException` 带一串偏移量，得自己说。
 *
 * 刻意**没有**「口令太短」这一支：那条 6 位的下限是 keytool 与 Android Studio 的约定，不是
 * `KeyStore` 的——实测 `setKeyEntry` + `store` 用 3 位口令照样写得进去（见 `ApkSignLogicTest`）。
 * 所以把关在**表单**那一层（[keyStoreRequestProblem]），这里不编一条不会发生的错。
 */
private fun Throwable.readableCreateMessage(): String {
    val root = rootCause()
    return when {
        root is IOException -> "写不进这个位置：${root.message.orEmpty()}"

        root is IllegalArgumentException ->
            "主题写得不对，它要像 `CN=名字, O=组织, C=CN` 这样（${root.message.orEmpty()}）"

        else -> root.message?.takeIf { it.isNotBlank() } ?: root::class.simpleName.orEmpty()
    }
}

/** 验一个 APK 的签名（`apksigner verify --print-certs` 的等价物）。 */
@Suppress("DEPRECATION")
internal actual fun verifyApk(path: String): VerifyOutcome = runCatching {
    val result = ApkVerifier.Builder(File(path)).build().verify()
    // 密钥轮换过的包会带一条「证书链」（lineage），普通包没有。有链时取链上**末位**——那是当前
    // 真正在签的那一张，也正是 `--print-certs` 报的那张；没有链（绝大多数包）时用
    // `signerCertificates`，那是按签名块报出来的一组。
    val lineage = result.signingCertificateLineage
    val certificates = lineage?.certificatesInLineage?.lastOrNull()?.let(::listOf)
        ?: result.signerCertificates
    VerifyOutcome.Ready(
        verified = result.isVerified,
        schemes = buildSet {
            if (result.isVerifiedUsingV1Scheme) add(SignScheme.V1)
            if (result.isVerifiedUsingV2Scheme) add(SignScheme.V2)
            if (result.isVerifiedUsingV3Scheme) add(SignScheme.V3)
        },
        signers = certificates.map { SignerInfo(it.toCertInfo()) },
        // `ApkVerificationIssue` 没有 `getMessage()`，它把「模板 + 参数」都塞在 `toString()` 里
        // ——`apksigner` 打出来的那一行正是它。
        errors = result.errors.map { it.toString() },
        warnings = result.warnings.map { it.toString() },
    )
}.getOrElse { VerifyOutcome.Failed(it.readableVerifyMessage()) }

/** 给一个 APK 签名（见 `ApkSign.kt` 里对 [SignRequest] 的说明）。 */
internal actual fun signApk(request: SignRequest): SignOutcome = runCatching {
    val store = loadKeyStore(request.keystorePath, request.storeType, request.storePassword)
    val key = store.getKey(request.keyAlias, request.keyPassword) as? PrivateKey
        ?: throw SignFailure("别名「${request.keyAlias}」下没有能取出来的私钥")
    val chain = store.getCertificateChain(request.keyAlias)
        .orEmpty()
        .filterIsInstance<X509Certificate>()
    if (chain.isEmpty()) throw SignFailure("别名「${request.keyAlias}」下没有证书链")

    // 走 `KeyConfig.Jca` 这一支而不是直接塞 `PrivateKey` 的那个重载：后者已被标记废弃（apksig 把
    // 「本机私钥」与「云端 KMS 里的私钥」拆成了两支），而这里用的正是本机这一支。
    val signer = ApkSigner.SignerConfig.Builder(request.keyAlias, KeyConfig.Jca(key), chain).build()
    ApkSigner.Builder(listOf(signer))
        .setInputApk(File(request.apkPath))
        .setOutputApk(File(request.outPath))
        .setV1SigningEnabled(SignScheme.V1 in request.schemes)
        .setV2SigningEnabled(SignScheme.V2 in request.schemes)
        .setV3SigningEnabled(SignScheme.V3 in request.schemes)
        .build()
        .sign()
    SignOutcome.Done(request.outPath)
}.getOrElse { SignOutcome.Failed(it.readableSignMessage()) }

/**
 * 能直接给用户看的失败：消息本身就是那句话。
 *
 * 用它而不是到处拼字符串：`runCatching` 那条链上只剩一个 `Throwable`，而「别名下没有私钥」这种
 * 结论是**查过之后才知道**的，不是异常自带的——不套一层类型，它到下面就只剩一句 `IllegalStateException`。
 */
private class SignFailure(message: String) : Exception(message)

/** 开一个密钥库。[storeType] 为 `null` 时按魔数认（见 [storeTypeFromMagic]）。 */
private fun loadKeyStore(path: String, storeType: String?, password: CharArray?): KeyStore {
    val actualType = storeType ?: storeTypeFromMagic(headOf(path))
    val store = KeyStore.getInstance(actualType)
    File(path).inputStream().use { store.load(it, password) }
    return store
}

/**
 * 文件开头的几个字节。
 *
 * 读不出来的文件在这里**不抛**：让它以空数组往下走，由 `KeyStore.getInstance` / `load` 去报错——
 * 那句「不是 JKS / PKCS#12」比这里能编出来的任何一句都准确。
 */
private fun headOf(path: String): ByteArray = runCatching {
    File(path).inputStream().use { input ->
        val buffer = ByteArray(8)
        var read = 0
        while (read < buffer.size) {
            val count = input.read(buffer, read, buffer.size - read)
            if (count <= 0) break
            read += count
        }
        buffer.copyOf(read)
    }
}.getOrDefault(ByteArray(0))

@Suppress("DEPRECATION") // 见下：要的就是这一份（keytool 打的那一份），新 API 给的是另一种写法。
private fun X509Certificate.toCertInfo(): CertInfo = CertInfo(
    // 用**弃用**的 `getSubjectDN()` 而不是 `getSubjectX500Principal()`：两者给的不是同一个字符串
    // ——实测同一张证书，前者是 `CN=demo, O=Demo, C=CN`（逗号后**带空格**，keytool 打的就是它），
    // 后者是 `CN=demo,O=Demo,C=CN`。这份输出的用途就是跟 keytool 逐字对照，所以取它那一份。
    subject = subjectDN.name,
    issuer = issuerDN.name,
    // 序列号用**小写**十六进制、不带前导零——`keytool` 报的就是这个写法（它用的是
    // `BigInteger.toString(16)`）。`X509CertImpl.toString()` 里那种 `00:8c:50:…` 的大写补零写法
    // 是另一处，两者不要混。
    serial = serialNumber.toString(16),
    notBeforeMillis = notBefore.time,
    notAfterMillis = notAfter.time,
    signatureAlgorithm = sigAlgName,
    // 这两个读的都是**证书里的公钥**（不是私钥，私钥的属性证书里压根没有）。
    publicKeyAlgorithm = publicKey.algorithm,
    publicKeyBits = publicKey.keySizeBits(),
    version = version,
    digests = toDigests(),
    extensionsText = extensionsTextOf(),
)

/**
 * 一个条目上的整条证书链，**叶在前**。
 *
 * `getCertificateChain` 对**只放证书的条目**（trustedCertEntry）返回 `null` 而不是单元素数组
 * ——JDK 的实现里它只认私钥条目（`if (entry instanceof PrivateKeyEntry)`）。所以拿不到时退回
 * `getCertificate`，把它当成长度 1 的链：「这个条目有证书、只是没有链」与「这个条目没有证书」
 * 是两件事，混成后一句就成了上一版那个假话（把链上的后几张直接扔掉）。
 */
private fun KeyStore.certificateChainOf(alias: String): List<Certificate> {
    getCertificateChain(alias)?.let { return it.toList() }
    return listOfNotNull(getCertificate(alias))
}

/**
 * 这个条目**被写进密钥库**的时刻（`keytool` 的「创建日期」）。
 *
 * 它不是证书的生效时间，是密钥库自己记的一件事。接口上并不保证给得出来，所以取不到就返回
 * `null`，由界面决定不打那一行——**编一个日期出来比不打更坏**。
 */
private fun KeyStore.creationDateOf(alias: String): Long? =
    runCatching { getCreationDate(alias)?.time }.getOrNull()

/** 公钥位数。只认签名实际会用的那两种（RSA / EC），别的给 0 而不是瞎猜。 */
private fun PublicKey.keySizeBits(): Int = when (this) {
    is RSAPublicKey -> modulus.bitLength()
    is ECPublicKey -> params.order.bitLength()
    else -> 0
}

/**
 * 一张证书的两枚摘要。
 *
 * 对**整张证书的 DER**（`getEncoded()`）取摘要：各家平台文档里写的那串指纹（Google、微信、
 * Firebase）指的都是证书指纹，不是公钥的。只要 `keytool` 会给的那两枚。
 */
private fun X509Certificate.toDigests(): CertDigests {
    val encoded = encoded
    return CertDigests(
        sha1 = MessageDigest.getInstance("SHA-1").digest(encoded),
        sha256 = MessageDigest.getInstance("SHA-256").digest(encoded),
    )
}

/** `X509CertImpl.toString()` 里每条扩展的开头：`[1]: ObjectId: 2.5.29.35 Criticality=false`。 */
private val ExtensionIndexLine = Regex("""^\[(\d+)]: """)

/**
 * 「扩展」那一段的正文（见 `CertInfo.extensionsText` 对「为什么是文本」的说明）。
 *
 * 取法是**从 JDK 自己那份 `toString()` 里切**，不是自己解析 DER：那几行正文（`BasicConstraints:[`
 * `KeyIdentifier [`、`0000: A4 D4 …` 的十六进制转储）是 `sun.security.x509` 内部那几个扩展类打的，
 * 而那个包**不导出**——公共 API 只给得到 `getExtensionValue` 的 DER，要自己打就得把几十种扩展
 * 一一重写一遍。
 *
 * 切法跟着它自己的结构走：从第一条 `[N]: ObjectId:` 起，到扩展段收尾的那个 `]` 止（它后面紧跟着
 * `  Algorithm: `）。这两个标记都是**硬编码的英文**——实测在中文 locale 下也不变。
 *
 * 编号要换一下：`toString()` 打 `[1]:`，keytool 打 `#1:`。
 */
private fun X509Certificate.extensionsTextOf(): String {
    val lines = toString().lines()
    val start = lines.indexOfFirst { ExtensionIndexLine.containsMatchIn(it) }
    if (start < 0) return ""
    // `  Algorithm: ` 前面那一行是扩展段的收尾括号，它不属于任何一条扩展，切掉。
    val algorithmLine = lines.indexOfFirst { it.startsWith("  Algorithm: ") }
    if (algorithmLine <= start) return ""
    val body = lines.subList(start, algorithmLine - 1).joinToString("\n") { line ->
        ExtensionIndexLine.replaceFirst(line, "#$1: ")
    }
    // 末尾补一个换行：最后那条扩展正文后面本来就空一行（好与下一段或星号线分开）。
    return "$body\n"
}

/**
 * 把密钥库这一侧的异常翻成人话。
 *
 * 麻烦在于**四种失败长得几乎一样**：密钥库口令错、别名口令错、别名下没有私钥、这东西根本不是
 * JKS/PKCS#12。JDK 把真正的说明埋在 `cause` 链里（外层常常只是一句泛泛的 `IOException`），所以
 * 先扒到最里层再判——用户最卡的正是分不清这四种。
 */
private fun Throwable.readableKeyStoreMessage(): String {
    val root = rootCause()
    val text = (root.message ?: "").lowercase()
    return when {
        root is UnrecoverableKeyException ->
            "别名口令不对（也可能这个别名不是私钥条目）"

        root is SignFailure -> root.message.orEmpty()

        text.contains("password was incorrect") || text.contains("password verification failed") ->
            "密钥库口令不对。只看看证书的话可以把口令留空"

        text.contains("invalid keystore format") || text.contains("unrecognized keystore") ->
            "这不是 JKS / PKCS#12：文件可能损坏，或者压根不是密钥库"

        text.contains("integrity check failed") || text.contains("tampered") ->
            "完整性校验没过：口令不对，或者文件被改过"

        text.contains("no such file") || text.contains("filenotfound") || text.contains("not found") ->
            "找不到这个文件"

        else -> root.message?.takeIf { it.isNotBlank() } ?: root::class.simpleName.orEmpty()
    }
}

/**
 * 验签失败时的说明。
 *
 * 「这不是能读的 APK」与「能读但签名不对」必须分开：前者要用户换文件，后者要用户**看结果区**——
 * 签名对不上正是这个工具存在的理由，不该被一句「读取失败」盖过去。
 */
private fun Throwable.readableVerifyMessage(): String {
    val root = rootCause()
    return when {
        root is ZipException || (root.message ?: "").contains("zip", ignoreCase = true) ->
            "这不是一个能读的 ZIP / APK（文件可能下载不完整，或者根本不是 APK）"

        else -> root.message?.takeIf { it.isNotBlank() } ?: root::class.simpleName.orEmpty()
    }
}

/**
 * 签名失败时的说明。
 *
 * 失败可能出在两处——取私钥（密钥库那一侧）与写文件（APK 那一侧），而且两处的异常都长得很像。
 * 先按密钥库那套判据过一遍，认不出来才当成通用错误：签名这一步里**密钥库出错的可能性远高于**
 * 别处，一刀切地报原文只会让人以为工具坏了。
 */
private fun Throwable.readableSignMessage(): String = when (this) {
    is SignFailure -> message.orEmpty()
    is UnrecoverableKeyException -> readableKeyStoreMessage()
    else -> {
        val text = (rootCause().message ?: "").lowercase()
        val keystoreShaped = text.contains("password") ||
            text.contains("keystore") ||
            text.contains("integrity") ||
            text.contains("alias")
        if (keystoreShaped) readableKeyStoreMessage() else rootCause().readableVerifyMessage()
    }
}

/**
 * 最里层的原因。
 *
 * 只看最外层是不够的：`KeyStore.load` 抛的那一句往往只是个壳，真正的原因（口令错 / 格式不对）
 * 挂在它的 `cause` 上。限层数是为了防环——异常链自引用虽然不该出现，但真出现时不该把界面拖死。
 */
private fun Throwable.rootCause(): Throwable {
    var current: Throwable = this
    repeat(8) {
        current = current.cause ?: return current
    }
    return current
}

/**
 * 主题能不能被解析（用哪把尺子见 `ApkSign.kt` 里 `subjectProblem` 的说明）。
 *
 * 把 JDK 那句英文原话一起带出来而不是只留自己那句：它会指出**是哪儿**不对
 * （`improperly specified input name: FOO=bar`），而「属性名只能是已知的那几个」这条规则光看格式
 * 说明看不出来。
 */
internal actual fun subjectProblem(subject: String): String? = runCatching {
    X500Principal(subject)
    null
}.getOrElse {
    "主题写得不对：要像 `CN=名字, O=组织, C=CN` 这样，逗号分隔、属性名用已知的那几个" +
        "（${it.message.orEmpty()}）"
}

/**
 * `keytool` 的「生效时间 / 失效时间」：**就是** `java.util.Date.toString()`，逐字照搬，
 * 包括末尾那个本机时区缩写（`CST`）。
 */
internal actual fun keytoolTimestampText(millis: Long): String = Date(millis).toString()

/**
 * `keytool` 的「创建日期」：`DateFormat.getDateInstance()`（默认样式与默认 locale）。
 *
 * 刻意用默认 locale 而不是钉死中文：keytool 打这一行时用的也是默认 locale，钉死反而会在英文
 * 机器上与 keytool 对不上——而这一整份输出的用途就是「跟 keytool 对着看」。
 */
internal actual fun keytoolDayText(millis: Long): String =
    DateFormat.getDateInstance().format(Date(millis))
