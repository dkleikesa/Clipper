package com.qcmian.clipper.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.util.decodeBase64
import com.qcmian.clipper.core.util.fnv1a64
import com.qcmian.clipper.core.util.withLock
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 缩略图的最长边（像素）。
 *
 * 列表槽位默认 40dp，Retina 上也只有 80 物理像素，512 留了六倍余量，够应付用户把
 * `imageMaxHeight` 调大好几档。固定成常量而不是跟着设置走，是为了让缓存键里不必再掺进尺寸
 * ——设置一变就换一份缓存，只会把整张表翻来覆去地重建。
 */
private const val THUMBNAIL_MAX_EDGE = 512

/** 预览大图的最长边。预览面板一屏也就几百物理像素，1024 足够，同时把单张压到 4 MiB 内。 */
private const val PREVIEW_MAX_EDGE = 1024

/**
 * 一张解码好的图片。
 *
 * [bitmap] 可能**已经被降采样**（列表缩略图就是这么来的），而 [sourceWidth] / [sourceHeight]
 * 始终是原图尺寸。需要区分这两者：预览面板的「尺寸:」一行要显示原图分辨率，读位图自身的
 * 宽高会把缩略图的尺寸当成原图报出去。
 */
internal class LoadedImage(
    val bitmap: ImageBitmap,
    val sourceWidth: Int,
    val sourceHeight: Int,
)

/**
 * 已解码的图片，**按用途分三块键空间**缓存。
 *
 * 关键的一条是：**列表缩略图不能按原尺寸缓存**。列表槽位默认 40dp（Retina 下 80 物理像素），
 * 而剪贴板里的原图动辄 4K——一张 3840×2160 解码后是 31.6 MiB，比整张表的 32 MiB 预算还大，
 * 结果是一张都留不住，列表每滚动一行就要把整张图重解一遍。缩略图降到 [THUMBNAIL_MAX_EDGE]
 * 之后不到 1 MiB，同一张图还能另外给预览留一份大的（[PREVIEW_MAX_EDGE]），两者互不挤占。
 *
 * 缓存同时容纳剪贴板图片与来源应用图标，因此用最高两位把键切成互不重叠的三块：
 *
 * ```
 * 位 63 = 1        来源应用图标
 * 位 62 = 1        剪贴板图片的缩略图
 * 两位都是 0        剪贴板图片的预览大图
 * ```
 *
 * 刻意使用普通的按插入顺序排列的 [LinkedHashMap]：`java.util.LinkedHashMap` 的按访问顺序
 * 构造器并非在所有 Kotlin 目标上都可用。
 */
internal object ImageCache {
    /**
     * 位图占用的**总字节**上限（按 4 字节/像素计）。
     *
     * 按字节而不是按条数：这张表同时装着剪贴板图片与应用图标，两者体积差着几个数量级——
     * 一个 16×16 图标几百字节，一张 4K 图解码后是 31.6 MiB。按条数限制时，「32 条」的实际
     * 占用能从几十 KB 一路飘到上千 MB，等于没有上限。
     */
    private const val MAX_BYTES = 32L * 1024L * 1024L

    /** 位 63：来源应用图标。 */
    private const val ICON_KEY = 1L shl 63

    /** 位 62：剪贴板图片的缩略图；预览大图这两位都是 0。 */
    private const val THUMBNAIL_KEY = 1L shl 62

    /** 内容哈希可用的低位：最高两位留给上面两个用途标记。 */
    private const val HASH_MASK = Long.MAX_VALUE ushr 2

    /** 解码在后台线程、查询在组合线程，两者都读写 [entries]，必须串行化。 */
    private val lock = Any()

    /**
     * 键 → 已解码的图片。
     *
     * **值可以是 `null`**，那表示这个键试过、确定解不出来。负结果同样要记下来——行每次滚回
     * 视野都会重新组合，不记的话每次都要把必定失败的解码再跑一遍。
     */
    private val entries = LinkedHashMap<Long, LoadedImage?>()

    /** [entries] 里所有位图占用的字节数，与 [entries] 同步增减。 */
    private var bytes = 0L

