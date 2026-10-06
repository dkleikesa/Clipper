package com.qcmian.clipper.devtools.tools.regex

import com.qcmian.clipper.devtools.tools.textPositionAt
import kotlin.text.RegexOption

/**
 * 一个标志位：界面上的那枚开关，以及它落到引擎上的方式。
 *
 * **只有 [IgnoreCase] 与 [Multiline] 交给引擎**：stdlib 的 `RegexOption` 是 `expect enum class`，
 * 只有这两个在每个平台都认。另外两个由我们自己按模式改写实现（见 `rewritePattern`）——语义因此
 * 由我们定义、到处一致，也不会在某天加了 JS / iOS target 之后才在编译期炸出来。
 */
internal enum class RegexFlag(
    val title: String,
    /** 正则圈里的单字母写法，悬停提示带上它——找人问的时候对得上。 */
    val letter: Char,
    /**
     * 它到底改了什么。
     *
     * 不带字母前缀：这一句同时喂两处——工具栏那枚开关的悬停提示（[tooltip]）与速查表里的「示例」
     * 列（见 `RegexSyntax`）。各写一份的话，两处迟早说得不一样。
     */
    val effect: String,
    /**
     * 交给引擎的选项；`null` 表示**不交给引擎**，由 `rewritePattern` 改写模式来实现。
     *
     * 判据是「这个选项在 common 上有没有」：`COMMENTS` 与 `DOT_MATCHES_ALL` 在 JS 上压根不存在，
     * 而本项目现在只编 jvm target，本地怎么编都看不出来。往这里加选项之前先问一句：它在
     * [RegexOption] 上是所有平台都有的吗？
     */
    val engineOption: RegexOption?,
) {
    IgnoreCase("忽略大小写", 'i', "A 与 a 算同一个字符", RegexOption.IGNORE_CASE),
    Multiline("多行", 'm', "^ 与 $ 认每一行的开头与结尾", RegexOption.MULTILINE),
    DotAll("点匹配换行", 's', ". 也匹配换行符", null),

    /**
     * 由 [rewritePattern] 改写实现。**不**用引擎自带的 `Pattern.COMMENTS`：那一个会把字符类里的
     * 空白也忽略掉，连 `[#]` 都编译不过（见 [rewritePattern] 的说明）。
     */
    Comments("忽略空白与注释", 'x', "忽略模式里的空白与 # 注释", null);

    /** 悬停提示里那一句：带上单字母写法，找人问的时候对得上。 */
    val tooltip: String get() = "$letter：$effect"
}

/**
 * 一套模式：模式串 + 标志位。
 *
 * 是值类型而不是散着的两个参数：它是「算一次匹配」的全部输入（见 `RegexDevTool` 里那条管线），
 * 相等性也正好能当「这次算的是不是眼前这份」的判据。
 */
internal data class RegexPattern(
    val source: String,
    val flags: Set<RegexFlag> = emptySet(),
) {
    /** 交给引擎的选项：只有 [RegexFlag.engineOption] 非空的那几个。 */
    val engineOptions: Set<RegexOption>
        get() = flags.mapNotNullTo(mutableSetOf()) { it.engineOption }
}

/** 一个捕获组。[name] 只有命名组才非空。 */
internal data class RegexGroup(val index: Int, val name: String?, val value: String)

/**
 * 一处命中。
 *
 * [index] 从 1 起，是结果里的序号（不是偏移）；[range] 才是它在正文里的位置——用引擎自己给的
 * `MatchResult.range`，绝不拿 [value] 回正文里 `indexOf`：同一段内容出现两次就会指错。
 */
internal data class RegexMatch(
    val index: Int,
    val range: IntRange,
    val value: String,
    val groups: List<RegexGroup>,
) {
    /** 零宽命中（`^`、`\b`、`(?=…)` 这类）：位置有意义，内容为空。 */
    val isZeroWidth: Boolean get() = range.isEmpty()
}

/** 一次匹配的产出：命中的那些，以及有没有撞到上限。 */
internal data class MatchScan(val matches: List<RegexMatch>, val truncated: Boolean)

/** 一次替换的产出。[count] 是替换掉的处数。 */
internal data class ReplaceOutcome(val text: String, val count: Int)

/**
 * 模式（或按它改写出来的那份）不能用；[reason] 已经是给人看的一句话。
 *
 * 文案在 [RegexFormat] 里就翻好，是为了让工具那一层完全不必认识引擎的报错长什么样——这也是
 * 「只编 jvm target」时最容易被忽略的一处：换个平台，引擎的报错描述就换成另一种英文了。
 */
