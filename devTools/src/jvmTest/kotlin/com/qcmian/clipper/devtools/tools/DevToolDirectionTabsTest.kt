@file:OptIn(ExperimentalTestApi::class)

package com.qcmian.clipper.devtools.tools

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
import com.qcmian.clipper.devtools.api.DevTool
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.tools.barcode.BarcodeDevTool
import com.qcmian.clipper.devtools.tools.base64.Base64DevTool
import com.qcmian.clipper.devtools.tools.url.UrlDevTool
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「编码 / 解码」是**两页**，不是工具栏里的一枚开关——这三个工具都要守住这条。
 *
 * 原先方向只是工具栏最左边一枚分段控件，与旁边的「规则」「URL 安全」长得一模一样；可它换掉的是
 * **整页**：输入框叫什么、装什么、结果画成什么，全都跟着变。于是「换一个方向」读起来像「改一个
 * 选项」。改成另一副长相（顶上一行页签）之后，这条用例盯的是**页真的换了**：上一个方向那套卡片
 * 得整块下去，只剩当前这一页的。
 *
 * 断言落在用户看得见的东西上（框名、工具栏上的选项、占位文案），不碰内部状态——页签是不是页签，
 * 用户看的就是这些。
 */
class DevToolDirectionTabsTest {

    @Test
    fun `Base64 换页时整页卡片跟着换`() = runComposeUiTest {
        render(Base64DevTool, input = null)

        // 默认落在编码页：文本输入框 + 只属于编码的那两个开关。
        onNodeWithText(ENCODE_INPUT).assertIsDisplayed()
        onNodeWithText("URL 安全").assertIsDisplayed()
        assertMissing(DECODE_INPUT)

        onNodeWithText("解码").performClick()
        waitForIdle()

        // 整页换掉：解码页的输入框与编码页那个不是一回事，编码页的开关也不该跟过来
        // （「URL 安全」「类型前缀」只管编）。
        onNodeWithText(DECODE_INPUT).assertIsDisplayed()
        assertMissing(ENCODE_INPUT)
        assertMissing("URL 安全")

        // 换回去还在：两页各存各的，来回切不丢东西。
        onNodeWithText("编码").performClick()
        waitForIdle()
        onNodeWithText(ENCODE_INPUT).assertIsDisplayed()
    }

    @Test
    fun `URL 两页各一套卡片、共用一个规则`() = runComposeUiTest {
        render(UrlDevTool, input = null)

        onNodeWithText("输入 · 原文").assertIsDisplayed()
        // 「规则」是同一页里的一个取值（编码与解码都按它选字符集），换页不该把它换掉。
        onNodeWithText("规则").assertIsDisplayed()

        onNodeWithText("解码").performClick()
        waitForIdle()

        onNodeWithText("输入 · 已编码").assertIsDisplayed()
        assertMissing("输入 · 原文")
        onNodeWithText("规则").assertIsDisplayed()
    }

    @Test
    fun `条码两页各是各的，来回切都在`() = runComposeUiTest {
        render(BarcodeDevTool, input = null)

        onNodeWithText("码制").assertIsDisplayed()

        onNodeWithText("解码").performClick()
        waitForIdle()

        // 解码页的输入是一张**码图**（空态是一句落点提示），编码那一套工具栏整块下去。
        onNodeWithText("输入 · 码图").assertIsDisplayed()
        onNodeWithText("把码图拖进来，或粘贴 / 打开一张图片").assertIsDisplayed()
        assertMissing("码制")

        onNodeWithText("编码").performClick()
        waitForIdle()
        onNodeWithText("码制").assertIsDisplayed()
    }

    /**
     * 带进来的内容会替用户落在多半想要的那一页上：一段 Base64 进来，工具直接开在解码页。
     *
     * 判据与面板的探测共用同一个（见 `Base64DataTypeDetector`），所以这是「面板推荐了、进来方向
     * 也对」的最后一环。
     */
    @Test
    fun `一段 Base64 进来时落在解码页`() = runComposeUiTest {
        render(Base64DevTool, input = ClipItem(id = "id0", text = "SGVsbG8sIFdvcmxkIQ=="))

        awaitText(DECODE_INPUT)
        assertMissing(ENCODE_INPUT)
    }

    // ---------------------------------------------------------------------------------------
    // 夹具

    /**
     * 把一个工具铺在测试窗口上。
     *
     * 直接调 `Content`（不套面板）：这一层要验的是**工具自己**把方向画成了两页，面板不参与。
     */
    private fun ComposeUiTest.render(tool: DevTool, input: ClipItem?) {
        setContent {
            MaterialTheme {
                tool.Content(input = input, host = FakeHost)
            }
        }
        waitForIdle()
    }

    /** 等某段文案出现。带剪贴板记录时，方向判定在后台线程上跑，慢一点的机器上得等一下。 */
    private fun ComposeUiTest.awaitText(text: String) {
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun ComposeUiTest.assertMissing(text: String) {
        assertEquals(
            0,
            onAllNodesWithText(text).fetchSemanticsNodes().size,
            "「$text」不该出现在这一页上",
        )
    }

    /** 只实现这几个工具真会用到的那几道口子；其余接口自带空实现。 */
    private object FakeHost : DevToolHost {
        override fun copyToClipboard(text: String) = Unit

        override fun showStatus(message: String) = Unit

        override fun reportStatus(text: String?) = Unit

        override fun pickFileToOpen(): String? = null

        override fun pickFileToSave(suggestedName: String): String? = null

        override fun droppedFilePaths(event: DragAndDropEvent): List<String> = emptyList()
    }

    private companion object {
        /** 编码页的输入框名。 */
        const val ENCODE_INPUT = "输入 · 文本"

        /** 解码页的输入框名。 */
        const val DECODE_INPUT = "输入 · Base64"
    }
}
