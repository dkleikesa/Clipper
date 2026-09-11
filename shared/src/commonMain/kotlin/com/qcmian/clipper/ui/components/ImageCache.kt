package com.qcmian.clipper.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import com.qcmian.clipper.util.decodeBase64

/**
 * 已解码的剪贴板图片，以编码后的载荷为键。
 *
 * Maccy 把生成的缩略图与预览图保存在 `HistoryItemDecorator` 内，只缩放一次。Compose 会在行
 * 重新滚入视野时重新解码，因此这里改为把解码结果记下来。映射表有容量上限，很长的历史也不会
 * 耗尽内存。
 *
 * 刻意使用普通的按插入顺序排列的 [LinkedHashMap]：`java.util.LinkedHashMap` 的按访问顺序
 * 构造器并非在所有 Kotlin 目标上都可用。
 */
internal object ImageCache {
    /** 大约两屏的行数；足以让往回滚动变得便宜。 */
    private const val MAX_ENTRIES = 32

    private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL // 14695981039346656037
    private const val FNV_PRIME = 0x100000001B3L // 1099511628211

    private val entries = LinkedHashMap<Long, ImageBitmap>()

    fun decode(encoded: String): ImageBitmap? {
        val key = keyOf(encoded)

        entries[key]?.let { cached ->
            // 重新插入，使该条目成为最近最少使用之外的最新项。
            entries.remove(key)
            entries[key] = cached
            return cached
        }

        val bitmap = runCatching { decodeBase64(encoded)?.decodeToImageBitmap() }.getOrNull()
            ?: return null

        entries[key] = bitmap
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
        return bitmap
    }

    /**
     * 对载荷做 FNV-1a 哈希。`String.hashCode()` 只有 32 位，一旦历史中存有数千张图片就太窄：
     * 两个不同的载荷可能碰撞，缓存便会返回错误的位图。
     */
    private fun keyOf(encoded: String): Long {
        var hash = FNV_OFFSET_BASIS
        for (character in encoded) {
            hash = hash xor character.code.toLong()
            hash *= FNV_PRIME
        }
        return hash xor encoded.length.toLong()
    }
}
