/*
 * 本仓库自己的测试（不是从上游拷来的，见 README.md「本仓库补丁」）。
 */
package com.monkopedia.kodemirror.view

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [lineSelectionRowRects]：折行时选区高亮按**视觉行**逐行铺开。
 *
 * 这是「单行超长文本、开了自动换行，全选之后背景只有左侧一小块」那个 bug 的回归测试——上游
 * 一个文档行只画一块矩形，右端取的是 `to` 的横坐标，而折行之后那个坐标落在**最后一个视觉行**
 * 的行内，于是中间几行整行漏掉。断言因此盯住两件事：**每一行都有高亮**，且**除末行外都铺到
 * 容器右边缘**。
 *
 * 排版用 [TextMeasurer] 真量一遍（`softWrap = true` + 一个窄宽度），不是拿手写的几何数据去喂：
 * 这一处的对错全在「排版把哪个偏移放在哪个视觉行上」，只有真排版才算数。
 */
class SelectionRowRectsTest {

    @Test
    fun `全选一条折行的超长行时每一行都铺满`() {
        val layout = layoutOf(LONG_LINE)
        // 前提：真的折成了多行，否则下面几条断言是空转。
        assertTrue(layout.lineCount >= 3, "用例前提：这一行要折成 3 行以上，实际 ${layout.lineCount} 行")

        val rects = selectionOf(layout, from = 0, to = LONG_LINE.length)

        assertEquals(layout.lineCount, rects.size, "一个视觉行一块高亮")
        for (row in 0 until layout.lineCount - 1) {
            assertEquals(0f, rects[row].left, TOLERANCE, "第 ${row + 1} 行从行首起")
            assertEquals(
                CONTAINER_WIDTH,
                rects[row].right,
                TOLERANCE,
                "第 ${row + 1} 行要铺到容器右边缘（bug 时这里只有最末行的行内横坐标）"
            )
        }
        // 末行到文本末尾为止：不多画到右边缘，也不越过。
        val textEnd = layout.getHorizontalPosition(LONG_LINE.length, true)
        assertEquals(0f, rects.last().left, TOLERANCE)
        assertEquals(textEnd, rects.last().right, TOLERANCE, "末行画到文本末尾为止")
    }

    @Test
    fun `高亮的纵向范围就是各自视觉行的范围`() {
        val layout = layoutOf(LONG_LINE)

        val rects = selectionOf(layout, from = 0, to = LONG_LINE.length)

        for (row in rects.indices) {
            assertEquals(layout.getLineTop(row), rects[row].top, TOLERANCE, "第 ${row + 1} 行的行顶")
            assertEquals(
                layout.getLineBottom(row),
                rects[row].bottom,
                TOLERANCE,
                "第 ${row + 1} 行的行底"
            )
        }
    }

    @Test
    fun `终点正好落在折行处时不漏掉前一行`() {
        // 故意用一个不含空格的长词：折行只能落在字与字之间，于是「折行处前一个字符属于上一行」
        // 没有歧义（带空格时那个空格算上行还是下行由排版决定，用例会跟着排版漂）。
        val layout = layoutOf(LONG_WORD)
        assertTrue(layout.lineCount >= 2, "用例前提：这一行要折行，实际 ${layout.lineCount} 行")
        val wrapAt = layout.getLineStart(1)
        assertTrue(wrapAt > 0, "用例前提：折行处不在行首")
        assertEquals(
            0,
            layout.getLineForOffset(wrapAt - 1),
            "用例前提：折行处前一个字符排在上一行"
        )

        val rects = selectionOf(layout, from = 0, to = wrapAt)

        assertEquals(1, rects.size, "只选中了第一个视觉行，就该只有一块高亮")
        assertEquals(CONTAINER_WIDTH, rects.single().right, TOLERANCE, "选到折行处＝这一行整行选中")
    }

