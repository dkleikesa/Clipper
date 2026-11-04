@file:OptIn(ExperimentalTestApi::class)

package com.qcmian.clipper.devtools.tools.logviewer

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.devtools.api.DevToolHost
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 日志查看工具：把上游 LogViewer 的**工作台**塞进 DevTool 之后，最该守住的是两件事——
 * 它真的接到了宿主交来的内容，以及工作台该有的入口（打开/重载/粘贴/导出/跳转）还在。
 */
class LogViewerDevToolTest {

    private val sampleLog = """
        08-14 12:00:00.123  1234  5678 I ActivityManager: start proc
        08-14 12:00:00.456  1234  5678 E AndroidRuntime: FATAL EXCEPTION: main
        java.lang.IllegalStateException: boom
        	at com.example.Foo.bar(Foo.kt:12)
        08-14 12:00:01.000  1234  5678 W Choreographer: Skipped 42 frames
    """.trimIndent()

    @Test
    fun `剪贴板里的日志进来就是一个标签页`() = runComposeUiTest {
        render(ClipItem(id = "id0", text = sampleLog))

        // 标签条：内容落在固定的一页里，换剪贴板记录只换这一页
        awaitText(CLIPBOARD_TAB_NAME)
        awaitText("FATAL EXCEPTION")
    }

    @Test
    fun `工作台的文件与跳转入口都在`() = runComposeUiTest {
        render(ClipItem(id = "id0", text = sampleLog))
        awaitText("FATAL EXCEPTION")

        // 注意「导出」在规则面板底部也有一份，这里只要求工具栏上存在（见 `hasText`）
        listOf("打开", "重载", "粘贴导入", "导出", "跳转").forEach { label ->
            assertTrue(hasText(label), "工具栏缺少「$label」")
        }
    }

    @Test
    fun `没有内容时是欢迎页`() = runComposeUiTest {
        render(input = null)

        onNodeWithText("LogViewer").assertIsDisplayed()
        onNodeWithText("从剪贴板粘贴").assertIsDisplayed()
    }

    @Test
    fun `点加号能再开一页`() = runComposeUiTest {
        render(ClipItem(id = "id0", text = sampleLog))
        awaitText(CLIPBOARD_TAB_NAME)

        onAllNodesWithText("＋")[0].performClick()
        waitForIdle()

        awaitText("未打开文件")
    }

    // ---------------------------------------------------------------------------------------
    // 夹具

    private fun ComposeUiTest.render(input: ClipItem?) {
        setContent {
            MaterialTheme {
                LogViewerDevTool.Content(input = input, host = FakeHost)
            }
        }
        waitForIdle()
    }

    /** 等某段文案出现：文档装载与首次排版在后台线程上跑，慢一点的机器上得等一下。 */
    private fun ComposeUiTest.hasText(text: String): Boolean =
        onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.awaitText(text: String) {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private object FakeHost : DevToolHost {
        override fun copyToClipboard(text: String) = Unit

        override fun showStatus(message: String) = Unit

        override fun reportStatus(text: String?) = Unit

        override fun pickFileToOpen(): String? = null

        override fun pickFileToSave(suggestedName: String): String? = null

        override fun droppedFilePaths(event: DragAndDropEvent): List<String> = emptyList()
    }
}

/** 与工具里的常量同名，测试里只需要它的字面值。 */
private const val CLIPBOARD_TAB_NAME = "剪贴板内容"
