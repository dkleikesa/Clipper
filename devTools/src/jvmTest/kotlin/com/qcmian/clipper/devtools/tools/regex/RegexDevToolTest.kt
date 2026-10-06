@file:OptIn(ExperimentalTestApi::class)

package com.qcmian.clipper.devtools.tools.regex

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.registry.DevToolsRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull


/**
 * 正则工具的界面验收：**接线**对不对。
 *
 * 为什么必须真跑组合：这一步要验的全是节点树上的事——页签点下去右边那一栏换没换、剪贴板内容有没有
 * 落进「待匹配文本」、敲完模式右边会不会跟着出来。这些纯逻辑测不出来，而它们恰恰是最容易悄悄断掉的
 * 一环（管线挂在哪个键上、框名挂在哪一层）。
 *
 * 断言落在用户看得见的东西上：框名、结果里那行文字。算得对不对由 `RegexFormatTest` 负责，这里只管
 * 「它有没有被显示出来」。
 */
class RegexDevToolTest {

    @Test
    fun `入口暂时没挂进注册表`() {
        // 正则工具先藏着入口、内容全留（见 `DevToolsRegistry.builtIn` 的说明）。这一条就是那道闸门：
        // 哪天有人把它挂回去，这里会红，提醒他顺手把文档与那两行注释一起翻过来。
        assertNull(DevToolsRegistry.builtIn().tool("regex"))
    }

    @Test
    fun `两页换的是右边那一栏`() = runComposeUiTest {
        render(RegexDevTool, input = null)

        // 默认在匹配页：右边那一栏叫「命中」，替换那一行不出现。
        onNodeWithText("命中").assertIsDisplayed()
        assertMissing("替换结果")
        assertMissing("替换为")

        onNodeWithText("替换").performClick()
        waitForIdle()

        // 换页换掉的是右边那一栏与多出来的那一行设定；模式那一行两页共用，得留着。
        onNodeWithText("替换结果").assertIsDisplayed()
        onNodeWithText("替换为").assertIsDisplayed()
        onNodeWithText("模式").assertIsDisplayed()
        assertMissing("命中")
    }

    @Test
    fun `剪贴板里的文本落进待匹配文本`() = runComposeUiTest {
        render(RegexDevTool, input = ClipItem(id = "id0", text = "hello\nworld"))

        // 逐行等，不等整段：默认那套代码框（KodeMirror）把文档摊成可视行来画，语义树里没有
        // 「带着换行的一整段文字」这个节点。两行分别出现，也就说明内容整份都进来了。
        awaitText("hello")
        awaitText("world")
    }

    @Test
    fun `写好模式后右边列出命中`() = runComposeUiTest {
        render(RegexDevTool, input = ClipItem(id = "id1", text = "hi there"))

        // 模式框是树里第一个可输入的节点（正文框在它下面）。这个位置约定本身也被下面那条断言
        // 盯着：敲错了框，模式就是空的，右边不会有任何报告出来。
        onAllNodes(hasSetTextAction())[0].performTextInput("""(?<word>\w+)""")

        // 手敲要过一道防抖，且计算在后台线程上跑——等它出现，不等「空闲」。
        awaitText("组 1(word) = hi")
    }

    @Test
    fun `没有命中时说没有命中，而不是说还没写模式`() = runComposeUiTest {
        render(RegexDevTool, input = ClipItem(id = "id3", text = "hi there"))

        // 还没写模式：空态说的是「它会给出什么」。
        awaitText("写好模式后，这里列出每一处命中的位置与捕获组")

        onAllNodes(hasSetTextAction())[0].performTextInput("zzz")

        // 写好了却一处都没撞上：这时说的是结果本身（空），不是再催一遍写模式。
        awaitText("没有匹配")
    }

    @Test
    fun `替换页按模板给出结果`() = runComposeUiTest {
        render(RegexDevTool, input = ClipItem(id = "id2", text = "a@b"))

        onAllNodes(hasSetTextAction())[0].performTextInput("""(\w+)@(\w+)""")
        onNodeWithText("替换").performClick()
        waitForIdle()

        // 替换页里模式是第一个可输入节点，替换模板是第二个（正文框还在它们下面）。
        onAllNodes(hasSetTextAction())[1].performTextInput("\$2@\$1")

        awaitText("b@a")
    }

