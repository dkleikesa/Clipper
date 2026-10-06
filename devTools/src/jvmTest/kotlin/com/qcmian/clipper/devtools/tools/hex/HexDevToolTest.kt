@file:OptIn(ExperimentalTestApi::class)

package com.qcmian.clipper.devtools.tools.hex

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.registry.DevToolsRegistry
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * 二进制查看的界面验收：**接线**对不对。
 *
 * 版面本身（空格落在哪、组间空几格、末行怎么补）由 `HexFormatTest` 盯死，这里只管几件纯逻辑测不
 * 出来的事——工具有没有挂进注册表、三个版面选项与输入框画没画出来、剪贴板内容有没有真的走完
 * 「取文本 → 排 dump → 报到状态栏」这条路。
 *
 * 断言取状态栏而不是去语义树里找 dump 正文：默认那套代码框（KodeMirror）把文档摊成可视行来画，
 * 一整段 dump 在语义树里并不是一个节点（与 `RegexDevToolTest` 取按行等是同一个原因）。
 */
class HexDevToolTest {

    @Test
    fun `工具挂进了注册表`() {
        assertNotNull(DevToolsRegistry.builtIn().tool("hex"))
    }

    @Test
    fun `输入框与三个版面选项都画得出来`() = runComposeUiTest {
        render(HexDevTool, input = null)

        onNodeWithText("输入").assertIsDisplayed()
        onNodeWithText("每行 16").assertIsDisplayed()
        onNodeWithText("大写").assertIsDisplayed()
        onNodeWithText("字符列").assertIsDisplayed()
    }

    @Test
    fun `剪贴板里的文本排成 dump 并报到状态栏`() = runComposeUiTest {
        val host = RecordingHost()
        render(HexDevTool, input = ClipItem(id = "id0", text = "Hello"), host = host)

        // 五个字节排成一行；这一句只在「取文本 → 排 dump」都走完之后才会出现。
        waitUntil(timeoutMillis = 10_000) { host.lastStatus == "5 字节 · 1 行" }
    }

    // ---------------------------------------------------------------------------------------
    // 夹具

    /** 直接把工具铺在测试窗口上；不套面板，这一层只验工具自己（与 `RegexDevToolTest` 同一做法）。 */
    private fun ComposeUiTest.render(
        tool: HexDevTool,
        input: ClipItem?,
        host: DevToolHost = RecordingHost(),
    ) {
        setContent {
            MaterialTheme {
                tool.Content(input = input, host = host)
            }
        }
        waitForIdle()
    }

    /** 只实现这个工具真会用到的那几道口子；状态栏那一句记下来供断言。 */
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
