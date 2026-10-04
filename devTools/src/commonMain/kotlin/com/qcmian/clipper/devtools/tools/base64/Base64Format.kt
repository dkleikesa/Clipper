package com.qcmian.clipper.devtools.tools.base64

import com.qcmian.clipper.core.util.Base64Decode
import com.qcmian.clipper.core.util.decodeBase64Detailed
import com.qcmian.clipper.core.util.encodeBase64
import kotlin.math.round

/**
 * 图片格式：靠文件头几个字节认出来。
 *
 * 只用于一件事——决定「解码出来的字节该不该当图片显示」。因此只认这些**有固定魔数**的常见格式；
 * SVG 那种「其实就是文本」的不在这里，它会自然落到文本那一档，本来也更该让人看见源码。
 */
internal enum class ImageKind(val label: String, val extension: String, val mime: String) {
    Png("PNG 图片", "png", "image/png"),
    Jpeg("JPEG 图片", "jpg", "image/jpeg"),
    Gif("GIF 图片", "gif", "image/gif"),
    Webp("WebP 图片", "webp", "image/webp"),
    Bmp("BMP 图片", "bmp", "image/bmp"),
    Ico("ICO 图标", "ico", "image/x-icon"),

    /** macOS 剪贴板里的图片多半是这个格式，所以它不只是「多认一种」而已。 */
    Tiff("TIFF 图片", "tiff", "image/tiff"),
}

/** 解码失败：一句话（`message`，继承自 [Exception]），加上出错字符在**原始输入**里的下标。 */
internal class Base64Error(message: String, val index: Int) : Exception(message)

/** 剥掉 Data URL 之后的正文，以及它在原始输入里的起点（报错定位要加上它）。 */
internal class DataUri(val text: String, val offset: Int)

/**
 * Base64 工具的格式逻辑：编解码、Data URL、图片识别、体积换算。
 *
 * 编解码本体**复用 `shared` 的 `encodeBase64` / `decodeBase64Detailed`**，不在工具里另起一份：
 * 字母表与解码循环只该有一处（那边同时服务来源应用图标的解码）。这里只补工具特有的四件事——
 * 带行列的报错、Data URL 的剥离、UTF-8 判定、图片格式识别。
 */
internal object Base64Format {

    fun encode(bytes: ByteArray, urlSafe: Boolean): String = encodeBase64(bytes, urlSafe)

    /** 解码；失败时错误里带着出错字符在**原始输入**中的下标。 */
    fun decode(input: String): Result<ByteArray> {
        val uri = dataUriPayload(input)
        return when (val result = decodeBase64Detailed(uri.text)) {
            is Base64Decode.Success -> Result.success(result.bytes)
            is Base64Decode.Failure ->
                Result.failure(Base64Error(result.message, result.index + uri.offset))
        }
    }

    /**
     * 字节能不能当 UTF-8 文本看；含非法序列时返回 `null`。
     *
     * 必须自己严判，而不是直接用 `decodeToString()` 再检查替换字符：后者会把非法序列**静默**
     * 换成 `�`，用户看到一坨乱码却不知道「这压根不是文本」。
     */
    fun utf8OrNull(bytes: ByteArray): String? =
        if (isValidUtf8(bytes)) bytes.decodeToString() else null

    /** 靠文件头认出图片格式；不是已知图片就返回 `null`。 */
    fun imageKindOf(bytes: ByteArray): ImageKind? = when {
        bytes.startsWithBytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> ImageKind.Png
        bytes.startsWithBytes(0xFF, 0xD8, 0xFF) -> ImageKind.Jpeg
        bytes.startsWithAscii("GIF87a") || bytes.startsWithAscii("GIF89a") -> ImageKind.Gif
        // WebP 是 RIFF 容器：前四字节 `RIFF`，四字节长度，随后才是 `WEBP`。
        bytes.startsWithAscii("RIFF") && bytes.asciiAt(8, "WEBP") -> ImageKind.Webp
        bytes.startsWithAscii("BM") -> ImageKind.Bmp
        bytes.startsWithBytes(0x00, 0x00, 0x01, 0x00) -> ImageKind.Ico
        // TIFF 有大小端两套魔数：`II*\0` 与 `MM\0*`。
        bytes.startsWithBytes(0x49, 0x49, 0x2A, 0x00) -> ImageKind.Tiff
        bytes.startsWithBytes(0x4D, 0x4D, 0x00, 0x2A) -> ImageKind.Tiff
        else -> null
    }

