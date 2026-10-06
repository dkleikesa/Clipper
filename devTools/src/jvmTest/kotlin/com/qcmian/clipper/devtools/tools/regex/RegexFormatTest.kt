package com.qcmian.clipper.devtools.tools.regex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 正则工具的逻辑层：标志位、捕获组名的解析、命中与替换、模板校验、报告排版。
 *
 * 期望值取自 Java / Kotlin 正则的公认语义（`Pattern` 的文档），不是「跑出来就这样」的回归值。
 */
class RegexFormatTest {

    private fun scan(pattern: String, text: String, flags: Set<RegexFlag> = emptySet(), limit: Int = RegexFormat.MaxMatches) =
        RegexFormat.scan(RegexPattern(pattern, flags), text, limit).getOrThrow()

    private fun replace(pattern: String, text: String, template: String) =
        RegexFormat.replace(RegexPattern(pattern), text, template).getOrThrow()

    @Test
    fun `capturing groups are numbered in order`() {
        // 下标 0 是整段匹配，它没有名字。
        assertEquals(listOf(null), RegexFormat.captureNames("a+b"))
        assertEquals(listOf(null, null, null), RegexFormat.captureNames("(a)(b)"))
    }

    @Test
    fun `non capturing constructs do not take a number`() {
        // `(?:` `(?=` `(?i)` 这些都不占组号——数错了，「第 2 个组」就会指到别处。
        assertEquals(listOf(null, null), RegexFormat.captureNames("(?:a)(b)"))
        assertEquals(listOf(null, null), RegexFormat.captureNames("(?i)(b)"))
        assertEquals(listOf(null, null), RegexFormat.captureNames("(?<=a)(b)"))
        assertEquals(listOf(null, null), RegexFormat.captureNames("(?<!a)(b)"))
    }

    @Test
    fun `named groups carry their names`() {
        assertEquals(listOf(null, "year", "month"), RegexFormat.captureNames("""(?<year>\d{4})-(?<month>\d{2})"""))
        // 另外两种命名写法在 JVM 上会被引擎拒掉，但组数得先数对，报错才报得准。
        assertEquals(listOf(null, "x"), RegexFormat.captureNames("(?'x'a)"))
        assertEquals(listOf(null, "x"), RegexFormat.captureNames("(?P<x>a)"))
    }

    @Test
    fun `parentheses that are not group starts do not count`() {
        // `\Q…\E` 之间全是字面量。
        assertEquals(listOf(null, null), RegexFormat.captureNames("""\Q(\E(a)"""))
        // 字符类里的括号也是普通字符。
        assertEquals(listOf(null, null), RegexFormat.captureNames("[(](a)"))
        // 紧跟在 `[` 后面的 `]` 是类里的普通字符，不是类的结尾——`[]]` 整个是一个类。
        assertEquals(listOf(null, null), RegexFormat.captureNames("[]](a)"))
    }

    @Test
    fun `a match carries its position, text and groups`() {
        val result = scan("""(\w+)@(\w+)\.com""", "a@b.com and c@d.com")

        assertEquals(2, result.matches.size)
        assertFalse(result.truncated)

        val first = result.matches[0]
        assertEquals(1, first.index)
        assertEquals(0..6, first.range)
        assertEquals("a@b.com", first.value)
        assertFalse(first.isZeroWidth)
        assertEquals(listOf(1, 2), first.groups.map { it.index })
        assertEquals(listOf("a", "b"), first.groups.map { it.value })

        // 第二处的位置由引擎给，不是拿内容回原文里找的。
        assertEquals(12, result.matches[1].range.first)
    }

    @Test
    fun `group names come along with the groups`() {
        val match = scan("""(?<user>\w+)@(?<host>\w+)""", "me@example").matches.single()

        assertEquals(listOf("user", "host"), match.groups.map { it.name })
        assertEquals(listOf("me", "example"), match.groups.map { it.value })
    }

