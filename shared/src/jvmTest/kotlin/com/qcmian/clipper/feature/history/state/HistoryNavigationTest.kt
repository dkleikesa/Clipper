package com.qcmian.clipper.feature.history.state

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [HistoryNavigation] 的**纯状态转移**：光标 / 锚点 / 选中集三者的关系，以及空列表、单元素、
 * 越界、锚点伸缩这些边界。
 *
 * 没有可观察的行为面——走 UI 才能触达，而每一条转移都在一堆 `coerce` 之间，写歪了只表现为
 * 「偶尔选不中 / 多选错一条」，很难在功能测试里稳定复现。
 */
class HistoryNavigationTest {

    private val ids = listOf("a", "b", "c")

    @Test
    fun `single selects one id and clamps the index`() {
        val result = HistoryNavigation.single(index = 5, lastIndex = 2, ids = ids)
        assertEquals(2, result.historyIndex)
        assertEquals(2, result.anchorIndex)
        assertEquals(-1, result.footerIndex, "落在列表上就不该同时高亮页脚")
        assertEquals(setOf("c"), result.selectedIds)

        val belowZero = HistoryNavigation.single(index = -3, lastIndex = 2, ids = ids)
        assertEquals(0, belowZero.historyIndex)
        assertEquals(setOf("a"), belowZero.selectedIds)
    }

    @Test
    fun `single on an empty list selects nothing`() {
        val result = HistoryNavigation.single(index = 3, lastIndex = -1, ids = emptyList())
        assertEquals(0, result.historyIndex)
        assertEquals(emptySet(), result.selectedIds)
    }

    @Test
    fun `range grows and shrinks around a fixed anchor`() {
        val anchor = HistoryNavigation.single(index = 0, lastIndex = 2, ids = ids)

        val extended = HistoryNavigation.range(anchor, index = 2, lastIndex = 2, ids = ids)
        assertEquals(setOf("a", "b", "c"), extended.selectedIds)
        assertEquals(2, extended.historyIndex)
        assertEquals(0, extended.anchorIndex, "锚点不动，只有光标跟着走")

        val shrunk = HistoryNavigation.range(extended, index = 1, lastIndex = 2, ids = ids)
        assertEquals(
            setOf("a", "b"),
            shrunk.selectedIds,
            "回退是「锚点到当前光标」的整段，而不是从上一个光标再算",
        )
        assertEquals(1, shrunk.historyIndex)
        assertEquals(0, shrunk.anchorIndex)
    }

    @Test
    fun `range downwards selects the same span`() {
        val anchor = HistoryNavigation.single(index = 2, lastIndex = 2, ids = ids)
        val result = HistoryNavigation.range(anchor, index = 0, lastIndex = 2, ids = ids)

        assertEquals(setOf("a", "b", "c"), result.selectedIds, "min/max 取段，向上向下一致")
        assertEquals(0, result.historyIndex)
        assertEquals(2, result.anchorIndex, "锚点仍是落点 2")
    }

    @Test
    fun `range over an empty result set degrades to a cursor move`() {
        val moved = HistoryNavigation.range(HistorySelection(), index = 2, lastIndex = 0, ids = emptyList())

        assertEquals(emptySet(), moved.selectedIds)
        assertEquals(0, moved.historyIndex)
        assertEquals(-1, moved.footerIndex)
    }

    @Test
    fun `range clamps an out of bounds target to the last id`() {
        val anchor = HistoryNavigation.single(index = 0, lastIndex = 2, ids = ids)
        val result = HistoryNavigation.range(anchor, index = 99, lastIndex = 2, ids = ids)

        assertEquals(2, result.historyIndex)
        assertEquals(setOf("a", "b", "c"), result.selectedIds)
    }

    @Test
    fun `toggle adds and removes ids and moves the anchor`() {
        val single = HistoryNavigation.single(index = 0, lastIndex = 2, ids = ids)

        val added = HistoryNavigation.toggle(single, index = 2, lastIndex = 2, ids = ids)
        assertEquals(setOf("a", "c"), added.selectedIds)
        assertEquals(2, added.anchorIndex)
        assertEquals(2, added.historyIndex)

        val removed = HistoryNavigation.toggle(added, index = 2, lastIndex = 2, ids = ids)
        assertEquals(setOf("a"), removed.selectedIds)
        assertEquals(2, removed.anchorIndex, "取消选中也把锚点落到该条")
    }

