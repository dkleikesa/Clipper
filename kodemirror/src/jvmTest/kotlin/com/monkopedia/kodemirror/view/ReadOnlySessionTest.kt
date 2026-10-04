/*
 * 本仓库自己的测试（不是从上游拷来的，见 README.md「本仓库补丁」）。
 */
package com.monkopedia.kodemirror.view

import com.monkopedia.kodemirror.commands.deleteCharBackward
import com.monkopedia.kodemirror.state.DocPos
import com.monkopedia.kodemirror.state.EditorState
import com.monkopedia.kodemirror.state.EditorStateConfig
import com.monkopedia.kodemirror.state.asDoc
import com.monkopedia.kodemirror.state.extensionListOf
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 「只读」在会话这一层的定义：**改不动，但选得动、拷得走**。
 *
 * 只读框照样有插入光标、点得动、拖得出选区（见 `CodeFieldSpec.editable`），但只要它改不动正文，
 * 退格 / 删除 / ⌘X / ⌘V 这些键位命令就必须一条都落不到文档上——上游只把「打字」挡在输入法那
 * 一层，命令是直接派发事务的。这里钉住的就是那条闸门（`EditorSessionImpl.programmaticDocChange`）：
 *
 *  - 改文档的事务：一律不生效；
 *  - 选区与效果事务：照常（否则「能选、能复制」就是空话）；
 *  - `setDoc`（宿主换内容）：照常（否则只读框根本显示不出东西）；
 *  - 可编辑的会话：一个字都不受影响。
 *
 * 喂的是**真的命令**（`deleteCharBackward` 就是 Backspace 那一条），不是手写的事务：闸门漏掉的
 * 正是「命令 → 事务」这条路上没人管的那一段。
 */
class ReadOnlySessionTest {

    @Test
    fun `只读会话里退格改不动正文`() {
        val session = sessionOf(readOnly = true)
        session.select(DocPos(0), DocPos(5))

        deleteCharBackward(session)

        assertEquals(DOC, session.state.doc.toString())
    }

    @Test
    fun `只读会话里插入也改不动正文`() {
        val session = sessionOf(readOnly = true)
        session.select(DocPos(2))

        session.insertAt(DocPos(2), "XYZ")

        assertEquals(DOC, session.state.doc.toString())
    }

    @Test
    fun `只读会话里剪切也改不动正文`() {
        val session = sessionOf(readOnly = true)
        session.select(DocPos(0), DocPos(5))

        // ⌘X 走的是另一条路（`clipboardCut` 自己拼事务、连 userEvent 都没有）：闸门在派发那一层，
        // 因此这条与上一条一起把「命令」和「剪贴板」两路都钉住了。
        clipboardCut(session)

        assertEquals(DOC, session.state.doc.toString())
    }

    @Test
    fun `只读会话里选区照常进状态`() {
        val session = sessionOf(readOnly = true)

        session.select(DocPos(2), DocPos(7))

        // 「可复制」的前提就是这一条：选区得能落到状态里，⌘C 才拷得出东西。
        assertEquals(2, session.state.selection.main.from.value)
        assertEquals(7, session.state.selection.main.to.value)
    }

    @Test
    fun `只读会话里 setDoc 照常换掉内容`() {
        val session = sessionOf(readOnly = true)

        // 宿主换条目 / 点了格式化走的就是这条路：它是喂数据，不是用户在改。
        session.setDoc("host fed content")

        assertEquals("host fed content", session.state.doc.toString())
    }

    @Test
    fun `可编辑会话里退格照常生效`() {
        val session = sessionOf(readOnly = false)
        session.select(DocPos(0), DocPos(5))

        deleteCharBackward(session)

        // 闸门只认只读：可编辑的会话一个字都不该受影响（"hello brave new world" 去掉 "hello"）。
        assertEquals(" brave new world", session.state.doc.toString())
    }

    private fun sessionOf(readOnly: Boolean): EditorSession = EditorSession(
        EditorState.create(
            EditorStateConfig(
                doc = DOC.asDoc(),
                extensions = extensionListOf(editable.of(!readOnly))
            )
        )
    )

    private companion object {
        const val DOC = "hello brave new world"
    }
}