    @Test
    fun `a zero width match is flagged instead of looking empty`() {
        // 词边界：`hi there` 里在 h 前、i 后、t 前、e 后各一处。
        val result = scan("""\b""", "hi there")

        assertEquals(4, result.matches.size)
        assertTrue(result.matches.all { it.isZeroWidth })
        assertEquals(listOf(0, 2, 3, 8), result.matches.map { it.range.first })
    }

    @Test
    fun `hitting the limit truncates instead of failing`() {
        val result = scan("""\d""", "12345", limit = 3)

        assertEquals(listOf("1", "2", "3"), result.matches.map { it.value })
        assertTrue(result.truncated)

        // 正好等于上限时不算截断：后面确实没有了。
        assertFalse(scan("""\d""", "123", limit = 3).truncated)
    }

    @Test
    fun `flags change what matches`() {
        assertEquals(2, scan("abc", "ABC abc", setOf(RegexFlag.IgnoreCase)).matches.size)
        assertEquals(1, scan("abc", "ABC abc").matches.size)
        assertEquals(0, scan("abc", "ABC").matches.size)

        // 多行：`^` 认每一行的开头，所以 `b\nb` 两行都命中；不开就只认整段的开头。
        assertEquals(2, scan("^b", "b\nb", setOf(RegexFlag.Multiline)).matches.size)
        assertEquals(1, scan("^b", "b\nb").matches.size)

        // 点匹配换行。
        assertEquals(1, scan("a.b", "a\nb", setOf(RegexFlag.DotAll)).matches.size)
        assertEquals(0, scan("a.b", "a\nb").matches.size)

        // 宽松模式：模式里的空白被忽略，长模式因此可以分行写。
        assertEquals(1, scan("a b", "ab", setOf(RegexFlag.Comments)).matches.size)
        // 而字符类里的空白与 `#` 是内容，不受它影响——JDK 自带的 `COMMENTS` 会把 `[#]` 判成
        // 「未闭合字符类」，我们不吃那个亏（见 `RegexRewriteTest`）。
        assertEquals(1, scan("[#]", "#", setOf(RegexFlag.Comments)).matches.size)
        assertEquals(1, scan("[ a]", " ", setOf(RegexFlag.Comments)).matches.size)
    }

    @Test
    fun `replacement expands numbered and named groups`() {
        val numbered = replace("""(\w+)@(\w+)""", "a@b", "\$2@\$1")
        assertEquals("b@a", numbered.text)
        assertEquals(1, numbered.count)

        val named = RegexFormat
            .replace(RegexPattern("""(?<user>\w+)@(?<host>\w+)"""), "u@h", "\${host}/\${user}")
            .getOrThrow()
        assertEquals("h/u", named.text)
    }

    @Test
    fun `a dollar sign in the template has to be escaped`() {
        // `$0` 是整段匹配。
        assertEquals("(a)b", replace("a", "ab", "(\$0)").text)
        // 字面量 `$` 写成 `\$`（与引擎的写法一致，这里不做翻译）。
        assertEquals("价\$", replace("""\d""", "5", "价\\\$").text)
    }

    @Test
    fun `the replacement count covers every match`() {
        assertEquals(3, replace("""\d""", "1 2 3", "x").count)
        assertEquals("x x x", replace("""\d""", "1 2 3", "x").text)
    }

    @Test
    fun `a usable template has no problem`() {
        val pattern = RegexPattern("""(?<year>\d{4})-(?<month>\d{2})""")

        assertNull(RegexFormat.templateProblem(pattern, "\$1/\$2"))
        assertNull(RegexFormat.templateProblem(pattern, "\${year}-\${month}"))
        assertNull(RegexFormat.templateProblem(pattern, "\$0"))
        assertNull(RegexFormat.templateProblem(pattern, "字面量 \\\$"))
        assertNull(RegexFormat.templateProblem(pattern, "跟组无关的一串字"))
    }

