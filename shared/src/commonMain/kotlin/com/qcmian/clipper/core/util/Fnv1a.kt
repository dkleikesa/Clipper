package com.qcmian.clipper.core.util

/**
 * FNV-1a 64 位哈希的初始值与质数。
 *
 * 图片缓存与图标缓存共用同一套口径，因此放在这里而不是各自复制一份。
 */
internal const val FNV1A_OFFSET_BASIS = -0x340d631b7bdddcdbL // 14695981039346656037
internal const val FNV1A_PRIME = 0x100000001B3L // 1099511628211

/**
 * 逐字节的 FNV-1a 64 位哈希。
 *
 * 用 64 位而不是 `String.hashCode()` 那样的 32 位：键只有二三十位时，历史里存上几千张图片
 * 就可能碰撞，缓存便会返回别人的位图。
 */
internal fun fnv1a64(bytes: ByteArray): Long {
    var hash = FNV1A_OFFSET_BASIS
    for (byte in bytes) {
        hash = hash xor (byte.toLong() and 0xFF)
        hash *= FNV1A_PRIME
    }
    return hash
}

/** 逐字符（UTF-16 码元）的 FNV-1a 64 位哈希，用于把字符串当作缓存键。 */
internal fun fnv1a64(value: String): Long {
    var hash = FNV1A_OFFSET_BASIS
    for (character in value) {
        hash = hash xor character.code.toLong()
        hash *= FNV1A_PRIME
    }
    return hash
}