internal class RegexPatternError(val reason: String) : IllegalArgumentException(reason)

/**
 * 正则的纯逻辑：编译、查找、替换，外加重写模板的校验与命中报告的排版。
 *
 * 一次计算的完整链路是「**改写** → 编译 → 跑」（改写见 [rewritePattern]），失败时抛出的
 * [RegexPatternError] 里已经是一句人话，调用方直接显示即可。
 *
 * 是纯函数、不碰 UI，因此能脱离组合环境单测；与 `JsonFormat` / `UrlFormat` 同一分工——工具界面
 * 只管交互与排版，引擎这一层的问题都在这里说清楚。
 */
internal object RegexFormat {

    /**
     * 一次最多列出多少处命中。
     *
     * 上限是给**界面**的，不是给性能的：结果那一栏是一次性铺开的一段文字，一篇几十万行的日志配
     * `.+` 能撞出几十万处。截断而不是拒绝——前 2000 处一般已经够看出模式写得对不对。
     */
    const val MaxMatches: Int = 2000

    /**
     * 扫一遍 [text]，列出命中（最多 [limit] 处）。
     *
     * 空模式给的是**空结果**而不是「处处都匹配」：界面上那个空的模式框是「还没写」，拿它去跑一遍
     * 只会得到一屏零宽命中，对用户没有任何意义。
     *
     * 用 `findAll` 而不是自己推进下标：零宽命中该前进多少由引擎说了算，手写循环在 `^`、`\b`、
     * `(?!…)` 这些地方极容易原地打转。
     */
    fun scan(pattern: RegexPattern, text: String, limit: Int = MaxMatches): Result<MatchScan> {
        if (pattern.source.isEmpty()) return Result.success(MatchScan(emptyList(), truncated = false))
        val prepared = rewritePattern(pattern.source, pattern.flags)
        prepared.emptyProblem()?.let { return Result.failure(RegexPatternError(it)) }
        return runCatching {
            val regex = compiled(prepared, pattern)
            val names = captureNames(prepared.text)
            val found = ArrayList<RegexMatch>(minOf(limit, 256))
            var truncated = false
            var index = 0
            for (result in regex.findAll(text)) {
                if (found.size >= limit) {
                    truncated = true
                    break
                }
                index++
                found.add(result.toMatch(index, names))
            }
            MatchScan(found, truncated)
        }.recoverCatching { error -> throw RegexPatternError(regexErrorMessage(prepared, error)) }
    }

    /**
     * 按 [template] 替换全部命中；[template] 应当先过 [templateProblem]。
     *
     * 替换本身不报条数（`Regex.replace` 只给结果），所以另数一遍。多出的这一遍仍在同一个后台任务
     * 里，用户看到的是同一次结果，不会多一次「计算中」。
     */
    fun replace(pattern: RegexPattern, text: String, template: String): Result<ReplaceOutcome> {
        if (pattern.source.isEmpty()) return Result.success(ReplaceOutcome(text, 0))
        val prepared = rewritePattern(pattern.source, pattern.flags)
        prepared.emptyProblem()?.let { return Result.failure(RegexPatternError(it)) }
        return runCatching {
            val regex = compiled(prepared, pattern)
            val count = regex.findAll(text).count()
            ReplaceOutcome(regex.replace(text, template), count)
        }.recoverCatching { error -> throw RegexPatternError(regexErrorMessage(prepared, error)) }
    }

    /**
     * 替换模板里的第一个问题；没有就返回 `null`。
     *
     * 与 [RegexFormat.replace] 收同样的两个入参，是为了让调用方不必自己拼「组名从哪儿数」这件事：
     * 组名要认**真正交给引擎的那份模式**——`x` 会把注释连同里面的括号一起删掉，两边的组数可能
     * 不一样。
     */
    fun templateProblem(pattern: RegexPattern, template: String): String? {
        val prepared = rewritePattern(pattern.source, pattern.flags)
        return problemInTemplate(template, captureNames(prepared.text))
    }

