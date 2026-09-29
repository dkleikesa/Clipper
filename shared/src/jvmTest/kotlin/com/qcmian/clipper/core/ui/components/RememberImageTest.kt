package com.qcmian.clipper.core.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import com.qcmian.clipper.core.domain.model.ClipImage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `rememberImage` 的状态语义。
 *
 * 这里守的是一个**踩过的坑**：状态若不带键，换图时上一张的位图会留在里面，producer 里的判空
 * 立刻命中，新图永远加载不出来——表现是预览切到别的条目后画面停住不动。
 * 只用 `ImageCache` 那一层的测试看不见这个问题，它只存在于组合语义里。
 */
@OptIn(ExperimentalTestApi::class)
class RememberImageTest {

    @Test
    fun `switching the payload swaps the loaded image`() = runComposeUiTest {
        val first = ClipImage(pngOf(width = 64, height = 32))
        val second = ClipImage(pngOf(width = 128, height = 64))
        var payload by mutableStateOf<ClipImage?>(first)
        var observed: LoadedImage? = null

        setContent {
            observed = rememberImage(payload, thumbnail = true)
        }

        // 首帧走的是异步解码，等它落地。
        waitUntil { observed != null }
        assertEquals(64, observed?.sourceWidth, "第一条应解出自己的图")

        payload = second

        waitUntil { observed?.sourceWidth == 128 }
        assertEquals(128, observed?.sourceWidth, "换图之后必须换成新图，而不是停在上一张")
    }

    @Test
    fun `switching to a payload without an image clears it`() = runComposeUiTest {
        val first = ClipImage(pngOf(width = 64, height = 32))
        var payload by mutableStateOf<ClipImage?>(first)
        var observed: LoadedImage? = null

        setContent {
            observed = rememberImage(payload, thumbnail = true)
        }
        waitUntil { observed != null }

        // 切到没有图片的条目：预览必须退回文本，而不是继续显示上一张图。
        payload = null

        waitUntil { observed == null }
    }

    private companion object {
        fun pngOf(width: Int, height: Int): ByteArray {
            val output = ByteArrayOutputStream()
            ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", output)
            return output.toByteArray()
        }
    }
}
