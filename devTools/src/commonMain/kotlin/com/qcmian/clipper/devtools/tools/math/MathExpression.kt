package com.qcmian.clipper.devtools.tools.math

/**
 * 表达式里的一处错误。
 *
 * [position] 是出错处在原文里的**字符下标**（从 0 数起）；`mathErrorMessage` 会把它换算成
 * 「第几行 第几列」。取 `-1` 表示这条错误不属于某个具体位置（例如「结果超出可计算范围」），
 * 那时报错只给一句话、不带位置。
 *
 * 与 JSON 工具同一取舍：位置是唯一能直接指导修改的信息，所以只要定位得到就带上它。
 */
internal class MathError(message: String, val position: Int) : Exception(message)

/**
 * 语法树：解析的产物，求值的输入。
 *
 * 刻意做成只读的数据层级、不夹带任何求值状态：解析（[MathParser]）与求值（[MathEvaluator]）
 * 因此各管一段，将来要加「显示步骤」「化简」这类功能，也能在树上再走一遍。
 */
internal sealed interface MathExpr {
    /** 一个字面量数字。 */
    data class Number(val value: Double) : MathExpr

    /** 一个名字（常量，例如 `pi`）：到底是常量还是写错了，留到求值时才判定。 */
    data class Name(val text: String, val position: Int) : MathExpr

    /** 一元运算：前缀 `+` / `-`。[op] 为 `+` 或 `-`。 */
    data class Unary(val op: Char, val operand: MathExpr, val position: Int) : MathExpr

    /** 二元运算。[op] 为 `+ - * / % ^` 之一，[position] 指向运算符本身。 */
    data class Binary(
        val op: Char,
        val left: MathExpr,
        val right: MathExpr,
        val position: Int,
    ) : MathExpr

    /** 函数调用，例如 `max(1, 2)`。[position] 指向函数名起点。 */
    data class Call(val name: String, val args: List<MathExpr>, val position: Int) : MathExpr
}
