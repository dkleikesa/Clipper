package com.qcmian.clipper.core.ui.code

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JSON 扫描器：高亮片段、括号配对与行号的判定。
 *
 * 这些结论靠界面看不出来（错一个偏移就是「颜色串位」或「折错范围」），所以钉在单测上。
 */
class CodeStructureTest {

    /** 把 token 还原成「原始片段 → 类别」，比断言偏移更好读。 */
    private fun tokens(text: String): List<Pair<String, CodeKind>> =
        scanJson(text).tokens.map { text.substring(it.start, it.end) to it.kind }

    @Test
    fun `keys and string values are told apart`() {
        val text = """{"a": "b", "c": 1}"""
        assertEquals(
            listOf(
                "{" to CodeKind.Punctuation,
                "\"a\"" to CodeKind.Key,
                ":" to CodeKind.Punctuation,
                "\"b\"" to CodeKind.StringLiteral,
                "," to CodeKind.Punctuation,
                "\"c\"" to CodeKind.Key,
                ":" to CodeKind.Punctuation,
                "1" to CodeKind.Number,
                "}" to CodeKind.Punctuation,
            ),
            tokens(text)
        )
    }

    @Test
    fun `key detection skips the whitespace before the colon`() {
        val text = "{\n  \"a\"   : 1\n}"
        assertEquals("\"a\"" to CodeKind.Key, tokens(text)[1])
    }

    @Test
    fun `literals and numbers are recognised`() {
        assertEquals(
            listOf(
                "true" to CodeKind.Constant,
                "false" to CodeKind.Constant,
                "null" to CodeKind.Constant,
                "-1.5e+3" to CodeKind.Number,
            ),
            tokens("true false null -1.5e+3")
        )
    }

    @Test
    fun `a word that merely contains a literal is not a literal`() {
        // `xtrue` 不是字面量：少了「前一个字符不能是标识符」这个判断就会把它吃掉。
        assertEquals(emptyList(), tokens("xtrue"))
    }

    @Test
    fun `escaped quotes do not end the string`() {
        val text = """{"a": "x\", y"}"""
        assertEquals("\"x\\\", y\"" to CodeKind.StringLiteral, tokens(text)[3])
    }

    @Test
    fun `brackets pair up in closing order and empty braces are not foldable`() {
        val text = """{"a": [1], "b": {}}"""
        val pairs = scanJson(text).brackets
        assertEquals(
            listOf(
                6 to 8, // [1]  ← 先闭合的在内层
                16 to 17, // {}  ← 空的，不值得折
                0 to 18, // 最外层
            ),
            pairs.map { it.open to it.close }
        )
        assertTrue(pairs[0].isFoldable)
        assertFalse(pairs[1].isFoldable)
        assertTrue(pairs[2].isFoldable)
    }

    @Test
    fun `fold range starts after the opening bracket and ends before the closing one`() {
        val text = "{\n  \"a\": 1\n}"
        val pair = scanJson(text).brackets.single()
        assertEquals(1, pair.foldStart)
        assertEquals(text.length - 1, pair.foldEnd)
    }

    @Test
    fun `lines are numbered and searched`() {
        val text = "{\n  \"a\": 1\n}"
        val structure = scanJson(text)
        assertEquals(3, structure.lineCount)
        assertEquals(1, structure.lineNumberAt(0))
        assertEquals(2, structure.lineNumberAt(3))
        assertEquals(3, structure.lineNumberAt(text.length))
        assertEquals(2, structure.lineStart(2))
    }

    @Test
    fun `foldable pairs are found by the line their opening bracket sits on`() {
        val text = "{\n  \"a\": {\n    \"b\": 1\n  }\n}"
        val structure = scanJson(text)
        val outer = structure.brackets.last()
        assertEquals(outer, structure.foldableOnLine(text, 1))
        // 第 3 行是纯值，没有开括号。
        assertNull(structure.foldableOnLine(text, 3))
    }

    @Test
    fun `unclosed brackets colour but cannot be folded`() {
        // 边打边写的中间态：闭括号还没出现，就没有可折叠的区间（也就不会折出一个「越长越大」
        // 的范围）；高亮照常工作——这是显示路径，不是校验路径（校验归 `JsonFormat`）。
        val text = "{\"a\": [1, "
        val structure = scanJson(text)
        assertEquals(emptyList(), structure.brackets)
        assertEquals("\"a\"" to CodeKind.Key, tokens(text)[1])
        assertEquals(1, structure.lineCount)
    }

