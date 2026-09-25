package com.qcmian.clipper.cli

import com.qcmian.clipper.protocol.CliCommand
import com.qcmian.clipper.protocol.CliRequest
import com.qcmian.clipper.protocol.DEFAULT_LIMIT
import com.qcmian.clipper.protocol.MAX_LIMIT

/**
 * 命令定义的**唯一来源**。
 *
 * 同一份 spec 现在用来：解析 argv、校验取值、渲染 `--help`。将来还要用来生成 MCP 的
 * `tools/list` JSON Schema——那时才不至于出现「MCP 说支持 `--sort` 但 CLI 根本没实现」
 * 这种漂移。因此 [OptionSpec.choices] 与 [OptionSpec.range] 都是声明式的，
 * 它们同时是命令行校验规则和 JSON Schema 的 `enum` / `minimum` / `maximum`。
 *
 * 这里只描述**有哪些命令、各有什么参数**，不涉及解析与渲染的细节。
 */

enum class ValueKind {
    TEXT,
    NUMBER,

    /** 布尔开关：出现即为真，**不吃**下一个参数。 */
    FLAG,
}

data class OptionSpec(
    val name: String,
    val kind: ValueKind,
    val description: String,
    /** 用法里的取值占位符（不含尖括号），如 `N`、`TYPE`；省略时用选项名的大写。 */
    val valueName: String? = null,
    /** 取值闭区间；`null` 表示不限制。只对 [ValueKind.NUMBER] 有意义。 */
    val range: IntRange? = null,
    val choices: List<String>? = null,
    /** 未给出时的取值；只影响 `--help` 的显示，解析器另有兜底。 */
    val defaultValue: String? = null,
) {
    /** 取值占位符，如 `<N>`；[ValueKind.FLAG] 没有取值，用不到它。 */
    val valueLabel: String get() = "<${valueName ?: name.uppercase()}>"
}

data class PositionalSpec(
    val name: String,
    val description: String,
    /** 吃掉剩余全部位置参数（`delete <id> <id> …`）。 */
    val variadic: Boolean = false,
)

data class CommandSpec(
    val name: String,
    val summary: String,
    val positionals: List<PositionalSpec> = emptyList(),
    val options: List<OptionSpec> = emptyList(),
    /** 不能同时出现的开关组；声明式写在 spec 里，校验与将来的 MCP schema 都直接取用。 */
    val mutuallyExclusive: List<List<String>> = emptyList(),
    /** `--help` 里的示例，每条写全 `clipper …`。 */
    val examples: List<String> = emptyList(),
) {
    val acceptsVariadic: Boolean get() = positionals.any { it.variadic }

    /** 非变长位置参数都必填；变长位置参数至少一个。 */
    val minPositionals: Int
        get() = positionals.count { !it.variadic } + if (acceptsVariadic) 1 else 0

    val maxPositionals: Int? get() = if (acceptsVariadic) null else positionals.size
}

object Commands {

    private val LIMIT = OptionSpec(
        name = "limit",
        kind = ValueKind.NUMBER,
        description = "最多返回多少条",
        valueName = "N",
        range = 1..MAX_LIMIT,
        defaultValue = DEFAULT_LIMIT.toString(),
    )

