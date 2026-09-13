package com.qcmian.clipper.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.util.decodeBase64
import com.qcmian.clipper.core.util.fnv1a64

/**
 * 已解码的图片，以载荷的 64 位哈希为键。
 *
 * 原生实现把生成的缩略图与预览图缓存起来，只缩放一次。Compose 会在行
 * 重新滚入视野时重新解码，因此这里改为把解码结果记下来。映射表有容量上限，很长的历史也不会
 * 耗尽内存。剪贴板图片与来源应用图标共用这张表。
 *
 * 刻意使用普通的按插入顺序排列的 [LinkedHashMap]：`java.util.LinkedHashMap` 的按访问顺序
 * 构造器并非在所有 Kotlin 目标上都可用。
 */
internal object ImageCache {
    /** 大约两屏的行数；足以让往回滚动变得便宜。 */
    private const val MAX_ENTRIES = 32

    /**
     * 来源应用图标的键标记位。位图缓存同时容纳剪贴板图片与图标，两者都用 64 位哈希做键，
     * 但哈希的对象不同（一个按字节、一个按字符）。用最高位把两套键切成互不重叠的两半，
     * 否则「某张图片」与「某个图标」理论上可能哈希相同，从而取到对方的位图。
     */
    private const val ICON_KEY_TAG = Long.MIN_VALUE

    private val entries = LinkedHashMap<Long, ImageBitmap>()

    /** 解码剪贴板图片的原始字节；图片的键空间不含标记位，这里把它清掉。 */
    fun decode(image: ClipImage): ImageBitmap? = cached(image.cacheKey and Long.MAX_VALUE) {
        runCatching { image.toByteArray().decodeToImageBitmap() }.getOrNull()
    }

    /** 解码来源应用图标的 base64 PNG。 */
    fun decode(encoded: String): ImageBitmap? = cached(keyOf(encoded)) {
        runCatching { decodeBase64(encoded)?.decodeToImageBitmap() }.getOrNull()
    }

    private inline fun cached(key: Long, decode: () -> ImageBitmap?): ImageBitmap? {
        entries[key]?.let { hit ->
            // 重新插入，使该条目成为最近最少使用之外的最新项。
            entries.remove(key)
            entries[key] = hit
            return hit
        }

        val bitmap = decode() ?: return null
        entries[key] = bitmap
        while (entries.size > MAX_ENTRIES) {
            val oldest = entries.keys.firstOrNull() ?: break
            entries.remove(oldest)
        }
        return bitmap
    }

    /**
     * 图标的键：64 位 FNV-1a 哈希再混入长度，最后盖上标记位把它挪进图标那一半键空间。
     *
     * `String.hashCode()` 只有 32 位，一旦历史中存有数千个图标就太窄：两个不同的载荷可能
     * 碰撞，缓存便会返回错误的位图。
     */
    private fun keyOf(encoded: String): Long =
        (fnv1a64(encoded) xor encoded.length.toLong()) or ICON_KEY_TAG
}

/**
 * 对每个载荷只解码一次，并把位图保存在 [ImageCache] 中，
 * 这样某一行重新滚入视野时不会重复解码同一张图片。
 *
 * 比较用的是图片的内容哈希而不是字节本身，因此每次重组都不会逐字节比对整幅图片。
 *
 * 图片解码是历史列表与偏好设置共用的能力，因此它下沉到 `core/ui/components`，
 * 两个子功能都直接复用，而不必互相依赖。
 */
@Composable
internal fun rememberImageBitmap(image: ClipImage?): ImageBitmap? =
    remember(image?.cacheKey) { image?.let(ImageCache::decode) }

/** 来源应用图标仍是 base64 PNG 字符串，这里按字符串内容做缓存键。 */
@Composable
internal fun rememberImageBitmap(encoded: String?): ImageBitmap? =
    remember(encoded) { encoded?.let(ImageCache::decode) }
