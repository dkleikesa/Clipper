package com.qcmian.clipper.cli

const val DEFAULT_TIMEOUT_MILLIS: Long = 5_000

/** 与命令无关的开关；不从属于任何 [CommandSpec]，也不发给服务端。 */
data class GlobalOptions(
    /** 缩进输出。默认紧凑：Agent 读的每一个字节都要花 token。 */
    val pretty: Boolean = false,
    val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
)

sealed interface ParseOutcome {
    data class Parsed(val args: ParsedArgs, val global: GlobalOptions) : ParseOutcome

    /**
     * 参数不合法。
     *
     * [hint] 是给人看的补充（「可用命令：…」「要查某个命令的用法：clipper list --help」），
     * 会作为信封里的 `hint` 字段一起输出。
     */
    data class Failure(val message: String, val hint: String? = null) : ParseOutcome

    /** 请求帮助；[command] 为 `null` 表示总览。 */
    data class Help(val command: CommandSpec?) : ParseOutcome
}

/**
 * argv 解析器。
 *
 * 三条刻意的选择：
 *
 * - **不认识就报错，绝不猜。** `--limt` 拼错不会被当成位置参数悄悄吞掉——那会让 Agent
 *   拿到一个「看起来成功、其实是全量列表」的结果，比直接失败危险得多。
 * - **不读环境变量、不读配置文件。** 调用方的全部意图都在 argv 里，Agent 不必先去
 *   猜某台机器上还设了什么。
 * - **`--` 之后一律当位置参数。** id 与查询词里万一出现 `--` 开头的串，还有退路。
 */
object ArgParser {

    fun parse(argv: List<String>): ParseOutcome {
        val global = GlobalOptions()
        var pretty = global.pretty
        var timeout = global.timeoutMillis
        var help = false

        // 逐项扫描而非「只看开头」，这样 `clipper list --help` 也能拿到子命令的用法。
        val rest = ArrayList<String>(argv.size)
        var index = 0
        while (index < argv.size) {
            val arg = argv[index]
            when {
                arg == "-h" || arg == "--help" -> {
                    help = true
                    index++
                }

                arg == "--pretty" -> {
                    pretty = true
                    index++
                }

                arg == "--timeout" || arg.startsWith("--timeout=") -> {
                    val raw = if (arg == "--timeout") argv.getOrNull(index + 1) else arg.substringAfter('=')
                    val parsed = raw?.toLongOrNull()
                    if (parsed == null || parsed <= 0) {
                        return ParseOutcome.Failure(
                            message = "--timeout 需要一个正整数毫秒值",
                            hint = usageHint(null),
                        )
                    }
                    timeout = parsed
                    index += if (arg == "--timeout") 2 else 1
                }

                else -> {
                    rest += arg
                    index++
                }
            }
        }

        val options = GlobalOptions(pretty = pretty, timeoutMillis = timeout)

        // 没有子命令（或就是来问 help 的）都给总览——「不带参数跑一下看看」是 Agent
        // 发现能力的自然动作，那里返回错误码只会妨碍它。
        if (rest.isEmpty()) return ParseOutcome.Help(null)

        val spec = Commands.BY_NAME[rest[0]]
            ?: return ParseOutcome.Failure(
                message = "未知命令：${rest[0]}",
                hint = usageHint(null),
            )

        if (help) return ParseOutcome.Help(spec)

        return parseCommand(spec, rest.subList(1, rest.size), options)
    }

    private fun parseCommand(
        spec: CommandSpec,
        tokens: List<String>,
        global: GlobalOptions,
    ): ParseOutcome {
        val positionals = ArrayList<String>(tokens.size)
        val options = LinkedHashMap<String, String>()
        var optionsEnded = false

        var index = 0
        while (index < tokens.size) {
            val token = tokens[index]

            when {
                token == "--" -> {
                    optionsEnded = true
                    index++
                }

                optionsEnded || !token.startsWith("-") -> {
                    positionals += token
                    index++
                }

                !token.startsWith("--") -> return ParseOutcome.Failure(
                    message = "未知选项：$token",
                    hint = usageHint(spec),
                )

                else -> {
                    val name = token.removePrefix("--").substringBefore('=')
                    val inline = if (token.contains('=')) token.substringAfter('=') else null
                    val optionSpec = spec.options.firstOrNull { it.name == name }
                        ?: return ParseOutcome.Failure(
                            message = "未知选项：--$name",
                            hint = usageHint(spec),
                        )

                    if (options.containsKey(name)) {
                        return ParseOutcome.Failure("--$name 重复出现", usageHint(spec))
                    }

                    if (optionSpec.kind == ValueKind.FLAG) {
                        if (inline != null) {
                            return ParseOutcome.Failure("--$name 是开关，不接受取值", usageHint(spec))
                        }
                        options[name] = "true"
                        index++
                        continue
                    }

                    val value = inline ?: tokens.getOrNull(index + 1)
                    if (value == null) {
                        return ParseOutcome.Failure("--$name 缺少取值", usageHint(spec))
                    }
                    validate(optionSpec, value)?.let { return ParseOutcome.Failure(it, usageHint(spec)) }
                    options[name] = value
                    index += if (inline != null) 1 else 2
                }
            }
        }

        val args = ParsedArgs(spec, positionals, options)
        validateShape(args)?.let { return ParseOutcome.Failure(it, usageHint(spec)) }
        return ParseOutcome.Parsed(args, global)
    }

    /** 规则全部来自 spec 声明，没有针对某个命令的特例；合法时返回 `null`。 */
    private fun validate(option: OptionSpec, value: String): String? {
        if (option.kind == ValueKind.NUMBER) {
            val number = value.toIntOrNull() ?: return "--${option.name} 需要一个整数，收到「$value」"
            val range = option.range
            if (range != null && number !in range) {
                return "--${option.name} 需在 ${range.first}..${range.last} 之间，收到 $number"
            }
        }
        val choices = option.choices
        if (choices != null && value !in choices) {
            return "--${option.name} 只接受 ${choices.joinToString(" / ")}，收到「$value」"
        }
        return null
    }

    private fun validateShape(args: ParsedArgs): String? {
        val spec = args.command
        val given = args.positionals.size
        if (given < spec.minPositionals) {
            val missing = spec.positionals.drop(given).joinToString(" ") { "<${it.name}>" }
            return "缺少参数：$missing"
        }
        val max = spec.maxPositionals
        if (max != null && given > max) {
            return "参数过多：${spec.name} 最多接受 $max 个"
        }
        spec.mutuallyExclusive.forEach { group ->
            val present = group.filter { args.options.containsKey(it) }
            if (present.size > 1) {
                return "${present.joinToString(" 与 ") { "--$it" }} 不能同时使用"
            }
        }
        return null
    }

    private fun usageHint(spec: CommandSpec?): String =
        if (spec == null) {
            "可用命令：${Commands.ALL.joinToString("、") { it.name }}；用 --help 看总览"
        } else {
            "用法：clipper ${spec.name}${spec.usageSuffix()}；用 clipper ${spec.name} --help 看详情"
        }
}

/** 拼接 spec 的用法后缀，`--help` 与错误提示共用，避免两处写法不一致。 */
internal fun CommandSpec.usageSuffix(): String = buildString {
    positionals.forEach { append(if (it.variadic) " <${it.name}>…" else " <${it.name}>") }
    options.forEach { option ->
        append(
            when (option.kind) {
                ValueKind.FLAG -> " [--${option.name}]"
                else -> " [--${option.name} <${option.name}>]"
            }
        )
    }
}