    @Test
    fun `a bad template says which group is missing`() {
        val pattern = RegexPattern("""(?<year>\d{4})-(?<month>\d{2})""")

        // 越界的那一号要说清楚，顺带把能用的组列出来——不然用户还得回去数括号。
        assertEquals(
            "没有第 3 个捕获组（这个模式有 2 个组：1=year、2=month）",
            RegexFormat.templateProblem(pattern, "\$3"),
        )
        assertEquals(
            "没有名为「day」的捕获组（这个模式有 2 个组：1=year、2=month）",
            RegexFormat.templateProblem(pattern, "\${day}"),
        )
        assertEquals(
            "没有第 1 个捕获组（这个模式没有捕获组）",
            RegexFormat.templateProblem(RegexPattern("a"), "\$1"),
        )
    }

    @Test
    fun `other template mistakes are called out too`() {
        val pattern = RegexPattern("(a)")
        val template = { text: String -> RegexFormat.templateProblem(pattern, text) }

        assertNotNull(template("abc\$"))
        assertNotNull(template("\$x"))
        assertNotNull(template("\${a"))
        assertNotNull(template("尾部 \\"))
    }

    @Test
    fun `groups are counted from the pattern the engine actually runs`() {
        // `x` 会把注释连同里面的括号一起删掉：注释里的 `(a)` 不是一个组，组数因此只有 1。
        val pattern = RegexPattern("""(?<user>\w+) # 注释 (a)""", setOf(RegexFlag.Comments))

        assertNull(RegexFormat.templateProblem(pattern, "\${user}"))
        assertEquals(
            "没有名为「a」的捕获组（这个模式有 1 个组：1=user）",
            RegexFormat.templateProblem(pattern, "\${a}"),
        )
    }

    @Test
    fun `an empty pattern gives an empty result instead of matching everywhere`() {
        // 空的模式框是「还没写」，不是「处处都匹配」。
        val result = scan("", "abc")

        assertEquals(0, result.matches.size)
        assertFalse(result.truncated)
    }

    @Test
    fun `a pattern that the comments flag eats entirely is called out`() {
        // 引擎会老老实实编译一个空模式（于是处处零宽命中）；这里先把它拦下来说清楚。
        val failure = RegexFormat
            .scan(RegexPattern(" # 通篇都是注释", setOf(RegexFlag.Comments)), "abc")
            .exceptionOrNull()

        assertEquals("忽略空白与注释之后，模式里什么都不剩了", failure?.message)
    }

    @Test
    fun `the report gives one line per match plus its groups`() {
        val matches = scan("""(?<word>\w+)""", "hi there").matches

        assertEquals(
            "#1  第 1 行 第 1 列  hi\n" +
                "    组 1(word) = hi\n" +
                "#2  第 1 行 第 4 列  there\n" +
                "    组 1(word) = there",
            matchReport("hi there", matches, truncated = false),
        )
    }

    @Test
    fun `the report counts lines from the subject`() {
        // 多行模式下 `^` 在第 1、2 行各命中一次。
        val matches = scan("^", "a\nb", setOf(RegexFlag.Multiline)).matches

        assertEquals(
            "#1  第 1 行 第 1 列  (零宽)\n#2  第 2 行 第 1 列  (零宽)",
            matchReport("a\nb", matches, truncated = false),
        )
    }

    @Test
    fun `a multi line match does not break the one line per match layout`() {
        val matches = scan("""a[\s\S]*b""", "a1\n2b").matches

        assertEquals("#1  第 1 行 第 1 列  a1\\n2b", matchReport("a1\n2b", matches, truncated = false))
    }

    @Test
    fun `a truncated report says so`() {
        val matches = scan("""\d""", "1234", limit = 2).matches
        val report = matchReport("1234", matches, truncated = true)

        assertTrue(report.endsWith("…（只列出前 2 处，后面还有）"), report)
    }
}
