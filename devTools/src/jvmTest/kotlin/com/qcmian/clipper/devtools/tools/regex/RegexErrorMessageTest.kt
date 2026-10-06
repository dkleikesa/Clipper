package com.qcmian.clipper.devtools.tools.regex

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 报错翻译：把引擎的英文描述 + 字符下标说成「第几行 第几列 + 中文原因」。
 *
 * 断言一律走**真实报错**（把坏模式喂给 [RegexFormat]），而不是手写一句像样的英文：引擎的模板一变
 * （换 JDK、换实现），这里就该红，而不是界面悄悄退回英文。期望里的描述与下标都是核对过
 * `PatternSyntaxException` 的实际输出写下的。
 */
class RegexErrorMessageTest {

    /** 喂一个坏模式，拿引擎的真实报错去翻译。 */
    private fun translate(badPattern: String, flags: Set<RegexFlag> = emptySet()): String {
        val prepared = rewritePattern(badPattern, flags)
        val error = runCatching { Regex(prepared.text) }.exceptionOrNull()
            ?: error("这个模式应该是坏的：$badPattern")
        return regexErrorMessage(prepared, error)
    }

    @Test
    fun `an unclosed group points at the end of the pattern`() {
        // 引擎报的 index 是 1，也就是这个 `(` 之后——它说的正是「到这里还没闭合」。
        assertEquals("第 1 行 第 2 列：有未闭合的「(」", translate("("))
    }

    @Test
    fun `an unclosed character class is reported`() {
        assertEquals("第 1 行 第 1 列：有未闭合的「[」", translate("["))
        assertEquals("第 1 行 第 2 列：有未闭合的「[」", translate("[a"))
    }

    @Test
    fun `an extra closing paren has no position to point at`() {
        // 这一条引擎自己就不给位置（index 是 -1）：只说原因，不硬编一个「第 1 行 第 1 列」出来。
        assertEquals("多了一个「)」，它前面没有与之配对的「(」", translate(")"))
    }

    @Test
    fun `a dangling meta character names the character`() {
        assertEquals("第 1 行 第 1 列：「*」前面没有可重复的内容", translate("*"))
        assertEquals("第 1 行 第 1 列：「?」前面没有可重复的内容", translate("?"))
        // 报的是后面那个 `*`（下标 2）。
        assertEquals("第 1 行 第 3 列：「*」前面没有可重复的内容", translate("a**"))
    }

    @Test
    fun `a repetition that is not finished is called out`() {
        assertEquals(
            "第 1 行 第 3 列：「{」没有写完整，或者重复符号（* + ? { }）用错了位置",
            translate("a{"),
        )
    }

    @Test
    fun `an impossible repetition range says which way round it is`() {
        assertEquals(
            "第 1 行 第 6 列：重复次数写反了：{最少,最多} 里最少不能大于最多",
            translate("a{2,1}"),
        )
    }

    @Test
    fun `a duplicate group name keeps the name`() {
        assertEquals("第 1 行 第 12 列：组名「n」定义了两次", translate("(?<n>a)(?<n>b)"))
    }

    @Test
    fun `a group name that is missing its closing bracket is reported`() {
        assertEquals("第 1 行 第 5 列：组名后面少了「>」", translate("(?<a"))
    }

    @Test
    fun `a group name must start with a letter`() {
        assertEquals("第 1 行 第 4 列：组名要以英文字母开头", translate("(?<1>a)"))
    }

    @Test
    fun `an unknown group syntax is explained with what is allowed`() {
        // JVM 不认 Python 那种 `(?P<名>`，报的就是这句。
        assertEquals(
            "第 1 行 第 3 列：「(?」后面这一串不认识。" +
                "能用的有 (?: (?= (?! (?<= (?<! (?> (?<组名>，以及 (?i) 这类标志",
            translate("(?q)"),
        )
    }

    @Test
    fun `a reversed character range is called out`() {
        assertEquals("第 1 行 第 4 列：字符区间写反了（例如 [z-a]）", translate("[z-a]"))
    }

    @Test
    fun `an unknown unicode property keeps the name`() {
        assertEquals("第 1 行 第 7 列：不认识的 Unicode 属性名「Foo」", translate("""\p{Foo}"""))
    }

    @Test
    fun `a trailing backslash is explained from the pattern itself`() {
        // 引擎对这种情况只说 `Unexpected internal error`（等于没说）：能反推出具体毛病的就这一处，
        // 所以单独认——模式以「\」结尾，那正是「转义符后面没了」。
        assertEquals("第 1 行 第 3 列：结尾的「\\」后面要跟一个字符", translate("a\\"))
    }

    @Test
    fun `the column is counted inside its own line`() {
        // 第 2 行从偏移 2 开始，引擎报的 index 是 3（模式末尾），故列是 2。
        assertEquals("第 2 行 第 2 列：有未闭合的「(」", translate("a\n("))
    }

    @Test
    fun `an unrecognised cause keeps the english but still gives a position`() {
        val translated = regexErrorMessage(
            prepared = rewritePattern("abc", emptySet()),
            error = IllegalStateException("something brand new near index 0"),
        )

        assertEquals("第 1 行 第 1 列：something brand new", translated)
    }

    @Test
    fun `a message without a position or a translation is passed through`() {
        assertEquals(
            "Pattern exploded",
            regexErrorMessage(rewritePattern("abc", emptySet()), IllegalStateException("Pattern exploded")),
        )
    }

    @Test
    fun `the position is mapped back to what the user actually typed`() {
        // 宽松模式删掉空白之后，引擎看到的是 `a(`，它报的 index 2 是**改写后**的末尾；
        // 报给用户的必须是原文（`a  (`，长度 4）的末尾。
        assertEquals("第 1 行 第 5 列：有未闭合的「(」", translate("a  (", setOf(RegexFlag.Comments)))
    }

    @Test
    fun `the position is mapped back under the dot matches all flag too`() {
        // `.` 被摊成 `[\s\S]`（6 个字符）之后，`(` 在改写后的串里远在 6 号位；原文里它是 1 号位。
        assertEquals("第 1 行 第 3 列：有未闭合的「(」", translate(".(", setOf(RegexFlag.DotAll)))
    }
}
