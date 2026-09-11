package com.qcmian.clipper.util

import kotlin.random.Random
import kotlin.time.Clock

/** Wall clock in milliseconds since the epoch. */
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** A random, collision resistant identifier. */
fun randomId(): String {
    val alphabet = "0123456789abcdef"
    return buildString(32) {
        repeat(32) { append(alphabet[Random.nextInt(alphabet.length)]) }
    }
}

private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

private val BASE64_DECODE_TABLE = IntArray(128) { -1 }.also { table ->
    BASE64_ALPHABET.forEachIndexed { index, char -> table[char.code] = index }
}

/**
 * Standard base64 (with padding) encoding.
 *
 * Implemented in pure Kotlin so every target behaves identically without any
 * platform specific helper.
 */
fun encodeBase64(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""

    val output = StringBuilder(((bytes.size + 2) / 3) * 4)
    var index = 0
    while (index + 2 < bytes.size) {
        val b0 = bytes[index].toInt() and 0xFF
        val b1 = bytes[index + 1].toInt() and 0xFF
        val b2 = bytes[index + 2].toInt() and 0xFF
        output.append(BASE64_ALPHABET[b0 shr 2])
        output.append(BASE64_ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
        output.append(BASE64_ALPHABET[((b1 and 0x0F) shl 2) or (b2 shr 6)])
        output.append(BASE64_ALPHABET[b2 and 0x3F])
        index += 3
    }

    when (bytes.size - index) {
        1 -> {
            val b0 = bytes[index].toInt() and 0xFF
            output.append(BASE64_ALPHABET[b0 shr 2])
            output.append(BASE64_ALPHABET[(b0 and 0x03) shl 4])
            output.append("==")
        }

        2 -> {
            val b0 = bytes[index].toInt() and 0xFF
            val b1 = bytes[index + 1].toInt() and 0xFF
            output.append(BASE64_ALPHABET[b0 shr 2])
            output.append(BASE64_ALPHABET[((b0 and 0x03) shl 4) or (b1 shr 4)])
            output.append(BASE64_ALPHABET[(b1 and 0x0F) shl 2])
            output.append("=")
        }
    }

    return output.toString()
}

/** Standard base64 decoding, returns `null` when the input is not valid base64. */
fun decodeBase64(value: String): ByteArray? {
    val cleaned = value.filterNot { it.isWhitespace() }
    if (cleaned.isEmpty()) return ByteArray(0)

    val output = ByteArray(cleaned.length / 4 * 3 + 3)
    var outputIndex = 0
    var buffer = 0
    var bits = 0

    for (char in cleaned) {
        if (char == '=') break
        val code = char.code
        if (code >= BASE64_DECODE_TABLE.size) return null
        val decoded = BASE64_DECODE_TABLE[code]
        if (decoded < 0) return null

        buffer = (buffer shl 6) or decoded
        bits += 6
        if (bits >= 8) {
            bits -= 8
            output[outputIndex++] = ((buffer shr bits) and 0xFF).toByte()
        }
    }

    return output.copyOf(outputIndex)
}
