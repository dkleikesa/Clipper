package com.qcmian.clipper.cli

import com.qcmian.clipper.protocol.CliCommand
import com.qcmian.clipper.protocol.PROTOCOL_VERSION
import com.qcmian.clipper.protocol.TITLE_CHAR_LIMIT

/**
 * `--help` 的渲染。
 *
 * 内容是**纯粹从 [Commands] 的 spec 生成**的，没有一个字是手写的命令说明——
 * 这样加一个 `--limit` 的取值上限时，帮助里不会漏改。
 *
 * 输出是给人读的纯文本，不是 JSON：帮助是散文，套进信封只会更难读，
 * 而 Agent 读 `--help` 本来就是为了理解语义。
 */
object Help {

    fun overview(): String = buildString {
        appendLine("clipper — 查询与操作 Clipper 的剪贴板历史")
        appendLine()
        appendLine("用法：clipper <命令> [参数…]")
        appendLine()
        appendLine("命令：")

        // 按名字对齐，让 Agent 一眼扫完。
        val width = Commands.ALL.maxOf { it.name.length }
        Commands.ALL.forEach { spec ->
            appendLine("  ${spec.name.padEnd(width)}  ${spec.summary}")
        }

        appendLine()
        appendLine("全局开关：")
        appendLine("  --pretty           缩进输出；默认紧凑（不缩进、不输出默认值，省 token）")
        appendLine("  --timeout <毫秒>   等待 app 响应的上限，默认 $DEFAULT_TIMEOUT_MILLIS")
        appendLine("  -h, --help         看总览；`clipper <命令> --help` 看单条命令")
        appendLine()
        appendLine("输出：")
        appendLine("  一律是同一形状的 JSON 信封：")
        appendLine("""    {"ok":true,"data":…}""")
        appendLine("""    {"ok":false,"error":{"code":"NOT_FOUND","message":"…"}}""")
        appendLine("  错误同时出现在 stdout 与退出码里——只读 stdout 也能拿到详情。")
        appendLine()
        appendLine("  列表与搜索只给截断后的 title（最多 $TITLE_CHAR_LIMIT 字符）；要全文用 `clipper get <id>`。")
        appendLine("  二进制内容（图片 / PDF）**不经过 stdout**：响应里的 path 就是它的落盘位置。")
        appendLine("  `get --raw` 只直出文本；`get --format <html|rtf|pdf>` 把附加表示导出成文件并给路径。")
        appendLine()
        appendLine("退出码：0 成功 · 2 用法错误 · 3 未找到 · 4 app 未运行 · 5 超时 · 1 其他")
        appendLine()
        appendLine("协议版本：$PROTOCOL_VERSION")
    }

    fun forCommand(spec: CommandSpec): String = buildString {
        appendLine("clipper ${spec.name} — ${spec.summary}")
        appendLine()
        appendLine("用法：clipper ${spec.name}${spec.usageSuffix()}")

        if (spec.positionals.isNotEmpty()) {
            appendLine()
            appendLine("参数：")
            val labels = spec.positionals.map { "  ${it.name}" + if (it.variadic) "…" else "" }
            val column = labels.maxOf { it.length } + 2
            spec.positionals.forEachIndexed { index, positional ->
                appendLine(labels[index].padEnd(column) + positional.description)
            }
        }

        if (spec.options.isNotEmpty()) {
            appendLine()
            appendLine("选项：")
            // 列宽必须按「渲染后的标签」算，含 `<值>` 后缀——只按选项名算的话，
            // 带取值的选项会把说明列整体挤歪。
            val labels = spec.options.map {
                "  --${it.name}" + if (it.kind == ValueKind.FLAG) "" else " <值>"
            }
            val column = labels.maxOf { it.length } + 2
            spec.options.forEachIndexed { index, option ->
                appendLine(labels[index].padEnd(column) + option.description)
                // 取值约束另起一行，避免把说明列撑得很宽、读起来要来回扫。
                val constraints = buildList {
                    option.choices?.let { add(it.joinToString(" / ")) }
                    option.range?.let { add("${it.first}..${it.last}") }
                }
                if (constraints.isNotEmpty()) {
                    appendLine(" ".repeat(column) + "（${constraints.joinToString("；")}）")
                }
            }
        }

        if (spec.name == CliCommand.LIST) {
            appendLine()
            appendLine("示例：clipper list --kind image --limit 5")
        }
    }
}