    @Test
    fun `只落在中间某个视觉行的选区不按整块高度画`() {
        val layout = layoutOf(LONG_LINE)
        val rowStart = layout.getLineStart(1)
        val from = rowStart + 1
        val to = rowStart + 4
        assertEquals(1, layout.getLineForOffset(from), "用例前提：起点落在第二个视觉行上")

        val rects = selectionOf(layout, from = from, to = to)

        assertEquals(1, rects.size)
        assertEquals(layout.getLineTop(1), rects.single().top, TOLERANCE)
        assertEquals(layout.getLineBottom(1), rects.single().bottom, TOLERANCE)
        // 画布高度是整条折行行的总高：一块只覆盖第二行的 `bottom` 必须明显小于它。
        assertTrue(
            rects.single().bottom < layout.size.height,
            "只落在中间那一行，不该按整块高度画"
        )
        assertEquals(layout.getHorizontalPosition(from, true), rects.single().left, TOLERANCE)
        assertEquals(layout.getHorizontalPosition(to, true), rects.single().right, TOLERANCE)
    }

    @Test
    fun `选区延续到下一个文档行时末行也铺满`() {
        val layout = layoutOf(LONG_LINE)
        val rowStart = layout.getLineStart(1)

        val rects = lineSelectionRowRects(
            layout = layout,
            fromOffset = rowStart,
            toOffset = LONG_LINE.length,
            lineLength = LONG_LINE.length,
            containerWidth = CONTAINER_WIDTH,
            containerHeight = layout.size.height.toFloat(),
            extendsToNextLine = true
        )

        assertEquals(layout.lineCount - 1, rects.size, "从第二个视觉行直到末行")
        assertEquals(CONTAINER_WIDTH, rects.last().right, TOLERANCE, "接着往下走的末行铺满")
    }

    @Test
    fun `不折行时仍是整层高的那一块`() {
        val layout = layoutOf(SHORT_LINE, softWrap = false)
        assertEquals(1, layout.lineCount, "用例前提：不折行时一行就是一行")
        val containerHeight = 40f

        val rects = lineSelectionRowRects(
            layout = layout,
            fromOffset = 1,
            toOffset = 3,
            lineLength = SHORT_LINE.length,
            containerWidth = CONTAINER_WIDTH,
            containerHeight = containerHeight,
            extendsToNextLine = false
        )

        assertEquals(1, rects.size)
        assertEquals(0f, rects.single().top, TOLERANCE, "不折行仍铺满整层（上游画法）")
        assertEquals(containerHeight, rects.single().bottom, TOLERANCE)
        assertEquals(layout.getHorizontalPosition(1, true), rects.single().left, TOLERANCE)
        assertEquals(layout.getHorizontalPosition(3, true), rects.single().right, TOLERANCE)
    }

    /** 选一整层的 [from, to)，容器宽 [CONTAINER_WIDTH]、高取排版高度。 */
    private fun selectionOf(
        layout: TextLayoutResult,
        from: Int,
        to: Int
    ): List<SelectionRowRect> = lineSelectionRowRects(
        layout = layout,
        fromOffset = from,
        toOffset = to,
        lineLength = layout.layoutInput.text.length,
        containerWidth = CONTAINER_WIDTH,
        containerHeight = layout.size.height.toFloat(),
        extendsToNextLine = false
    )

    private fun layoutOf(text: String, softWrap: Boolean = true): TextLayoutResult =
        TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(),
            defaultDensity = Density(1f),
            defaultLayoutDirection = LayoutDirection.Ltr
        ).measure(
            text = AnnotatedString(text),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            softWrap = softWrap,
            constraints = Constraints(maxWidth = CONTAINER_WIDTH.toInt())
        )

    private companion object {
        const val CONTAINER_WIDTH = 200f
        const val TOLERANCE = 0.5f

        /** 一段带空格的长文本：折行会落在词之间，稳定折成很多行。 */
        val LONG_LINE = (1..24).joinToString(" ") { "segment$it" }

        /** 一个没有空格的长词：折行只能落在字与字之间。 */
        val LONG_WORD = (1..240).joinToString("") { ('a' + (it % 26)).toString() }

        const val SHORT_LINE = "abcdef"
    }
}