    /**
     * 模式里各捕获组的名字，**下标即组号**（下标 0 是整段匹配，恒为 `null`）。
     *
     * 为什么自己扫而不问引擎：通用代码里拿不到组名——`MatchNamedGroupCollection` 只有平台的
     * `MatchResult` 才带，而且手上没有匹配结果时（比如刚敲完模式、还没算）压根无从问起。扫一遍
     * 够用：它只认括号，不做语法分析，认错了也只是名字标错，不影响命中结果本身。
     *
     * 传进来的应当是**改写之后**的模式（`x` 会改掉括号的数量）。要认对四种情况：三种命名写法
     * （`(?<名>`、`(?'名'`、`(?P<名>`——后两种在 JVM 上会被引擎拒掉，这里认出来是为了在报错之前
     * 别把组数算错）、`\Q…\E` 里的字面量、以及字符类里的括号。
     */
    fun captureNames(pattern: String): List<String?> {
        val names = ArrayList<String?>()
        names.add(null)
        var i = 0
        var inClass = false
        // 紧跟 `[`（或 `[^`）的那个 `]` 是普通字符，不是类的结尾；记下位置才判得出来。
        var classStart = 0
        while (i < pattern.length) {
            when (pattern[i]) {
                '\\' -> {
                    // `\Q…\E` 之间全是字面量，里面的括号不算括号。
                    if (pattern.getOrNull(i + 1) == 'Q') {
                        val end = pattern.indexOf("\\E", i + 2)
                        i = if (end < 0) pattern.length else end + 2
                    } else {
                        i += 2
                    }
                }

                '[' -> {
                    inClass = true
                    classStart = if (pattern.getOrNull(i + 1) == '^') i + 2 else i + 1
                    i++
                }

                ']' -> {
                    if (inClass && i > classStart) inClass = false
                    i++
                }

                '(' -> {
                    val named = if (inClass) null else namedGroupAt(pattern, i)
                    when {
                        named != null -> {
                            names.add(named.first)
                            i = named.second
                        }
                        // `(?:`、`(?=`、`(?i)` 这些都不占组号：只跳掉 `(?`，组体照常往下扫。
                        !inClass && pattern.getOrNull(i + 1) == '?' -> i += 2
                        inClass -> i++
                        else -> {
                            names.add(null)
                            i++
                        }
                    }
                }

                else -> i++
            }
        }
        return names
    }
}

/**
 * 改写（`x`）之后模式里什么都不剩：这种模式拿去跑就是「处处都匹配的空串」，报出来是一屏零宽
 * 命中。引擎自己不会拦（它会老老实实编译一个空模式），所以在这里先说清楚。
 */
private fun PreparedPattern.emptyProblem(): String? =
    if (text.isEmpty() && source.isNotEmpty()) {
        "忽略空白与注释之后，模式里什么都不剩了"
    } else {
        null
    }

/** 编译改写后的模式，并把标志位里那几个交给引擎的选项带上。 */
private fun compiled(prepared: PreparedPattern, pattern: RegexPattern): Regex =
    Regex(prepared.text, pattern.engineOptions)

/**
 * 替换模板里的第一个问题；没有就返回 `null`。
 *
 * 模板的写法**就是引擎的写法**（`$1`、`${名}`、`\$`），所以这里只做校验与解释，不做翻译——多一层
 * 翻译就多一处会走样的地方。`$` 后面跟了不认识的东西、组号越界、组名不存在，都在这里说清楚，
 * 而不是让引擎抛一句英文的 `Illegal group reference` 出来。
 *
 * [names] 见 [RegexFormat.captureNames]。
 */
private fun problemInTemplate(template: String, names: List<String?>): String? {
    val groupCount = names.size - 1
    var i = 0
    while (i < template.length) {
        when (template[i]) {
            '\\' -> {
                if (i + 1 >= template.length) {
                    return "结尾的「\\」后面要跟一个字符；要一个字面量反斜杠，写「\\\\」"
                }
                i += 2
            }

            '$' -> {
                val next = template.getOrNull(i + 1)
                when {
                    next == null ->
                        return "结尾的「$」后面要跟组号（如 \$1）或组名（如 \${名}）"

                    next == '{' -> {
                        val end = template.indexOf('}', i + 2)
                        if (end < 0) return "「\${」少了配对的「}」"
                        val referenced = template.substring(i + 2, end)
                        val number = referenced.toIntOrNull()
                        if (number != null) {
                            if (number > groupCount) return groupProblem(number, names)
                        } else if (names.none { it == referenced }) {
                            return "没有名为「$referenced」的捕获组${availableGroups(names)}"
                        }
                        i = end + 1
                    }

                    next.isDigit() -> {
                        var end = i + 1
                        while (end < template.length && template[end].isDigit()) end++
                        val number = template.substring(i + 1, end).toInt()
                        if (number > groupCount) return groupProblem(number, names)
                        i = end
                    }

                    else -> return "「\$$next」不是组引用；要一个字面量「$」请写成「\\$」"
                }
            }

            else -> i++
        }
    }
    return null
}

