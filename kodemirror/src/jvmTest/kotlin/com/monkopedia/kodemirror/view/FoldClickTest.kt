/*
 * 本仓库自己的测试（不是从上游拷来的，见 README.md「本仓库补丁」）。
 */
package com.monkopedia.kodemirror.view

import com.monkopedia.kodemirror.language.FoldRange
import com.monkopedia.kodemirror.language.foldEffect
import com.monkopedia.kodemirror.language.foldGutter
import com.monkopedia.kodemirror.language.foldedRanges
import com.monkopedia.kodemirror.language.unfoldFold
import com.monkopedia.kodemirror.language.unfoldFoldAt
import com.monkopedia.kodemirror.state.DocPos
import com.monkopedia.kodemirror.state.EditorState
import com.monkopedia.kodemirror.state.EditorStateConfig
import com.monkopedia.kodemirror.state.TransactionSpec
import com.monkopedia.kodemirror.state.asDoc
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 折叠之后点下去要真的展开。
 *
 * 手势与组合那一层在离屏测试里按不动（同 `TapSelectionTest` 的说明），所以这里钉住的是**点下去
 * 做什么**那一层：
 * - 装订线箭头那一路直接揣着区间来（[unfoldFold]）；
 * - 正文里那个 `…` 那一路只给得出文档偏移（[unfoldFoldAt]）——手势换算完屏幕坐标拿到的就是它。
 *
 * 曾经坏过的地方正在这里——上游的箭头点击是「按行现查当前折叠状态」，而标记随组合存活；
 * 会话一换，标记指的区间就过期了，点下去什么也不会发生。现在区间随 [FoldRange] 一路带到点击处。
 */
class FoldClickTest {

    @Test
    fun `按偏移展开：正文里那个点只有文档偏移这一条线索`() {
        val doc = "{\n  \"a\": 1\n}"
        val session = sessionOf(doc)
        val range = FoldRange(DocPos(doc.indexOf('{') + 1), DocPos(doc.lastIndexOf('}')))

        session.dispatch(TransactionSpec(effects = listOf(foldEffect.of(range))))
        assertTrue(session.hasFold(), "前置条件：这一区间应当已经折起来")

        // 1 就是折叠区间的起点，也就是正文里那个 `…` 换算回来的文档偏移。
        unfoldFoldAt(session, 1)

        assertFalse(session.hasFold(), "展开之后不该还剩折叠区间")
    }

    @Test
    fun `按偏移展开：落在折叠区间之外就什么都不做`() {
        val doc = "{\n  \"a\": 1\n}"
        val session = sessionOf(doc)
        val range = FoldRange(DocPos(doc.indexOf('{') + 1), DocPos(doc.lastIndexOf('}')))

        session.dispatch(TransactionSpec(effects = listOf(foldEffect.of(range))))
        unfoldFoldAt(session, 0)

        assertTrue(session.hasFold(), "点在 `{` 上不该把这一段放出来（那一下是落光标）")
    }

    @Test
    fun `点占位符展开它代表的区间`() {
        val doc = "{\n  \"a\": 1\n}"
        val session = sessionOf(doc)
        val range = FoldRange(DocPos(doc.indexOf('{') + 1), DocPos(doc.lastIndexOf('}')))

        session.dispatch(TransactionSpec(effects = listOf(foldEffect.of(range))))
        assertTrue(session.hasFold(), "前置条件：这一区间应当已经折起来")

        unfoldFold(session, range)

        assertFalse(session.hasFold(), "展开之后不该还剩折叠区间")
    }

    @Test
    fun `同一段折起来再展开 正文一字不差`() {
        val doc = "[\n  {\n    \"a\": 1\n  },\n  2\n]"
        val session = sessionOf(doc)
        val range = FoldRange(DocPos(doc.indexOf('{') + 1), DocPos(doc.indexOf('}')))

        // 折叠只改显示（见 `FoldWidget`），文档本身不该被碰过——展开后因此要回到原样。
        session.dispatch(TransactionSpec(effects = listOf(foldEffect.of(range))))
        unfoldFold(session, range)

        assertTrue(session.state.sliceDoc(DocPos.ZERO, DocPos(doc.length)) == doc, "展开后正文应当与折叠前一致")
        assertFalse(session.hasFold())
    }

    // ---------------------------------------------------------------------------------------
    // 夹具

    /** 折叠那套扩展（`codeFolding` + 装订线）由 [foldGutter] 一并装上，与工具里那份一致。 */
    private fun sessionOf(doc: String): EditorSession = EditorSession(
        EditorState.create(
            EditorStateConfig(doc = doc.asDoc(), extensions = foldGutter())
        )
    )

    private fun EditorSession.hasFold(): Boolean {
        var found = false
        foldedRanges(state).between(DocPos.ZERO, DocPos(state.doc.length)) { _, _, _ ->
            found = true
            false
        }
        return found
    }
}
