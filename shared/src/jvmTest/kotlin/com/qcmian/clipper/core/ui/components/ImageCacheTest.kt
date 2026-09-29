package com.qcmian.clipper.core.ui.components

import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import com.qcmian.clipper.core.domain.model.ClipImage
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `ImageCache` 的降采样与缓存分档。
 *
 * 这些用例不碰 `NSPasteboard`，因此不受 `CLIPPER_PASTEBOARD_TESTS` 开关约束，默认就会跑。
 */
class ImageCacheTest {

    @Test
    fun `a thumbnail is downscaled while the original size is kept`() {
        val bytes = pngOf(width = 1024, height = 512)
        val image = ClipImage(bytes)

        val loaded = ImageCache.decode(ImageCache.keyOf(image, thumbnail = true), maxEdge = 512, data = bytes)

        assertNotNull(loaded, "应当解得出来")
        assertEquals(512, maxOf(loaded.bitmap.width, loaded.bitmap.height), "最长边应被压到上限")
        assertEquals(1024, loaded.sourceWidth, "原图宽必须保留——预览的「尺寸:」要显示它")
        assertEquals(512, loaded.sourceHeight, "原图高必须保留")
    }

    @Test
    fun `a thumbnail and a preview of the same payload are cached separately`() {
        val bytes = pngOf(width = 1024, height = 512)
        val image = ClipImage(bytes)

        val thumbnailKey = ImageCache.keyOf(image, thumbnail = true)
        val previewKey = ImageCache.keyOf(image, thumbnail = false)
        assertTrue(thumbnailKey != previewKey, "两种用途必须落在不同的键上")

        val thumbnail = ImageCache.decode(thumbnailKey, maxEdge = 512, data = bytes)
        val preview = ImageCache.decode(previewKey, maxEdge = 1024, data = bytes)

        assertNotNull(thumbnail)
        assertNotNull(preview)
        assertEquals(512, maxOf(thumbnail.bitmap.width, thumbnail.bitmap.height))
        assertEquals(1024, maxOf(preview.bitmap.width, preview.bitmap.height), "预览按自己的上限解")
    }

    @Test
    fun `an image already smaller than the cap is left alone`() {
        val bytes = pngOf(width = 64, height = 32)

        val loaded = ImageCache.decode(
            key = ImageCache.keyOf(ClipImage(bytes), thumbnail = true),
            maxEdge = 512,
            data = bytes,
        )

        assertNotNull(loaded)
        assertEquals(64, loaded.bitmap.width, "本来就够小就不该被重采样")
        assertEquals(32, loaded.bitmap.height)
    }

    @Test
    fun `a failed decode is remembered`() {
        // 不是任何图片格式的字节：直解与转码都会失败。
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val key = ImageCache.keyOf(ClipImage(bytes), thumbnail = true)

        assertNull(ImageCache.decode(key, maxEdge = 512, data = bytes), "解不出来应返回 null")
        assertTrue(ImageCache.isUndecodable(key), "失败也要记下来，行滚回视野时不必重试")
        assertFalse(ImageCache.isUndecodable(key + 1), "没试过的键不算失败")
    }

    @Test
    fun `downscaled thumbnails are neither blank nor out of bounds`() {
        // 覆盖几种极端长宽比：宽幅截图、竖图、以及几乎退化成一条线的图。
        for ((width, height) in listOf(1024 to 512, 4000 to 3000, 300 to 4000, 4000 to 1, 1 to 4000)) {
            val bytes = pngOf(width, height)
            // 每个尺寸给一个独立的键，避免互相命中缓存。
            val key = ImageCache.keyOf(ClipImage(bytes), thumbnail = true) xor width.toLong()
            val loaded = ImageCache.decode(key, maxEdge = 512, data = bytes)

            assertNotNull(loaded, "${width}×${height} 应当解得出来")
            assertTrue(
                maxOf(loaded.bitmap.width, loaded.bitmap.height) <= 512,
                "${width}×${height} 的最长边应被压到 512，实际 ${loaded.bitmap.width}×${loaded.bitmap.height}",
            )
            val pixel = loaded.bitmap.toPixelMap()[loaded.bitmap.width / 2, loaded.bitmap.height / 2]
            assertEquals(
                1f,
                pixel.alpha,
                "${width}×${height} 降采样后画布是空的（alpha=${pixel.alpha}）",
            )
        }
    }

    @Test
    fun `decoding several payloads at once loses none`() = runBlocking {
        // 列表滚起来时多行会同时解码，而后台线程池是真并发的。这条用例补上串行测试覆盖不到的
        // 那一面：Skia 的解码与缩放若在并发下失败，失败会被当成负结果记进缓存，那一行就再也
        // 不显示了——正好是「滚回来也不恢复」的现象。
        val payloads = (0 until 24).map { index -> pngOf(1024, 512 + index) }

        val failures = payloads.mapIndexed { index, bytes ->
            async(Dispatchers.Default) {
                val key = ImageCache.keyOf(ClipImage(bytes), thumbnail = true) xor (index + 100).toLong()
                ImageCache.decode(key, maxEdge = 512, data = bytes)
            }
        }.mapIndexedNotNull { index, deferred -> index.takeIf { deferred.await() == null } }

        assertTrue(failures.isEmpty(), "并发解码不该有失败，但第 $failures 张没解出来")
    }

    private companion object {
        /** 用 JDK 的 ImageIO 造一张**不透明**的纯色 PNG——测的是解码链，不依赖任何图片素材。 */
        fun pngOf(width: Int, height: Int): ByteArray {
            val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            val graphics = image.createGraphics()
            graphics.color = Color(0x33, 0x66, 0xCC)
            graphics.fillRect(0, 0, width, height)
            graphics.dispose()

            val output = ByteArrayOutputStream()
            ImageIO.write(image, "png", output)
            return output.toByteArray()
        }
    }
}