/**
 * 把命中列表排成结果框里那段文字。
 *
 * 每处一行「序号 + 位置 + 匹配内容」，有捕获组就在下面缩进列出来。位置报**行列**而不是字符
 * 下标：用户要拿它回原文里找，行列才指得准。
 *
 * 传给 [RegexFormat.scan] 的 [subject] 必须与这里的是同一份：位置是按它算的。
 */
internal fun matchReport(subject: String, matches: List<RegexMatch>, truncated: Boolean): String {
    val builder = StringBuilder()
    for (match in matches) {
        val at = textPositionAt(subject, match.range.first)
        builder.append('#').append(match.index)
            .append("  第 ").append(at.line).append(" 行 第 ").append(at.column).append(" 列  ")
            .append(if (match.isZeroWidth) "(零宽)" else inline(match.value))
            .append('\n')
        for (group in match.groups) {
            builder.append("    组 ").append(group.index)
            group.name?.let { builder.append('(').append(it).append(')') }
            builder.append(" = ").append(if (group.value.isEmpty()) "(空)" else inline(group.value))
                .append('\n')
        }
    }
    if (truncated) {
        builder.append("…（只列出前 ").append(matches.size).append(" 处，后面还有）")
    }
    return builder.toString().trimEnd('\n')
}

/**
 * 报告里怎么显示一段匹配内容：换行折成一个 `\n`。
 *
 * 不折的话，一处 `[\s\S]+` 那样的命中会把整段报告的「一处一行」版式撑开，后面那些命中全被推得
 * 看不出是一行。折成两个字符是编辑器的通行写法，眼睛一看就知道那里有换行。
 */
private fun inline(value: String): String = value.replace("\n", "\\n")

/** 组号越界的说法：报出越界的那一号，并说清这个模式到底有几个组、叫什么。 */
private fun groupProblem(index: Int, names: List<String?>): String =
    "没有第 $index 个捕获组${availableGroups(names)}"

/** 这个模式有哪些组——报「组不存在」时顺带把能用的列出来，省得用户回去数括号。 */
private fun availableGroups(names: List<String?>): String {
    if (names.size <= 1) return "（这个模式没有捕获组）"
    val listed = names.drop(1).mapIndexed { position, name ->
        val number = position + 1
        if (name == null) "$number" else "$number=$name"
    }
    return "（这个模式有 ${names.size - 1} 个组：${listed.joinToString("、")}）"
}

/**
 * `(?<名>` / `(?'名'` / `(?P<名>` 的组名；返回（组名, 组名之后的下标）；不是命名组返回 `null`。
 *
 * `(?<=` 与 `(?<!` 是环视，开头的那个 `<` 不是组名的开始，要单独挡掉。
 */
private fun namedGroupAt(pattern: String, at: Int): Pair<String, Int>? {
    var i = at + 2
    val close: Char
    when {
        pattern.getOrNull(i) == '<' -> {
            // `(?<=` / `(?<!`：`<` 后面接的是 = 或 !。
            if (pattern.getOrNull(i + 1) == '=' || pattern.getOrNull(i + 1) == '!') return null
            close = '>'
            i++
        }

        pattern.getOrNull(i) == 'P' && pattern.getOrNull(i + 1) == '<' -> {
            close = '>'
            i += 2
        }

        pattern.getOrNull(i) == '\'' -> {
            close = '\''
            i++
        }

        else -> return null
    }
    val end = pattern.indexOf(close, i)
    if (end < 0) return null
    val name = pattern.substring(i, end)
    if (name.isEmpty()) return null
    return name to (end + 1)
}

/** 一处命中怎么变成 [RegexMatch]：组号与组名在这一步对上（组名来自 [RegexFormat.captureNames]）。 */
private fun MatchResult.toMatch(index: Int, names: List<String?>): RegexMatch = RegexMatch(
    index = index,
    range = range,
    value = value,
    groups = (1..groupValues.lastIndex).map { number ->
        RegexGroup(number, names.getOrNull(number), groupValues[number])
    },
)
