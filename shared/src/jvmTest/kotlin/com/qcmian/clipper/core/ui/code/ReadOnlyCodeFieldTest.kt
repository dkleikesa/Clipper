@file:OptIn(ExperimentalTestApi::class)

package com.qcmian.clipper.core.ui.code

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 只读结果框（`editable = false`）的**可见**契约：改不动，但拖得出选区。
 *
 * 与 [CodeFieldContractTest] 不同，这里断言的是**画出来的东西**，不是语义树。选区压根不进语义树
 * （由 KodeMirror 自己画），而这条契约恰恰错在「画不出来」上——曾经有一版把只读框的选区绘制整个
 * 关掉，拖过去状态里其实选中了、画面上却一片空白（看着就像选不了，也就没人会去复制）。
 *
 * 判据是**同一份组合拖动前后的像素差**，不是绝对像素值：本机字体怎么渲染都不影响「拖过之后多出
 * 一块选区色」这件事。
 */
class ReadOnlyCodeFieldTest {

    @Test
    fun `只读框里拖一下看得见选区`() = runComposeUiTest {
        underTest()
        waitForIdle()
        val before = onRoot().captureToImage().toAwtImage()

        onRoot().performMouseInput {
            moveTo(Offset(60f, 100f))
            press(MouseButton.Primary)
            moveTo(Offset(200f, 100f))
            moveTo(Offset(380f, 100f))
            release(MouseButton.Primary)
        }
        waitForIdle()
        val after = onRoot().captureToImage().toAwtImage()

        val changed = diff(before, after)
        assertTrue(
            changed > MIN_SELECTION_PIXELS,
            "只读框里拖过之后画面只变了 $changed 个像素——选区没画出来（只读不等于选不了）",
        )
    }

    @Test
    fun `只读框换 value 照样显示出新内容`() = runComposeUiTest {
        // 只读会话把「改文档」的事务全丢了（见 `EditorSessionImpl.programmaticDocChange`），而宿主换
        // 内容走的正是派发事务那条路：这一条钉住那个旁路没被闸门一起挡掉——否则只读结果框会永远
        // 停在第一次显示的内容上（Base64 这类「改输入、看输出」的工具立刻就是废的）。
        val value = mutableStateOf("第一份内容")
        underTest { value.value }
        onNodeWithText("第一份内容").assertIsDisplayed()

        value.value = "第二份内容"
        waitForIdle()

        onNodeWithText("第二份内容").assertIsDisplayed()
    }

    /** 走业务层真正走的那条路（[DevToolCodeField]）。 */
    private fun ComposeUiTest.underTest(value: () -> String = { LONG_TEXT }) {
        setContent {
            MaterialTheme {
                Box(Modifier.size(420.dp, 240.dp).background(Color(0xFF20242C))) {
                    DevToolCodeField(
                        label = "结果 · Base64",
                        value = value(),
                        onValueChange = {},
                        editable = false,
                        softWrap = true,
                        folding = false,
                        modifier = Modifier.size(420.dp, 240.dp),
                    )
                }
            }
        }
    }

    /** 两张同尺寸图里明显不同的像素个数（每通道容 8 级，避开抗锯齿的零头）。 */
    private fun diff(a: BufferedImage, b: BufferedImage): Int {
        var changed = 0
        for (y in 0 until minOf(a.height, b.height)) {
            for (x in 0 until minOf(a.width, b.width)) {
                val p = a.getRGB(x, y)
                val q = b.getRGB(x, y)
                val delta = abs((p and 0xFF) - (q and 0xFF)) +
                    abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) +
                    abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF))
                if (delta > 24) changed++
            }
        }
        return changed
    }

    private companion object {
        /**
         * 一块拖出来的选区在这一档尺寸下是几千个像素（实测在 4600 以上）。取 1000 当下限：
         * 光标这类零星变化混不进来，而「一个像素都没画」这种回归躲不掉。
         */
        const val MIN_SELECTION_PIXELS = 1000

        /** 拖选用的一条长文：折行开关打开，于是几个可视行连成一片。 */
        val LONG_TEXT = (1..40).joinToString(" ") { "segment$it" }
    }
}
