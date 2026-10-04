/*
 * 本仓库自己的测试（不是从上游拷来的，见 README.md「本仓库补丁」）。
 */
package com.monkopedia.kodemirror.view

import com.monkopedia.kodemirror.state.EditorState
import com.monkopedia.kodemirror.state.EditorStateConfig
import com.monkopedia.kodemirror.state.asDoc
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [dispatchTapSelection] 的三档：单击落光标、双击选中词、三击选中整行。
 *
 * 这里喂**位置与连击数**、读**最终选区**——正是那三档的全部语义。手势那一层（双记时限与
 * 落点 slop）不在覆盖范围内：它的判定要真实的事件时钟，而离屏测试两次注入之间会把帧跑完
 * （同 `KodemirrorCodeFieldTest` 里拖拽用例的说明），只能手点。
 */
class TapSelectionTest {

    @Test
    fun `单击只落光标`() {
        val session = sessionOf(DOC)

        dispatchTapSelection(session, pos = 6, clickCount = 1)

        assertEquals("", session.selectedText())
        assertEquals(6, session.selectionFrom())
    }

    @Test
    fun `双击选中该处的词`() {
        val session = sessionOf(DOC)

        // 6 落在第一行 "answer" 的中间。
        dispatchTapSelection(session, pos = 6, clickCount = 2)

        assertEquals("answer", session.selectedText())
    }

    @Test
    fun `三击选中整行且到换行之前为止`() {
        val session = sessionOf(DOC)

        dispatchTapSelection(session, pos = 6, clickCount = 3)

        // 到换行之前为止——与平台（原生框的段落选择）同一个落点：带上换行的话，光标会跟着
        // 跑到下一行首（整行选中时光标只能落在选区两端之一）。
        assertEquals("let answer = 42", session.selectedText())
    }

    @Test
    fun `末行三击同样只到行尾`() {
        val session = sessionOf(DOC)

        dispatchTapSelection(session, pos = 20, clickCount = 3)

        assertEquals("second line", session.selectedText())
    }

    @Test
    fun `双击点在没有词的地方退回光标`() {
        val session = sessionOf(DOC)

        // 11 是第一行那个 `=`：两侧都不是词字符，`wordAt` 给 null，于是退化回单击。
        dispatchTapSelection(session, pos = 11, clickCount = 2)

        assertEquals("", session.selectedText())
        assertEquals(11, session.selectionFrom())
    }

    /**
     * 顺带锁住上游 `wordAt` 的一个**不对称**：它按 pos **左侧**字符向前扩、按 pos **处**字符向后扩，
     * 于是点在 `let` 右侧的空格上取到的是**左边那个词**，而不是 null。
     *
     * 这条不是我们要的行为，但它是上游（也就是浏览器里 CodeMirror 6）的行为：改它得改 `wordAt`，
     * 那会连带影响 `selectNextOccurrence` 等命令，所以这里只把它钉住——将来谁改成「两侧都要算」，
     * 这条会红。
     */
    @Test
    fun `双击点在词的右侧空白会选中左边那个词`() {
        val session = sessionOf(DOC)

        dispatchTapSelection(session, pos = 3, clickCount = 2)

        assertEquals("let", session.selectedText())
    }

    /** 空文档之外的边界：第四下（超出三档）应当只当单击。 */
    @Test
    fun `超出三档只当单击`() {
        val session = sessionOf(DOC)

        dispatchTapSelection(session, pos = 6, clickCount = 4)

        assertEquals("", session.selectedText())
        assertEquals(6, session.selectionFrom())
    }

    // ---------------------------------------------------------------------------------------
    // 夹具

    /**
     * 脱离组合环境建会话：`EditorSessionImpl` 对插件宿主是空安全的（`pluginHost?.update(...)`），
     * 因此这里派发事务不需要先把编辑器挂起来。
     */
    private fun sessionOf(doc: String): EditorSession =
        EditorSession(EditorState.create(EditorStateConfig(doc = doc.asDoc())))

    /** 主选区的文本。 */
    private fun EditorSession.selectedText(): String {
        val range = state.selection.main
        return state.sliceDoc(range.from, range.to)
    }

    /** 主选区的起始偏移（空选区时即光标位置）。 */
    private fun EditorSession.selectionFrom(): Int = state.selection.main.from.value

    private companion object {
        /** 两行，且词、空格、行尾三种情形都在：便于把三档断言成具体的文本。 */
        const val DOC = "let answer = 42\nsecond line"
    }
}
