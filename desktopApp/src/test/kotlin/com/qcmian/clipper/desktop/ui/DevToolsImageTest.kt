package com.qcmian.clipper.desktop.ui

import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 从粘贴板 / 拖放载荷里取图片字节的那段逻辑（[imageBytes]）。
 *
 * 为什么值得单测：真粘贴板与真拖放事件在测试里都造不出来，而这段逻辑里藏着几条只在实机上才
 * 显形的约定——
 *  - **同一种图上可能有好几条表示**（macOS 上「复制图片」会给 `image/tiff` + `image/png`，顺序
 *    还由写的一方定），挑哪一条决定用户能不能预览、以及 Base64 出来是什么格式；
 *  - **能原样搬就原样搬**：AWT 的 `imageFlavor` 会解码成像素再编回 PNG，走它等于把用户复制的
 *    那份图片改写一遍（剪贴板捕获那条主链路刻意避开的正是这件事）；
 *  - 某一条表示读不出来时**继续找下一条**，而不是整个失败。
 */
class DevToolsImageTest {

    @Test
    fun `具体图片类型的原始字节原样取回`() {
        val original = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x01)
        val png = DataFlavor("image/png;class=java.io.InputStream")

        val bytes = imageBytes(listOf(png)) { original.inputStream() }

        assertContentEquals(original, bytes, "取回的必须是原样字节，不能被重新编码")
    }

    @Test
    fun `有原始字节时不去碰像素那一路`() {
        val original = byteArrayOf(1, 2, 3)
        val png = DataFlavor("image/png;class=java.io.InputStream")
        var pixelReads = 0

        val bytes = imageBytes(listOf(png, DataFlavor.imageFlavor)) { flavor ->
            if (flavor == DataFlavor.imageFlavor) {
                pixelReads++
                BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
            } else {
                original.inputStream()
            }
        }

        assertContentEquals(original, bytes)
        assertEquals(0, pixelReads, "有现成的图片字节就不该再去解码像素")
    }

    @Test
    fun `TIFF 那一条会让位给 PNG`() {
        // 实测的粘贴板形状：TIFF 与 PNG 各一条，而 TIFF 往往排在前面。界面画不出 TIFF（Skia 不认），
        // 原始 TIFF 又常常是未压缩的一整张大图——所以要挑 PNG 那一条。
        val tiff = DataFlavor("image/tiff;class=java.io.InputStream")
        val png = DataFlavor("image/png;class=java.io.InputStream")
        val original = byteArrayOf(9, 9, 9)
        val reads = mutableListOf<String>()

        val bytes = imageBytes(listOf(tiff, png)) { flavor ->
            // `mimeType` 带着 `class=…` 那一串参数，比之前先掐掉。
            reads += flavor.mimeType.substringBefore(';').trim()
            original.inputStream()
        }

        assertContentEquals(original, bytes)
        assertEquals(listOf("image/png"), reads, "画不出来的那条表示不该被读进来")
    }

    @Test
    fun `只有像素时才编成 PNG`() {
        val pixel = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB)

        val bytes = imageBytes(listOf(DataFlavor.imageFlavor)) { pixel }

        assertNotNull(bytes)
        val decoded = ImageIO.read(ByteArrayInputStream(bytes))
        assertNotNull(decoded, "编出来的应当是解码器认得的 PNG")
        assertEquals(3, decoded.width)
        assertEquals(2, decoded.height)
    }

    @Test
    fun `给不出可预览格式时退到像素那一路`() {
        // 粘贴板上只有 TIFF，且它同时以「像素」形态给出：走像素那一路，得到的是能预览的 PNG。
        val tiff = DataFlavor("image/tiff;class=java.io.InputStream")
        val pixel = BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
        var tiffReads = 0

        val bytes = imageBytes(listOf(tiff, DataFlavor.imageFlavor)) { flavor ->
            if (flavor == tiff) {
                tiffReads++
                null
            } else {
                pixel
            }
        }

        assertNotNull(bytes)
        assertNotNull(ImageIO.read(ByteArrayInputStream(bytes)), "退到像素那一路也应当是张画得出来的图")
        assertEquals(0, tiffReads, "能靠像素编出 PNG 就不必再去搬那份原始 TIFF")
    }

    @Test
    fun `连像素都没有时剩下的图片字节照搬`() {
        // 兜底：宁可 Base64 出来是一份用不上的格式，也别让用户按了粘贴却什么都没发生。
        val tiff = DataFlavor("image/tiff;class=java.io.InputStream")
        val original = byteArrayOf(4, 4, 4)

        val bytes = imageBytes(listOf(tiff)) { original.inputStream() }

        assertContentEquals(original, bytes)
    }

    @Test
    fun `没有图片表示时返回空`() {
        val text = DataFlavor("text/plain;class=java.io.InputStream")

        assertNull(imageBytes(listOf(text)) { "hello".byteInputStream() })
    }

    @Test
    fun `读不出来的表示继续往下找`() {
        val broken = DataFlavor("image/jpeg;class=java.io.InputStream")
        val png = DataFlavor("image/png;class=java.io.InputStream")
        val original = byteArrayOf(7, 7, 7)

        val bytes = imageBytes(listOf(broken, png)) { flavor ->
            if (flavor == broken) error("这条表示读不出来") else original.inputStream()
        }

        assertContentEquals(original, bytes, "一条表示失败不该让整次取图失败")
    }

    @Test
    fun `空的图片字节不算取到了图片`() {
        val png = DataFlavor("image/png;class=java.io.InputStream")

        // 粘贴板上声明了 image/png 但给的是空流：当作没有，交回调用方（而不是返回一个空字节数组，
        // 那会被当成「一张 0 字节的图片」一路编下去）。
        assertNull(imageBytes(listOf(png)) { ByteArray(0).inputStream() })
    }
}
