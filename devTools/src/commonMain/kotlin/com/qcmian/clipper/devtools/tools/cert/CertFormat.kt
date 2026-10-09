package com.qcmian.clipper.devtools.tools.cert

import com.qcmian.clipper.devtools.tools.apksign.certificateKeytoolText
import com.qcmian.clipper.devtools.tools.apksign.keytoolTimestampText
import com.qcmian.clipper.devtools.tools.base64.Base64Format

/**
 * 证书工具的**纯文本**那一半：PEM 块抽取、格式判别、把解析结果排成人看的文本。
 *
 * 与平台无关，因此探测（`CertDataTypeDetector`）与界面都走它、也都能被单测钉住。真正要
 * `java.security` / bouncycastle 的解析在 `CertParse.jvm.kt`——这里一行 JVM 也不碰。
 *
 * **证书信息的排版复用 `apksign` 的 [certificateKeytoolText]**（`keytool -printcert` 那一套字段）：
 * 同一张证书在「证书查看」与「Android 签名」两个工具里逐字一致，用户不必学两套读法。请求 /
 * 吊销列表 / 密钥那几类 `keytool -printcert` 本来就不管，另排（但沿用它的标签风格）。
 */
internal object CertFormat {

    /** PEM 的 `-----BEGIN <label>-----` 里，表示「这是一张 X.509 证书」的那几个 label。 */
    val CertificateLabels: Set<String> = setOf("CERTIFICATE", "X509 CERTIFICATE", "TRUSTED CERTIFICATE")

    /** PEM 里表示 PKCS#7 容器的 label（`openssl crl2pkcs7` 导出的 `.p7b` 走这一路）。 */
    const val Pkcs7Label: String = "PKCS7"

    private const val PemBegin = "-----BEGIN "
    private const val PemEnd = "-----END "
    private const val PemDashes = "-----"

    /** 裸 Base64 至少要这么长才值得去解：短串解出来的东西几乎不可能是证书。 */
    private const val MinBareBase64Chars = 64

    /** 但也不能无限长：整篇长 Base64 拿来做探测会把每次粘贴都拖慢。 */
    private const val MaxBareBase64Chars = 128 * 1024

    /** PEM 换行的列宽。RFC 7468 建议 64，openssl 也按它排。 */
    private const val PemLineWidth = 64

    /** PEM 文件里的一段，[label] 已去掉两侧的虚线。 */
    class PemBlock(val label: String, val base64: String)

