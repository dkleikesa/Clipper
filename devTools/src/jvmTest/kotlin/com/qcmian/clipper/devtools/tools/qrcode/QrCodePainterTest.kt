package com.qcmian.clipper.devtools.tools.qrcode

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.decodeToImageBitmap
import io.github.alexzhirkevich.qrose.ImageFormat
import io.github.alexzhirkevich.qrose.QrCodePainter
import io.github.alexzhirkevich.qrose.options.QrBackground
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.QrColors
import io.github.alexzhirkevich.qrose.options.QrErrorCorrectionLevel
import io.github.alexzhirkevich.qrose.options.QrOptions
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.toByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * 二维码工具依赖 qrose 的那条链路：**编码 → 矢量绘制 → 栅格化并编成 PNG**。
 *
 * 为什么值得单测：这三步全在库里，工具侧只是接起来，真跑错了在界面上也只表现为「图没出来」——
 * 而它的成因可能差很远（编码器抛错、绘制用的 Skiko 没到位、`toByteArray` 的平台实现缺失）。
 * 这里钉住两件事：
 *
 *  - 正常文本能一路导出成**解码得回来的 PNG**，尺寸与请求一致；
 *  - 文本超出容量时编码器会**抛错**（因此工具必须接住它，见 `QrCodeDevTool`）。
 */
class QrCodePainterTest {

    /** 与工具同口径的绘制选项：黑码白底 + 静区。 */
    private fun options(level: QrErrorCorrectionLevel) = QrOptions(
        colors = QrColors(
            dark = QrBrush.solid(Color.Black),
            light = QrBrush.solid(Color.White),
        ),
        background = QrBackground(fill = SolidColor(Color.White)),
        errorCorrectionLevel = level,
        scale = 0.8f,
    )

    @Test
    fun `二维码能导出成 PNG 且尺寸与请求一致`() {
        val painter = QrCodePainter("https://example.com", options(QrErrorCorrectionLevel.Medium))

        val bytes = painter.toByteArray(256, 256, ImageFormat.PNG)

        val decoded = assertNotNull(bytes.decodeToImageBitmap(), "导出的应当是解码器认得的图片")
        assertEquals(256, decoded.width)
        assertEquals(256, decoded.height)
    }

    @Test
    fun `文本超出容量时编码器抛错`() {
        // 二维码各等级都装不下这么多字符：工具必须接住这个异常并给出一句交代，
        // 而不是让它冒到组合里把窗口炸掉（见 `QrCodeDevTool` 的生成副作用）。
        val tooLong = "a".repeat(10_000)

        assertFailsWith<Exception> {
            QrCodePainter(tooLong, options(QrErrorCorrectionLevel.Low))
        }
    }
}
