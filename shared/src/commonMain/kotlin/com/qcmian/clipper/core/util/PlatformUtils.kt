package com.qcmian.clipper.core.util

import kotlin.random.Random
import kotlin.time.Clock

/** 自纪元以来的墙钟毫秒数。 */
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** 一个随机的、不易碰撞的标识符。 */
fun randomId(): String {
    val alphabet = "0123456789abcdef"
    return buildString(32) {
        repeat(32) { append(alphabet[Random.nextInt(alphabet.length)]) }
    }
}

/** 标准 base64 字母表。 */
private const val BASE64_STANDARD_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

/** URL 安全字母表：只把 `+` / `/` 换成 `-` / `_`，其余位置一一对应。 */
private const val BASE64_URL_SAFE_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

private val BASE64_STANDARD_TABLE = base64Table(BASE64_STANDARD_ALPHABET)
private val BASE64_URL_SAFE_TABLE = base64Table(BASE64_URL_SAFE_ALPHABET)

private fun base64Table(alphabet: String): IntArray = IntArray(128) { -1 }.also { table ->
    alphabet.forEachIndexed { index, char -> table[char.code] = index }
}

/**
 * base64 编码。
 *
 * 用纯 Kotlin 实现，因此各平台行为完全一致，无需任何平台专属辅助函数。
 *
 * [urlSafe] 为真时改用 URL 安全字母表（`-` / `_`）**并去掉填充 `=`**：这套写法本来就是为
 * 「放进 URL 或文件名」准备的，那里的 `=` 要转义，去掉更省事。
 */
fun encodeBase64(bytes: ByteArray, urlSafe: Boolean = false): String {
    if (bytes.isEmpty()) return ""

    val alphabet = if (urlSafe) BASE64_URL_SAFE_ALPHABET else BASE64_STANDARD_ALPHABET
    val padding = !urlSafe
    val output = StringBuilder(((bytes.size + 2) / 3) * 4)
    var index = 0
    while (index + 2 < bytes.size) {
        val b0 = bytes[index].toInt() and 0xFF
        val b1 = bytes[index + 1].toInt() and 0xFF
        val b2 = bytes[index + 2].toInt() and 0xFF
        output.append(alphabet[b0 shr 2])
        output.append(alphabet[((b0 and 0x03) shl 4) or (b1 shr 4)])
        output.append(alphabet[((b1 and 0x0F) shl 2) or (b2 shr 6)])
        output.append(alphabet[b2 and 0x3F])
        index += 3
    }

    when (bytes.size - index) {
        1 -> {
            val b0 = bytes[index].toInt() and 0xFF
            output.append(alphabet[b0 shr 2])
            output.append(alphabet[(b0 and 0x03) shl 4])
            if (padding) output.append("==")
        }

        2 -> {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = bytes[index + 1].toInt() and 0xFF
            output.append(alphabet[b0 shr 2])
            output.append(alphabet[((b0 and 0x03) shl 4) or (b1 shr 4)])
            output.append(alphabet[(b1 and 0x0F) shl 2])
            if (padding) output.append("=")
        }
    }

    return output.toString()
}

/** 一次 base64 解码的结果：要么拿到字节，要么知道在哪个字符上、为什么失败。 */
sealed interface Base64Decode {
    class Success(val bytes: ByteArray) : Base64Decode

    /**
     * @param index 出问题的字符在**原始输入**里的下标（空白与填充都算在内）。
     * @param message 一句话说明，调用方据此拼出带行列的提示。
     */
    class Failure(val index: Int, val message: String) : Base64Decode
}

/**
 * base64 解码，失败时带出**出错字符的位置**。
 *
 * 比 [decodeBase64] 多两件事，都是为了「让工具能指着屏幕说哪错了」：
 *  - 标准与 URL 安全两份字母表**同时接受**——用户很少记得手里这份是哪种；
 *  - 失败给出下标与原因，而不是一个 `null`。
 *
 * 空白一律忽略：base64 常按 76 列折行，复制时也会带上换行。填充 `=` 可有可无；长度只要不是
 * 「去掉填充后余 1 个字符」（6 位凑不出一个字节）就算合法。
 */
fun decodeBase64Detailed(value: String): Base64Decode {
    val output = ByteArray((value.length / 4 + 1) * 3)
    var outputIndex = 0
    var buffer = 0
    var bits = 0
    var symbols = 0
    var lastSymbolIndex = -1
    var paddingSeen = false

    for (index in value.indices) {
        val char = value[index]
        if (char.isWhitespace()) continue
        if (char == '=') {
            paddingSeen = true
            continue
        }
        if (paddingSeen) return Base64Decode.Failure(index, "填充符 = 之后不该再有字符")

        val code = char.code
        val decoded =
            if (code < 128) maxOf(BASE64_STANDARD_TABLE[code], BASE64_URL_SAFE_TABLE[code]) else -1
        if (decoded < 0) return Base64Decode.Failure(index, "「$char」不是合法的 Base64 字符")

        symbols++
        lastSymbolIndex = index
        buffer = (buffer shl 6) or decoded
        bits += 6
        if (bits >= 8) {
            bits -= 8
            output[outputIndex++] = ((buffer shr bits) and 0xFF).toByte()
        }
    }

    if (symbols % 4 == 1) {
        return Base64Decode.Failure(
            if (lastSymbolIndex < 0) 0 else lastSymbolIndex,
            "去掉填充后余下 1 个字符，凑不出一个字节",
        )
    }

    return Base64Decode.Success(output.copyOf(outputIndex))
}

/** 标准 base64 解码；输入不是合法 base64 时返回 `null`。 */
fun decodeBase64(value: String): ByteArray? =
    (decodeBase64Detailed(value) as? Base64Decode.Success)?.bytes
