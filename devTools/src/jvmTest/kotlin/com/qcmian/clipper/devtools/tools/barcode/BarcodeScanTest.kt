package com.qcmian.clipper.devtools.tools.barcode

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.alexzhirkevich.qrose.ImageFormat
import io.github.alexzhirkevich.qrose.toByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 认码：**把这张工具自己编出来的图再认回来**。
 *
 * 用自家编码器产图，是因为那正是用户手里的图——「生成」页存下来的 PNG、发给别人的截图，回头都
 * 会喂进「解码」页。往返因此是最强的一条判据：编码器与识别器有一边变了，这里立刻红。
 *
 * 三件事各自钉一条：
 *  - **码制**：二维码与一维码各走一遍（两者的识别路径完全不同：矩阵 vs 条空）；
 *  - **缩放**：长边 2400 的图先被缩到 1600 再认（手机照片走的就是这一路，见 `downscaledForScan`）；
 *  - **多码与认不出**：一张图里两个码都要给出来；白图与「不是图片的字节」各归各的结论——前者是
 *    「没找到码」，后者是「这图读不出来」，界面靠这个区分说话（见 `ScanOutcome`）。
 */
class BarcodeScanTest {

    @Test
    fun `二维码往返：编出来的图能认回原文`() {
        // 中文与查询串都带上：UTF-8 走的是字节模式，认回来必须一字不差。
        val text = "https://example.com/中文?q=1"

        val ready = scanToReady(codePng(BarcodeFormat.Qr, text))

        assertEquals(listOf(text), ready.hits.map { it.text })
        assertEquals("QR", barcodeFormatLabel(ready.hits.first().format))
    }

    @Test
    fun `Aztec 与 PDF417 也认得出`() {
        // Aztec 用**中文**：它不声明字符集（qrose 那份编码器是 ZXing 编码器的移植，ZXing 自己也不
        // 声明），识别器会按 ISO-8859-1 解成一串西欧字母——`repairTextEncoding` 要修的正是这一路，
        // 编回来必须一字不差。真机上就是这么暴露的：拿自家编的 Aztec 回头解，出来一片乱码。
        val chinese = "Base64 URL 条码(解码页面先留白占位)"
        assertEquals(
            listOf(chinese),
            scanToReady(codePng(BarcodeFormat.Aztec, chinese)).hits.map { it.text },
        )
        assertEquals(
            listOf("PAYLOAD-42"),
            scanToReady(codePng(BarcodeFormat.Pdf417, "PAYLOAD-42")).hits.map { it.text },
        )
    }

    /**
     * 字符集修复的判据：只动真正的乱码，**宁可什么都不做，也不把对的改坏**。
     *
     * 乱码在测试里现造（把原文的 UTF-8 字节逐个当 ISO-8859-1 字符读出来），而不是抄一串固定的乱码
     * 字面量：抄下来的那串会随着改动悄悄过期，造出来的永远是「识别器真会给的那一份」。
     */
    @Test
    fun `字符集修复只动真正的乱码`() {
        val original = "Base64 URL 条码(解码页面先留白占位)"
        val mojibake = original.encodeToByteArray()
            .map { (it.toInt() and 0xFF).toChar() }
            .joinToString("")

        assertEquals(original, repairTextEncoding(mojibake), "被按 ISO-8859-1 解坏的中文要修回来")
        assertEquals(original, repairTextEncoding(original), "已经是对的不动（字符超过 0xFF，进不了那一步）")
        // 真正的 ISO-8859-1 文本：`é` 单独一个字节，成不了合法的 UTF-8 序列，原样留下。
        assertEquals("café", repairTextEncoding("café"))
        assertEquals("ABC-1234", repairTextEncoding("ABC-1234"))
        assertEquals("", repairTextEncoding(""))
    }

    @Test
    fun `一维码往返：Code 128 与 EAN-13`() {
        val code128 = scanToReady(codePng(BarcodeFormat.Code128, "ABC-1234"))
        assertEquals(listOf("ABC-1234"), code128.hits.map { it.text })
        assertEquals("Code 128", barcodeFormatLabel(code128.hits.first().format))

        // EAN-13 的校验位是编码器补的；认回来的必须是**补全后**的那一串，而不是用户敲的 12 位。
        val ean = scanToReady(codePng(BarcodeFormat.Ean13, "4006381333931"))
        assertEquals(listOf("4006381333931"), ean.hits.map { it.text })
        assertEquals("EAN-13", barcodeFormatLabel(ean.hits.first().format))
    }

