package com.qcmian.clipper.devtools.tools

/** 一段文本里的位置：行列都从 1 起。 */
internal data class TextPosition(val line: Int, val column: Int)

/**
 * 把字符下标（UTF-16 偏移，与 Kotlin `String` 的索引是同一套）换算成行列。
 *
 * 只按 `\n` 分行：`\r\n` 里那个 `\r` 会被算进上一行的末尾，行数不受影响。偏移超出范围时夹到
 * 两端——报错路径上，「指得偏一点」比「指到别处去」好。
 *
 * 放在 `tools` 这一层而不是某个工具包里：JSON 的报错定位（`jsonErrorMessage`）与正则的报错定位
 * （`regexErrorMessage`）都要用它。各写一份的话，「第几列算错一位」这种毛病会在两个工具里各出现
 * 一次，而且只会在各自的报错路径上才被发现。
 */
internal fun textPositionAt(text: String, offset: Int): TextPosition {
    val at = offset.coerceIn(0, text.length)
    var line = 1
    var lineStart = 0
    for (index in 0 until at) {
        if (text[index] == '\n') {
            line++
            lineStart = index + 1
        }
    }
    return TextPosition(line, at - lineStart + 1)
}