    @Test
    fun `toggling the only selected id keeps it so the cursor always has one`() {
        val single = HistoryNavigation.single(index = 1, lastIndex = 2, ids = ids)
        val toggled = HistoryNavigation.toggle(single, index = 1, lastIndex = 2, ids = ids)

        assertEquals(setOf("b"), toggled.selectedIds, "取消到不剩时必须保留光标那一条")
        assertEquals(1, toggled.historyIndex)
        assertEquals(1, toggled.anchorIndex)
    }

    @Test
    fun `toggle on an empty list leaves the selection empty`() {
        val result = HistoryNavigation.toggle(HistorySelection(), index = 3, lastIndex = -1, ids = emptyList())
        assertEquals(emptySet(), result.selectedIds)
        assertEquals(0, result.historyIndex)
    }

    @Test
    fun `collapse drops the multi selection back to the cursor`() {
        val multi = HistorySelection(
            historyIndex = 1,
            anchorIndex = 0,
            selectedIds = setOf("a", "b", "c"),
        )

        val collapsed = HistoryNavigation.collapse(multi, lastIndex = 2, ids = ids)
        assertEquals(setOf("b"), collapsed.selectedIds)
        assertEquals(1, collapsed.historyIndex)
        assertEquals(1, collapsed.anchorIndex)
        assertEquals(-1, collapsed.footerIndex)
    }

    @Test
    fun `cursor moves the highlight without touching the selection`() {
        val multi = HistorySelection(historyIndex = 0, anchorIndex = 0, selectedIds = setOf("a", "b"))
        val moved = HistoryNavigation.cursor(multi, index = 2, lastIndex = 2)

        assertEquals(2, moved.historyIndex)
        assertEquals(setOf("a", "b"), moved.selectedIds, "只移动光标，选中集原样保留")
        assertEquals(0, moved.anchorIndex)
        assertEquals(-1, moved.footerIndex)
    }

    @Test
    fun `next advances cycles or stops depending on the flag`() {
        val start = HistorySelection(historyIndex = 0)
        assertEquals(1, HistoryNavigation.next(start, lastIndex = 2, allowCycle = true).historyIndex)

        val atEnd = start.copy(historyIndex = 2)
        assertEquals(
            0,
            HistoryNavigation.next(atEnd, lastIndex = 2, allowCycle = true).historyIndex,
            "到底后循环回第一条",
        )
        assertEquals(
            2,
            HistoryNavigation.next(atEnd, lastIndex = 2, allowCycle = false).historyIndex,
            "不循环就停在原地",
        )
        assertEquals(
            0,
            HistoryNavigation.next(start, lastIndex = 0, allowCycle = true).historyIndex,
            "单元素列表循环也停在唯一一条",
        )
    }

    @Test
    fun `previous and last clamp to the first and last index`() {
        val start = HistorySelection(historyIndex = 0)
        assertEquals(0, HistoryNavigation.previous(start, lastIndex = 2).historyIndex, "顶部再往上不动")
        assertEquals(
            1,
            HistoryNavigation.previous(start.copy(historyIndex = 2), lastIndex = 2).historyIndex,
        )
        assertEquals(2, HistoryNavigation.last(start, lastIndex = 2).historyIndex)
        assertEquals(0, HistoryNavigation.last(start, lastIndex = -1).historyIndex, "空列表落到 0")
    }

    @Test
    fun `every transition stays safe on an empty list`() {
        val start = HistorySelection()

        assertEquals(0, HistoryNavigation.single(3, -1, emptyList()).historyIndex)
        assertEquals(emptySet(), HistoryNavigation.range(start, 3, -1, emptyList()).selectedIds)
        assertEquals(0, HistoryNavigation.collapse(start, -1, emptyList()).historyIndex)
        assertEquals(emptySet(), HistoryNavigation.collapse(start, -1, emptyList()).selectedIds)
        assertEquals(0, HistoryNavigation.last(start, -1).historyIndex)
        assertEquals(0, HistoryNavigation.previous(start, -1).historyIndex)
        assertEquals(0, HistoryNavigation.next(start, -1, allowCycle = true).historyIndex)
    }

    @Test
    fun `footer clamps to the available rows and keeps the list position`() {
        val current = HistorySelection(historyIndex = 1, selectedIds = setOf("b"))

        val row = HistoryNavigation.footer(current, index = 5, footerCount = 3)
        assertEquals(2, row.footerIndex)
        assertEquals(1, row.historyIndex, "页脚高亮不动列表光标")
        assertEquals(setOf("b"), row.selectedIds)

        assertEquals(
            0,
            HistoryNavigation.footer(current, index = 0, footerCount = 0).footerIndex,
            "没有页脚项时落到 0，而不是负数",
        )
    }
}
