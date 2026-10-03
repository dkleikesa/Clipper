package com.qcmian.clipper.devtools.detect

import com.qcmian.clipper.devtools.api.DataTypes
import com.qcmian.clipper.devtools.tools.json.JsonFormat
import com.qcmian.clipper.devtools.tools.timestamp.TimestampConvert
import com.qcmian.clipper.devtools.tools.xml.isWellFormedXml

/**
 * 一次数据类型探测：判断一段文本属于哪种格式。
 *
 * 与工具解耦——探测器只说「这是什么」，工具只说「我能吃什么」，主面板拿 [typeName] 把两边接起来
 * （见 `DevToolsRegistry.rankedTools`）。因此新增一种格式可以只加探测器、也可以只加工具。
 */
interface DataTypeDetector {
    /** 它识别的类型名（取值见 [DataTypes]）。 */
    val typeName: String

    /**
     * 具体程度。越大越具体，[detectTypes] 据此降序排列：`json` 排在 `text` 之前。
     *
     * 兜底类型（`text`）取 0，任何格式探测器都应当大于它。
     */
    val specificity: Int get() = 0

    /** [text] 是否属于本类型。实现必须**不抛异常**——探测跑在打开面板的同步路径上。 */
    fun matches(text: String): Boolean
}

/**
 * 探测 [text] 命中的所有类型，**最具体的在前**；最后通常跟着兜底的 [DataTypes.TEXT]。
 *
 * 同分时保持探测器声明的顺序（`sortedByDescending` 是稳定排序），结果因此可预期。
 */
fun Iterable<DataTypeDetector>.detectTypes(text: String): List<String> =
    filter { it.matches(text) }
        .sortedByDescending { it.specificity }
        .map { it.typeName }

internal object JsonDataTypeDetector : DataTypeDetector {
    override val typeName: String = DataTypes.JSON
    override val specificity: Int = 60

    override fun matches(text: String): Boolean {
        val trimmed = text.trim()
        val head = trimmed.firstOrNull() ?: return false
        if (head != '{' && head != '[') return false
        return JsonFormat.isValid(trimmed)
    }
}

internal object XmlDataTypeDetector : DataTypeDetector {
    override val typeName: String = DataTypes.XML
    override val specificity: Int = 50

    override fun matches(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.firstOrNull() != '<') return false
        return isWellFormedXml(trimmed)
    }
}

internal object UrlDataTypeDetector : DataTypeDetector {
    override val typeName: String = DataTypes.URL
    override val specificity: Int = 40

    private val pattern = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://\S+$""")

    override fun matches(text: String): Boolean = pattern.matches(text.trim())
}

/**
 * Unix 时间戳：一串长度像时间戳的纯数字。
 *
 * 判据由 [TimestampConvert.isEpochNumber] 提供——探测与工具必须用同一把尺，否则会出现「按时间戳
 * 匹配了工具、工具却不认这串数字」的错位（与 JSON 探测器复用 `JsonFormat.isValid` 同一个道理）。
 */
internal object TimestampDataTypeDetector : DataTypeDetector {
    override val typeName: String = DataTypes.TIMESTAMP
    override val specificity: Int = 45

    override fun matches(text: String): Boolean = TimestampConvert.isEpochNumber(text)
}

/** 兜底：任何有内容的文本。始终排在最后（[specificity] 为 0）。 */
internal object TextDataTypeDetector : DataTypeDetector {
    override val typeName: String = DataTypes.TEXT

    override fun matches(text: String): Boolean = text.isNotBlank()
}

/**
 * 内置探测器集合。顺序不参与排序（排序只看 [DataTypeDetector.specificity]），
 * 但同分时按这里的声明顺序稳定排列。
 */
val BuiltInDataTypeDetectors: List<DataTypeDetector> = listOf(
    JsonDataTypeDetector,
    XmlDataTypeDetector,
    TimestampDataTypeDetector,
    UrlDataTypeDetector,
    TextDataTypeDetector,
)
