/*
 * 本仓库自己的测试（不是从上游拷来的，见 README.md「本仓库补丁」）。
 */
package com.monkopedia.kodemirror.view

import androidx.compose.ui.geometry.Offset
import com.monkopedia.kodemirror.commands.defaultKeymap
import com.monkopedia.kodemirror.state.DocPos
import com.monkopedia.kodemirror.state.EditorState
import com.monkopedia.kodemirror.state.EditorStateConfig
import com.monkopedia.kodemirror.state.asDoc
import com.monkopedia.kodemirror.state.extensionListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 「按着 Shift 接着扩」这一条在三条路上的落地：**点击**（[dispatchTapSelection] 的 `extend`）、
 * **方向键**（上游 `defaultKeymap` 里每条移动键自带的 `shift` 变体）、以及**拖拽**
 * （[handleDrag] 的 `anchorPos`）。
 *
 * 三路分开测是因为它们各走各的代码，而用户看到的只是「Shift 能不能多选」这一件事：方向键那一侧
 * 是上游就有的键位（这里只是把它钉住，免得将来接线断掉没人发现），点击与拖拽两侧是本仓库补的。
 *
 * 手势那一层（从事件上读 Shift、以及 `currentEvent.keyboardModifiers` 的时机）不在覆盖范围内：
 * 测试框架的鼠标注入**带不了修饰键**（`PlatformRootForTest.sendPointerEvent` 里
 * `keyboardModifiers = PointerKeyboardModifiers()`），喂不进真实的 Shift+点击。因此这里喂的是
 * 「手势层算出来的那几项」——位置、连击数、锚点。
 */
class ShiftExtendSelectionTest {

    // ------------------------------------------------------------------------ 点击

    @Test
    fun `Shift 点击从原选区的不动端扩到落点`() {
        val session = sessionOf()
        // 从 2 拖到 5：anchor = 2，head = 5。
        session.select(DocPos(2), DocPos(5))

        dispatchTapSelection(session, pos = 9, clickCount = 1, extend = true)

        assertEquals(2, session.anchor(), "不动端必须留在原处：Shift 点击是「接着扩」，不是「重选」")
        assertEquals(9, session.head())
    }

    @Test
    fun `Shift 点击往回缩`() {
        val session = sessionOf()
        // 从左往右拖出来的选区：anchor = 2（不动端），head = 9（活动端）。
        session.select(DocPos(2), DocPos(9))

        dispatchTapSelection(session, pos = 4, clickCount = 1, extend = true)

        // 活动端移到落点，不动端照旧：2..9 收成 2..4。往回拖出来的选区（anchor 在右）同理——
        // 换的是 head，anchor 无论落在哪一端都不动。
        assertEquals(2, session.anchor())
        assertEquals(4, session.head())
        assertEquals(2, session.state.selection.main.from.value)
        assertEquals(4, session.state.selection.main.to.value)
    }

    @Test
    fun `Shift 点击往回扩（选区是反向拖出来的）`() {
        val session = sessionOf()
        // 从右往左拖：anchor = 9，head = 2。
        session.select(DocPos(9), DocPos(2))

        dispatchTapSelection(session, pos = 4, clickCount = 1, extend = true)

        assertEquals(9, session.anchor())
        assertEquals(4, session.head())
        assertEquals(4, session.state.selection.main.from.value)
        assertEquals(9, session.state.selection.main.to.value)
    }

    @Test
    fun `选区为空时 Shift 点击从光标扩起`() {
        val session = sessionOf()
        session.select(DocPos(6))

        dispatchTapSelection(session, pos = 10, clickCount = 1, extend = true)

        assertEquals(6, session.state.selection.main.from.value)
        assertEquals(10, session.state.selection.main.to.value)
    }

