package com.qcmian.clipper.devtools.ui.components.code

/**
 * 一个高亮片段：文档偏移 [start]..[end) 属于某类语法单元。
 *
 * 不是语法树，只是一遍扫描出来的「着色区间」——原生 `BasicTextField` 只有一个
 * `VisualTransformation` 能插手显示，够用就好。
 */
internal data class CodeToken(val start: Int, val end: Int, val kind: CodeKind)

internal enum class CodeKind { Key, StringLiteral, Number, Constant, Punctuation }

/**
 * 一对配对的括号。
 *
 * @param open  开括号自己的位置（`{` 或 `[`）
 * @param close 闭括号自己的位置（`}` 或 `]`）
 */
internal data class BracketPair(val open: Int, val close: Int) {
    /** 可折叠区间从这里开始：开括号之后。 */
    val foldStart: Int get() = open + 1

    /** 可折叠区间在这里结束（不含）：闭括号之前。 */
    val foldEnd: Int get() = close

    /** 只有中间真的有内容才值得折。 */
    val isFoldable: Boolean get() = close > open + 1
}

/**
 * 一遍扫描的结果：着色片段、括号配对、行起点。
 *
 * 三样东西都要，而且都只需要一次遍历，所以合成一个函数返回，避免在同一段文本上扫三遍。
 */
internal class CodeStructure(
    val tokens: List<CodeToken>,
    val brackets: List<BracketPair>,
    private val lineStarts: IntArray
) {
    /** 逻辑行数（1 起）。行号栏的宽度按它算。 */
    val lineCount: Int get() = lineStarts.size

    /** 文档偏移落在第几行（1 起）。 */
    fun lineNumberAt(offset: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (lineStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo + 1
    }

    /** 第 [line] 行（1 起）的起点偏移。 */
    fun lineStart(line: Int): Int = lineStarts[line.coerceIn(1, lineStarts.size) - 1]

    /** 第 [line] 行（1 起）的结束偏移（不含换行符）。 */
    fun lineEnd(text: String, line: Int): Int {
        val index = line.coerceIn(1, lineStarts.size) - 1
        if (index >= lineStarts.size - 1) return text.length
        var end = lineStarts[index + 1] - 1
        if (end > lineStarts[index] && end - 1 >= 0 && text.getOrNull(end - 1) == '\r') end--
        return end
    }

    /** 起点落在第 [line] 行（1 起）内的可折叠括号对。 */
    fun foldableOnLine(text: String, line: Int): BracketPair? {
        val from = lineStart(line)
        val to = lineEnd(text, line)
        return brackets.firstOrNull { it.isFoldable && it.foldStart in from..to }
    }
}

/**
 * 扫描 JSON，得到着色片段、括号配对与行起点。
 *
 * 刻意**不是**解析器：JSON 工具已经有 `JsonFormat`（kotlinx）负责「合不合法 / 格式化成什么」，
 * 这里只回答显示层的两个问题——「这个 token 该上什么色」「哪对括号之间可以折叠」。因此它对
 * 非法输入照样工作（边打边有高亮），也不会与 `JsonFormat` 的判断出现分歧。
 */
internal fun scanJson(text: String): CodeStructure {
    val tokens = ArrayList<CodeToken>()
    val brackets = ArrayList<BracketPair>()
    val lineStarts = ArrayList<Int>()
    lineStarts.add(0)
    val openStack = ArrayList<Int>()

    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\n' -> {
                lineStarts.add(i + 1)
                i++
            }

            c == '"' -> {
                val start = i
                i++
                while (i < text.length) {
                    when (text[i]) {
                        '\\' -> i += 2
                        '"' -> {
                            i++
                            break
                        }

                        else -> i++
                    }
                }
                val end = i.coerceAtMost(text.length)
                tokens.add(
                    CodeToken(
                        start,
                        end,
                        if (followedByColon(text, end)) CodeKind.Key else CodeKind.StringLiteral
                    )
                )
            }

            c.isDigit() || c == '-' -> i = scanNumber(text, i, tokens)

            keywordAt(text, i) != null -> {
                val word = keywordAt(text, i)!!
                tokens.add(CodeToken(i, i + word.length, CodeKind.Constant))
                i += word.length
            }

            c == '{' || c == '[' -> {
                openStack.add(i)
                tokens.add(CodeToken(i, i + 1, CodeKind.Punctuation))
                i++
            }

            c == '}' || c == ']' -> {
                val open = if (openStack.isEmpty()) null else openStack.removeAt(openStack.size - 1)
                if (open != null) brackets.add(BracketPair(open, i))
                tokens.add(CodeToken(i, i + 1, CodeKind.Punctuation))
                i++
            }

            c == ',' || c == ':' -> {
                tokens.add(CodeToken(i, i + 1, CodeKind.Punctuation))
                i++
            }

            else -> i++
        }
    }

    return CodeStructure(tokens = tokens, brackets = brackets, lineStarts = lineStarts.toIntArray())
}

/** 字符串后面（跳过空白）跟着 `:`，说明它是键而不是字符串值。 */
private fun followedByColon(text: String, from: Int): Boolean {
    var i = from
    while (i < text.length && text[i].isWhitespace()) i++
    return i < text.length && text[i] == ':'
}

/** 该位置是否是 JSON 字面量，返回它的长度（否则 null）。 */
private fun keywordAt(text: String, at: Int): String? {
    // 前面必须不是标识符字符，否则 `xtrue` 里的 `true` 会被误判。
    if (at > 0 && (text[at - 1].isLetterOrDigit() || text[at - 1] == '_')) return null
    for (word in listOf("true", "false", "null")) {
        if (at + word.length <= text.length &&
            text.regionMatches(at, word, 0, word.length)
        ) {
            return word
        }
    }
    return null
}

private fun scanNumber(text: String, from: Int, tokens: MutableList<CodeToken>): Int {
    var i = from
    while (i < text.length) {
        val c = text[i]
        if (c.isDigit() || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') i++ else break
    }
    if (i == from) i++ // 孤立的 `-`：至少吃掉一个字符，避免死循环
    tokens.add(CodeToken(from, i, CodeKind.Number))
    return i
}
