package com.qcmian.clipper.devtools.ui.components.code

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/** 代码区的配色（见 `rememberCodeColors`）。 */
internal data class CodeColors(
    val editorBackground: Color,
    val key: Color,
    val string: Color,
    val number: Color,
    val constant: Color,
    val punctuation: Color,
    val foldPlaceholder: Color,
    val foldPlaceholderBackground: Color,
    val gutterDivider: Color
)

/** 折叠区间被替换成的那个字符。 */
internal const val FoldPlaceholder = "\u2026"

/**
 * 把高亮与折叠做成**纯显示层**的变换。
 *
 * 关键取舍：`value` 始终是真实文档，折叠只改变「画出来的样子」。因此
 *  - 折叠不可能污染文本（不需要把隐藏内容另存一份，也就没有「合并回文档」这一步）；
 *  - 输入法、光标、选区仍然是原生控件在管（它们通过 [OffsetMapping] 换算偏移）；
 *  - 格式化 / 校验仍旧只看 `value`，与本变换无关。
 *
 * @param tokens 高亮片段，来自 [scanJson]
 * @param folded 当前折叠的括号对；嵌套时只取最外层
 */
internal class CodeVisualTransformation(
    private val tokens: List<CodeToken>,
    private val folded: List<BracketPair>,
    private val colors: CodeColors
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val hidden = outermostFolds(folded)
        if (hidden.isEmpty() && tokens.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        // original -> transformed（长度 source.length + 1）
        val forward = IntArray(source.length + 1)
        // transformed -> original（每追加一个显示字符记一格，末尾再补一格）
        val backward = ArrayList<Int>(source.length + 1)
        val builder = AnnotatedString.Builder()

        var hiddenIndex = 0
        var tokenIndex = 0
        var activeKind: CodeKind? = null
        var i = 0
        while (i < source.length) {
            val nextHidden = hidden.getOrNull(hiddenIndex)
            if (nextHidden != null && i == nextHidden.foldStart) {
                // 整段隐藏，换成一个占位符；被隐藏的偏移全部映射到占位符起点。
                // 隐藏区间是 [foldStart, foldEnd)：闭括号本身留在外面，显示成 `{…}`。
                if (activeKind != null) {
                    builder.pop()
                    activeKind = null
                }
                val chipStart = builder.length
                builder.pushStyle(
                    SpanStyle(
                        color = colors.foldPlaceholder,
                        background = colors.foldPlaceholderBackground
                    )
                )
                builder.append(FoldPlaceholder)
                builder.pop()
                repeat(FoldPlaceholder.length) { backward.add(nextHidden.foldStart) }
                for (offset in i until nextHidden.foldEnd) forward[offset] = chipStart
                i = nextHidden.foldEnd
                hiddenIndex++
                continue
            }

            while (tokenIndex < tokens.size && tokens[tokenIndex].end <= i) tokenIndex++
            val token = tokens.getOrNull(tokenIndex)
            val kind = if (token != null && i >= token.start) token.kind else null
            if (kind != activeKind) {
                if (activeKind != null) builder.pop()
                if (kind != null) builder.pushStyle(SpanStyle(color = colorOf(kind)))
                activeKind = kind
            }

            forward[i] = builder.length
            builder.append(source[i])
            backward.add(i)
            i++
        }
        if (activeKind != null) builder.pop()
        forward[source.length] = builder.length
        backward.add(source.length)

        return TransformedText(
            builder.toAnnotatedString(),
            MappedOffsetMapping(forward, backward.toIntArray())
        )
    }

    private fun colorOf(kind: CodeKind): Color = when (kind) {
        CodeKind.Key -> colors.key
        CodeKind.StringLiteral -> colors.string
        CodeKind.Number -> colors.number
        CodeKind.Constant -> colors.constant
        CodeKind.Punctuation -> colors.punctuation
    }
}

/** 嵌套折叠时只保留最外层：内层的内容已经被外层盖住了。 */
private fun outermostFolds(folded: List<BracketPair>): List<BracketPair> {
    val sorted = folded.sortedBy { it.foldStart }
    val result = ArrayList<BracketPair>(sorted.size)
    var coveredUntil = -1
    for (pair in sorted) {
        if (pair.foldStart > coveredUntil) {
            result.add(pair)
            coveredUntil = pair.close
        }
    }
    return result
}

/**
 * 用两张表实现的双向映射。
 *
 * [forward] 单调不减（折叠把一整段压到一个点），[backward] 同样单调不减（占位符映射回被折叠
 * 区间的起点），因此满足 `OffsetMapping` 对单调性的要求。
 */
private class MappedOffsetMapping(
    private val forward: IntArray,
    private val backward: IntArray
) : OffsetMapping {
    override fun originalToTransformed(offset: Int): Int =
        forward[offset.coerceIn(0, forward.size - 1)]

    override fun transformedToOriginal(offset: Int): Int =
        backward[offset.coerceIn(0, backward.size - 1)]
}