    @Test
    fun `a caret beside a bracket points at its pair`() {
        //        0123456789012
        val text = """{"a": [1, 2]}"""
        val structure = scanJson(text)
        assertEquals(listOf(6 to 11, 0 to 12), structure.brackets.map { it.open to it.close })

        // 光标落在开括号上，或紧随其后——正文里最常见的两种位置，都该认出配对。
        assertEquals(0 to 12, matchBracketPair(structure, 0))
        assertEquals(0 to 12, matchBracketPair(structure, 1))
        // 内层：光标在 `[` 上 / 紧挨它右边，配的是内层的 `]`，不是最外层。
        assertEquals(6 to 11, matchBracketPair(structure, 6))
        assertEquals(6 to 11, matchBracketPair(structure, 7))
        // 闭括号一侧对称成立。
        assertEquals(6 to 11, matchBracketPair(structure, 11))
        assertEquals(0 to 12, matchBracketPair(structure, 12))
        // 文末（闭括号之后）也算挨着最外层。
        assertEquals(0 to 12, matchBracketPair(structure, 13))
        // 不在任何括号旁边，就没有配对可高亮。
        assertNull(matchBracketPair(structure, 5))
    }

    @Test
    fun `an unpaired bracket has nothing to highlight`() {
        // 边打边写的中间态：`{` 还没闭合，`brackets` 里根本没有它。
        val structure = scanJson("""{"a": 1""")
        assertTrue(structure.brackets.isEmpty())
        assertNull(matchBracketPair(structure, 0))
        assertNull(matchBracketPair(structure, 1))
    }

    // ---- XML 扫描器 ----

    /** 把 token 还原成「原始片段 → 类别」，比断言偏移更好读。 */
    private fun xmlTokens(text: String): List<Pair<String, CodeKind>> =
        scanXml(text).tokens.map { text.substring(it.start, it.end) to it.kind }

    @Test
    fun `xml tags name attributes and values are told apart`() {
        val text = """<a b="c">d</a>"""
        assertEquals(
            listOf(
                "<" to CodeKind.Punctuation,
                "a" to CodeKind.Key,
                "b" to CodeKind.Key,
                "=" to CodeKind.Punctuation,
                "\"c\"" to CodeKind.StringLiteral,
                ">" to CodeKind.Punctuation,
                "<" to CodeKind.Punctuation,
                "/" to CodeKind.Punctuation,
                "a" to CodeKind.Key,
                ">" to CodeKind.Punctuation,
            ),
            xmlTokens(text)
        )
    }

    @Test
    fun `xml comments cdata and declarations each colour as one piece`() {
        val text = """<?xml version="1.0"?><!-- hi --><![CDATA[x]]>"""
        assertEquals(
            listOf(
                "<?xml version=\"1.0\"?>" to CodeKind.Comment,
                "<!-- hi -->" to CodeKind.Comment,
                "<![CDATA[x]]>" to CodeKind.Comment,
            ),
            xmlTokens(text)
        )
    }

    @Test
    fun `xml elements fold between their start and end tags`() {
        val text = "<a>\n  <b>1</b>\n</a>"
        // 内层先闭合所以在内层先入表，与 JSON 一致。
        assertEquals(
            listOf(8 to 10, 2 to 15),
            scanXml(text).brackets.map { it.open to it.close }
        )
        val outer = scanXml(text).brackets.last()
        assertEquals(3, outer.foldStart) // 起始标签 `>` 之后
        assertEquals(15, outer.foldEnd) // 结束标签 `<` 之前
        assertTrue(outer.isFoldable)
        assertEquals(outer, scanXml(text).foldableOnLine(text, 1))
        assertEquals(scanXml(text).brackets.first(), scanXml(text).foldableOnLine(text, 2))
    }

    @Test
    fun `a self-closing tag never opens a fold`() {
        val text = "<a>\n  <b/>\n</a>"
        // `<b/>` 没有内容、也不占用栈：只留下最外层那一对。
        assertEquals(listOf(2 to 11), scanXml(text).brackets.map { it.open to it.close })
    }

    @Test
    fun `empty elements colour but cannot be folded`() {
        val text = "<a></a>"
        val pair = scanXml(text).brackets.single()
        assertEquals(2 to 3, pair.open to pair.close)
        assertFalse(pair.isFoldable)
    }

    @Test
    fun `unclosed xml tags colour but cannot be folded`() {
        // 边打边写的中间态：结束标签还没出现，`brackets` 里就没有可折叠的区间。
        val structure = scanXml("<a><b>")
        assertTrue(structure.brackets.isEmpty())
        assertEquals("<" to CodeKind.Punctuation, xmlTokens("<a><b>")[0])
        assertEquals(1, structure.lineCount)
    }
}
