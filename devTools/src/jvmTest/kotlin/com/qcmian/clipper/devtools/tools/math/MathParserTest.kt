package com.qcmian.clipper.devtools.tools.math

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 词法与语法分析：优先级、结合性、数字字面量、以及每类错误的位置。
 *
 * 语法树的形状与「位置」都靠界面看不出来（错一处就是算错或报错指到别处），所以钉在单测上。
 */
class MathParserTest {

    private fun parse(text: String): MathExpr = MathParser.parse(text)

    private fun error(text: String): MathError = assertFailsWith<MathError> { MathParser.parse(text) }

    @Test
    fun `multiplication binds tighter than addition`() {
        assertEquals(
            MathExpr.Binary(
                '+',
                MathExpr.Number(1.0),
                MathExpr.Binary('*', MathExpr.Number(2.0), MathExpr.Number(3.0), 3),
                1,
            ),
            parse("1+2*3"),
        )
    }

    @Test
    fun `power is right associative`() {
        assertEquals(
            MathExpr.Binary(
                '^',
                MathExpr.Number(2.0),
                MathExpr.Binary('^', MathExpr.Number(3.0), MathExpr.Number(2.0), 3),
                1,
            ),
            parse("2^3^2"),
        )
    }

    @Test
    fun `unary minus is below power so -2^2 is -(2^2)`() {
        assertEquals(
            MathExpr.Unary(
                '-',
                MathExpr.Binary('^', MathExpr.Number(2.0), MathExpr.Number(2.0), 2),
                0,
            ),
            parse("-2^2"),
        )
    }

    @Test
    fun `a negative exponent is allowed`() {
        assertEquals(
            MathExpr.Binary('^', MathExpr.Number(2.0), MathExpr.Unary('-', MathExpr.Number(3.0), 2), 1),
            parse("2^-3"),
        )
    }

    @Test
    fun `functions take a comma separated argument list`() {
        assertEquals(
            MathExpr.Call("max", listOf(MathExpr.Number(1.0), MathExpr.Number(2.0)), 0),
            parse("max(1, 2)"),
        )
        assertEquals(MathExpr.Call("now", emptyList(), 0), parse("now()"))
    }

    @Test
    fun `a bare name is a constant lookup`() {
        assertEquals(MathExpr.Name("pi", 0), parse("pi"))
    }

    @Test
    fun `a degree suffix folds into a deg call`() {
        val expected = MathExpr.Call("deg", listOf(MathExpr.Number(90.0)), 2)
        assertEquals(expected, parse("90deg"))
        assertEquals(expected, parse("90°"))
    }

    @Test
    fun `a degree suffix also applies to a parenthesised expression`() {
        assertEquals(
            MathExpr.Call(
                "deg",
                listOf(MathExpr.Binary('+', MathExpr.Number(1.0), MathExpr.Number(2.0), 3)),
                7,
            ),
            parse("(1 + 2)deg"),
        )
    }

    @Test
    fun `hex binary and octal literals are read with their radix`() {
        assertEquals(MathExpr.Number(255.0), parse("0xff"))
        assertEquals(MathExpr.Number(10.0), parse("0b1010"))
        assertEquals(MathExpr.Number(8.0), parse("0o10"))
    }

    @Test
    fun `a scientific suffix is read as exponent`() {
        assertEquals(MathExpr.Number(1000.0), parse("1e3"))
        assertEquals(MathExpr.Number(0.001), parse("1e-3"))
    }

    @Test
    fun `an e that is not followed by digits stays a name`() {
        // `2e` 不该被当成「指数少写了数字」，而是 2 与名字 e 两段内容。
        val failure = error("2e")
        assertEquals(1, failure.position)
    }

    @Test
    fun `unexpected character reports its own position`() {
        val failure = error("1@2")
        assertEquals(1, failure.position)
        assertEquals("不认识的字符 '@'", failure.message)
    }

    @Test
    fun `an incomplete expression points past the operator`() {
        val failure = error("1+")
        assertEquals(2, failure.position)
        assertEquals("这里应该是一个数字、常量或左括号 (", failure.message)
    }

    @Test
    fun `a missing closing paren points at the end`() {
        val failure = error("(1+2")
        assertEquals(4, failure.position)
        assertEquals("缺少右括号 )", failure.message)
    }

    @Test
    fun `trailing content is reported with its position`() {
        val failure = error("1 2")
        assertEquals(2, failure.position)
        assertEquals("这里多出一段看不懂的内容：'2'", failure.message)
    }

    @Test
    fun `an empty expression is rejected`() {
        val failure = error("")
        assertEquals(0, failure.position)
        assertEquals("表达式是空的", failure.message)
    }

    @Test
    fun `a radix prefix with no digits is rejected`() {
        val failure = error("0x")
        assertEquals("'0x' 后面要跟数字", failure.message)
    }
}
