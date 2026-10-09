package com.qcmian.clipper.devtools.tools.cert

import com.qcmian.clipper.devtools.tools.apksign.CertInfo

/**
 * 证书工具要吃的一份输入：一段**粘贴的文本**，或磁盘上的**一个文件**。
 *
 * 文本那一路覆盖 PEM 与裸 Base64（DER）；文件那一路还要覆盖 DER / PKCS#7 / PKCS#12 / JKS /
 * CSR / CRL / 密钥——那些是二进制（或太长），粘贴不进来，只能走文件。
 */
internal sealed interface CertInput {
    /**
     * 当前填的口令；`null` 表示还没填。
     *
     * 两处要它：PKCS#12 的证书也在加密的 safe bag 里，不给口令连证书都取不出来；**加密的 PKCS#8
     * 私钥**同理（JKS 的证书是明文，不给也能读）。
     */
    val password: String?

    class Text(val text: String, override val password: String? = null) : CertInput

    class File(val path: String, override val password: String? = null) : CertInput
}

/**
 * 解析出来的一项。
 *
 * 做成**异质的一串**而不是「一串证书」，因为一份 `.pem` 里常常混着装：证书 + 中间证书 + 私钥，
 * 甚至证书 + 它自己的请求。左栏照原样把每一块排出来，右栏逐块给信息。
 */
internal sealed interface CertItem {
    /** 左栏「原文」里那一份 PEM（原样重排，不丢内容）。 */
    val pem: String
}

/** 一张 X.509 证书。字段提取与排版复用 `apksign` 那一套（见 [CertFormat.infoText]）。 */
internal class CertificateItem(val info: CertInfo, override val pem: String) : CertItem

/** 一个证书请求（PKCS#10）。 */
internal class RequestItem(val info: RequestInfo, override val pem: String) : CertItem

/** 一张吊销列表（X.509 CRL）。 */
internal class CrlItem(val info: CrlInfo, override val pem: String) : CertItem

/** 一把密钥（私钥或公钥）。 */
internal class KeyItem(val info: KeyInfo, override val pem: String) : CertItem

/**
 * 一个证书请求上取出来的信息。
 *
 * 与 [CertInfo] 刻意分开：请求**没有**签发者、有效期、序列号（那要等 CA 签了才有），反过来它有
 * 一份「自签名是否验得过」——那正是「这个请求有没有被中途改过」的答案。
 */
internal class RequestInfo(
    val subject: String,
    /** 请求里那把公钥的算法（`RSA` / `EC` / `Ed25519`）。 */
    val publicKeyAlgorithm: String,
    /** 公钥位数；认不出为 0。 */
    val publicKeyBits: Int,
    val signatureAlgorithm: String,
    /** 请求自带的签名验过没有。 */
    val selfSigned: Boolean,
    /** 请求里带的扩展，已排成一段文本；空串表示没有。 */
    val extensionsText: String,
)

/** 一条吊销记录。 */
internal class RevokedEntry(val serial: String, val revokedAtMillis: Long, val reason: String?)

/** 一张 CRL 上取出来的信息。 */
internal class CrlInfo(
    val issuer: String,
    val thisUpdateMillis: Long,
    /** 下次更新时间；CRL 允许不写（那时整行不打）。 */
    val nextUpdateMillis: Long?,
    val signatureAlgorithm: String,
    val version: Int,
    val revoked: List<RevokedEntry>,
)

/**
 * 一把密钥上取出来的信息。
 *
 * **密钥材料一律不打**：私钥的正文本来就只在左栏原样出现（那是用户自己粘进来的），右栏只回答
 * 「这是什么」——算法、位数、怎么编码的、加没加密。界面上因此没有可被顺手抄走的字节。
 */
internal class KeyInfo(
    val isPrivate: Boolean,
    /** 算法名；解不开（加密且没给口令）时为空串，那时整行不打。 */
    val algorithm: String,
    /** 位数；认不出为 0（那时只报算法名，不编一个「0 位」）。 */
    val bits: Int,
    /** 编码方式：`PKCS#8` / `PKCS#1` / `SEC1` / `X.509 (SPKI)` 等。 */
    val encoding: String,
    /** 这把私钥是不是被口令加密的。 */
    val encrypted: Boolean,
    /** 加密时用的算法名；没加密时为 `null`。 */
    val encryptionAlgorithm: String?,
)

/**
 * 一次成功解析的结果。
 *
 * @param formatLabel 这份输入被认成什么**容器**（`PEM` / `DER` / `PKCS#7` / `PKCS#12` / `JKS`），
 *   报给状态栏。里面的条目是什么类型由 [items] 各自的类型说。
 * @param items 解析出来的条目，按在文件里出现的顺序。
 */
internal class CertDocument(val formatLabel: String, val items: List<CertItem>)

/** 解析结果。 */
internal sealed interface CertParseResult {
    class Ok(val document: CertDocument) : CertParseResult

    /**
     * 解析失败。
     *
     * @param needsPassword 为真表示「差一个口令」——可能是一份 PKCS#12，也可能是一把加密的私钥。
     *   界面据此露出那个口令框：不靠扩展名猜（改名是常事），靠**真的解析了一次**得出的结论。
     */
    class Failed(val message: String, val needsPassword: Boolean = false) : CertParseResult
}

/**
 * 解析一份输入。**实现不抛异常**：认不出来一律走 [CertParseResult.Failed]，由界面显示那句话。
 *
 * 只有平台（`java.security` + bouncycastle）做得了这件事，所以是一个 `expect`——与 `apksign` 里
 * 读写密钥库那几个是同一类。
 */
internal expect fun parseCertInput(input: CertInput): CertParseResult
