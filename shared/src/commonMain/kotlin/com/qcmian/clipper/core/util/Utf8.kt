package com.qcmian.clipper.core.util

/**
 * 这批字节能不能当 UTF-8 文本看；含非法序列时返回 `null`。
 *
 * 必须严判，而不是 `decodeToString()` 之后再看有没有替换字符：后者会把非法序列**静默**换成
 * `�`，调用方拿到一坨乱码却不知道「这压根不是文本」。Base64 解码与 URL 解码都要靠它分辨
 * 「解出来的是文本还是二进制」。
 */
fun decodeUtf8OrNull(bytes: ByteArray): String? =
    if (isValidUtf8(bytes)) bytes.decodeToString() else null

/**
 * 严格的 UTF-8 校验（RFC 3629）：过长编码、代理区、超出 U+10FFFF 一律判非法。
 *
 * 自己写而不是找现成 API：Kotlin commonMain 里没有「只校验、不转换」的入口，而 `decodeToString()`
 * 对非法序列默认是「替成 `�`」，这里要的恰恰是「合不合法」这个布尔答案。
 */
fun isValidUtf8(bytes: ByteArray): Boolean {
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
