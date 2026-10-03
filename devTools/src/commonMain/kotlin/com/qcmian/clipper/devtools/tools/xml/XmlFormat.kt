package com.qcmian.clipper.devtools.tools.xml

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter
import nl.adaptivity.xmlutil.newWriter
import nl.adaptivity.xmlutil.xmlStreaming

/**
 * 美化 XML 时用什么缩进。
 *
 * 用值类型而不是把缩进串直接交给调用方：工具界面要按它画「4 空格 / 制表符」这类控件，从 `"    "`
 * 反推「这是几格」很别扭。与 JSON 工具的 `JsonIndent` 同构——两个工具各自独立，但长相一致。
 */
internal sealed interface XmlIndent {
    /** [count] 个空格。 */
    data class Spaces(val count: Int) : XmlIndent

    /** 一个制表符。 */
    data object Tab : XmlIndent

    /** 交给流式写入器的缩进串（为空即不换行，见 [XmlFormat.minify]）。 */
    val text: String
        get() = when (this) {
            is Spaces -> " ".repeat(count)
            Tab -> "\t"
        }
}

/**
 * XML 的校验与排版，基于 kotlinx.serialization 的 XML 格式实现 xmlutil。
 *
 * 与 JSON 工具的 `JsonFormat` 同一分工：纯函数、不碰 UI，既能被工具界面直接调用、也能被数据类型
 * 探测器复用（见 `XmlDataTypeDetector`）。
 *
 * 为什么走 xmlutil 的**流式层**而不是它的 `XML` 格式：格式的编解码要求一个已知的 Kotlin 类型
 * （`encodeToString(serializer, value)`），而这里要处理的是「任意 XML」——没有对应的公开序列化器。
 * 缩进因此直接交给底层写入器（[XmlWriter.indentString]），不再经由 `XML` 格式的配置。
 *
 * 所有函数都是**纯函数**：给定同样的输入返回同样的结果，不抛异常（失败通过 `false` 或
 * [Result] 表达），因此探测与工具两条路径可以放心共用一个实现。
 */
internal object XmlFormat {
    /** 默认缩进宽度。与 JSON 工具、主流编辑器默认值一致。 */
    const val DefaultIndentSpaces: Int = 4

    /** 缩进可选档位的下限：再小就等于没缩进。 */
    const val MinIndentSpaces: Int = 1

    /** 缩进可选档位的上限：再大一层就顶到半屏宽。 */
    const val MaxIndentSpaces: Int = 8

    /**
     * 界面上可选的全部缩进，顺序即菜单顺序：先按档位从小到大排的空格，最后是制表符。
     *
     * 取值域放在这里而不是工具界面里：它是 [XmlIndent] 的定义域，界面只负责把它渲染出来。
     */
    val IndentOptions: List<XmlIndent> =
        (MinIndentSpaces..MaxIndentSpaces).map { XmlIndent.Spaces(it) } + XmlIndent.Tab

    /** 默认缩进方式。 */
    val DefaultIndent: XmlIndent = XmlIndent.Spaces(DefaultIndentSpaces)

    /** 能当作良构的 XML 读一遍就返回 `true`。探测路径读它。 */
    fun isValid(text: String): Boolean = isWellFormedXml(text)

    /** 美化：失败时 [Result] 带着解析器的原始异常信息。 */
    fun format(
        text: String,
        indent: XmlIndent = DefaultIndent,
        sortAttributes: Boolean = false,
        keepComments: Boolean = true,
    ): Result<String> = transform(text, indent.text, sortAttributes, keepComments)

    /** 压缩：不缩进即不换行（见 [transform] 里对空缩进的说明）。 */
    fun minify(
        text: String,
        sortAttributes: Boolean = false,
        keepComments: Boolean = true,
    ): Result<String> = transform(text, "", sortAttributes, keepComments)
}

/** [text] 是否是一份良构的 XML 文档。 */
internal fun isWellFormedXml(text: String): Boolean {
    val reader = runCatching { xmlStreaming.newReader(text) }.getOrNull() ?: return false
    return try {
        // 解析是惰性的：只有把事件全部取完，括号不匹配这类错误才会暴露出来。
        while (reader.hasNext()) reader.next()
        true
    } catch (_: Exception) {
        false
    } finally {
        runCatching { reader.close() }
    }
}

/** 文档开头的 XML 声明，捕获 `<?xml … ?>` 本体（前导 BOM / 空白归到捕获组之外）。 */
private val DECLARATION = Regex("""^[\uFEFF\s]*(<\?xml\s[^?]*\?>)""")

/** 写入器自己生成的那条声明；它一定在输出开头。 */
private val WRITTEN_DECLARATION = Regex("""^[\uFEFF\s]*<\?xml\s[^?]*\?>""")

