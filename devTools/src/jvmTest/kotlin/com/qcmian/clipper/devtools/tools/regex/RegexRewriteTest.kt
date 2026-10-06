package com.qcmian.clipper.devtools.tools.regex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * 模式改写：`s`（点匹配换行）与 `x`（忽略空白与注释）由我们自己实现，不交给引擎的 `RegexOption`。
 *
 * 这两条是同一个取舍的两面——`COMMENTS` 与 `DOT_MATCHES_ALL` 在 JS 上根本没有，交给引擎就等于把
 * 工具的开关集绑死在平台上；改写成「引擎只认 i 与 m 也能跑」的样子之后，语义由我们定义、到处一致。
 * 代价是多了一层「改写后的下标 → 原文下标」的映射，所以这里两个都测：改成什么样、位置怎么回。
 */
class RegexRewriteTest {

    private fun rewrite(source: String, vararg flags: RegexFlag): PreparedPattern =
        rewritePattern(source, flags.toSet())

    @Test
    fun `没有标志位时一字不改`() {
        val source = """a\.b[\d]+(?<n>x)"""
        val prepared = rewrite(source)

        assertEquals(source, prepared.text)
        // 恒等映射：位置照旧。
        assertEquals(0, prepared.sourceIndexAt(0))
        assertEquals(source.length, prepared.sourceIndexAt(source.length))
    }

    @Test
    fun `点匹配换行把每个点换成任意字符`() {
        assertEquals("""a[\s\S]b""", rewrite("a.b", RegexFlag.DotAll).text)
    }

    @Test
    fun `换出来的六个字符都对应原来那个点`() {
        val prepared = rewrite(".", RegexFlag.DotAll)

        assertEquals("""[\s\S]""", prepared.text)
        assertEquals(0, prepared.sourceIndexAt(0))
        assertEquals(0, prepared.sourceIndexAt(5))
        // 末尾那一格映射到原文末尾，引擎报「模式末尾」时才指得准。
        assertEquals(1, prepared.sourceIndexAt(6))
    }

    @Test
    fun `转义与字符类里的点不动`() {
        // `\.` 是字面量点。
        assertEquals("""a\.b""", rewrite("""a\.b""", RegexFlag.DotAll).text)
        // 字符类里的点是字面量点。
        assertEquals("[.]", rewrite("[.]", RegexFlag.DotAll).text)
        // `\Q…\E` 之间全是字面量。
        assertEquals("""\Q.\E""", rewrite("""\Q.\E""", RegexFlag.DotAll).text)
    }

    @Test
    fun `宽松模式删掉字符类之外的空白与注释`() {
        assertEquals("ab", rewrite("a b", RegexFlag.Comments).text)
        assertEquals("ab", rewrite("a\n  b", RegexFlag.Comments).text)
        assertEquals("ab", rewrite("a # 注释\nb", RegexFlag.Comments).text)
        // `#` 之后整行都是注释（要字面量的 `#` 得转义）。
        assertEquals("a", rewrite("a#b", RegexFlag.Comments).text)
        assertEquals("""a\#b""", rewrite("""a\#b""", RegexFlag.Comments).text)
        // 被转义的空白是普通字符。
        assertEquals("""a\ b""", rewrite("""a\ b""", RegexFlag.Comments).text)
    }

    @Test
    fun `字符类里的空白与井号照旧`() {
        // 这一条是**刻意与 JDK 的 `Pattern.COMMENTS` 不同**：那一个会把 `[ a]` 里的空格也忽略掉，
        // 还会把 `[#]` 里的 `#` 当成注释起点，于是 `[#]` 直接报「未闭合字符类」（实测过）。
        // 我们不开那个选项，字符类里的一切照旧——`[ ]` 就是「匹配一个空格」。
        assertEquals("[ a]", rewrite("[ a]", RegexFlag.Comments).text)
        assertEquals("[#]", rewrite("[#]", RegexFlag.Comments).text)
    }

    @Test
    fun `删掉的内容不会让后面的位置错位`() {
        val prepared = rewrite("a  (", RegexFlag.Comments)

        assertEquals("a(", prepared.text)
        assertEquals(0, prepared.sourceIndexAt(0))
        // `(` 在原文里是下标 3，不是改写后的 1。
        assertEquals(3, prepared.sourceIndexAt(1))
        assertEquals(4, prepared.sourceIndexAt(2))
    }

    @Test
    fun `两个标志位一起用时互不干扰`() {
        // 注释里的点不该被换成 `[\s\S]`，注释本身要被删掉。
        assertEquals("""a[\s\S]b[\s\S]""", rewrite("a.b # . 注释\n.", RegexFlag.DotAll, RegexFlag.Comments).text)
    }

    @Test
    fun `交给引擎的选项只有跨平台保证的那两个`() {
        // 闸门：`RegexOption` 是 expect/actual，`COMMENTS` 与 `DOT_MATCHES_ALL` 在 JS 上根本没有。
        // 本项目只编 jvm target，引用它们照样编得过——所以这条不在编译器里，只能钉在这里。
        assertEquals(listOf('i', 'm'), RegexFlag.entries.filter { it.engineOption != null }.map { it.letter })

        // 反过来：没交给引擎的那些，必须真的由改写实现（否则就是「开关点了没反应」）。
        RegexFlag.entries.filter { it.engineOption == null }.forEach { flag ->
            assertNotEquals(
                "a.b a b",
                rewrite("a.b a b", flag).text,
                "「${flag.title}」没交给引擎，就必须由改写兜住",
            )
        }
    }
}