    /** 剪贴板图片的键。[thumbnail] 区分列表缩略图与预览大图——两者的目标尺寸差两个数量级。 */
    fun keyOf(image: ClipImage, thumbnail: Boolean): Long =
        (image.cacheKey and HASH_MASK) or if (thumbnail) THUMBNAIL_KEY else 0L

    /**
     * 来源应用图标的键。
     *
     * `String.hashCode()` 只有 32 位，一旦历史里存有数千个图标就太窄：两个不同的载荷可能
     * 碰撞，缓存便会返回错误的位图。因此走 64 位 FNV-1a 再混入长度。
     */
    fun keyOf(encoded: String): Long =
        ((fnv1a64(encoded) xor encoded.length.toLong()) and HASH_MASK) or ICON_KEY

    /** 同步查缓存；命中时把它挪到最新端。解不出来与没试过都返回 `null`，见 [isUndecodable]。 */
    fun peek(key: Long): LoadedImage? = withLock(lock) {
        val hit = entries[key] ?: return@withLock null
        entries.remove(key)
        entries[key] = hit
        hit
    }

    /** 这个键试过、确定解不出来——不必再试。 */
    fun isUndecodable(key: Long): Boolean = withLock(lock) {
        entries.containsKey(key) && entries[key] == null
    }

    /**
     * 同上，按图片与用途问。
     *
     * 列表靠它把「图片坏了」与「还没加载」分开（见 `HistoryRow`）：前者要给一句说明，后者
     * 什么都不该显示——下一帧就有图。
     */
    fun isUndecodable(image: ClipImage, thumbnail: Boolean): Boolean =
        isUndecodable(keyOf(image, thumbnail))

    /**
     * 解码 [data] 并把结果记进缓存（解不出来也记）。**会耗时**，调用方必须放到后台线程上
     * ——见 [rememberImage]。
     */
    fun decode(key: Long, maxEdge: Int, data: ByteArray): LoadedImage? {
        peek(key)?.let { return it }

        // 解码放在锁外：它可能几十毫秒，持锁会挡住组合线程的查询。
        val decoded = decodeBytes(data, maxEdge)

        return withLock(lock) {
            // 并发解码同一张图时，后到的直接复用先到的结果。
            entries[key]?.let { return@withLock it }
            entries[key] = decoded
            decoded?.let { bytes += it.bitmap.byteSize() }
            trim()
            decoded
        }
    }

    /** 解码来源应用图标的 base64 PNG。图标本来就小，不缩。 */
    fun decode(key: Long, encoded: String): LoadedImage? {
        peek(key)?.let { return it }

        val decoded = runCatching { decodeBase64(encoded)?.decodeToImageBitmap() }.getOrNull()

        return withLock(lock) {
            entries[key]?.let { return@withLock it }
            val loaded = decoded?.let { LoadedImage(it, it.width, it.height) }
            entries[key] = loaded
            decoded?.let { bytes += it.byteSize() }
            trim()
            loaded
        }
    }

    /**
     * 先按原样解码；Compose 的解码器不认识这个格式时（例如 macOS 粘贴板原始的 TIFF），
     * 交给平台转成 PNG 再试一次。
     *
     * 「先直解」是必要的快路径：PNG / JPEG 是绝大多数条目，直接解码省掉一次全图重编码。
     * 转码结果同样落在本缓存里，因此同一张图只会转一次。
     */
    private fun decodeBytes(data: ByteArray, maxEdge: Int): LoadedImage? {
        val full = runCatching { data.decodeToImageBitmap() }.getOrNull()
            ?: runCatching { transcodeForDisplay(data)?.decodeToImageBitmap() }.getOrNull()
            ?: return null
        return LoadedImage(downscaled(full, maxEdge), full.width, full.height)
    }

