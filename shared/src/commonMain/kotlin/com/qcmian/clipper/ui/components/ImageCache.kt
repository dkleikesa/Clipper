package com.qcmian.clipper.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import com.qcmian.clipper.util.decodeBase64

/**
 * Decoded clipboard images, keyed by their encoded payload.
 *
 * Maccy keeps the generated thumbnail and preview images alive inside `HistoryItemDecorator`
 * and resizes them once. Compose re-decodes whenever a row scrolls back into view, so the
 * decoded bitmaps are memoised here instead. The map is bounded so a long history cannot
 * exhaust memory.
 *
 * A plain insertion-ordered [LinkedHashMap] is used on purpose: `java.util.LinkedHashMap`'s
 * access-order constructor is not available on every Kotlin target.
 */
internal object ImageCache {
    /** Roughly two screens worth of rows; enough to make scrolling back cheap. */
    private const val MAX_ENTRIES = 32

    private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL // 14695981039346656037
    private const val FNV_PRIME = 0x100000001B3L // 1099511628211

    private val entries = LinkedHashMap<Long, ImageBitmap>()

    fun decode(encoded: String): ImageBitmap? {
        val key = keyOf(encoded)

        entries[key]?.let { cached ->
            // Re-insert so the entry counts as the most recently used one.
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
     * FNV-1a over the payload. `String.hashCode()` is only 32 bits wide, which is too narrow
     * once a history holds thousands of images: two different payloads could collide and the
     * cache would hand out the wrong bitmap.
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
