package com.qcmian.clipper.devtools.tools.regex

import com.qcmian.clipper.devtools.tools.textPositionAt

/**
 * 把正则引擎的报错说成人话。
 *
 * 引擎的消息形如 `Unclosed group near index 4` + 换行 + 模式原文（有时还有一行 `^`），描述是
 * 英文，位置是**字符下标**。这里做两件事：把描述翻成中文，把下标换成「第几行 第几列」。
 *
 * 位置报的是**用户写的那份**模式里的位置（[PreparedPattern.source]），不是引擎实际执行的那份：
 * `x` 删掉空白之后下标会整体错位，照引擎的原始下标报出来会指到别处去。模式原文里那个字符的
 * 判定（比如「是不是以 `\` 结尾」）用的则是引擎执行的那份——描述说的就是它。
 *
 * **认不出就照抄英文**：这类描述有上百条（各家实现、各个版本还不一样），硬凑一句像样的中文比
 * 留着英文更误导；而位置照给——那是唯一能直接指导修改的信息。这条与 `jsonErrorMessage` 同一
 * 口径。
 *
 * 也有些报错引擎自己就不给位置（例如多了一个 `)`，它给的 index 是 -1）：那时只说原因，不硬编
 * 一个「第 1 行 第 1 列」出来。
 */
internal fun regexErrorMessage(prepared: PreparedPattern, error: Throwable): String {
    val message = error.message ?: error::class.simpleName ?: return "正则模式无法编译"
    // 第二行起是模式原文与 `^` 指示行，这里用不上：界面里左边那个框就是原文。
    val head = message.substringBefore('\n')
    val description = head.substringBefore(" near index ").trim()
    val translated = descriptionText(description, prepared.text) ?: description
    val index = IndexPattern.find(head)?.groupValues?.get(1)?.toIntOrNull()
    if (index == null || index < 0) return translated
    val at = textPositionAt(prepared.source, prepared.sourceIndexAt(index))
    return "第 ${at.line} 行 第 ${at.column} 列：$translated"
}

/**
 * 引擎的英文描述 → 中文；认不出来返回 `null`，由调用方退回原文。
 *
 * 比对一律走小写：引擎自己的大小写并不统一（`Named capturing group <n> is already defined`
 * 是大写开头，`named capturing group is missing trailing '>'` 是小写）。
 */
private fun descriptionText(description: String, pattern: String): String? {
    val lower = description.lowercase()
    return when {
        lower.startsWith("unclosed group") -> "有未闭合的「(」"

        lower.startsWith("unmatched closing") -> "多了一个「)」，它前面没有与之配对的「(」"

        lower.startsWith("unclosed character class") -> "有未闭合的「[」"

        lower.startsWith("dangling meta character") ->
            "「${quoted(description) ?: "?"}」前面没有可重复的内容"

        lower.startsWith("illegal repetition range") ->
            "重复次数写反了：{最少,最多} 里最少不能大于最多"

        lower.startsWith("illegal repetition") ->
            "「{」没有写完整，或者重复符号（* + ? { }）用错了位置"

        lower.startsWith("named capturing group") && lower.contains("missing trailing") ->
            "组名后面少了「>」"

        lower.startsWith("named capturing group") ->
            bracketed(description, '<', '>')?.let { "组名「$it」定义了两次" }

        lower.startsWith("unknown inline modifier") ->
            "「(?」后面这一串不认识。能用的有 (?: (?= (?! (?<= (?<! (?> (?<组名>，以及 (?i) 这类标志"

        lower.startsWith("illegal character range") -> "字符区间写反了（例如 [z-a]）"

        lower.contains("does not start with a latin letter") -> "组名要以英文字母开头"

        lower.contains("hexadecimal codepoint is too big") -> "\\x{…} 里的码位超出了 Unicode 的范围"

        lower.startsWith("unknown character property name") ->
            bracketed(description, '{', '}')?.let { "不认识的 Unicode 属性名「$it」" }
                ?: "不认识的 Unicode 属性名"

        // 模式以「\」结尾：这一句是少数能直接反推出具体毛病的地方，值得单独认。
        // 报错描述随 JDK 版本变过：21 上说的是 `Unescaped trailing backslash`，17 上只说
        // `Unexpected internal error`（等于没说）。后者要靠模式自身才认得出，所以两句都收在
        // 这里，且都必须同时「以 \ 结尾」才算数。
        lower.contains("unescaped trailing backslash") ||
            (lower.contains("unexpected internal error") && pattern.endsWith("\\")) ->
            "结尾的「\\」后面要跟一个字符"

        else -> null
    }
}

/** 描述里用单引号括起来的那个字符，例如 `Dangling meta character '*'`。 */
private fun quoted(description: String): String? =
    QuotedPattern.find(description)?.groupValues?.get(1)

/** 描述里被一对括号括起来的那截名字，例如 `<n>` 与 `{Foo}`。 */
private fun bracketed(text: String, open: Char, close: Char): String? {
    val from = text.indexOf(open)
    val to = text.indexOf(close, from + 1)
    if (from < 0 || to < 0) return null
    return text.substring(from + 1, to)
}

private val IndexPattern = Regex("""near index (\d+)""")

private val QuotedPattern = Regex("""'(.+)'""")