    /**
     * 等比缩放到最长边不超过 [maxEdge]；本来就够小就原样返回。
     *
     * 这一步只改变**缓存里常驻的那一份**：解码本身仍是全尺寸的，因此瞬时峰值没变。但常驻
     * 占用从 31.6 MiB 掉到 1 MiB 以内——「缓存装不下、每行重新解码」才是真正的问题，
     * 那正是这一步解决的。
     */
    private fun downscaled(source: ImageBitmap, maxEdge: Int): ImageBitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maxEdge) return source

        val scale = maxEdge.toFloat() / longest
        val width = maxOf(1, (source.width * scale).roundToInt())
        val height = maxOf(1, (source.height * scale).roundToInt())

        val target = ImageBitmap(width, height)
        CanvasDrawScope().draw(
            // 画布 1:1 映射到位图像素，没有 dp 参与，密度取 1 即可。
            Density(1f),
            LayoutDirection.Ltr,
            Canvas(target),
            Size(width.toFloat(), height.toFloat()),
        ) {
            drawImage(
                image = source,
                dstSize = IntSize(width, height),
                filterQuality = FilterQuality.High,
            )
        }
        return target
    }

    /** 淘汰最久没用到的，直到回到上限之内。至少留下刚放进去的那一张。 */
    private fun trim() {
        while (bytes > MAX_BYTES && entries.size > 1) {
            val oldest = entries.keys.firstOrNull() ?: break
            // 负结果条目的值是 null，不占字节，`?.let` 自然跳过。
            entries.remove(oldest)?.let { bytes -= it.bitmap.byteSize() }
        }
    }

    /** 位图占用的近似字节数：Compose 的位图按 4 字节/像素（ARGB8888）算。 */
    private fun ImageBitmap.byteSize(): Long = width.toLong() * height.toLong() * 4L
}

/**
 * 取一张剪贴板图片：列表缩略图传 `thumbnail = true`，预览大图传 `false`。
 *
 * **命中缓存时同步返回**——列表滚动过程中绝大多数行都走这条，第一帧就有图，不会闪出空白。
 * 没命中才进协程、在后台线程解码：Skia 必须把原图整个解出来才能缩，放在组合线程上就是一次
 * 肉眼可见的卡顿（4K 图尤其明显）。
 *
 * 解码失败的结果也会被缓存（见 [ImageCache.isUndecodable]），行滚回视野时不会反复重试。
 */
@Composable
internal fun rememberImage(image: ClipImage?, thumbnail: Boolean): LoadedImage? {
    val cached = remember(image?.cacheKey, thumbnail) {
        image?.let { ImageCache.peek(ImageCache.keyOf(it, thumbnail)) }
    }

    // 状态自己搭、并且**带键**。不能写成 `produceState(cached, key) { ... }`：后者内部的
    // `mutableStateOf` 没有键，换 key 时并不会把值还原成新的 `initialValue`——上一张图的
    // 位图会一直留在里面，producer 开头那句判空立刻命中，于是新图永远加载不出来（表现就是
    // 预览切到别的条目后画面停住不动）。带键的 `remember` 保证换图时换的是一份全新的状态。
    val state = remember(image?.cacheKey, thumbnail) { mutableStateOf(cached) }

    LaunchedEffect(image?.cacheKey, thumbnail) {
        // 同步命中（或已经确定解不出来）都无需再动。
        if (state.value != null) return@LaunchedEffect
        val source = image ?: return@LaunchedEffect
        val key = ImageCache.keyOf(source, thumbnail)
        if (ImageCache.isUndecodable(key)) return@LaunchedEffect

        // `Dispatchers.Default` 而不是 `IO`：解码是 CPU 密集的，线程数受核数约束才不会
        // 一次滚动就把整机占满。旧协程在 key 变化时会被取消，而它写的是**旧**那份状态对象，
        // 因此不会回头覆盖新图。
        state.value = withContext(Dispatchers.Default) {
            ImageCache.decode(
                key = key,
                maxEdge = if (thumbnail) THUMBNAIL_MAX_EDGE else PREVIEW_MAX_EDGE,
                data = source.toByteArray(),
            )
        }
    }
    return state.value
}

/** 来源应用图标仍是 base64 PNG 字符串，按字符串内容做缓存键；图标很小，解码很快。 */
@Composable
internal fun rememberImageBitmap(encoded: String?): ImageBitmap? =
    remember(encoded) { encoded?.let { ImageCache.decode(ImageCache.keyOf(it), it)?.bitmap } }
