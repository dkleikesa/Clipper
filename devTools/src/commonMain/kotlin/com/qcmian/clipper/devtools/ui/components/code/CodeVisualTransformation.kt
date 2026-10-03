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
    val gutterDivider: Color,
    /** 光标所在那一行的整行底纹。 */
    val currentLineBackground: Color,
    /** 光标停在某个括号上时，与它配对的那两个括号的底纹。 */
    val bracketBackground: Color
)

/** 折叠区间被替换成的那个字符。 */
internal const val FoldPlaceholder = "\u2026"

/**
 * 一个制表符在屏幕上占几列。
 *
 * 取 4：制表符本来就是一整个缩进档，而 Compose 自己的排版把它画成**正好一个空格宽**（实测
 * 8px == 1 个空格），于是「制表符」与「1 个空格」在屏幕上一模一样，偏偏前者本该缩进一格。
 *
 * 这里只是把宽度摆正，不额外画标记。代价是它与「4 个空格」在屏幕上也一样宽——但那是制表符的
 * 本性：它没有固有宽度，「一档等于几列」是排版策略（tab size），不同编辑器可以不一样。
 * 想区分只能靠标记，而标记属于「显示里多出一个字符」，不要。
 *
 * 只按显示算，`value` 里仍是那一个 `\t`。
 */
internal const val TabDisplayWidth = 4

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
        // 制表符要展开成多列，映射就不再是恒等的，因此快路径多一个前提。
        if (hidden.isEmpty() && tokens.isEmpty() && '\t' !in source) {
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
            if (source[i] == '\t') {
                // 一个制表符占满一档：只把宽度摆正，不额外画标记——显示里不该多出文本里没有的字符。
                builder.append(" ".repeat(TabDisplayWidth))
                // 展开出来的每一列都映射回这一个制表符：光标落在其中哪一列，文档偏移都是它。
                repeat(TabDisplayWidth) { backward.add(i) }
            } else {
                builder.append(source[i])
                backward.add(i)
            }
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
