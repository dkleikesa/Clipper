package com.qcmian.clipper.devtools.tools.math

/**
 * 把 [MathError] 说成「第几行 第几列：为什么」。
 *
 * 与 JSON 工具同一口径（见 `jsonErrorMessage`）：位置是最能指导修改的信息，所以只要错误带着
 * 位置就写在前面。表达式通常只有一行，那时省掉「第 1 行」，只说「第 N 列」，短一些也好读。
 *
 * [MathError.position] 为负表示这条错误不属于某个具体位置（例如结果溢出），只给一句话。
 */
internal fun mathErrorMessage(source: String, error: MathError): String {
    val message = error.message ?: "表达式有误"
    if (error.position < 0) return message
    val (line, column) = lineColumn(source, error.position)
    return if (line == 1) "第 $column 列：$message" else "第 $line 行 第 $column 列：$message"
}

/**
 * 字符下标 → 行列（都从 1 起）。
 *
 * 下标是 UTF-16 下标，与 Kotlin `String` 的索引一致，所以直接按 `\n` 数即可；`\r\n` 里那个 `\r`
 * 会被算进上一行末尾，不影响行数。
 */
private fun lineColumn(source: String, offset: Int): Pair<Int, Int> {
    val at = offset.coerceIn(0, source.length)
    var line = 1
    var lineStart = 0
    for (index in 0 until at) {
        if (source[index] == '\n') {
            line++
            lineStart = index + 1
        }
    }
    return line to (at - lineStart + 1)
}