/**
 * 读一遍 [text] 再写出来，写入时按 [indent] 缩进；[indent] 为空即不换行（写入器的缩进同时负责换行）。
 *
 * 声明**有就留、没有就不补**：输入带 `<?xml … ?>` 才放行 `START_DOCUMENT`，否则写入器会凭空
 * 造一条 `<?xml version='1.0' ?>`（实测）。
 */
private fun transform(
    text: String,
    indent: String,
    sortAttributes: Boolean,
    keepComments: Boolean,
): Result<String> = runCatching {
    val declaration = DECLARATION.find(text)?.groupValues?.get(1)
    val out = StringBuilder()
    val writer = xmlStreaming.newWriter(out, xmlDeclMode = XmlDeclMode.None)
    try {
        writer.indentString = indent
        val reader = xmlStreaming.newReader(text)
        try {
            copyEvents(
                reader,
                writer,
                keepDeclaration = declaration != null,
                sortAttributes = sortAttributes,
                keepComments = keepComments,
            )
        } finally {
            reader.close()
        }
    } finally {
        writer.close()
    }
    restoreDeclaration(out.toString(), declaration)
}

/**
 * 把写入器生成的声明整条换成**原文的那一条**。
 *
 * 不按结构化字段重建，是因为 xmlutil 报出来的 `version` / `encoding` 是错位的——同一份
 * `version="1.1" encoding="GBK"` 会读成 `version=GBK encoding=1.1`，写出来就是
 * `<?xml version='GBK' encoding='1.1' ?>`；原文没有 encoding 时它还会自己补一个。
 * 直接贴回原文，既绕开这个坑，也顺带保住了引号风格、属性顺序与大小写。
 *
 * 与 DevToys 同一思路（它对写入器生成的 `utf-16` 做值替换），只是我们换整条、不做值匹配。
 */
private fun restoreDeclaration(output: String, declaration: String?): String {
    if (declaration == null) return output
    val match = WRITTEN_DECLARATION.find(output) ?: return output
    return output.replaceRange(match.range, declaration)
}

/**
 * 逐个事件搬运，丢掉不承载内容的那些：
 * 文档声明、处理指令、文档起止（[EventType.isIgnorable] 已覆盖），
 * 以及元素之间的纯空白文本（不丢的话会和写入器自己的缩进叠在一起）。
 *
 * 注释是 [keepComments] 开关管的：默认保留，关掉才丢——它是「人的话」，多数时候比缩进更该活着。
 * 注释同样算「可忽略」（[EventType.COMMENT] 的 `isIgnorable` 为真），所以这一条必须排在
 * [EventType.isIgnorable] 那一支之前。
 *
 * [EventType.START_DOCUMENT] 是另一处例外：它同样算「可忽略」，但它是声明的载体，只有
 * [keepDeclaration] 为真时才写出去。
 *
 * [sortAttributes] 为真时，起始标签改走 [writeSortedStartTag]——其余事件照旧整条搬运。
 */
private fun copyEvents(
    reader: XmlReader,
    writer: XmlWriter,
    keepDeclaration: Boolean,
    sortAttributes: Boolean,
    keepComments: Boolean,
) {
    while (reader.hasNext()) {
        val type = reader.next()
        when {
            type == EventType.START_DOCUMENT ->
                if (keepDeclaration) type.createEvent(reader).writeTo(writer)

            sortAttributes && type == EventType.START_ELEMENT -> writeSortedStartTag(reader, writer)

            type == EventType.COMMENT && keepComments -> type.createEvent(reader).writeTo(writer)

            type.isIgnorable -> Unit
            type == EventType.TEXT && reader.isWhitespace() -> Unit
            else -> type.createEvent(reader).writeTo(writer)
        }
    }
}

/**
 * 写一个起始标签，属性按名字排序——这就是 XML 世界的「键排序」。
 *
 * 顺序照搬 `StartElementEvent.writeTo`（先属性、后命名空间声明），只把属性那一列换成本地名的升序；
 * 名字相同的属性（不同命名空间）靠稳定排序保持原有先后。
 *
 * 直接调写入器而不是重建事件：`StartElementEvent` 的构造函数要一个父命名空间上下文，而它不在
 * 公开接口上；这里逐个 `attribute` / `namespaceAttr` 写出去，输出与整条搬运逐字节一致。
 */
private fun writeSortedStartTag(reader: XmlReader, writer: XmlWriter) {
    writer.startTag(reader.namespaceURI, reader.localName, reader.prefix)
    val order = (0 until reader.attributeCount).sortedBy { reader.getAttributeLocalName(it) }
    for (index in order) {
        writer.attribute(
            reader.getAttributeNamespace(index),
            reader.getAttributeLocalName(index),
            reader.getAttributePrefix(index),
            reader.getAttributeValue(index),
        )
    }
    for (namespace in reader.namespaceDecls) {
        writer.namespaceAttr(namespace.prefix, namespace.namespaceURI)
    }
}