    /**
     * 大图先缩再认：手机的截图与照片动辄 3000+ 像素，那条路必须照样认得出来。
     *
     * 这条用例**专门**守住缩放：图小了根本走不到 `downscaledForScan`，把那段删掉也不会红。
     */
    @Test
    fun `长边超过缩放上限的图照样认得出`() {
        val png = codePng(BarcodeFormat.Qr, "https://example.com/big", longSide = 2400)

        // 先确认这张图真的够大（缩放的判据是长边，编码器给的像素不能缩水）。
        assertEquals(2400, pngSize(png).let { (width, height) -> maxOf(width, height) })

        val ready = scanToReady(png)

        assertEquals(listOf("https://example.com/big"), ready.hits.map { it.text })
    }

    @Test
    fun `一张图里两个码都认出来`() {
        val ready = scanToReady(twoQrPng("first-code", "second-code"))

        assertEquals(
            setOf("first-code", "second-code"),
            ready.hits.map { it.text }.toSet(),
            "两个都要给出来——单码读法只会给出其中一个（见 `BarcodeScan.jvm.kt`）",
        )
    }

    @Test
    fun `图里没有码是没找到，不是读不出来`() {
        val ready = scanToReady(blankPng())

        assertTrue(ready.hits.isEmpty(), "白图上不该凭空认出一个码")
    }

    @Test
    fun `不是图片的字节归到读不出来`() {
        assertIs<ScanOutcome.Unreadable>(scanBarcodeImage("这不是一张图片".encodeToByteArray()))
        assertIs<ScanOutcome.Unreadable>(scanBarcodeImage(ByteArray(0)))
    }

    // ---------------------------------------------------------------------------------------
    // 夹具

    /** 认一张图，并断言它至少被读出来了（`Unreadable` 单独一条用例管）。 */
    private fun scanToReady(bytes: ByteArray): ScanOutcome.Ready =
        assertIs<ScanOutcome.Ready>(scanBarcodeImage(bytes))

    /** 用工具自己的编码器画一张 PNG——认码的图，就该是这张工具真会产出的那种。 */
    private fun codePng(format: BarcodeFormat, text: String, longSide: Int = 320): ByteArray {
        val code = encodeBarcode(format, text, QrErrorLevel.Medium)
        val size = exportSizeOf(code.painter, longSide)
        return renderPng(code.painter, size.width, size.height)
    }

    /** 一张纯白图：给「图里没有码」用。 */
    private fun blankPng(width: Int = 240, height: Int = 240): ByteArray =
        drawPng(width, height) { drawRect(Color.White) }

    /** 并排两张二维码：给「一张图里好几个码」用。 */
    private fun twoQrPng(left: String, right: String, side: Int = 320): ByteArray {
        val first = encodeBarcode(BarcodeFormat.Qr, left, QrErrorLevel.Medium).painter
        val second = encodeBarcode(BarcodeFormat.Qr, right, QrErrorLevel.Medium).painter
        return drawPng(side * 2, side) {
            drawRect(Color.White)
            translate(left = 0f, top = 0f) { with(first) { draw(Size(side.toFloat(), side.toFloat())) } }
            translate(left = side.toFloat(), top = 0f) {
                with(second) { draw(Size(side.toFloat(), side.toFloat())) }
            }
        }
    }

    /** 白底 PNG。与 `renderPng` 同一套画法，只是这里要自己铺排内容。 */
    private fun drawPng(width: Int, height: Int, block: DrawScope.() -> Unit): ByteArray {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(
            density = Density(1f, 1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap),
            size = Size(width.toFloat(), height.toFloat()),
        ) { block() }
        return bitmap.toByteArray(ImageFormat.PNG)
    }

    /** 把 PNG 解回位图，只为量一量尺寸（见上面那条缩放用例）。 */
    private fun pngSize(bytes: ByteArray) = bytes.decodeToImageBitmap().let { it.width to it.height }
}
