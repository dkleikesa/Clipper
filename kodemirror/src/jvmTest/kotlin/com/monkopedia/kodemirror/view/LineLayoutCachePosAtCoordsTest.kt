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
import com.monkopedia.kodemirror.state.EditorState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [LineLayoutCache.posAtCoords] 换出来的位置必须落在**当前**文档里。
 *
 * 缓存的行排版比文档慢一帧，整篇变短之后它记的 `lineFrom` 还在变短前那一行上——上游照样把
 * 「行起点 + 偏移」交出去，调用方拿去 `doc.lineAt(...)` 就抛
 * `Invalid position DocPos(973) in document of length 951`。实测那次是**下方向键**触发的
 * （`selectLineDown` → `moveVertically`），异常落在 AWT 事件线程上，整个窗口一起没。
 *
 * 补法与 [LineLayoutCache.coordsAtPos] / [LineLayoutCache.blockAtPos] 一致：越界就返回 null，
 * 而不是夹到文末——夹过去会让调用方以为「目标就在那儿」，null 才会让它走已有的兜底
 * （`moveVertically` 退成按整逻辑行走一行）。
 *
 * 排版用 [TextMeasurer] 真量一遍（与 `SelectionRowRectsTest` 同一套夹具），不起组合环境。
 */
class LineLayoutCachePosAtCoordsTest {

    /** 崩溃现场：缓存记的是第 22 行、起点 973，而文档此刻只有 951 个字符。 */
    @Test
    fun `缓存那一行的起点已落在文档之外时不给位置`() {
        val cache = LineLayoutCache()
        cache.store(
            lineNumber = 22,
            lineFrom = 973,
            topPx = 0f,
            leftPx = 0f,
            result = layoutOf("abc")
        )

        assertNull(
            cache.posAtCoords(x = 0f, y = 1f, state = EditorState.create("x".repeat(951))),
            "行起点已经越过文档末尾，不该把 973 这个位置交出去",
        )
    }

    /**
     * 行起点还在文档里，但**偏移**把位置推出了文档之外：同一帧里这一行刚被删短，缓存里的排版还是
     * 长的那一版。靠右端的横坐标算出来的偏移会越过文档末尾——只夹行首是不够的。
     */
    @Test
    fun `偏移把位置推出文档之外时不给位置`() {
        val cache = LineLayoutCache()
        cache.store(
            lineNumber = 1,
            lineFrom = 0,
            topPx = 0f,
            leftPx = 0f,
            result = layoutOf(LONG_LINE)
        )

        assertNull(
            cache.posAtCoords(x = 10_000f, y = 1f, state = EditorState.create(SHORT_DOC)),
            "排版比文档长时，靠右端算出来的偏移会越过文档末尾",
        )
    }

    /** 反面对照：范围内的坐标照常换出位置——否则上面两条的通过也可能只是因为这一路压根没算出东西。 */
    @Test
    fun `范围内的坐标照常换出位置`() {
        val cache = LineLayoutCache()
        cache.store(
            lineNumber = 1,
            lineFrom = 0,
            topPx = 0f,
            leftPx = 0f,
            result = layoutOf(SHORT_DOC)
        )

        assertEquals(0, cache.posAtCoords(x = 0f, y = 1f, state = EditorState.create(SHORT_DOC)))
    }

    private fun layoutOf(text: String): TextLayoutResult =
        TextMeasurer(
            defaultFontFamilyResolver = createFontFamilyResolver(),
            defaultDensity = Density(1f),
            defaultLayoutDirection = LayoutDirection.Ltr
        ).measure(
            text = AnnotatedString(text),
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
            // 不折行：这里量的是「一块排版 ↔ 一个位置」的换算，与折行无关。
            softWrap = false,
            constraints = Constraints(maxWidth = 10_000)
        )

    private companion object {
        /** 比文档长的排版：用来把偏移推出文档末尾。 */
        const val LONG_LINE = "hello world hello world"

        const val SHORT_DOC = "short"
    }
}
