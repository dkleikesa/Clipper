@file:OptIn(ExperimentalTestApi::class)

package com.qcmian.clipper.devtools.tools.barcode

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.runComposeUiTest
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.devtools.api.DevToolHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 结果区**状态选择**的验收：空输入不能落到「生成中…」上。
 *
 * 这是真机上发现的：把输入框清空后，结果区对着空白一直写「生成中…」。成因是判据撞在一起——
 * `fresh`（结果对不对得上当前输入）在文本为空时**必然为假**，于是「空输入」与「正在等防抖 /
 * 编码」落进了同一个分支，而那一句永远等不来结果。
 *
 * 空态该说的是**要求**（这个码制吃什么、最多多少），与编码失败时的第一行是同一句，只是不带
 * 错误色：那会儿还没有「错」，只是还没输入。
 *
 * 为什么得真跑组合：错的是「按什么顺序判断」这件事，`BarcodeFormatTest` 只管编码，测不到它。
 */
class BarcodeDevToolTest {

    @Test
    fun `输入为空时结果区给文本要求`() = runComposeUiTest {
        render(input = null)

        onNodeWithText("文本要求：${BarcodeFormat.Qr.inputHint}").assertIsDisplayed()
        assertEquals(
            0,
            onAllNodesWithText("生成中…").fetchSemanticsNodes().size,
            "空输入没有可等的东西，不该说「生成中…」",
        )
    }

    /**
     * 输入框的占位提示是**示例**（「这里填什么」），不是要求（「限多少」）。
     *
     * 它一度直接铺 `inputHint`：EAN-13 那条二十多字的规格在输入框里既长又答非所问。要求现在
     * 由结果区（输入为空时）与失败提示交代，这条用例守住这条分工别再合回去。
     */
    @Test
    fun `输入框占位提示给示例而不是要求`() = runComposeUiTest {
        render(input = null)

        onNodeWithText("例如 ${BarcodeFormat.Qr.inputExample}").assertIsDisplayed()
        assertEquals(
            0,
            onAllNodesWithText(BarcodeFormat.Qr.inputHint).fetchSemanticsNodes().size,
            "整段容量规格不该出现在输入框里",
        )
    }

    /**
     * 切码制时输入框里的示例要跟着换。
     *
     * 真机上这条最初是坏的：切到 EAN-13，输入框里还挂着 QR 的「例如 https://example.com」。根因
     * 在 `KodemirrorCodeField`——占位扩展把首次组合的提示冻住了（契约测试 `每个实现的占位提示都会
     * 跟着参数换` 守那一层）；这里从工具这一侧再钉一遍，顺带验**接线**：占位取的是当前码制的示例。
     */
    @Test
    fun `切码制时输入框的示例跟着换`() = runComposeUiTest {
        render(input = null)

        // 码制按钮上的文字就是当前码制，点开是一列选项。
        onNodeWithText(BarcodeFormat.Qr.title).performClick()
        onNodeWithText(BarcodeFormat.Ean13.title).performClick()
        waitForIdle()

        onNodeWithText("例如 ${BarcodeFormat.Ean13.inputExample}").assertIsDisplayed()
    }

