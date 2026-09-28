// `prettyPrintIndent` 目前仍标着实验标记；与 `ClipperJson` 一样在这里一次性放行。
@file:OptIn(ExperimentalSerializationApi::class)

package com.qcmian.clipper.devtools.tools.json

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * JSON 的解析与两种排版。
 *
 * 是纯函数、不碰 UI，因此既能被工具界面直接调用、也能被数据类型探测器复用（见
 * `JsonDataTypeDetector`），将来要给它写单测也不必起组合环境。
 *
 * 唯一依赖是 `kotlinx-serialization-json`——本项目已经在用，不额外引入实现。
 */
internal object JsonFormat {
    /** 缩进宽度。与主流编辑器默认值一致。 */
    const val INDENT: String = "    "

    /** 严格模式：不接受尾逗号、注释、单引号这些「宽松 JSON」，避免把噪声当成合法内容。 */
    private val parser = Json { isLenient = false }

    private val pretty = Json {
        prettyPrint = true
        prettyPrintIndent = INDENT
    }

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
    fun format(text: String): Result<String> = runCatching {
        pretty.encodeToString(JsonElement.serializer(), parser.parseToJsonElement(text))
    }

    /** 压缩成一行。 */
    fun minify(text: String): Result<String> = runCatching {
        compact.encodeToString(JsonElement.serializer(), parser.parseToJsonElement(text))
    }
}
