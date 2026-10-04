@file:OptIn(ExperimentalTestApi::class)

package com.qcmian.clipper.devtools.ui.components.code

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.devtools.registry.DevToolsRegistry
import com.qcmian.clipper.devtools.ui.DevToolsPanel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [CodeFieldEngine.Kodemirror] 那一侧的**冒烟**：真跑一遍组合，确认它画得出来、装订线里有行号、
 * 编辑与回灌两条数据路都通。
 *
 * 之所以要「真跑组合」：这一侧的行号、折叠箭头、当前行底纹全在 KodeMirror 的插件管线里，
 * 桥接或扩展配错的表现不是编译错误，而是**画出来少一块**——纯逻辑测不出来（见
 * [KodemirrorScanTest]，那里验的是另一半：区间与着色片段算得对不对）。
 *
 * 两套实现**共同**的那部分契约不在这里，见 [CodeFieldContractTest]——同一批断言对每个实现都跑。
 *
 * 断言的取法：装订线里的行号是 `BasicText`，因此**有语义节点**；而原生实现的行号画在 Canvas 上、
 * 没有语义节点。下面几条正是靠这个差别证明「开关真的换了实现」。
 *
 * 离屏渲染验不了的（要在面板里手动过）：点折叠箭头折起来、光标旁括号亮起、输入法上屏、
 * 滚轮与触控板滚动，以及**拖滚动条时的手感**——滚动条本身有下面两条拖拽用例兜着，但「一帧里
 * 要量多少新露出的行」这类开销，测试框架量不出来。
 */
class KodemirrorCodeFieldTest {

