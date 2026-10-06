@file:OptIn(ExperimentalTestApi::class)

package com.qcmian.clipper.devtools.ui.components.code

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import com.monkopedia.kodemirror.view.GutterView
import com.monkopedia.kodemirror.view.lineNumbers
import com.monkopedia.kodemirror.view.rememberEditorSession
import kotlin.test.Test

/**
 * 装订线拿到一个**比当前文档还大**的行号时，不许把整块编辑器打断。
 *
 * 这是 `:kodemirror` 的内联补丁（`view/Gutter.kt`：行号夹到当前文档范围内）那一侧的回归。它要防的
 * 是真实崩溃：整篇变短时（换剪贴板条目、点清空、撤销），`columnItems` 那一份**快照**与 `GutterView`
 * 读的**实时** `session.state` 会差一帧——快照还按旧文档 subcompose 末尾那几行，于是拿旧行号去查
 * 新文档，抛 `Invalid line number ... in ...-line document`，异常落在组合里
 * （`Error was captured in composition`），工具窗口当场废掉。
 *
 * **为什么不走真实路径（`DevToolCodeField` 换内容）来复现**：这是一帧之内的调度竞争，回灌又落在
 * `LaunchedEffect` 里（测试框架下它跑在两帧之间），两条路都试过、都躲过了那一帧——那样的用例修不修
 * 都是绿的，等于没验。所以这里直接钉 `GutterView` 的契约：喂一个越界行号，它必须画得出来。
 *
 * 与 `:kodemirror` 自己那批测试的分工：那边刻意不起组合环境（见 `kodemirror/README.md`），需要
 * 真组合的用例都放在这一侧或 `:shared`。
 */
class GutterLineNumberGuardTest {

    /**
     * 越界的行号夹到**最后一行**，而不是抛异常。
     *
     * `22` 是崩溃里那个典型值：旧快照按 22 行的文档建，而这里读到的已经是 3 行的文档。
     */
    @Test
    fun `行号超出当前文档时夹到最后一行而不是崩`() = runComposeUiTest {
        gutters(lineNumber = 22)
        waitForIdle()

        // 夹到第 3 行，画出来的号因此是 3。正文（`{"a": "x"}`）里没有数字，所以这个节点只可能是行号。
        onNodeWithText("3").assertIsDisplayed()
    }

    /** 反面对照：范围内的行号照常画出来——否则上一条的「没崩」可能只是因为压根没渲染。 */
    @Test
    fun `文档范围内的行号照常画出来`() = runComposeUiTest {
        gutters(lineNumber = 2)
        waitForIdle()

        onNodeWithText("2").assertIsDisplayed()
    }

    /** 摆一列行号装订线，单独喂一个行号进去（不经过编辑器正文，见类注释）。 */
    private fun ComposeUiTest.gutters(lineNumber: Int) {
        setContent {
            MaterialTheme {
                val session = rememberEditorSession(doc = THREE_LINES, extensions = lineNumbers)
                GutterView(session = session, lineNumber = lineNumber)
            }
        }
    }

    private companion object {
        /** 三行、且正文里不含数字：这样画出来的数字只可能是行号。 */
        const val THREE_LINES = "{\n  \"a\": \"x\"\n}"
    }
}
