package com.qcmian.clipper.cli

import com.qcmian.clipper.protocol.CliExitCode
import com.qcmian.clipper.protocol.PROTOCOL_VERSION
import com.qcmian.clipper.protocol.TITLE_CHAR_LIMIT

/**
 * `--help` 的渲染。
 *
 * 除全局开关与「输出」「退出码」两段说明外，内容全部从 [Commands] 的 spec 生成，
 * 因此改一个取值上限时帮助不会漏改。
 *
 * 版式按命令行惯例排：描述 → 用法 → 命令 / 参数 / 选项 → 示例 → 退出码。
 * 两列布局的列宽一律按最宽标签算，不手写空格（手写的那些一遇中文就会歪）。
 */
object Help {

    fun overview(): String = buildString {
        appendLine("查询与操作 Clipper 的剪贴板历史")
        appendLine()
        appendLine("用法：clipper <命令> [选项]")
        appendLine()
        appendLine("命令：")
        appendTwoColumn(Commands.ALL.map { it.name }, Commands.ALL.map { it.summary })
        appendLine()
        appendLine("选项：")
        appendTwoColumn(GLOBAL_OPTIONS.map { it.label }, GLOBAL_OPTIONS.map { it.description })
        appendLine()
        appendLine("输出：")
        appendLine("  JSON 格式，错误同时出现在 stdout 与退出码里：")
        appendLine("""    {"ok":true,"data":…}""")
        appendLine("""    {"ok":false,"error":{"code":"NOT_FOUND","message":"…"}}""")
        appendLine("  列表与搜索只给截断后的 title（最多 $TITLE_CHAR_LIMIT 字符），要全文用 clipper get <id>；")
        appendLine("  二进制内容（图片 / PDF）不经过 stdout，响应里的 path 就是它的落盘位置。")
        appendLine()
        appendLine("退出码：")
        appendTwoColumn(EXIT_CODES.map { it.first.toString() }, EXIT_CODES.map { it.second })
        appendLine()
        appendLine("协议版本：$PROTOCOL_VERSION")
    }

    fun forCommand(spec: CommandSpec): String = buildString {
        appendLine(spec.summary)
        appendLine()
        appendLine("用法：${spec.synopsis()}")

        val positionals = spec.positionals.map { it.name + if (it.variadic) "…" else "" }
        val options = spec.options.map { it.label() }
        // 两段共用同一列宽，免得「参数」与「选项」的说明列各对齐各的。
        val column = (positionals + options).maxOfOrNull { it.length }?.plus(2) ?: 0

        if (positionals.isNotEmpty()) {
            appendLine()
            appendLine("参数：")
            appendTwoColumn(positionals, spec.positionals.map { it.description }, column)
        }

        if (options.isNotEmpty()) {
            appendLine()
            appendLine("选项：")
            appendTwoColumn(options, spec.options.map { it.describe() }, column)
        }

        // 全局开关对每个命令都有效，与该命令有没有自己的选项无关。
        appendLine()
        appendLine("全局选项：-h / --pretty / --timeout，见 clipper --help")

        if (spec.examples.isNotEmpty()) {
            appendLine()
            appendLine("示例：")
            spec.examples.forEach { appendLine("  $it") }
        }
    }

    /** 两列布局：标签列按最宽标签对齐，说明列统一起点。 */
    private fun StringBuilder.appendTwoColumn(
        labels: List<String>,
        descriptions: List<String>,
        column: Int = labels.maxOf { it.length } + 2,
    ) {
        labels.forEachIndexed { index, label ->
            appendLine("  " + label.padEnd(column) + descriptions[index])
        }
    }
}

/** 选项在用法与列表里的写法：`--limit <N>`；[ValueKind.FLAG] 没有取值。 */
private fun OptionSpec.label(): String =
    "--$name" + if (kind == ValueKind.FLAG) "" else " $valueLabel"

/** 说明后面缀上取值约束：`最多返回多少条（1..200，默认 20）`。 */
private fun OptionSpec.describe(): String {
    val constraints = buildList {
        choices?.let { add(it.joinToString(" / ")) }
        range?.let { add("${it.first}..${it.last}") }
        defaultValue?.let { add("默认 $it") }
    }
    return if (constraints.isEmpty()) description else "$description（${constraints.joinToString("，")}）"
}

/** 只给 `--help` 用的全局开关条目；解析在 [ArgParser] 里手工做。 */
private data class HelpOption(
    val name: String,
    val description: String,
    val short: String? = null,
    val valueLabel: String? = null,
) {
    /** `-h, --help` 与 `    --pretty`：有没有短名，长选项都落在同一列。 */
    val label: String
        get() = (short?.let { "-$it, " } ?: "    ") + "--$name" + (valueLabel?.let { " $it" } ?: "")
}

private val GLOBAL_OPTIONS = listOf(
    HelpOption(
        name = "help",
        short = "h",
        description = "显示总览；写在某个命令后面（clipper list --help）看该命令的详情",
    ),
    HelpOption(
        name = "pretty",
        description = "缩进输出；默认紧凑（不缩进、不输出默认值，省 token）",
    ),
    HelpOption(
        name = "timeout",
        valueLabel = "<MS>",
        description = "等待 app 响应的上限（毫秒，默认 $DEFAULT_TIMEOUT_MILLIS）",
    ),
)

private val EXIT_CODES = listOf(
    CliExitCode.OK to "成功（空结果也算成功）",
    CliExitCode.ERROR to "其他错误",
    CliExitCode.USAGE to "用法错误（参数缺失或非法）",
    CliExitCode.NOT_FOUND to "未找到",
    CliExitCode.NO_DAEMON to "app 未运行（连不上 socket）",
    CliExitCode.TIMEOUT to "超时",
)
