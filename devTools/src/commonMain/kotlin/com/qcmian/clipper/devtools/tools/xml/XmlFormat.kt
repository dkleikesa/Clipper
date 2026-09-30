package com.qcmian.clipper.devtools.tools.xml

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlWriter
import nl.adaptivity.xmlutil.newWriter
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.xmlStreaming

/**
 * XML 的校验与排版，基于 kotlinx.serialization 的 XML 格式实现 xmlutil。
 *
 * 取代原先把 `javax.xml`（DOM + 恒等变换）兜在 `expect`/`actual` 后面的写法：这里全是多平台 API，
 * 因此三个函数都留在 commonMain，模块不再需要 JVM 专属实现。
 *
 * 为什么走 xmlutil 的**流式层**而不是它的 `XML` 格式：格式的编解码要求一个已知的 Kotlin 类型
 * （`encodeToString(serializer, value)`），而这里要处理的是「任意 XML」——没有对应的公开序列化器。
 * 缩进因此直接取自 `XML` 格式自身的配置（[Pretty] / [Compact]），保持与它默认输出一致。
 *
 * 三个函数都是**纯函数**：给定同样的输入返回同样的结果，不抛异常（失败通过 `false` 或
 * [Result] 表达），因此探测与工具两条路径可以放心共用一个实现。
 */

/** 「推荐」格式：4 空格缩进。`XML.v1()` 是 `XML.v1.recommended()` 的非废弃写法，两者等价。 */
private val Pretty: XML = XML.v1()

private val Compact: XML = XML.v1.compact()

/** 文档开头的 XML 声明，捕获 `<?xml … ?>` 本体（前导 BOM / 空白归到捕获组之外）。 */
private val DECLARATION = Regex("""^[\uFEFF\s]*(<\?xml\s[^?]*\?>)""")

/** 写入器自己生成的那条声明；它一定在输出开头。 */
private val WRITTEN_DECLARATION = Regex("""^[\uFEFF\s]*<\?xml\s[^?]*\?>""")

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

internal fun formatXml(text: String): Result<String> = transform(text, Pretty.config.indentString)

internal fun minifyXml(text: String): Result<String> = transform(text, Compact.config.indentString)

/**
 * 读一遍 [text] 再写出来，写入时按 [indent] 缩进；[indent] 为空即不换行。
 *
 * 声明**有就留、没有就不补**：输入带 `<?xml … ?>` 才放行 `START_DOCUMENT`，否则写入器会凭空
 * 造一条 `<?xml version='1.0' ?>`（实测）。
 */
private fun transform(text: String, indent: String): Result<String> = runCatching {
    val declaration = DECLARATION.find(text)?.groupValues?.get(1)
    val out = StringBuilder()
    val writer = xmlStreaming.newWriter(out, xmlDeclMode = XmlDeclMode.None)
    try {
        writer.indentString = indent
        val reader = xmlStreaming.newReader(text)
        try {
            copyEvents(reader, writer, keepDeclaration = declaration != null)
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
 * 逐个事件搬运，丢掉不承载内容的那些——与旧实现一致：
 * 注释、文档声明、处理指令、文档起止（[EventType.isIgnorable] 已覆盖），
 * 以及元素之间的纯空白文本（不丢的话会和写入器自己的缩进叠在一起）。
 *
 * [EventType.START_DOCUMENT] 是唯一的例外：它同样算「可忽略」，但它是声明的载体，只有
 * [keepDeclaration] 为真时才写出去。
 */
private fun copyEvents(reader: XmlReader, writer: XmlWriter, keepDeclaration: Boolean) {
    while (reader.hasNext()) {
        val type = reader.next()
        when {
            type == EventType.START_DOCUMENT ->
                if (keepDeclaration) type.createEvent(reader).writeTo(writer)
            type.isIgnorable -> Unit
            type == EventType.TEXT && reader.isWhitespace() -> Unit
            else -> type.createEvent(reader).writeTo(writer)
        }
    }
}
