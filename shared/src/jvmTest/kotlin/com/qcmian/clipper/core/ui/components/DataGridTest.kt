package com.qcmian.clipper.core.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `DataGrid` 里那几段纯逻辑：选中区域的几何、整行判断、TSV 复制。
 *
 * 这些是「看着简单、错了很难看出来」的部分——反着拖一次选区就归不出正确的矩形，而复制出去
 * 少一个制表符，贴进表格软件时只是「有一列粘歪了」。
 */
class DataGridTest {

    @Test
    fun selectionNormalisesCornersRegardlessOfDragDirection() {
        val forward = DataGridSelection(anchorRow = 1, anchorColumn = 2, cursorRow = 4, cursorColumn = 3)
        val backward = DataGridSelection(anchorRow = 4, anchorColumn = 3, cursorRow = 1, cursorColumn = 2)
        listOf(forward, backward).forEach { selection ->
            assertEquals(1, selection.firstRow)
            assertEquals(4, selection.lastRow)
            assertEquals(2, selection.firstColumn)
            assertEquals(3, selection.lastColumn)
        }
    }

    @Test
    fun selectionCoversItsRectangleOnly() {
        val selection = DataGridSelection(anchorRow = 1, anchorColumn = 1, cursorRow = 3, cursorColumn = 2)
        assertTrue(selection.contains(1, 1))
        assertTrue(selection.contains(3, 2))
        assertTrue(selection.contains(2, 1))
        assertFalse(selection.contains(0, 1))
        assertFalse(selection.contains(1, 0))
        assertFalse(selection.contains(4, 1))
        assertFalse(selection.contains(1, 3))
    }

    @Test
    fun wholeRowNeedsEveryColumn() {
        val wholeRow = DataGridSelection(anchorRow = 2, anchorColumn = 0, cursorRow = 2, cursorColumn = 2)
        assertTrue(wholeRow.coversWholeRow(2, columnCount = 3))
        assertFalse(wholeRow.coversWholeRow(2, columnCount = 4))
        assertFalse(wholeRow.coversWholeRow(1, columnCount = 3))

        val partial = DataGridSelection(anchorRow = 2, anchorColumn = 0, cursorRow = 2, cursorColumn = 1)
        assertFalse(partial.coversWholeRow(2, columnCount = 3))
    }

    @Test
    fun tsvPutsTabsBetweenColumnsAndNewlinesBetweenRows() {
        val rows = listOf(
            listOf("a1", "b1", "c1"),
            listOf("a2", "b2", "c2"),
        )
        val text = dataGridTsv(
            DataGridSelection(anchorRow = 0, anchorColumn = 0, cursorRow = 1, cursorColumn = 2),
            rows,
        )
        assertEquals("a1\tb1\tc1\na2\tb2\tc2", text)
    }

    @Test
    fun tsvPicksOnlyTheSelectedRectangle() {
        val rows = listOf(
            listOf("a1", "b1", "c1"),
            listOf("a2", "b2", "c2"),
        )
        // 反着选（右下 → 左上）与正着选应当得到同一段文本。
        val text = dataGridTsv(
            DataGridSelection(anchorRow = 1, anchorColumn = 1, cursorRow = 0, cursorColumn = 0),
            rows,
        )
        assertEquals("a1\tb1\na2\tb2", text)
    }

    /** `NULL` 与空串在 TSV 里都是空字段——这是格式的限制，要保留 `NULL` 得走「复制为 INSERT」。 */
    @Test
    fun tsvWritesNullAndEmptyTheSame() {
        val rows = listOf(listOf(null, ""))
        val text = dataGridTsv(DataGridSelection.cell(0, 0).copy(cursorColumn = 1), rows)
        assertEquals("\t", text)
    }

    @Test
    fun multiSelectionKeepsEveryBlockAndMakesTheLastOneActive() {
        val first = DataGridSelection.cell(0, 0)
        val second = DataGridSelection.cell(5, 2)
        val selection = DataGridSelections.of(first).plus(second)

        assertEquals(listOf(first, second), selection.blocks)
        assertEquals(second, selection.active)
        // 跨块命中：不属于任何一块的格子仍然不算选中。
        assertTrue(selection.contains(0, 0))
        assertTrue(selection.contains(5, 2))
        assertFalse(selection.contains(3, 1))
    }

    @Test
    fun replaceActiveLeavesTheOtherBlocksAlone() {
        val first = DataGridSelection.cell(0, 0)
        val moved = DataGridSelection(anchorRow = 5, anchorColumn = 2, cursorRow = 7, cursorColumn = 4)
        val selection = DataGridSelections.of(first)
            .plus(DataGridSelection.cell(5, 2))
            .replaceActive(moved)

        assertEquals(listOf(first, moved), selection.blocks)
    }

    @Test
    fun wholeRowAndWholeColumnLookAcrossAllBlocks() {
        val row = DataGridSelection(anchorRow = 2, anchorColumn = 0, cursorRow = 2, cursorColumn = 2)
        val column = DataGridSelection(anchorRow = 0, anchorColumn = 1, cursorRow = 3, cursorColumn = 1)
        val selection = DataGridSelections.of(row).plus(column)

        assertTrue(selection.coversWholeRow(2, columnCount = 3))
        assertTrue(selection.coversWholeColumn(1, rowCount = 4))
        assertFalse(selection.coversWholeColumn(1, rowCount = 5))
    }

