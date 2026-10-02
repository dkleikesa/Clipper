// `prettyPrintIndent` 目前仍标着实验标记；与 `ClipperJson` 一样在这里一次性放行。
@file:OptIn(ExperimentalSerializationApi::class)

package com.qcmian.clipper.devtools.tools.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 美化时用什么缩进。
 *
 * 用值类型而不是把缩进串直接交给调用方：工具界面要按它画「4 空格 / 制表符」这类控件，从 `"    "`
 * 反推「这是几格」很别扭。
 */
internal sealed interface JsonIndent {
    /** [count] 个空格。 */
    data class Spaces(val count: Int) : JsonIndent

    /** 一个制表符。 */
    data object Tab : JsonIndent

    /** 交给 kotlinx 的缩进串。 */
    val text: String
        get() = when (this) {
            is Spaces -> " ".repeat(count)
            Tab -> "\t"
        }
}

/**
 * JSON 的解析与两种排版。
 *
 * 是纯函数、不碰 UI，因此既能被工具界面直接调用、也能被数据类型探测器复用（见
 * `JsonDataTypeDetector`），将来要给它写单测也不必起组合环境。
 *
 * 唯一依赖是 `kotlinx-serialization-json`——本项目已经在用，不额外引入实现。
 */
internal object JsonFormat {
    /** 默认缩进宽度。与主流编辑器默认值一致。 */
    const val DefaultIndentSpaces: Int = 4

    /** 缩进可选档位的下限：再小就等于没缩进。 */
    const val MinIndentSpaces: Int = 1

    /** 缩进可选档位的上限：再大一层就顶到半屏宽。 */
    const val MaxIndentSpaces: Int = 8

    /**
     * 界面上可选的全部缩进，顺序即菜单顺序：先按档位从小到大排的空格，最后是制表符。
     *
     * 取值域放在这里而不是工具界面里：它是 [JsonIndent] 的定义域，界面只负责把它渲染出来。
     * 也因此能脱离组合环境单测——「1..8 与制表符一个不少」是这个工具的硬要求，
     * 而它在界面上只是一列菜单项，靠眼看容易漏。
     */
    val IndentOptions: List<JsonIndent> =
        (MinIndentSpaces..MaxIndentSpaces).map { JsonIndent.Spaces(it) } + JsonIndent.Tab

    /** 默认缩进方式。 */
    val DefaultIndent: JsonIndent = JsonIndent.Spaces(DefaultIndentSpaces)

    /** 严格模式：不接受尾逗号、注释、单引号这些「宽松 JSON」，避免把噪声当成合法内容。 */
    private val parser = Json { isLenient = false }

    private val compact = Json { }

    /**
     * 能解析成 JSON 元素就返回它，否则返回 `null`。
     *
     * 探测路径读 [isValid]（只看成败），工具路径读 [format] / [minify]（要失败原因），两者共用
     * 这里，因此「探测器说它是 JSON、工具却报错」不可能发生。
     */
    fun parseOrNull(text: String): JsonElement? =
        runCatching { parser.parseToJsonElement(text) }.getOrNull()

    fun isValid(text: String): Boolean = parseOrNull(text) != null

    /** 美化：失败时 [Result] 带着解析器的原始异常信息（含出错位置）。 */
    fun format(
        text: String,
        indent: JsonIndent = DefaultIndent,
        sortKeys: Boolean = false,
    ): Result<String> = runCatching {
        prettyPrinter(indent).encodeToString(JsonElement.serializer(), prepare(text, sortKeys))
    }

    fun minify(text: String, sortKeys: Boolean = false): Result<String> = runCatching {
        compact.encodeToString(JsonElement.serializer(), prepare(text, sortKeys))
    }

    /** 解析，并按 [sortKeys] 决定要不要先把键排好。排版与压缩共用这一段。 */
    private fun prepare(text: String, sortKeys: Boolean): JsonElement {
        val parsed = parser.parseToJsonElement(text)
        return if (sortKeys) parsed.sortedByKey() else parsed
    }

    /**
     * 按 [indent] 建一个只管写出来的实例。
     *
     * `prettyPrintIndent` 是构造参数，实例建好就改不了，而缩进在界面上随时可调，所以只能按需建。
     * 调用点在输入防抖之后（见 `JsonDevTool`），这点构造开销不值得再加一层缓存——缓存反而要处理
     * 「缓存几个、什么时候失效」。
     */
    private fun prettyPrinter(indent: JsonIndent): Json = Json {
        prettyPrint = true
        prettyPrintIndent = indent.text
    }
}

/**
 * 递归把所有对象的键按升序重排；数组只是容器，里面的元素照样往下走。
 *
 * 排序必须在**解析后的树**上做，不能拿格式化好的文本按行排：键的样子会出现在字符串值里
 * （`{"a":"{\"b\":1}"}`），而且文本的行序根本表达不了嵌套关系——按行排出的结果大部分时候看着
 * 是对的，碰上上面那种输入就悄悄错了。
 *
 * 比较用 [String] 的自然序，也就是码位序：`A` < `Z` < `a` < `z`。这与 jq `--sort-keys`、
 * Python `json.dumps(sort_keys=True)` 一致，跨工具对得上；代价是大小写混排时 `URL` 会排在
 * `id` 前面。想换成「忽略大小写」的字典序，把下面的比较子换掉即可。
 *
 * kotlinx.serialization 本身没有这个开关（`JsonBuilder` 里没有排序选项），所以只能自己重建节点。
 * [JsonObject] 与 [JsonArray] 都按传入容器的迭代顺序写出去，[associate] 建出的 `LinkedHashMap`
 * 因此就是最终顺序，不用再跟写出端打招呼。
 */
private fun JsonElement.sortedByKey(): JsonElement = when (this) {
    is JsonObject -> JsonObject(
        entries.sortedBy { it.key }.associate { it.key to it.value.sortedByKey() }
    )

    is JsonArray -> JsonArray(map { it.sortedByKey() })
    else -> this
}
