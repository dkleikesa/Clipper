package com.qcmian.clipper.devtools.tools.math

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 结果排版与报错文案：去浮点噪声、科学计数的边界、其它进制、以及行列换算。 */
class MathFormatTest {

    @Test
    fun `floating point noise never reaches the screen`() {
        assertEquals("0.3", MathFormat.format(0.1 + 0.2))
        assertEquals("0.3", MathFormat.format(MathFormat.evaluate("0.1 + 0.2").getOrThrow()))
    }

    @Test
    fun `non-integers keep twelve significant digits`() {
        assertEquals("0.333333333333", MathFormat.format(1.0 / 3))
        assertEquals("2.5", MathFormat.format(2.5))
    }

    @Test
    fun `integers and zero have no decimal part`() {
        assertEquals("2", MathFormat.format(2.0))
        assertEquals("255", MathFormat.format(255.0))
        assertEquals("0", MathFormat.format(0.0))
        assertEquals("0", MathFormat.format(-0.0))
    }

    @Test
    fun `a value that only toString would make scientific is written out`() {
        // JVM 的 Double.toString 对 >= 1e7 会切成 1.23456785E7，这里要铺开。
        assertEquals("12345678.5", MathFormat.format(12345678.5))
        assertEquals("0.00001", MathFormat.format(1e-5))
    }

    @Test
    fun `the extremes go scientific`() {
        assertEquals("1e15", MathFormat.format(1e15))
        assertEquals("1e-10", MathFormat.format(1e-10))
    }

    @Test
    fun `integer results also list the other bases`() {
        assertEquals("0xff · 0b11111111 · 0o377", MathFormat.integerBases(255.0))
        assertEquals("-0x1 · -0b1 · -0o1", MathFormat.integerBases(-1.0))
    }

    @Test
    fun `non-integer and zero results have no other bases`() {
        assertNull(MathFormat.integerBases(2.5))
        assertNull(MathFormat.integerBases(0.0))
    }

    @Test
    fun `evaluate returns the value or a located failure`() {
        assertEquals(2.0, MathFormat.evaluate("1 + 1").getOrThrow())
        assertTrue(MathFormat.evaluate("1/0").isFailure)
        assertTrue(MathFormat.evaluate("").isFailure)
    }

    @Test
    fun `a non-finite result is a failure without a position`() {
        val failure = MathFormat.evaluate("1e400")
        assertTrue(failure.isFailure)
        val error = failure.exceptionOrNull() as MathError
        assertEquals(-1, error.position)
        assertEquals("结果超出可表示范围（无穷或非数）", error.message)
    }

    @Test
    fun `error messages carry a line and column`() {
        assertEquals("第 3 列：这里应该是一个数字、常量或左括号 (", mathErrorMessage("1+", MathError("这里应该是一个数字、常量或左括号 (", 2)))
        assertEquals("第 2 行 第 1 列：有问题", mathErrorMessage("1+\n2", MathError("有问题", 3)))
    }

    @Test
    fun `a positionless error is shown as-is`() {
        assertFalse(mathErrorMessage("1+", MathError("整个式子都不行", -1)).contains("列"))
    }
}