    /**
     * 抽出文本里所有 PEM 块。
     *
     * 认法是「成对出现」：`-----BEGIN X-----` 之后到 `-----END X-----` 之间的行拼成正文。中间的
     * 空白一律丢掉（PEM 允许折行，也允许缩进）。没有闭合的块直接丢弃——半截块解不出东西，报错也
     * 帮不上忙。
     */
    fun extractPemBlocks(text: String): List<PemBlock> {
        val blocks = mutableListOf<PemBlock>()
        var label: String? = null
        val body = StringBuilder()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (label == null) {
                val opened = pemLabel(line, PemBegin) ?: continue
                label = opened
                body.clear()
                continue
            }
            val closed = pemLabel(line, PemEnd)
            if (closed != null && closed == label) {
                blocks.add(PemBlock(label, body.toString()))
                label = null
                body.clear()
            } else {
                body.append(line)
            }
        }
        return blocks
    }

    /** 把一段正文重新包成 PEM：左栏「原文」要的就是它。 */
    fun pemBlock(label: String, base64: String): String =
        "$PemBegin$label$PemDashes\n${wrapBase64(base64)}\n$PemEnd$label$PemDashes\n"

    /**
     * 这段文本像不像一份证书类文件（证书 / 请求 / 吊销列表 / 密钥）。
     *
     * 两条路，都是**确定性**判据而不是猜：有 PEM 头（任意 label——具体是哪一类由解析器认），或者
     * 能解出一段以 DER 头（`30 8x`）开头的 Base64。收得紧是为了不误判：「一段恰好很长的 Base64」
     * 到处都是，宽松一点点就会把普通内容认成证书，于是打开任何长串都跳到这里。
     */
    fun looksLikeCertificate(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        if (extractPemBlocks(trimmed).isNotEmpty()) return true
        return looksLikeDerBase64(trimmed)
    }

    /** 这段文本是不是「能解成 DER 对象」的一整段 Base64。 */
    fun looksLikeDerBase64(text: String): Boolean {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.length < MinBareBase64Chars || compact.length > MaxBareBase64Chars) return false
        val bytes = Base64Format.decode(compact).getOrNull() ?: return false
        return looksLikeDerCertificate(bytes)
    }

    /**
     * 一段 DER 是不是证书类对象的形状。
     *
     * 只看到「一个 SEQUENCE + 长形式长度」就够：X.509 证书 / 请求 / CRL 与 PKCS#8 密钥的 DER 都
     * 超过 127 字节，因此长度字段必是 `0x81`–`0x84` 这种长形式。真正「是哪一类」由平台那一侧解析
     * 决定，这里只负责把它挑出来。
     */
    fun looksLikeDerCertificate(der: ByteArray): Boolean =
        der.size >= 4 && der[0] == 0x30.toByte() && (der[1].toInt() and 0xFF) in 0x81..0x84

    /** 按 PEM 列宽折行。Base64 是一整串，不折的话在框里就是没完没了的一行。 */
    fun wrapBase64(base64: String, width: Int = PemLineWidth): String = base64.chunked(width).joinToString("\n")

    /** 左栏「原文」：逐条拼 PEM，条与条之间空一行。 */
    fun pemText(document: CertDocument): String = document.items.joinToString("\n") { it.pem }

    /** 这一份文档里是不是只有证书（只有证书时左栏的框名才叫「证书原文」）。 */
    fun hasOnlyCertificates(document: CertDocument): Boolean = document.items.all { it is CertificateItem }

    /** 文档里有几张证书（状态栏那一行要用）。 */
    fun certificateCount(document: CertDocument): Int = document.items.count { it is CertificateItem }

    /** 状态栏那一行：容器格式 + 条目概况。 */
    fun statusText(document: CertDocument): String {
        val total = document.items.size
        val certificates = certificateCount(document)
        return when {
            total == 0 -> document.formatLabel
            certificates == total -> "${document.formatLabel} · $total 张证书"
            certificates == 0 -> "${document.formatLabel} · $total 项"
            else -> "${document.formatLabel} · $total 项（含 $certificates 张证书）"
        }
    }

    /**
     * 右栏「信息」：只有一条时直排（不多一行标题），多条时按类型分块。
     *
     * 标题里的编号只在**同一类出现多次**时才加：一份 `.pem` 里有一张证书加一把私钥，写成
     * `证书[1]:` / `私钥:` 是噪音，直接 `证书:` / `私钥:` 就够。
     */
    fun infoText(document: CertDocument): String {
        val items = document.items
        if (items.isEmpty()) return ""
        if (items.size == 1) return bodyText(items[0])

        val totals = mutableMapOf<String, Int>()
        items.forEach { totals[kindName(it)] = (totals[kindName(it)] ?: 0) + 1 }
        val seen = mutableMapOf<String, Int>()
        return items.joinToString("\n\n") { item ->
            val name = kindName(item)
            val index = (seen[name] ?: 0) + 1
            seen[name] = index
            val title = if ((totals[name] ?: 0) > 1) "$name[$index]:" else "$name:"
            "$title\n${bodyText(item)}"
        }
    }

    // ---------------------------------------------------------------- 给用户看的话

    /** 既没有 PEM 头、也不是能解开的 Base64 / DER 时说什么。 */
    fun notACertificateHint(): String =
        "看不出这是证书类文件：没有 PEM 头（-----BEGIN …-----），也不是能解出 DER 的 Base64。"

    /** 解析成功但一条都没有。 */
    fun emptyCertificatesMessage(label: String): String = "$label 里没有可读的证书。"

    /** `label` 那一段没解出任何条目。 */
    fun emptyBlockMessage(label: String): String = "这段 $label 里没有可读的内容。"

    /** 一份 PEM 里全是本工具不认识（也不该认识）的块。 */
    fun unrecognizedMessage(labels: List<String>): String =
        "这些 PEM 块本工具不认：${labels.distinct().joinToString(" / ")}。" +
            "它只读证书、证书请求、吊销列表与密钥。"

    /** 密钥库的证书被口令挡住（还没给口令，或给错了）。 */
    fun lockedKeyStoreMessage(type: String): String =
        "这份 $type 密钥库的证书受口令保护，请在上方填入口令。"

    /** 加密私钥没给口令时说什么。 */
    fun lockedPrivateKeyMessage(): String = "这把私钥被口令加密，请在上方填入口令。"

    /** 加密私钥解不开（口令不对）时说什么。 */
    fun wrongPrivateKeyPasswordMessage(): String = "私钥解密失败：口令不正确。"

    // ---------------------------------------------------------------- 各类条目的正文

    private fun kindName(item: CertItem): String = when (item) {
        is CertificateItem -> "证书"
        is RequestItem -> "证书请求"
        is CrlItem -> "吊销列表"
        is KeyItem -> if (item.info.isPrivate) "私钥" else "公钥"
    }

    private fun bodyText(item: CertItem): String = when (item) {
        is CertificateItem -> certificateKeytoolText(item.info)
        is RequestItem -> requestText(item.info)
        is CrlItem -> crlText(item.info)
        is KeyItem -> keyText(item.info)
    }

    private fun requestText(info: RequestInfo): String = buildString {
        appendLine("主体: ${info.subject}")
        appendLine("公钥算法: ${keyAlgorithmText(info.publicKeyAlgorithm, info.publicKeyBits)}")
        appendLine("签名算法: ${info.signatureAlgorithm}")
        // 请求是自签的：验得过说明它在半路上没被改过。这一行是请求上唯一「结论性」的信息。
        appendLine("自签名验证: ${if (info.selfSigned) "通过" else "未通过"}")
        if (info.extensionsText.isEmpty()) return@buildString
        // 与证书那一段同样的版式（`扩展: ` 带尾随空格、前后各空一行），两边读起来才是一家的。
        appendLine()
        appendLine("扩展: ")
        appendLine()
        append(info.extensionsText)
    }

    private fun crlText(info: CrlInfo): String = buildString {
        appendLine("版本: ${info.version}")
        appendLine("颁发者: ${info.issuer}")
        appendLine("本次更新: ${keytoolTimestampText(info.thisUpdateMillis)}")
        // 下次更新是可选字段，没有就不打——编一个日期出来比不打更坏。
        info.nextUpdateMillis?.let { appendLine("下次更新: ${keytoolTimestampText(it)}") }
        appendLine("签名算法: ${info.signatureAlgorithm}")
        appendLine("吊销条目: ${info.revoked.size}")
        info.revoked.forEachIndexed { index, entry ->
            appendLine(
                "  #${index + 1} 序列号: ${entry.serial}" +
                    " · 吊销时间: ${keytoolTimestampText(entry.revokedAtMillis)}" +
                    " · 原因: ${entry.reason ?: "未指定"}",
            )
        }
    }

    private fun keyText(info: KeyInfo): String = buildString {
        // 加密且没给口令时算法读不出来，那一行就整条不打（见 `KeyInfo.algorithm`）。
        if (info.algorithm.isNotEmpty()) appendLine("算法: ${keyAlgorithmText(info.algorithm, info.bits)}")
        appendLine("编码: ${info.encoding}")
        appendLine("加密: ${if (info.encrypted) "是" else "否"}")
        info.encryptionAlgorithm?.let { appendLine("加密算法: $it") }
    }

    /** `2048 位 RSA 密钥`；认不出位数时只报算法名，不编一个「0 位」。 */
    private fun keyAlgorithmText(algorithm: String, bits: Int): String =
        if (bits > 0) "$bits 位 $algorithm 密钥" else algorithm

    /** `-----BEGIN X-----` / `-----END X-----` 里的 `X`；不是这个形状就给 `null`。 */
    private fun pemLabel(line: String, prefix: String): String? {
        if (!line.startsWith(prefix) || !line.endsWith(PemDashes)) return null
        return line.removePrefix(prefix).removeSuffix(PemDashes).trim().ifEmpty { null }
    }
}