    /**
     * 参数控件按码制摆：三种二维码都有纠错，但**刻度各不相同**（QR 四档、Aztec 百分比、PDF417
     * 十档），一维码没有纠错，那个位置换成「下方是否印字」。这条守住「三者的刻度没有被拼成一个
     * 统一数值」，也守住「一维码不会冒出一个纠错控件」。
     */
    @Test
    fun `按码制摆出对应的参数控件`() = runComposeUiTest {
        render(input = null)

        // QR：四档，默认「中」；是分段控件，不是滑杆。
        onNodeWithText("纠错").assertIsDisplayed()
        onNodeWithText(QrErrorLevel.Medium.title).assertIsDisplayed()
        assertEquals(0, progressSliders(), "QR 的纠错是四档分段，不该出现滑杆")

        // Aztec：纠错是**百分比**，给可拖的滑杆（默认 33%）——切四档会够不到 23% 这类值。
        selectFormat(BarcodeFormat.Qr, BarcodeFormat.Aztec)
        onNodeWithText("$DefaultAztecEcPercent%").assertIsDisplayed()
        assertEquals(1, progressSliders(), "Aztec 的纠错该是能拖的滑杆")

        // PDF417：十档铺不进一条轨道，用下拉，默认「自动」。
        selectFormat(BarcodeFormat.Aztec, BarcodeFormat.Pdf417)
        onNodeWithText(Pdf417ErrorLevel.Auto.title).assertIsDisplayed()
        assertEquals(0, progressSliders(), "PDF417 的纠错是九档 + 自动，用下拉而不是滑杆")

        // 一维码：没有纠错（下拉上不再是 PDF417 的档位），换成「文本」这一组，默认印字。
        selectFormat(BarcodeFormat.Pdf417, BarcodeFormat.Ean13)
        onNodeWithText(Pdf417ErrorLevel.Auto.title).assertDoesNotExist()
        onNodeWithText("文本").assertIsDisplayed()
        onNodeWithText("印字").assertIsDisplayed()
    }

    /** 拖滑杆要真的改到取值上：读数跟着变，重新编码也才会用新百分比（参数进了 `EncodeRequest`）。 */
    @Test
    fun `Aztec 纠错滑杆能改取值`() = runComposeUiTest {
        render(input = null)
        selectFormat(BarcodeFormat.Qr, BarcodeFormat.Aztec)

        onNode(sliderMatcher).performSemanticsAction(SemanticsActions.SetProgress) { it(60f) }
        waitForIdle()

        onNodeWithText("60%").assertIsDisplayed()
    }

    /** 滑杆在语义树里的记号：`SetProgress` 动作只有可拖的控件才有。 */
    private val sliderMatcher = SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)

    private fun ComposeUiTest.progressSliders(): Int =
        onAllNodes(sliderMatcher).fetchSemanticsNodes().size

    /** 导出尺寸：它管的是**输出**那张图，所以在线头的保存 / 复制旁边，选了就换成新的档位。 */
    @Test
    fun `导出尺寸可选`() = runComposeUiTest {
        render(input = null)

        onNodeWithText("$DefaultExportLongSide px").assertIsDisplayed()
        // 点开下拉（此时按钮上那份是唯一一处），选最大一档。
        onNodeWithText("$DefaultExportLongSide px").performClick()
        onNodeWithText("4096 px").performClick()
        waitForIdle()

        onNodeWithText("4096 px").assertIsDisplayed()
    }

    @Test
    fun `输入文本后结果区出现码`() = runComposeUiTest {
        render(input = ClipItem(id = "one", text = "12345"))

        // 防抖（150ms）之后才编码，编码在后台线程；等它落地。
        waitUntil(timeoutMillis = 10_000) {
            onAllNodesWithText("生成中…").fetchSemanticsNodes().isEmpty()
        }

        assertTrue(
            onAllNodesWithText("21×21").fetchSemanticsNodes().isNotEmpty(),
            "5 位数字在 QR 中档下是 21×21，结果区应当报出尺寸",
        )
    }

    /** 点开码制下拉，从 [from] 换到 [to]。 */
    private fun ComposeUiTest.selectFormat(from: BarcodeFormat, to: BarcodeFormat) {
        onNodeWithText(from.title).performClick()
        onNodeWithText(to.title).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.render(input: ClipItem?) {
        setContent {
            MaterialTheme {
                BarcodeDevTool.Content(input = input, host = FakeHost())
            }
        }
        waitForIdle()
    }

    /** 只实现 [BarcodeDevTool.Content] 真会用到的那几道口子；其余接口自带空实现。 */
    private class FakeHost : DevToolHost {
        override fun copyToClipboard(text: String) = Unit

        override fun showStatus(message: String) = Unit

        override fun reportStatus(text: String?) = Unit

        override fun pickFileToOpen(): String? = null

        override fun pickFileToSave(suggestedName: String): String? = null

        override fun droppedFilePaths(event: DragAndDropEvent): List<String> = emptyList()
    }
}
