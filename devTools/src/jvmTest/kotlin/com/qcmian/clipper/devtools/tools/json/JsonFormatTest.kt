package com.qcmian.clipper.devtools.tools.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 缩进：美化按给定的 [JsonIndent] 排，压缩与之无关。 */
class JsonFormatTest {

    private val source = """{"a":[1,2]}"""

    private fun pretty(indent: JsonIndent): String = JsonFormat.format(source, indent).getOrThrow()

    @Test
    fun `spaces indent is exactly that many spaces per level`() {
        assertEquals(
            "{\n  \"a\": [\n    1,\n    2\n  ]\n}",
            pretty(JsonIndent.Spaces(2)),
        )
    }

    @Test
    fun `one tab is one level`() {
        assertEquals(
            "{\n\t\"a\": [\n\t\t1,\n\t\t2\n\t]\n}",
            pretty(JsonIndent.Tab),
        )
    }

    @Test
    fun `omitting the indent uses the default`() {
        assertEquals(pretty(JsonIndent.Spaces(JsonFormat.DefaultIndentSpaces)), JsonFormat.format(source).getOrThrow())
    }

    /**
     * 「1..8 个空格 + 制表符都能选」是这个工具对用户的承诺，但它在界面上只是一列菜单项，
     * 漏掉一档、重复一档或顺序乱了都很难靠眼看发现，所以在取值域这里钉死。
     */
    @Test
    fun `indent options are one to eight spaces then tab`() {
        val spaces = JsonFormat.IndentOptions.filterIsInstance<JsonIndent.Spaces>().map { it.count }

        assertEquals((1..8).toList(), spaces)
        assertEquals(JsonIndent.Tab, JsonFormat.IndentOptions.last())
        assertEquals(JsonFormat.IndentOptions.size, JsonFormat.IndentOptions.distinct().size)
        assertTrue(JsonFormat.DefaultIndent in JsonFormat.IndentOptions)
    }
}