    /**
     * 给一份输入猜 MIME：先信**文件头**（图片认得最准），再退回**扩展名**，都不认识就给
     * `application/octet-stream`——Data URL 的头里总得填一个类型，中性的那个比瞎猜一个更安全。
     *
     * 只服务「编码时写出 `data:…;base64,` 前缀」这一件事；[name] 是文件名（可以是路径，
     * 会自己取末段），[kind] 是 [imageKindOf] 的结果。
     */
    fun mimeTypeOf(name: String?, kind: ImageKind?): String =
        kind?.mime ?: name?.let { mimeByExtension(it) } ?: DEFAULT_MIME

    /**
     * 输入若是 `data:<mime>[;base64],<data>` 形式，剥出其中的 Base64 部分。
     *
     * 只在**确实带 `;base64`** 时才剥：`data:text/plain,hello` 那种是百分号编码的明文，当成
     * Base64 解只会得到一个看不懂的报错，还不如原样交给解码器去说「这不合法」。
     */
    fun dataUriPayload(input: String): DataUri {
        val trimmed = input.trimStart()
        if (!trimmed.startsWith("data:", ignoreCase = true)) return DataUri(input, 0)
        val comma = trimmed.indexOf(',')
        if (comma < 0) return DataUri(input, 0)
        val header = trimmed.substring(5, comma)
        if (!header.contains("base64", ignoreCase = true)) return DataUri(input, 0)
        val offset = input.length - trimmed.length + comma + 1
        return DataUri(input.substring(offset), offset)
    }

    /** 把字节数写成人看的大小（B / KB / MB）：体积对比要的是「大概多大」，不是精确到字节。 */
    fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024L * 1024 -> "${oneDecimal(bytes / 1024.0)} KB"
        else -> "${oneDecimal(bytes / (1024.0 * 1024.0))} MB"
    }
}

/**
 * 把解码错误写成带行列的人话；[source] 是用户看到的**原始输入**（含 Data URL 前缀与换行）。
 *
 * 与 `mathErrorMessage` 同一套写法：只有一行时不提「第几行」，免得短输入上也挂一句废话。
 */
internal fun base64ErrorMessage(source: String, error: Base64Error): String {
    val index = error.index.coerceIn(0, source.length)
    var line = 1
    var column = 1
    for (i in 0 until index) {
        if (source[i] == '\n') {
            line++
            column = 1
        } else {
            column++
        }
    }
    return if (line == 1) "第 $column 列：${error.message}" else "第 $line 行 第 $column 列：${error.message}"
}

/** 保留一位小数；commonMain 没有 `String.format`，自己换算。 */
private fun oneDecimal(value: Double): String {
    val scaled = round(value * 10).toLong()
    return "${scaled / 10}.${scaled % 10}"
}

/** 认不出来时用的兜底类型（见 [Base64Format.mimeTypeOf]）。 */
private const val DEFAULT_MIME = "application/octet-stream"

