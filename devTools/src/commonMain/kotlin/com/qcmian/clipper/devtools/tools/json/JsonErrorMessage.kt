package com.qcmian.clipper.devtools.tools.json

/**
 * 把 kotlinx-serialization 的报错说成人话。
 *
 * 库的原话形如
 * `Unexpected JSON token at offset 12: Expected quotation mark '"', but had 'c' instead at path: $`：
 * `offset` 是从 0 数起的字符下标（不是行号）、原因是英文、末尾缀着解析路径，直接显示没法用。这里做两件事：
 *  - 拿原文把偏移换算成「第几行 第几列」；
 *  - 把已知的原因翻成中文。
 *
 * **认错比认不出更糟**，所以只有能准确对上库的模板时才翻译；对不上的照抄英文，但位置照给——位置是唯一
 * 能直接指导修改的信息。翻出来时另附一行库的原文，方便拿它去搜或反馈。
 */
internal fun jsonErrorMessage(source: String, error: Throwable): String {
    val message = error.message ?: error::class.simpleName ?: return "解析失败"
    // 库会把「出错位置前后各 30 字符」附在第二行；用户眼前的输入框就是原文，不必再抄一遍。
    val head = message.substringBefore('\n')
    // 认不出偏移就原样带出：其余部分都依赖它，硬凑只会给出错的「行列」。
    val offset = OffsetPattern.find(head)?.groupValues?.get(1)?.toIntOrNull() ?: return head
    // 库里 `fail()` 一律在末尾缀 ` at path: $...`；我们只做 `parseToJsonElement`（树解析），
    // 路径恒为根，所以摘掉即可，不必显示。
    val cause = PathPattern.replace(head, "").substringAfter("Unexpected JSON token at offset $offset: ")
    val (line, column) = lineColumn(source, offset)
    val where = "第 $line 行 第 $column 列"
    val translated = causeText(cause) ?: return "$where：$cause"
    return "$where：$translated\n原文：$head"
}

/** 库的英文原因 → 中文；认不出来返回 `null`，由调用方退回原文。 */
private fun causeText(cause: String): String? {
    // 「期望 X，实际读到 Y」：最常见的一类。
    ExpectedButHadPattern.find(cause)?.let { match ->
        val expected = expectedText(match.groupValues[1])
        return "这里应该有$expected，实际是 ${actualText(match.groupValues[2])}"
    }
    // 「一个完整的值之后还有内容」：库这条没给实际字符加引号（`but had e instead`），另立一条。
    EofAfterValuePattern.find(cause)?.let { match ->
        return "一个完整的 JSON 值之后还有多余内容：${actualText(match.groupValues[1])}"
    }
    // `{"a": }` 这类：值的位置直接撞上了闭合符。
    CannotReadPattern.find(cause)?.let { match ->
        return "这里应该有一个值，实际碰上了${expectedText(match.groupValues[1])}"
    }
    return when {
        cause.startsWith("Expected end of the object or comma") ->
            "一个键值对之后应该是逗号 , 或右花括号 }"

        cause.startsWith("Expected end of the array or comma") ->
            "一个数组元素之后应该是逗号 , 或右方括号 ]"

        cause.startsWith("Trailing comma before the end of JSON") ->
            "结尾多了一个逗号（JSON 不允许尾逗号）"

        cause.startsWith("Unexpected leading comma") -> "开头多了一个逗号"

        cause.startsWith("Expected string literal but 'null' literal was found") ->
            "这里应该是字符串，实际是 null"

        cause.startsWith("Expected closing quotation mark") -> "字符串少了结尾的双引号"

        cause.startsWith("Expected beginning of the string") -> "这里应该是一个用双引号引起的字符串"

        cause.startsWith("Expected numeric literal") -> "这里应该是一个数字"

        cause.startsWith("Encountered an unknown key") -> "出现了不认识的字段名"

        else -> null
    }
}

/** 期望项的名字：库给的是 `quotation mark '"'` 这种「人话 + 字符」的写法。 */
private fun expectedText(description: String): String = when (description) {
    "quotation mark '\"'" -> "一个双引号 \""
    "string escape sequence '\\'" -> "一个转义符 \\"
    "comma ','" -> "一个逗号 ,"
    "colon ':'" -> "一个冒号 :"
    "start of the object '{'" -> "左花括号 {"
    "end of the object '}'" -> "右花括号 }"
    "start of the array '['" -> "左方括号 ["
    "end of the array ']'" -> "右方括号 ]"
    "end of the input" -> "输入的结尾"
    // 认不出的照抄英文：翻译得含糊比不翻译更误导。
    else -> description
}

/** 库把「实际读到的」写成单个字符，`EOF` 与空白单独说。 */
private fun actualText(actual: String): String = when (actual) {
    "EOF" -> "输入的结尾"
    " " -> "空格"
    else -> "'$actual'"
}

/**
 * 字符下标 → 行列（都从 1 起）。
 *
 * 库的 `offset` 是 UTF-16 下标，与 Kotlin `String` 的索引一致，所以直接按 `\n` 数即可。
 * 只按 `\n` 分行：`\r\n` 里那个 `\r` 会被算进上一行末尾，行数不受影响。
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

private val OffsetPattern = Regex("""\bat offset (\d+)""")

private val PathPattern = Regex(""" at path: [^\n]*""")

private val ExpectedButHadPattern = Regex("""Expected (.+), but had '(.+)' instead""")

private val EofAfterValuePattern = Regex("""Expected EOF after parsing, but had (.+) instead""")

private val CannotReadPattern = Regex("""Cannot read Json element because of unexpected (.+)""")