    @Test
    fun `Shift 点击不再按连击数重选词或行`() {
        // 连击数在 Shift 那一路**不算数**：Compose 原生输入框同样是先看 Shift、再看连击数
        // （`mouseSelection` 里 Shift 那一支在连击判断之前）。双击 / 三击落点若被当成「选词 / 选行」，
        // 用户按住 Shift 连点两下就会把刚扩出来的选区整块换掉。
        for (clickCount in 2..3) {
            val session = sessionOf()
            session.select(DocPos(2), DocPos(5))

            dispatchTapSelection(session, pos = 12, clickCount = clickCount, extend = true)

            assertEquals(2, session.anchor(), "连击数 $clickCount：不动端不该被换掉")
            assertEquals(12, session.head(), "连击数 $clickCount：活动端落在点击处")
        }
    }

    // ------------------------------------------------------------------------ 方向键

    @Test
    fun `Shift 右方向键逐字扩选`() {
        val session = sessionOf(withKeymap = true)
        session.select(DocPos(2))

        assertTrue(shiftArrow(session, "ArrowRight"), "Shift-ArrowRight 应当由 defaultKeymap 接住")

        assertEquals(2, session.anchor())
        assertEquals(3, session.head())
    }

    @Test
    fun `Shift 左方向键逐字往回扩`() {
        val session = sessionOf(withKeymap = true)
        session.select(DocPos(2))

        assertTrue(shiftArrow(session, "ArrowLeft"))

        assertEquals(2, session.anchor())
        assertEquals(1, session.head())
    }

    @Test
    fun `Shift 方向键连续按是接着扩而不是重新起一段`() {
        val session = sessionOf(withKeymap = true)
        session.select(DocPos(2))

        shiftArrow(session, "ArrowRight")
        shiftArrow(session, "ArrowRight")
        shiftArrow(session, "ArrowRight")

        assertEquals(2, session.anchor())
        assertEquals(5, session.head())
    }

    // ------------------------------------------------------------------------ 拖拽

    @Test
    fun `普通拖拽的锚点取自按下的落点`() {
        val session = sessionOf()
        session.select(DocPos(6))

        handleDrag(
            view = session,
            start = Offset(0f, 0f),
            current = Offset(40f, 0f),
            posAt = { offset -> if (offset.x == 0f) 3 else 8 },
        )

        assertEquals(3, session.anchor())
        assertEquals(8, session.head())
    }

    @Test
    fun `Shift 拖拽接着原选区的不动端扩`() {
        val session = sessionOf()
        // 已有选区 4..6：Shift 拖拽要从 4 接着扩，而不是从按下的那个字符重新起一段。
        session.select(DocPos(4), DocPos(6))

        handleDrag(
            view = session,
            start = Offset(0f, 0f),
            current = Offset(40f, 0f),
            posAt = { offset -> if (offset.x == 0f) 0 else 9 },
            anchorPos = 4,
        )

        assertEquals(4, session.anchor())
        assertEquals(9, session.head())
    }

    // ---------------------------------------------------------------------------------------
    // 夹具

    /**
     * 走**产品代码里那一条**绑定解析路径（[runKeyBindings]），而不是在这里再写一遍
     * 「Shift-ArrowRight 该调谁」。
     */
    private fun shiftArrow(session: EditorSession, arrow: String): Boolean = runKeyBindings(
        view = session,
        name = "Shift-$arrow",
        nameWithoutShift = arrow,
        physicalKeyName = null,
        isShift = true,
        event = null,
    )

    private fun sessionOf(withKeymap: Boolean = false): EditorSession = EditorSession(
        EditorState.create(
            EditorStateConfig(
                doc = DOC.asDoc(),
                extensions = if (withKeymap) {
                    extensionListOf(keymapOf(defaultKeymap))
                } else {
                    extensionListOf()
                },
            )
        )
    )

    private fun EditorSession.anchor(): Int = state.selection.main.anchor.value

    private fun EditorSession.head(): Int = state.selection.main.head.value

    private companion object {
        /** 一行够长的 ASCII：扩选断言都拿得到具体下标，不必数中文的宽度。 */
        const val DOC = "let answer = 42 and more text"
    }
}