/** 扩展名 → MIME。只列常见的那些：认出是加分，认不出退回兜底也不影响可用性。 */
private val MIME_BY_EXTENSION = mapOf(
    "png" to "image/png",
    "jpg" to "image/jpeg",
    "jpeg" to "image/jpeg",
    "gif" to "image/gif",
    "webp" to "image/webp",
    "bmp" to "image/bmp",
    "ico" to "image/x-icon",
    "tif" to "image/tiff",
    "tiff" to "image/tiff",
    "svg" to "image/svg+xml",
    "txt" to "text/plain",
    "md" to "text/markdown",
    "csv" to "text/csv",
    "json" to "application/json",
    "xml" to "application/xml",
    "html" to "text/html",
    "css" to "text/css",
    "js" to "text/javascript",
    "pdf" to "application/pdf",
    "zip" to "application/zip",
    "gz" to "application/gzip",
    "mp3" to "audio/mpeg",
    "wav" to "audio/wav",
    "mp4" to "video/mp4",
    "mov" to "video/quicktime",
    "woff" to "font/woff",
    "woff2" to "font/woff2",
    "ttf" to "font/ttf",
    "otf" to "font/otf",
)

/** 按文件名的扩展名查 MIME；没扩展名或不在表里返回 `null`。 */
private fun mimeByExtension(name: String): String? =
    MIME_BY_EXTENSION[name.substringAfterLast('.', "").lowercase()]

private fun ByteArray.startsWithBytes(vararg values: Int): Boolean {
    if (size < values.size) return false
    for (i in values.indices) {
        if ((this[i].toInt() and 0xFF) != values[i]) return false
    }
    return true
}

private fun ByteArray.startsWithAscii(text: String): Boolean = asciiAt(0, text)

private fun ByteArray.asciiAt(offset: Int, text: String): Boolean {
    if (offset + text.length > size) return false
    for (i in text.indices) {
        if ((this[offset + i].toInt() and 0xFF) != text[i].code) return false
    }
    return true
}

/**
 * 严格的 UTF-8 校验（RFC 3629）：过长编码、代理区、超出 U+10FFFF 一律判非法。
 *
 * 自己写而不是找现成 API：Kotlin commonMain 里 `decodeToString()` 对非法序列默认是「替成 `�`」，
 * 而这里要的恰恰是「能不能当文本」这个布尔答案。
 */
private fun isValidUtf8(bytes: ByteArray): Boolean {
    var i = 0
    while (i < bytes.size) {
        val b = bytes[i].toInt() and 0xFF
        when {
            b < 0x80 -> i++
            b in 0xC2..0xDF -> {
                if (!continuations(bytes, i + 1, 1)) return false
                i += 2
            }

            b == 0xE0 -> {
                if (!inRange(bytes, i + 1, 0xA0, 0xBF) || !continuations(bytes, i + 2, 1)) return false
                i += 3
            }

            b in 0xE1..0xEC -> {
                if (!continuations(bytes, i + 1, 2)) return false
                i += 3
            }

            b == 0xED -> {
                if (!inRange(bytes, i + 1, 0x80, 0x9F) || !continuations(bytes, i + 2, 1)) return false
                i += 3
            }

            b in 0xEE..0xEF -> {
                if (!continuations(bytes, i + 1, 2)) return false
                i += 3
            }

            b == 0xF0 -> {
                if (!inRange(bytes, i + 1, 0x90, 0xBF) || !continuations(bytes, i + 2, 2)) return false
                i += 4
            }

            b in 0xF1..0xF3 -> {
                if (!continuations(bytes, i + 1, 3)) return false
                i += 4
            }

            b == 0xF4 -> {
                if (!inRange(bytes, i + 1, 0x80, 0x8F) || !continuations(bytes, i + 2, 2)) return false
                i += 4
            }

            else -> return false
        }
    }
    return true
}

/** [from] 起连续 [count] 个字节是否都是 `10xxxxxx`。 */
private fun continuations(bytes: ByteArray, from: Int, count: Int): Boolean {
    for (i in from until from + count) {
        if (i >= bytes.size) return false
        val b = bytes[i].toInt() and 0xFF
        if (b !in 0x80..0xBF) return false
    }
    return true
}

private fun inRange(bytes: ByteArray, index: Int, min: Int, max: Int): Boolean {
    if (index >= bytes.size) return false
    val b = bytes[index].toInt() and 0xFF
    return b in min..max
}
