package com.qcmian.clipper.devtools.ui.components.code

/**
 * 一个高亮片段：文档偏移 [start]..[end) 属于某类语法单元。
 *
 * 不是语法树，只是一遍扫描出来的「着色区间」——原生 `BasicTextField` 只有一个
 * `VisualTransformation` 能插手显示，够用就好。
 */
internal data class CodeToken(val start: Int, val end: Int, val kind: CodeKind)

internal enum class CodeKind { Key, StringLiteral, Number, Constant, Punctuation, Comment }

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
 * 不做任何着色与折叠的扫描结果，只算出每一行的起点。
 *
 * 给「不是代码的输入」用（数学表达式）：它既没有需要着色的语法单元，也没有块可折，但共用的代码框
 * 仍要靠行起点支撑「当前行底纹」等按行的工作。
 */
internal fun scanPlain(text: String): CodeStructure {
    val lineStarts = ArrayList<Int>()
    lineStarts.add(0)
    text.forEachIndexed { index, c -> if (c == '\n') lineStarts.add(index + 1) }
    return CodeStructure(tokens = emptyList(), brackets = emptyList(), lineStarts = lineStarts.toIntArray())
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

/**
 * 扫描 XML，得到着色片段、可折叠的元素区间与行起点。
 *
 * 与 [scanJson] 同一取舍：**不是**解析器（校验 / 排版归 `XmlFormat`），只回答显示层的两个问题——
 * 「这个 token 该上什么色」「哪个元素的内容可以折」。非法输入照样有高亮，也不会与解析结果打架。
 *
 * 折叠用元素表达，而不是 XML 里并不存在的「括号」：起始标签的 `>` 与对应结束标签的 `<` 配成
 * [BracketPair]，中间那段就是元素内容。于是 `<a>…</a>` 折叠成 `<a>…</a>` 里的一个省略号，
 * 与 [scanJson] 把 `{…}` 折起来是同一套机制。
 */
internal fun scanXml(text: String): CodeStructure {
    val tokens = ArrayList<CodeToken>()
    val brackets = ArrayList<BracketPair>()
    val lineStarts = ArrayList<Int>()
    lineStarts.add(0)
    // 尚未闭合元素的起始标签末尾（`>` 的下标）。`</name>` 到来时与栈顶配成一对。
    val openStack = ArrayList<Int>()

    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\n' -> {
                lineStarts.add(i + 1)
                i++
            }

            c == '<' -> when {
                // 注释 / CDATA / DOCTYPE / 处理指令：整段一个 Comment 着色，不再往里拆。
                text.startsWith("<!--", i) -> i = markXmlRun(text, i, "-->", tokens)
                text.startsWith("<![CDATA[", i) -> i = markXmlRun(text, i, "]]>", tokens)
                text.startsWith("<?", i) -> i = markXmlRun(text, i, "?>", tokens)
                text.startsWith("<!", i) -> i = markXmlRun(text, i, ">", tokens)
                else -> i = scanXmlTag(text, i, tokens, brackets, openStack)
            }

            // 正文里的字符实体，例如 `&amp;`；没找到分号就当一个普通字符跳过。
            c == '&' -> {
                val end = text.indexOf(';', i + 1)
                if (end in (i + 1) until text.length) {
                    tokens.add(CodeToken(i, end + 1, CodeKind.Constant))
                    i = end + 1
                } else {
                    i++
                }
            }

            else -> i++
        }
    }

    return CodeStructure(tokens = tokens, brackets = brackets, lineStarts = lineStarts.toIntArray())
}

/** 从 [start] 起把一整段（注释 / CDATA / 声明）标成 Comment，返回它之后的下标。 */
private fun markXmlRun(
    text: String,
    start: Int,
    terminator: String,
    tokens: MutableList<CodeToken>,
): Int {
    val found = text.indexOf(terminator, start + 1)
    val stop = if (found < 0) text.length else found + terminator.length
    tokens.add(CodeToken(start, stop, CodeKind.Comment))
    return stop
}

/** 扫一个标签（起始或结束）；返回标签结束后的下标。 */
private fun scanXmlTag(
    text: String,
    start: Int,
    tokens: MutableList<CodeToken>,
    brackets: MutableList<BracketPair>,
    openStack: MutableList<Int>,
): Int {
    var i = start
    tokens.add(CodeToken(i, i + 1, CodeKind.Punctuation)) // `<`
    i++

    if (i < text.length && text[i] == '/') {
        // 结束标签 `</name>`：与栈顶的起始标签配成一对，中间那段即元素内容。
        tokens.add(CodeToken(i, i + 1, CodeKind.Punctuation)) // `/`
        i++
        val nameStart = i
        while (i < text.length && !text[i].isWhitespace() && text[i] != '>') i++
        if (i > nameStart) tokens.add(CodeToken(nameStart, i, CodeKind.Key))
        while (i < text.length && text[i] != '>') i++
        if (i < text.length) {
            tokens.add(CodeToken(i, i + 1, CodeKind.Punctuation))
            i++
        }
        val openEnd = if (openStack.isEmpty()) null else openStack.removeAt(openStack.lastIndex)
        if (openEnd != null && start > openEnd) brackets.add(BracketPair(openEnd, start))
        return i
    }

    // 起始标签：名字，随后是一串「属性名 = "值"」，末尾可能是 `/>`。
    val nameStart = i
    while (i < text.length && !text[i].isWhitespace() && text[i] != '>' && text[i] != '/') i++
    if (i > nameStart) tokens.add(CodeToken(nameStart, i, CodeKind.Key))

    var selfClosing = false
    while (i < text.length && text[i] != '>') {
        when {
            text[i].isWhitespace() -> i++

            text[i] == '/' -> {
                tokens.add(CodeToken(i, i + 1, CodeKind.Punctuation))
                selfClosing = true
                i++
            }

            text[i] == '=' -> {
                tokens.add(CodeToken(i, i + 1, CodeKind.Punctuation))
                i++
            }

            text[i] == '"' || text[i] == '\'' -> {
                val quote = text[i]
                val valueStart = i
                i++
                while (i < text.length && text[i] != quote) i++
                if (i < text.length) i++
                tokens.add(CodeToken(valueStart, i, CodeKind.StringLiteral))
            }

            else -> {
                val attrStart = i
                while (i < text.length && !text[i].isWhitespace() &&
                    text[i] != '=' && text[i] != '>' && text[i] != '/'
                ) {
                    i++
                }
                if (i > attrStart) tokens.add(CodeToken(attrStart, i, CodeKind.Key))
            }
        }
    }

    if (i < text.length) {
        tokens.add(CodeToken(i, i + 1, CodeKind.Punctuation)) // `>`
        // `>` 的下标要留到对应 `</name>` 到来时才算得出折叠加 `isFoldable`；自闭合标签没有。
        if (!selfClosing) openStack.add(i)
        i++
    }
    return i
}