    @Test
    fun `速查接进去的写法当场就能用`() = runComposeUiTest {
        render(RegexDevTool, input = ClipItem(id = "id5", text = "abc 12"))

        onNodeWithText("速查").performClick()
        waitForIdle()

        // 点「数字」那一行：`\d` 接进模式框，右边当场列出命中——这一步同时验了「接进去的是
        // 哪个框」与「接完之后照常算」。
        onNodeWithText("\\d").performClick()

        awaitText("#1  第 1 行 第 5 列  1")
    }

    @Test
    fun `匹配页不列替换模板那一组`() = runComposeUiTest {
        render(RegexDevTool, input = null)

        onNodeWithText("速查").performClick()
        waitForIdle()

        onNodeWithText("常用写法 · 点一下接进「模式」").assertIsDisplayed()
        // 替换为那个框还没画出来，列出来点了也没处落。
        assertMissing("替换模板 · 点一下接进「替换为」")
    }

    @Test
    fun `替换页里模板那一组才出现`() = runComposeUiTest {
        render(RegexDevTool, input = null)

        onNodeWithText("替换").performClick()
        waitForIdle()

        // 两个「速查」入口开的是同一张表（模式行一个、替换为行一个）。
        onAllNodesWithText("速查")[0].performClick()
        waitForIdle()

        onNodeWithText("替换模板 · 点一下接进「替换为」").assertIsDisplayed()
    }

    @Test
    fun `速查里的模板写法接进替换为`() = runComposeUiTest {
        val host = RecordingHost()
        render(RegexDevTool, input = ClipItem(id = "id6", text = "12-34"), host = host)

        onAllNodes(hasSetTextAction())[0].performTextInput("""(\d+)-(\d+)""")
        onNodeWithText("替换").performClick()
        waitForIdle()

        onAllNodesWithText("速查")[0].performClick()
        waitForIdle()
        onNodeWithText("\$1").performClick()

        // 模板成了 `$1`：只留下第一段——替换 1 处、结果是 2 个字符。这一步只能靠状态栏断言，
        // 界面上没有第二处能把「替换了几处」说清楚（结果是 `12`，正文里也有 `12`）。
        waitUntil(timeoutMillis = 10_000) { host.lastStatus?.startsWith("替换 1 处") == true }
    }

    // ---------------------------------------------------------------------------------------
    // 夹具

    /**
     * 把一个工具铺在测试窗口上。
     *
     * 直接调 `Content`（不套面板）：这一层要验的是**工具自己**，面板不参与——与
     * `DevToolDirectionTabsTest` 同一做法。
     */
    private fun ComposeUiTest.render(tool: RegexDevTool, input: ClipItem?, host: DevToolHost = RecordingHost()) {
        setContent {
            MaterialTheme {
                tool.Content(input = input, host = host)
            }
        }
        waitForIdle()
    }

    /** 等某段文案出现：计算在后台调度器上跑，慢一点的机器上得等一下。 */
    private fun ComposeUiTest.awaitText(text: String) {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeUiTest.assertMissing(text: String) {
        assertEquals(
            0,
            onAllNodesWithText(text).fetchSemanticsNodes().size,
            "「$text」不该出现在这一页上",
        )
    }

    /**
     * 只实现这个工具真会用到的那几道口子；其余接口自带空实现。
     *
     * 状态栏那一句记下来：有些结论（「替换了几处」）只在状态栏里说得清，而在界面上又正好没有
     * 第二个地方能断言。
     */
    private class RecordingHost : DevToolHost {
        var lastStatus: String? = null

        override fun copyToClipboard(text: String) = Unit

        override fun showStatus(message: String) = Unit

        override fun reportStatus(text: String?) {
            lastStatus = text
        }

        override fun pickFileToOpen(): String? = null

        override fun pickFileToSave(suggestedName: String): String? = null

        override fun droppedFilePaths(event: DragAndDropEvent): List<String> = emptyList()
    }
}
