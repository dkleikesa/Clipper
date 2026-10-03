package com.qcmian.clipper.devtools.tools.math

import kotlin.math.E
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cbrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.math.truncate

/**
 * 语法树求值。
 *
 * 与解析分开：解析只关心「形状对不对」，这里才管「算不算得出来」——除以零、负数开方、名字写错
 * 都在这一步报，位置取自对应的节点，与 JSON 工具「先解析、失败才翻译报错」是同一套思路。
 *
 * 三角函数一律按**弧度**，与 `kotlin.math` 和各类编程语言一致：`sin(pi / 2) = 1`。要按角度算，
 * 在表达式里写 `sin(90deg)` 或 `sin(90°)`——`MathParser` 把那个后缀折成一次 `deg(...)` 调用，
 * 由此这里只当它是一个普通函数（把角度换成弧度），不必再知道「单位」这回事。
 *
 * 结果一律是 [Double]。commonMain 里没有 `BigDecimal`，要精确十进制得另想办法（见 `MathFormat`），
 * 这里先用双精度，够这个工具用。
 */
internal object MathEvaluator {
    fun evaluate(expr: MathExpr): Double = eval(expr)

    private fun eval(expr: MathExpr): Double = when (expr) {
        is MathExpr.Number -> expr.value
        is MathExpr.Name -> constant(expr)
        is MathExpr.Unary -> {
            val value = eval(expr.operand)
            if (expr.op == '-') -value else value
        }

        is MathExpr.Binary -> binary(expr)
        is MathExpr.Call -> call(expr)
    }

    private fun constant(expr: MathExpr.Name): Double = when (expr.text.lowercase()) {
        "pi" -> PI
        "e" -> E
        "tau" -> 2 * PI
        else -> throw MathError("不认识的名字：${expr.text}", expr.position)
    }

    private fun binary(expr: MathExpr.Binary): Double {
        val left = eval(expr.left)
        val right = eval(expr.right)
        return when (expr.op) {
            '+' -> left + right
            '-' -> left - right
            '*' -> left * right
            '/' -> if (right == 0.0) throw MathError("除数不能为 0", expr.position) else left / right
            '%' -> if (right == 0.0) {
                throw MathError("取余的除数不能为 0", expr.position)
            } else {
                left % right
            }

            '^' -> power(left, right, expr.position)
            else -> throw MathError("不认识的运算符 ${expr.op}", expr.position)
        }
    }

    private fun call(expr: MathExpr.Call): Double {
        val name = expr.name.lowercase()
        val fn = Functions[name] ?: throw MathError("不认识的函数：${expr.name}", expr.position)
        if (expr.args.size !in fn.arity) {
            throw MathError("${fn.name} ${arityText(fn.arity)}，实际给了 ${expr.args.size} 个", expr.position)
        }
        return fn.body(expr.args.map(::eval), expr.position)
    }
}

/** 函数表里的一项：名字、参数个数范围、实现。[body] 额外拿到出错位置，供定义域报错使用。 */
private class Fn(val name: String, val arity: IntRange, val body: (List<Double>, Int) -> Double)

private val Functions: Map<String, Fn> = listOf(
    // 角度后缀 `90deg` / `90°` 折成的就是它；也可以直接当函数写：`deg(180)` ≈ 3.1416。
    Fn("deg", 1..1) { a, _ -> a[0] * PI / 180.0 },
    Fn("abs", 1..1) { a, _ -> abs(a[0]) },
    Fn("sign", 1..1) { a, _ -> sign(a[0]) },
    Fn("sqrt", 1..1) { a, at -> if (a[0] < 0) throw MathError("sqrt 不接受负数", at) else sqrt(a[0]) },
    Fn("cbrt", 1..1) { a, _ -> cbrt(a[0]) },
    Fn("exp", 1..1) { a, _ -> exp(a[0]) },
    Fn("ln", 1..1) { a, at -> if (a[0] <= 0) throw MathError("ln 只接受正数", at) else ln(a[0]) },
    Fn("log10", 1..1) { a, at -> if (a[0] <= 0) throw MathError("log10 只接受正数", at) else log10(a[0]) },
    Fn("log2", 1..1) { a, at -> if (a[0] <= 0) throw MathError("log2 只接受正数", at) else log2(a[0]) },
    Fn("log", 2..2) { a, at ->
        if (a[0] <= 0) throw MathError("log 的底数与被求值都要为正", at)
        if (a[1] <= 0 || a[1] == 1.0) throw MathError("log 的底数要为不为 1 的正数", at)
        ln(a[0]) / ln(a[1])
    },
    Fn("pow", 2..2) { a, at -> power(a[0], a[1], at) },
    Fn("hypot", 2..2) { a, _ -> hypot(a[0], a[1]) },
    Fn("sin", 1..1) { a, _ -> sin(a[0]) },
    Fn("cos", 1..1) { a, _ -> cos(a[0]) },
    Fn("tan", 1..1) { a, _ -> tan(a[0]) },
    Fn("asin", 1..1) { a, at ->
        if (abs(a[0]) > 1) throw MathError("asin 只接受 -1 到 1 之间的数", at) else asin(a[0])
    },
    Fn("acos", 1..1) { a, at ->
        if (abs(a[0]) > 1) throw MathError("acos 只接受 -1 到 1 之间的数", at) else acos(a[0])
    },
    Fn("atan", 1..1) { a, _ -> atan(a[0]) },
    Fn("sinh", 1..1) { a, _ -> sinh(a[0]) },
    Fn("cosh", 1..1) { a, _ -> cosh(a[0]) },
    Fn("tanh", 1..1) { a, _ -> tanh(a[0]) },
    Fn("round", 1..1) { a, _ -> round(a[0]) },
    Fn("floor", 1..1) { a, _ -> floor(a[0]) },
    Fn("ceil", 1..1) { a, _ -> ceil(a[0]) },
    Fn("trunc", 1..1) { a, _ -> truncate(a[0]) },
    Fn("min", 1..Int.MAX_VALUE) { a, _ -> a.min() },
    Fn("max", 1..Int.MAX_VALUE) { a, _ -> a.max() },
    Fn("avg", 1..Int.MAX_VALUE) { a, _ -> a.average() },
    Fn("sum", 1..Int.MAX_VALUE) { a, _ -> a.sum() },
).associateBy { it.name }

private fun power(base: Double, exponent: Double, position: Int): Double {
    if (base < 0 && exponent != floor(exponent)) {
        throw MathError("负数的非整数次幂没有实数结果", position)
    }
    if (base == 0.0 && exponent < 0) throw MathError("0 不能取负数次幂", position)
    return base.pow(exponent)
}

/** 参数个数的中文说法：「需要 3 个参数」/「需要 2 到 3 个参数」/「需要至少 2 个参数」。 */
private fun arityText(arity: IntRange): String = when {
    arity.first == arity.last -> "需要 ${arity.first} 个参数"
    arity.last == Int.MAX_VALUE -> "需要至少 ${arity.first} 个参数"
    else -> "需要 ${arity.first} 到 ${arity.last} 个参数"
}