    @Test
    fun `渲染出行号与正文`() = runComposeUiTest {
        // 正文里没有数字，所以「3」只可能是第三行的行号。
        field(value = THREE_LINES)

        onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `关掉行号后装订线里没有数字`() = runComposeUiTest {
        field(value = THREE_LINES, lineNumbers = false)

        onNodeWithText("2").assertDoesNotExist()
    }

    @Test
    fun `只读结果框照样画行号`() = runComposeUiTest {
        field(value = THREE_LINES, readOnly = true)

        onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `拖动右侧滚动条能滚动编辑区`() = runComposeUiTest {
        // 两百行，一屏放不下：末行的行号一开始看不见。
        val document = (1..200).joinToString("\n") { "line$it" }
        underTest({ CodeFieldEngine.Kodemirror }) {
            DevToolCodeField(
                label = "输入",
                value = document,
                onValueChange = {},
                modifier = Modifier.fillMaxSize(),
            )
        }
        onNodeWithText("200").assertDoesNotExist()

        // 轨道顶端从哪开始：正文框在标题行之下，而标题行的高度不是这里该猜的东西——
        // 直接问第一行的行号落在哪，从它上面一点开始按（滑块此刻就贴在轨道顶端）。
        val trackTop = onNodeWithText("1").fetchSemanticsNode().boundsInRoot.top

        // 抓住滑块，一路拖到轨道末端。
        onRoot().performTouchInput {
            down(Offset(right - 6f, trackTop + 2f))
            moveTo(Offset(right - 6f, bottom - 4f))
            up()
        }

        // 滚到底之后末行的行号才出现：这一条证明右侧那条滚动条驱动的**确实是编辑器的行列表**
        // （它是画在编辑器内部的，所以「画出来」与「拖得动」是两件事，只有拖一下才知道）。
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithText("200").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `拖动滑块滚过的距离与手指一致`() = runComposeUiTest {
        val lines = 200
        underTest({ CodeFieldEngine.Kodemirror }) {
            DevToolCodeField(
                label = "输入",
                value = (1..lines).joinToString("\n") { "line$it" },
                onValueChange = {},
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 先把几何量出来：行高（相邻两行行号的顶距）、轨道长（正文顶端到窗口底边），
        // 再推出「滑块行程 ↔ 内容可滚像素」之比。都用实测值，不写死任何像素。
        val rootHeight = onRoot().fetchSemanticsNode().size.height.toFloat()
        val trackTop = onNodeWithText("1").fetchSemanticsNode().boundsInRoot.top
        val rowHeight = onNodeWithText("2").fetchSemanticsNode().boundsInRoot.top - trackTop
        val trackLength = rootHeight - trackTop
        val contentHeight = rowHeight * lines
        val scrollable = contentHeight - trackLength
        val thumbLength = trackLength * trackLength / contentHeight
        val travel = trackLength - thumbLength
        val ratio = scrollable / travel

        // 抓住滑块（此刻贴顶）往下拖 30% 行程，**分 40 步**。
        //
        // 这条测的是「换算比例对」：滑块行程 ↔ 内容可滚像素是同一份换算，拖 30% 就该滚 30%
        // 的内容（比例写反、乘除颠倒都会在这里红）。
        //
        // **它测不出真机上那种卡顿**，别指望它：真机上指针事件比帧密，滚动位置在一帧里要被改写
        // 很多次，而测试框架在两次注入之间会把协程与帧都跑完——这个前提在测试里根本不成立。
        // 实测把增量式（`draggable` + `scrollBy`）或异步式（`scrollToItem`）的实现放回去，同一条
        // 用例照样通过；两种写法在真机上的问题见 `KodeMirror.kt` 里 `scrollToContentOffset` 的说明。
        val startY = trackTop + 2f
        val distance = travel * 0.3f
        val expectedFirstVisible = 1 + (distance * ratio / rowHeight).toInt()

        onRoot().performTouchInput {
            down(Offset(right - 6f, startY))
            repeat(40) { step ->
                moveTo(Offset(right - 6f, startY + distance * (step + 1) / 40f))
            }
            up()
        }

        // 期望的那一行已经在视野里；而行程一半处（约第 2 行）早该滚过去——丢位移的实现两头都不满足。
        onNodeWithText(expectedFirstVisible.toString()).assertIsDisplayed()
        onNodeWithText((expectedFirstVisible / 2).toString()).assertDoesNotExist()
    }

    @Test
    fun `敲进去的字会回调出去`() = runComposeUiTest {
        var typed = ""
        field(value = "", onValueChange = { typed = it })

        // KodeMirror 真正收键盘的是那个 1dp 的隐藏输入框（上游自己的 testTag）。
        onNodeWithTag("KodeMirror_input").performTextInput("abc")

        waitUntil { typed.isNotEmpty() }
        assertEquals("abc", typed)
    }

    /**
     * 折叠起来的那一行显示成 `{…}`，点那个 `…` 要能展开。
     *
     * 那条 `…` 是注进正文的一个**字符**（折叠的替换装饰带的 widget 从来没被画出来，见
     * `FoldWidget` 的说明），没有自己的节点可以点，所以这里横着扫正文最左端那一小段：哪一格能
     * 展开都算过——它左右各留了一点命中余量（`FoldPlaceholderTapSlop`），落在字形边缘也该中。
     *
     * 扫描同时钉住三件事：`…` 确实画在正文里（没画出来一处都点不着）、点它只展开**一次**、
     * 展开后那一行真的回到屏幕上。扫不到就直接失败，不会静默跳过。
     *
     * 折叠用装订线那一格完成（它的坐标由行号节点实测给出），点 `…` 才是这一条要验的展开路径。
     */
    @Test
    fun `点折叠占位符能展开`() = runComposeUiTest {
        field(value = FOLDABLE)
        onNodeWithText("3").assertIsDisplayed()

        // 折叠：点装订线第一行那一格（行号节点的右边就是折叠列）。
        val lineOne = onNodeWithText("1").fetchSemanticsNode().boundsInRoot
        onRoot().performTouchInput {
            down(Offset(lineOne.right + 9f, (lineOne.top + lineOne.bottom) / 2f))
            up()
        }
        waitForIdle()
        onNodeWithText("3").assertDoesNotExist()

        // 展开：点正文里的 `…`。从正文最左端横着扫，落点带风就往后挪几像素。
        val rowCenterY = (lineOne.top + lineOne.bottom) / 2f
        val contentLeft = lineOne.right + 26f
        val tappedX = (0..12).firstOrNull { step ->
            val x = contentLeft + step * 4f
            onRoot().performTouchInput {
                down(Offset(x, rowCenterY))
                up()
            }
            waitForIdle()
            onAllNodesWithText("3").fetchSemanticsNodes().isNotEmpty()
        }
        assertNotNull(tappedX, "正文最左端这一段里应当有一处能点开折叠的 `…`")
        onNodeWithText("3").assertIsDisplayed()

        // 再点同一下：已经没有折叠可展开了，这一下不该把什么又折回去。
        onRoot().performTouchInput {
            down(Offset(contentLeft + tappedX * 4f, rowCenterY))
            up()
        }
        waitForIdle()
        onNodeWithText("3").assertIsDisplayed()
    }


    @Test
    fun `外部换内容会回灌且不再回调回去`() = runComposeUiTest {
        var current by mutableStateOf(THREE_LINES)
        var callbacks = 0
        underTest({ CodeFieldEngine.Kodemirror }) {
            DevToolCodeField(
                label = "输入",
                value = current,
                onValueChange = { callbacks++ },
                modifier = Modifier.fillMaxSize(),
            )
        }
        onNodeWithText("3").assertIsDisplayed()

        // 面板换了一条剪贴板记录 / 点了格式化：整篇回灌。
        current = FOUR_LINES

        onNodeWithText("4").assertIsDisplayed()
        assertEquals(
            0,
            callbacks,
            "回灌是面板自己的动作，不该让工具把自己刚写进去的那份文本再解析一遍",
        )
    }

    @Test
    fun `软折行开关能动态重配`() = runComposeUiTest {
        var wrap by mutableStateOf(false)
        underTest({ CodeFieldEngine.Kodemirror }) {
            DevToolCodeField(
                label = "输入",
                value = ONE_LONG_LINE,
                onValueChange = {},
                softWrap = wrap,
                modifier = Modifier.fillMaxSize(),
            )
        }
        waitForIdle()

        // Base64 结果框那个折行开关走的就是这条路：派发一次 compartment 重配。
        // 重配写错（例如拿一个不在会话里的 compartment 去 reconfigure）会在这里抛出来。
        wrap = true
        waitForIdle()

        assertNotNull(onNodeWithText("输入").fetchSemanticsNode())
    }

    @Test
    fun `两套实现渲染同一段 XML 都不崩`() = runComposeUiTest {
        var engine by mutableStateOf(CodeFieldEngine.Native)
        underTest({ engine }) {
            DevToolCodeField(
                label = "输入",
                value = XML,
                onValueChange = {},
                // XML 那一侧的可折区间由 `scanXml` 给出（元素而不是括号），这里顺带把它跑一遍。
                scan = ::scanXml,
                folding = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
        waitForIdle()

        engine = CodeFieldEngine.Kodemirror

        assertNotNull(onNodeWithText("输入").fetchSemanticsNode())
    }

    @Test
    fun `面板开关能在两套实现之间来回切`() = runComposeUiTest {
        var engine by mutableStateOf(CodeFieldEngine.Native)
        underTest({ engine }) {
            DevToolCodeField(
                label = "输入",
                value = THREE_LINES,
                onValueChange = {},
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 原生实现：标题在，但行号画在 Canvas 上，没有语义节点。
        onNodeWithText("输入").assertIsDisplayed()
        onNodeWithText("3").assertDoesNotExist()

        engine = CodeFieldEngine.Kodemirror

        // 切过去之后行号成了真的文本节点——这一条同时证明了开关确实换了实现。
        onNodeWithText("3").assertIsDisplayed()
        onNodeWithText("输入").assertIsDisplayed()
    }

    @Test
    fun `不提供局部值时代码框默认就是 KodeMirror`() = runComposeUiTest {
        // 刻意**不**提供 `LocalCodeFieldEngine`：走的就是全项目默认那条路。
        underTest({ null }) {
            DevToolCodeField(
                label = "输入",
                value = THREE_LINES,
                onValueChange = {},
                modifier = Modifier.fillMaxSize(),
            )
        }

        // 行号只有 KodeMirror 那一侧才是真的文本节点，所以这一条同时证明了「默认换过去了」。
        onNodeWithText("3").assertIsDisplayed()
    }

    @Test
    fun `整块面板默认就用 KodeMirror 渲染`() = runComposeUiTest {
        panel(text = THREE_LINES)

        // 取「1」而不是别的行号：每个框的第一行总是画出来的，而结果框有几行取决于当前的
        // 格式化模式（压缩时只有一行），拿第三行去断言会看模式的脸色。
        // 正文（`{"a": "x"}`）与侧边栏里都没有单独一个「1」，所以这些节点只可能是行号。
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("1").fetchSemanticsNodes().size >= 2
        }

        // JSON 工具的**输入与结果两个框**各有一份行号——两个都换过去了，而不是只换了其中一个。
        assertTrue(
            onAllNodesWithText("1").fetchSemanticsNodes().size >= 2,
            "输入框与结果框都应当是 KodeMirror（行号各一份）",
        )
    }

    @Test
    fun `XML 工具的两个框也一起换了`() = runComposeUiTest {
        panel(text = XML)

        // 与 JSON 那条同一个判据：两个框各有一份「1」。
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("1").fetchSemanticsNodes().size >= 2
        }
        assertTrue(onAllNodesWithText("1").fetchSemanticsNodes().size >= 2)
    }

    // ---------------------------------------------------------------------------------------
    // 夹具

    /**
     * 把引擎钉死成 [engine]（返回 `null` 表示**刻意不提供**，走全项目默认），然后走业务层真正
     * 走的那条入口（[DevToolCodeField]）。
     *
     * [engine] 是**取值的函数**而不是值本身：`engine` 常是测试里的 `var ... by mutableStateOf`，
     * 如果把它的值当参数传进来，这次读取就发生在组合**之外**——组合不会订阅它，测试里改了它
     * 也不会重组，于是「切了引擎但界面没换」这种假绿会一直挂着（本项目真踩过一次）。
     */
    private fun ComposeUiTest.underTest(
        engine: () -> CodeFieldEngine?,
        content: @Composable () -> Unit,
    ) {
        setContent {
            MaterialTheme {
                val current = engine()
                if (current == null) {
                    content()
                } else {
                    CompositionLocalProvider(LocalCodeFieldEngine provides current) { content() }
                }
            }
        }
    }

    /** 与面板里同一个调用姿势：引擎钉死在 KodeMirror。 */
    private fun ComposeUiTest.field(
        value: String,
        onValueChange: (String) -> Unit = {},
        lineNumbers: Boolean = true,
        readOnly: Boolean = false,
    ) {
        underTest({ CodeFieldEngine.Kodemirror }) {
            DevToolCodeField(
                label = "输入",
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxSize(),
                readOnly = readOnly,
                lineNumbers = lineNumbers,
            )
        }
    }

    /** 渲染**真的 `DevToolsPanel`**：从工具注册表到代码框整条路都走一遍。 */
    private fun ComposeUiTest.panel(text: String) {
        setContent {
            MaterialTheme {
                DevToolsPanel(
                    registry = DevToolsRegistry.builtIn(),
                    item = ClipItem(id = "id0", text = text),
                    onClose = {},
                    onCopyToClipboard = {},
                )
            }
        }
    }

    private companion object {
        /** 三行、且正文里不含数字：这样「3」就只可能是行号。 */
        const val THREE_LINES = "{\n  \"a\": \"x\"\n}"

        /** 四行，同样不含数字（用来验「外部回灌之后行数确实变了」）。 */
        const val FOUR_LINES = "{\n  \"a\": \"x\",\n  \"b\": \"y\"\n}"

        /** 一行超长内容：验折行开关的重配路径。 */
        const val ONE_LONG_LINE = "{\"a\": \"一个足够长的值，长到在窄框里一行放不下\"}"

        const val XML = "<a>\n  <b/>\n</a>"

        /** 三行、且第一行能折起来（`{` 与 `}` 跨行）：点折叠占位符那条用例用它。 */
        const val FOLDABLE = "{\n  \"a\": 1\n}"
    }
}
