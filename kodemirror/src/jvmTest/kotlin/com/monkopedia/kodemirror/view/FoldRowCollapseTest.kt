/*
 * 本仓库自己的测试（不是从上游拷来的，见 README.md「本仓库补丁」）。
 */
package com.monkopedia.kodemirror.view

import androidx.compose.runtime.Composable
import com.monkopedia.kodemirror.state.DocPos
import com.monkopedia.kodemirror.state.EditorState
import com.monkopedia.kodemirror.state.EditorStateConfig
import com.monkopedia.kodemirror.state.RangeSetBuilder
import com.monkopedia.kodemirror.state.asDoc
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 折叠起来的块必须收成**一行**（补丁在 `DecorationApplication.buildColumnItems`）。
 *
 * 上游只把替换起点那一行截断，随后跳到结束那一行**整行**渲染——于是 `{...}` 会摊成两行
 * （`{…` 一行、闭括号一行），嵌套时连行尾那个逗号也会被甩到第二行去。折叠区间本身没有变，
 * 变的只是「结束那一行的剩余部分接在哪里」，所以这里喂的是与 `KodemirrorScan` 完全相同形状的
 * 区间（开括号之后 → 闭括号处）来钉住渲染结果。
 */
class FoldRowCollapseTest {

    @Test
    fun `折叠后收成一行`() {
        val doc = "{\n  \"a\": 1\n}"

        val rows = rows(doc, from = 1, to = doc.lastIndexOf('}'))

        assertEquals(1, rows.size, "闭括号那一行应当被并进第一行，而不是再占一行")
        assertEquals("{\u2026}", rows.first().content.text)
    }

    @Test
    fun `嵌套时行尾的逗号留在同一行`() {
        val doc = "[\n  {\n    \"a\": 1\n  },\n  2\n]"

        val rows = rows(doc, from = doc.indexOf('{') + 1, to = doc.indexOf('}'))

        // 第 0 行是外层那个 `[`；被折叠的块在第 1 行。
        // `  {` + 占位符 + `},`：逗号是内层闭括号那一行的剩余部分，必须接在同一行上。
        assertEquals(4, rows.size, "`[` / 折叠行 / `  2` / `]`，闭括号那一行不该再单独占一行")
        assertEquals("  {\u2026},", rows[1].content.text)
        // 折叠区间之后的行照旧，没被一并吃掉。
        assertEquals("  2", rows[2].content.text)
        assertEquals("]", rows[3].content.text)
    }

    // ---------------------------------------------------------------------------------------
    // 夹具

    private fun rows(doc: String, from: Int, to: Int): List<ColumnItem.TextLine> {
        val state = EditorState.create(EditorStateConfig(doc = doc.asDoc()))
        val builder = RangeSetBuilder<Decoration>()
        builder.add(
            DocPos(from),
            DocPos(to),
            Decoration.replace(ReplaceDecorationSpec(widget = FoldPlaceholder))
        )
        val decos: DecorationSet = builder.finish()
        return buildColumnItems(state, Viewport(0, state.doc.length), listOf(decos))
            .filterIsInstance<ColumnItem.TextLine>()
    }

    /**
     * 只为「装饰上带没带 widget」而存在的占位符：`buildColumnItems` 不调用它的 [Content]，
     * 只据「widget 非空」决定要不要在那行补一个 `…`（上游如此）。真正的占位符样式见
     * `FoldWidget`。
     */
    private object FoldPlaceholder : WidgetType() {
        @Composable
        override fun Content() = Unit
    }
}
