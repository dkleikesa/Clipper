package com.qcmian.clipper.devtools.tools.math

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** 求值：四则与幂、函数与常量、角度制、以及各种定义域/名字/参数个数的报错。 */
class MathEvaluatorTest {

    private fun eval(text: String): Double = MathEvaluator.evaluate(MathParser.parse(text))

    private fun evalError(text: String): MathError = assertFailsWith<MathError> { eval(text) }

    @Test
    fun `arithmetic follows precedence`() {
        assertEquals(7.0, eval("1+2*3"))
        assertEquals(9.0, eval("(1+2)*3"))
        assertEquals(1.0, eval("7 % 3"))
        assertEquals(0.125, eval("2^-3"))
    }

    @Test
    fun `power is right associative and unary minus is below it`() {
        assertEquals(512.0, eval("2^3^2"))
        assertEquals(-9.0, eval("-3^2"))
    }

    @Test
    fun `built-in functions compute`() {
        assertEquals(6.0, eval("sqrt(16) + abs(-2)"))
        assertEquals(5.0, eval("max(1, 5, 3)"))
        assertEquals(2.0, eval("avg(1, 2, 3)"))
        assertEquals(2.0, eval("round(2.4)"))
        assertEquals(-2.0, eval("floor(-1.2)"))
        assertEquals(3.0, eval("log(8, 2)"), 1e-12)
    }

    @Test
    fun `constants are case insensitive`() {
        assertEquals(PI, eval("pi"), 1e-12)
        assertEquals(PI, eval("PI"), 1e-12)
        assertEquals(2 * PI, eval("tau"), 1e-12)
    }

    @Test
    fun `trigonometry takes radians`() {
        assertEquals(1.0, eval("sin(pi / 2)"), 1e-12)
        assertEquals(0.0, eval("sin(0)"), 1e-12)
        assertEquals(PI / 2, eval("asin(1)"), 1e-12)
    }

    @Test
    fun `a degree suffix converts to radians inside the expression`() {
        assertEquals(1.0, eval("sin(90deg)"), 1e-12)
        assertEquals(1.0, eval("sin(90°)"), 1e-12)
        assertEquals(PI, eval("deg(180)"), 1e-12)
        assertEquals(PI / 2, eval("(45 + 45)deg"), 1e-12)
    }

    @Test
    fun `division by zero is reported at the operator`() {
        val failure = evalError("1/0")
        assertEquals("除数不能为 0", failure.message)
        assertEquals(1, failure.position)
    }

    @Test
    fun `domain errors are reported`() {
        assertEquals("sqrt 不接受负数", evalError("sqrt(-1)").message)
        assertEquals("ln 只接受正数", evalError("ln(0)").message)
        assertEquals("负数的非整数次幂没有实数结果", evalError("(-8)^0.5").message)
        assertEquals("0 不能取负数次幂", evalError("0^-1").message)
    }

    @Test
    fun `unknown names and functions are reported`() {
        assertEquals("不认识的名字：x", evalError("x + 1").message)
        assertEquals("不认识的函数：foo", evalError("foo(1)").message)
    }

    @Test
    fun `argument counts are checked with a readable message`() {
        val fixed = evalError("sqrt(1, 2)")
        assertTrue(fixed.message!!.contains("sqrt 需要 1 个参数，实际给了 2 个"), fixed.message)

        val variadic = evalError("max()")
        assertTrue(variadic.message!!.contains("max 需要至少 1 个参数，实际给了 0 个"), variadic.message)
    }
}
