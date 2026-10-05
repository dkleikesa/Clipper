@file:OptIn(ExperimentalTestApi::class)

package com.qcmian.clipper.core.ui.code

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 光标闪不闪，**看得见**的那一半契约：没焦点的框里那条竖线一次都不该画出来。
 *
 * 背景：上游的 KodeMirror 画的是**静态**光标（画一次就一直在），本仓库给它加了明灭节奏。加节奏
 * 容易，跟着焦点停就容易漏——漏了的表现正是「点进旁边那个框、甚至切到别的应用之后，这个框的
 * 光标还在自顾自地闪」。判据与 Compose 原生光标逐字相同（`TextFieldCursor`：
 * `isWindowFocused && state.hasFocus`），因此这里逐条各钉一个用例：窗口不在前台、**同一个窗口里
 * 失去焦点的那个框**（两个框并排时最容易看出来），以及原生实现那条**自绘**的只读光标（平台的
 * 光标它管不着，只读框里那条是它自己画的，得自己看窗口焦点）。
 *
 * 断言取的是**连拍几帧之间的像素差**，不是某一帧的绝对像素：光标那一条只有两个像素宽，颜色又
 * 跟主题走，写死颜色等于把用例绑在配色上；而「闪」这件事本身就是「两帧不一样」。
 *
 * 每条「不该闪」的用例都配了一条「**该闪的确实在闪**」（同一个画面里那个有焦点的框，或
 * 把窗口焦点打开的那一版）：光标要是压根没画出来（焦点没拿到、排版没跑），「没闪」同样成立——
 * 那样用例就成了空过。两条一起才是完整的一句话。
 */
class CaretFocusTest {

    @Test
    fun `窗口没有焦点时光标不闪`() {
        runComposeUiTest {
            setUp(windowFocused = mutableStateOf(false))
            onNodeWithTag(KODEMIRROR_INPUT).requestFocus()
            waitForIdle()

            val frames = frames(FRAMES)

            assertEquals(
                0,
                maxDiff(frames),
                "窗口不在前台时光标还在闪——判据要跟 Compose 原生光标一致" +
                    "（isWindowFocused && hasFocus）",
            )
        }
    }

    @Test
    fun `窗口有焦点时光标在闪`() {
        runComposeUiTest {
            setUp(windowFocused = mutableStateOf(true))
            onNodeWithTag(KODEMIRROR_INPUT).requestFocus()
            waitForIdle()

            val frames = frames(FRAMES)

            // 这一条是上面那条的**反面对照**：光标真的在闪，才说明上面的「没闪」不是空过。
            assertTrue(
                maxDiff(frames) > 0,
                "有焦点时光标应当有明灭——否则「没焦点时不闪」那条断言等于什么都没验",
            )
        }
    }

    @Test
    fun `同一个窗口里失去焦点的那个框不闪`() {
        runComposeUiTest {
            // 两个框并排、用真实窗口信息（离屏场景恒为前台——`ImageComposeScene` 就是这么建的）：
            // 焦点只能在一个框上，另一个正是用户眼里的「没有焦点」。
            setUp(windowFocused = null, fieldCount = 2)
            // 下面那个拿走焦点，上面那个就此失去焦点。
            onAllNodesWithTag(KODEMIRROR_INPUT)[1].requestFocus()
            waitForIdle()

            val frames = frames(FRAMES)
            val height = frames.first().height
            val top = Region(0, 0, frames.first().width, height / 2)
            val bottom = Region(0, height / 2, frames.first().width, height)

            assertEquals(
                0,
                maxDiff(frames, top),
                "上面那个框已经没有焦点了，它的光标不该还在闪",
            )
            assertTrue(
                maxDiff(frames, bottom) > 0,
                "下面那个框有焦点，它应当还在闪——否则上一条断言等于什么都没验",
            )
        }
    }

    /**
     * 原生实现那一侧：**只读框**的光标是本仓库自绘的（平台在 `readOnly` 时压根不画光标，见
     * `NativeCodeField.caretRect`）。因此它也得自己看窗口焦点——平台那条光标由 Compose 自己管，
     * 自绘这条不管就只剩它在闪。
     *
     * 点一下才亮：原生框没有「一出现就自动聚焦」那一路（KodeMirror 有），点进来才有焦点，
     * 而「点进来」正是用户看到这条光标的方式。
     */
    @Test
    fun `原生只读框在窗口失焦时那条自绘光标不闪`() {
        runComposeUiTest {
            setUp(
                windowFocused = mutableStateOf(false),
                engine = CodeFieldEngine.Native,
                editable = false,
            )
            focusByTap()
            waitForIdle()

            assertEquals(
                0,
                maxDiff(frames(FRAMES)),
                "窗口不在前台时，原生只读框那条自绘光标还在闪",
            )
        }
    }

    @Test
    fun `原生只读框在窗口有焦点时那条自绘光标在闪`() {
        runComposeUiTest {
            setUp(
                windowFocused = mutableStateOf(true),
                engine = CodeFieldEngine.Native,
                editable = false,
            )
            focusByTap()
            waitForIdle()

            assertTrue(
                maxDiff(frames(FRAMES)) > 0,
                "有焦点时那条自绘光标应当有明灭——否则上一条断言等于什么都没验",
            )
        }
    }

