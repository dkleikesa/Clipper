package com.qcmian.clipper.devtools.tools.math

import androidx.compose.ui.text.toUpperCase
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.round

/**
 * 数学表达式的求值入口与结果排版。
 *
 * 与 `JsonFormat` / `XmlFormat` 同一分工：纯函数、不碰 UI，既能被工具界面直接调用、也能被单测
 * 直接验。解析在 [MathParser]、求值在 [MathEvaluator]，这里只把它们接起来，再把 `Double` 写成
 * 人看得懂的样子。
 */
internal object MathFormat {
    /** 非整数结果保留几位有效数字。取 12：比 `Double` 的 15~17 位少，又足够把 `1/3` 写得像样。 */
    private const val SignificantDigits: Int = 12

    /**
     * 解析并求值；失败（语法错误、名字写错、定义域、结果溢出）通过 [Result] 带出 [MathError]。
     *
     * 非有限值（`Infinity` / `NaN`）不算成功：它多半来自溢出或未定义式，用户看到「Infinity」
     * 不会比看到一句说明更有用。
     */
    fun evaluate(text: String): Result<Double> = runCatching {
        val value = MathEvaluator.evaluate(MathParser.parse(text))
        if (!value.isFinite()) throw MathError("结果超出可表示范围（无穷或非数）", -1)
        value
    }

    /**
     * 把一个结果写成人看的样子。
     *
     * 目标只有一个：**别把浮点噪声端上去**。`0.1 + 0.2` 算出来是 `0.30000000000000004`，直接
     * 显示会让人以为算错了。做法是整数直接给整数串、非整数按有效数字四舍五入后去掉尾零。
     *
     * 大小两头（`< 1e-9` 或 `>= 1e15`）改走科学计数：铺开写会是一长串 0，反而不好读。
     *
     * commonMain 没有 `DecimalFormat` / `String.format`，这套换算只能自己写——下面几个私有函数
     * 就是替代品。
     */
    fun format(value: Double): String {
        if (value == 0.0) return "0" // 顺带把 -0.0 归一成 0
        val magnitude = abs(value)
        if (magnitude < 1e-9 || magnitude >= 1e15) return scientific(value)
        if (value == floor(value)) return value.toLong().toString()

        val exponent = floor(log10(magnitude)).toInt()
        val decimals = (SignificantDigits - 1 - exponent).coerceIn(1, 15)
        val factor = 10.0.pow(decimals)
        val rounded = round(value * factor) / factor
        if (rounded == floor(rounded)) return rounded.toLong().toString()
        return trimTrailingZeros(expandScientific(rounded.toString()))
    }

    /**
     * 整数结果顺带列出的其它进制（十六 / 二 / 八，按这个顺序）；非整数或超出安全整数范围时返回 `null`。
     *
     * 做成「三个一起给」而不是让用户在进制之间切：开发者多数时候想要的是「十六进制长什么样」，
     * 三个并排比来回切换更省事，也省掉一个容易忘掉当前选中的下拉。
     *
     * 返回**分开的三条**而不是拼成一串：界面上每一条各是一个可点击复制的目标，用户要的是
     * 某一个进制本身，而不是「三个连在一起」的一行字。
     */
    fun integerBases(value: Double): List<String>? {
        if (!value.isFinite() || value != floor(value) || abs(value) > 9.0e15) return null
        val number = value.toLong()
        if (number == 0L) return null
        val sign = if (number < 0) "-" else ""
        val magnitude = if (number < 0) -number else number
        // 符号分别写在每个前缀前：`-0xff`、`-0b…` 比只在前头写一个负号清楚。
        return listOf(
            "$sign" + "0x${magnitude.toString(16).uppercase()}",
            "$sign" + "0b${magnitude.toString(2).uppercase()}",
            "$sign" + "0o${magnitude.toString(8).uppercase()}",
        )
    }

    /** `1.2345E15` 这样的表示，转成 `1.2345e15`：小写的 e 更像数学写法，也更省一列。 */
    private fun scientific(value: Double): String {
        val text = value.toString()
        val e = text.indexOf('E')
        if (e < 0) return text
        return trimTrailingZeros(text.substring(0, e)) + "e" + text.substring(e + 1)
    }

    /**
     * 把 `1.23E-5` 这类科学计数展开成普通十进制（`0.0000123`）。
     *
     * 只在「大小落在正常区间、但 `toString` 仍返回科学计数」时用到——JVM 的 `Double.toString`
     * 对 `< 1e-3` 或 `>= 1e7` 会切到科学计数，而这两个区间里我们更想看到铺开的写法。
     */
    private fun expandScientific(text: String): String {
        val e = text.indexOf('E')
        if (e < 0) return text
        val mantissa = text.substring(0, e)
        val exponent = text.substring(e + 1).toIntOrNull() ?: return text
        val negative = mantissa.startsWith('-')
        val body = mantissa.removePrefix("-")
        val dot = body.indexOf('.')
        val integerLength = if (dot >= 0) dot else body.length
        val digits = body.replace(".", "")
        val pointPosition = integerLength + exponent
        return buildString {
            if (negative) append('-')
            when {
                pointPosition <= 0 -> {
                    append("0.")
                    repeat(-pointPosition) { append('0') }
                    append(digits)
                }

                pointPosition >= digits.length -> {
                    append(digits)
                    repeat(pointPosition - digits.length) { append('0') }
                }

                else -> {
                    append(digits, 0, pointPosition)
                    append('.')
                    append(digits, pointPosition, digits.length)
                }
            }
        }
    }

    /** 去掉小数末尾的 0（以及只剩小数点时连小数点一起去掉）：`0.300` → `0.3`，`2.0` → `2`。 */
    private fun trimTrailingZeros(text: String): String {
        if (!text.contains('.')) return text
        val trimmed = text.trimEnd('0').trimEnd('.')
        return if (trimmed.isEmpty() || trimmed == "-") "0" else trimmed
    }
}