    val ALL: List<CommandSpec> = listOf(
        CommandSpec(
            name = CliCommand.LIST,
            summary = "列出剪贴板历史",
            options = listOf(
                LIMIT,
                OptionSpec(
                    name = "kind",
                    kind = ValueKind.TEXT,
                    description = "只看某类型",
                    valueName = "TYPE",
                    choices = listOf("text", "image", "file", "richtext"),
                ),
                OptionSpec(
                    name = "sort",
                    kind = ValueKind.TEXT,
                    description = "排序字段",
                    valueName = "FIELD",
                    choices = listOf("lastCopiedAt", "firstCopiedAt", "copies", "size"),
                ),
                OptionSpec(
                    name = "order",
                    kind = ValueKind.TEXT,
                    description = "排序方向",
                    valueName = "ORDER",
                    choices = listOf("desc", "asc"),
                ),
                OptionSpec("pinned", ValueKind.FLAG, "只看置顶项"),
                OptionSpec("unpinned", ValueKind.FLAG, "只看未置顶项"),
            ),
            mutuallyExclusive = listOf(listOf("pinned", "unpinned")),
            examples = listOf(
                "clipper list --kind image --limit 5",
                "clipper list --sort copies",
            ),
        ),
        CommandSpec(
            name = CliCommand.SEARCH,
            summary = "在历史里搜索（匹配标题与正文）",
            positionals = listOf(PositionalSpec("query", "搜索词；多个词之间是 AND，含空格时加引号")),
            options = listOf(LIMIT),
            examples = listOf(
                "clipper search \"发票 报销\"",
                "clipper search 截图 --limit 50",
            ),
        ),
        CommandSpec(
            name = CliCommand.GET,
            summary = "取单条内容",
            positionals = listOf(PositionalSpec("id", "条目 id")),
            options = listOf(
                OptionSpec("raw", ValueKind.FLAG, "只直出文本内容，不套 JSON 信封"),
                OptionSpec("ocr", ValueKind.FLAG, "只直出图片识别原文"),
                OptionSpec(
                    name = "format",
                    kind = ValueKind.TEXT,
                    description = "把某种附加表示导出成文件，响应里给路径",
                    valueName = "FORMAT",
                    choices = listOf("html", "rtf", "pdf"),
                ),
            ),
            // `--format` 给文件路径，`--raw` / `--ocr` 直出文本；同给会静默给错东西，直接拒绝。
            mutuallyExclusive = listOf(listOf("format", "raw"), listOf("format", "ocr")),
            examples = listOf("clipper get <id> --raw", "clipper get <id> --format pdf"),
        ),
        CommandSpec(
            name = CliCommand.COPY,
            summary = "把某条内容写回系统剪贴板",
            positionals = listOf(PositionalSpec("id", "条目 id")),
        ),
        CommandSpec(
            name = CliCommand.PIN,
            summary = "置顶一条",
            positionals = listOf(PositionalSpec("id", "条目 id")),
        ),
        CommandSpec(
            name = CliCommand.UNPIN,
            summary = "取消置顶一条",
            positionals = listOf(PositionalSpec("id", "条目 id")),
        ),
        CommandSpec(
            name = CliCommand.DELETE,
            summary = "删除若干条",
            positionals = listOf(PositionalSpec("id", "条目 id，可给多个", variadic = true)),
        ),
        CommandSpec(
            name = CliCommand.STATS,
            summary = "历史概况：条数、类型分布、占用",
        ),
        CommandSpec(
            name = CliCommand.PING,
            summary = "探活并查看协议版本",
        ),
    )

    val BY_NAME: Map<String, CommandSpec> = ALL.associateBy { it.name }
}

/** 取值一律存字符串：校验已在解析阶段按 spec 做完，[intOption] 的转换不会落空。 */
data class ParsedArgs(
    val command: CommandSpec,
    val positionals: List<String>,
    val options: Map<String, String>,
) {
    fun option(name: String): String? = options[name]

    fun intOption(name: String): Int? = options[name]?.toIntOrNull()

    fun flag(name: String): Boolean = options.containsKey(name)
}

/**
 * 把解析结果翻译成线上请求，是**唯一**做「CLI 参数 → 协议字段」映射的地方：
 * `--pinned` → `pinned = true` 这类变形都收在这一个函数里，解析器里没有特例。
 *
 * `--raw` / `--ocr` 不在这里出现：它们只改 CLI 打印什么，不改服务端返回什么。
 */
fun ParsedArgs.toRequest(): CliRequest = when (command.name) {
    CliCommand.LIST -> CliRequest(
        cmd = CliCommand.LIST,
        kind = option("kind"),
        sort = option("sort"),
        order = option("order"),
        limit = intOption("limit") ?: DEFAULT_LIMIT,
        pinned = when {
            flag("pinned") -> true
            flag("unpinned") -> false
            else -> null
        },
    )

    CliCommand.SEARCH -> CliRequest(
        cmd = CliCommand.SEARCH,
        query = positionals.first(),
        limit = intOption("limit") ?: DEFAULT_LIMIT,
    )

    CliCommand.GET -> CliRequest(
        cmd = CliCommand.GET,
        id = positionals.first(),
        format = option("format"),
    )

    CliCommand.COPY -> CliRequest(cmd = CliCommand.COPY, id = positionals.first())
    CliCommand.PIN -> CliRequest(cmd = CliCommand.PIN, id = positionals.first())
    CliCommand.UNPIN -> CliRequest(cmd = CliCommand.UNPIN, id = positionals.first())
    CliCommand.DELETE -> CliRequest(cmd = CliCommand.DELETE, ids = positionals)
    CliCommand.STATS -> CliRequest(cmd = CliCommand.STATS)
    CliCommand.PING -> CliRequest(cmd = CliCommand.PING)

    // 走不到：调用方先按 BY_NAME 查过。
    else -> CliRequest(cmd = command.name)
}