    // ---------------------------------------------------------------------------------------
    // 夹具

    /**
     * 摆一个（或上下两个）[DevToolCodeField]。
     *
     * [windowFocused] 为 `null` 时用**场景自己的**窗口信息（离屏场景恒为前台）；否则换成一份假的
     * ——窗口焦点在离屏渲染里没别的地方能拨动，而它正是这一组用例的自变量。
     */
    private fun ComposeUiTest.setUp(
        windowFocused: State<Boolean>?,
        fieldCount: Int = 1,
        engine: CodeFieldEngine = CodeFieldEngine.Kodemirror,
        editable: Boolean = true,
    ) {
        setContent {
            MaterialTheme {
                val screen: @Composable () -> Unit = {
                    Column(Modifier.size(FIELD_WIDTH, FIELD_HEIGHT)) {
                        repeat(fieldCount) {
                            DevToolCodeField(
                                label = "输入",
                                value = TEXT,
                                onValueChange = {},
                                editable = editable,
                                // 行号与折叠都关掉：画面里因此只剩正文与光标，像素差只可能来自光标。
                                lineNumbers = false,
                                folding = false,
                                softWrap = true,
                                modifier = Modifier.fillMaxWidth().weight(1f),
                            )
                        }
                    }
                }
                CompositionLocalProvider(LocalCodeFieldEngine provides engine) {
                    if (windowFocused == null) {
                        screen()
                    } else {
                        CompositionLocalProvider(
                            LocalWindowInfo provides FakeWindowInfo(windowFocused)
                        ) {
                            screen()
                        }
                    }
                }
            }
        }
    }

    /**
     * 连拍 [count] 帧，帧间推进 [FRAME_STEP_MILLIS] 的测试时钟。
     *
     * 步长取得比明灭半周期（500ms）大：相邻两帧必定落在相反的相位上，光标「在闪」才跑得出来。
     * 时钟是**测试自己的**（`mainClock`），`LaunchedEffect` 里那个 `delay` 跟着它走——不用真的
     * sleep，用例也就不会慢。
     */
    private fun ComposeUiTest.frames(count: Int): List<BufferedImage> {
        val shots = mutableListOf<BufferedImage>()
        repeat(count) {
            shots += onRoot().captureToImage().toAwtImage()
            mainClock.advanceTimeBy(FRAME_STEP_MILLIS)
            waitForIdle()
        }
        return shots
    }

    /** 点一下正文把它点亮：原生实现只在点进来之后才有焦点（`focusRequester.requestFocus()`）。 */
    private fun ComposeUiTest.focusByTap() {
        val root = onRoot().fetchSemanticsNode().size
        val position = Offset(root.width * 0.4f, root.height * 0.7f)
        onRoot().performMouseInput {
            // 落在正文那一块里（标题行之下、避开右缘的滚动条）。
            moveTo(position)
            press(MouseButton.Primary)
            release(MouseButton.Primary)
        }
    }

    /** 连拍里**任何一对**帧之间差异最大的那个像素数（0 = 一帧都没变过）。 */
    private fun maxDiff(frames: List<BufferedImage>, region: Region? = null): Int {
        var worst = 0
        for (i in frames.indices) {
            for (j in i + 1 until frames.size) {
                worst = maxOf(worst, diff(frames[i], frames[j], region))
            }
        }
        return worst
    }

    /** 两张同尺寸图在 [region] 里明显不同的像素个数（每通道容 8 级，避开抗锯齿的零头）。 */
    private fun diff(a: BufferedImage, b: BufferedImage, region: Region?): Int {
        val left = region?.left ?: 0
        val top = region?.top ?: 0
        val right = region?.right ?: minOf(a.width, b.width)
        val bottom = region?.bottom ?: minOf(a.height, b.height)
        var changed = 0
        for (y in top until minOf(bottom, minOf(a.height, b.height))) {
            for (x in left until minOf(right, minOf(a.width, b.width))) {
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

    /** 图上一块矩形（像素坐标），用来把「上面的框」与「下面的框」分开量。 */
    private data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * 只有 [WindowInfo.isWindowFocused] 一位是真拨得动的：其余几位在离屏场景里没有意义，
     * 而 `WindowInfo` 给它们都留了默认实现。
     */
    private class FakeWindowInfo(private val focused: State<Boolean>) : WindowInfo {
        override val isWindowFocused: Boolean get() = focused.value
    }

    private companion object {
        /** 两个框上下摞起来的画布；一个框时也用它，尺寸不影响判定。 */
        val FIELD_WIDTH = 360.dp
        val FIELD_HEIGHT = 240.dp

        /** 上游隐藏输入框的 tag，见 `KodeMirror.kt` 里那个 `Modifier.testTag`。 */
        const val KODEMIRROR_INPUT = "KodeMirror_input"

        /** 帧间隔：比光标明灭的半周期（500ms）大一截，相邻两帧必定相位相反。 */
        const val FRAME_STEP_MILLIS = 620L

        /** 连拍帧数：够覆盖「亮—灭—亮」两段变化。 */
        const val FRAMES = 3

        /** 一行的长文，光标落在行首（首帧的默认选区），一定在可见区域内。 */
        const val TEXT = "let answer = 42 and some more text to look at"
    }
}