    /** 多块复制：各块按顺序直接接起来，**不插空行**——空行粘出去就是凭空多出来的一行。 */
    @Test
    fun multiBlockTsvJoinsBlocksWithoutExtraBlankLines() {
        val rows = listOf(
            listOf("a1", "b1"),
            listOf("a2", "b2"),
            listOf("a3", "b3"),
        )
        val text = dataGridTsv(
            DataGridSelections.of(DataGridSelection.cell(0, 0))
                .plus(DataGridSelection.cell(2, 1)),
            rows,
        )
        assertEquals("a1\nb3", text)
    }

    /** 同一行里选了两段（第 1 列与第 3 列）：粘出来是**一行两格**，不是两行。 */
    @Test
    fun multiBlockTsvMergesBlocksThatShareARow() {
        val rows = listOf(listOf("a1", "b1", "c1"))
        val text = dataGridTsv(
            DataGridSelections.of(DataGridSelection.cell(0, 0))
                .plus(DataGridSelection.cell(0, 2)),
            rows,
        )
        assertEquals("a1\tc1", text)
    }

    /** 重叠的块不该把同一格输出两遍。 */
    @Test
    fun multiBlockTsvDeduplicatesOverlappingCells() {
        val rows = listOf(listOf("a1", "b1"))
        val text = dataGridTsv(
            DataGridSelections.of(DataGridSelection.cell(0, 0))
                .plus(DataGridSelection.cell(0, 0)),
            rows,
        )
        assertEquals("a1", text)
    }

    /** 选满整行 → CSV：逗号分隔，值里的逗号与引号按 RFC 4180 转义。 */
    @Test
    fun wholeRowSelectionCopiesAsCsv() {
        val rows = listOf(listOf("a", "b,c", "d\"e"))
        val selection = DataGridSelections.of(
            DataGridSelection(anchorRow = 0, anchorColumn = 0, cursorRow = 0, cursorColumn = 2)
        )

        assertTrue(selection.coversWholeRows(columnCount = 3))
        assertEquals("a,\"b,c\",\"d\"\"e\"", dataGridCsv(selection, rows))
        assertEquals("a,\"b,c\",\"d\"\"e\"", dataGridClipboardText(selection, rows))
    }

    /** 没选满整行就还是 TSV：框选一片格子是要贴进另一张表，那里逗号会把列粘错。 */
    @Test
    fun partialSelectionCopiesAsTsv() {
        val rows = listOf(listOf("a", "b,c"))
        val selection = DataGridSelections.of(DataGridSelection.cell(0, 0))

        assertFalse(selection.coversWholeRows(columnCount = 2))
        assertEquals("a\tb,c", dataGridTsv(DataGridSelections.of(DataGridSelection(0, 0, 0, 1)), rows))
        assertEquals("a", dataGridClipboardText(selection, rows))
    }

    /** 点行号列选中**整行**。最容易退化成「只选中第一格」——行号列上取到的列号偏偏就是 0。 */
    @Test
    fun rowNumberPressSelectsTheWholeRow() {
        val whole = dataGridBlockAt(row = 3, column = 0, wholeRow = true, columnCount = 4)

        assertEquals(3, whole.firstRow)
        assertEquals(0, whole.firstColumn)
        assertEquals(3, whole.lastColumn)
        assertTrue(whole.coversWholeRow(3, columnCount = 4))
    }

    @Test
    fun cellPressSelectsASingleCell() {
        val cell = dataGridBlockAt(row = 3, column = 2, wholeRow = false, columnCount = 4)

        assertTrue(cell.contains(3, 2))
        assertFalse(cell.contains(3, 1))
        assertFalse(cell.coversWholeRow(3, columnCount = 4))
    }

    /** 逐行行高之后，`y` → 行号要**累加着**找；这里错了会「越往下点得越偏」。 */
    @Test
    fun rowAtAccumulatesPerRowHeights() {
        val heights = listOf(20f, 40f, 20f)

        assertEquals(0, dataGridRowAt(0f, heights))
        assertEquals(0, dataGridRowAt(19.9f, heights))
        assertEquals(1, dataGridRowAt(20f, heights))
        assertEquals(1, dataGridRowAt(59.9f, heights))
        assertEquals(2, dataGridRowAt(60f, heights))
        // 指针跑出表体之下给最后一行的号（拖拽时经常发生），之上给第 0 行。
        assertEquals(2, dataGridRowAt(999f, heights))
        assertEquals(0, dataGridRowAt(-5f, heights))
        // 空表没有行可言。
        assertNull(dataGridRowAt(0f, emptyList()))
    }

    @Test
    fun rowTopAddsUpTheHeightsBeforeIt() {
        val heights = listOf(20f, 40f, 20f)

        assertEquals(0f, dataGridRowTop(0, heights))
        assertEquals(20f, dataGridRowTop(1, heights))
        assertEquals(60f, dataGridRowTop(2, heights))
        // 越界不炸：夹到末尾。
        assertEquals(80f, dataGridRowTop(9, heights))
    }
}
