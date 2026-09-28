package com.qcmian.clipper.devtools.tools.xml

import org.w3c.dom.Document
import org.w3c.dom.Node
import org.xml.sax.ErrorHandler
import org.xml.sax.SAXParseException
import java.io.ByteArrayInputStream
import java.io.StringWriter
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilder
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

/**
 * JVM 实现：DOM 解析 + 恒等变换重排。
 *
 * 之所以用 `javax.xml` 而不是手写扫描：良构性检查（标签配对、实体、命名空间）的边界非常多，
 * 手写一份必然在边界上出错；而格式化本身就是「解析一次、再序列化一次」这个标准动作。
 */
internal actual fun isWellFormedXml(text: String): Boolean =
    runCatching { parse(text) }.isSuccess

internal actual fun formatXml(text: String, indentWidth: Int): Result<String> = runCatching {
    val document = parse(text)
    stripWhitespace(document)
    transform(document, indent = indentWidth)
}

internal actual fun minifyXml(text: String): Result<String> = runCatching {
    val document = parse(text)
    stripWhitespace(document)
    transform(document, indent = 0)
}

/**
 * 解析成 DOM。
 *
 * 关掉外部实体 / DTD 的远程加载（XXE）：剪贴板里的内容不可信，一旦允许外部实体，解析一份
 * 恶意 XML 就可能读到本机文件。`setFeature` / `setAttribute` 在不同实现上未必都支持，因此逐个
 * `runCatching`：不支持就跳过，不让「加一道保险」本身变成失败原因。
 */
private fun parse(text: String): Document {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "") }
        runCatching { setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "") }
    }
    val builder: DocumentBuilder = factory.newDocumentBuilder()
    // 默认错误处理器会把警告打到 stderr，而这里「解析失败」是预期内的一种结果（探测路径）。
    builder.setErrorHandler(object : ErrorHandler {
        override fun warning(exception: SAXParseException) = Unit
        override fun error(exception: SAXParseException): Unit = throw exception
        override fun fatalError(exception: SAXParseException): Unit = throw exception
    })
    return builder.parse(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))
}

/** 自底向上丢掉纯空白的文本节点与注释。 */
private fun stripWhitespace(node: Node) {
    val children = node.childNodes
    for (index in children.length - 1 downTo 0) {
        val child = children.item(index) ?: continue
        when {
            child.nodeType == Node.TEXT_NODE && child.nodeValue.isNullOrBlank() -> node.removeChild(child)
            child.nodeType == Node.COMMENT_NODE -> node.removeChild(child)
            else -> stripWhitespace(child)
        }
    }
}

/**
 * 序列化。[indent] 为 0 时不缩进（压缩）；否则用 JDK 的 `indent-amount` 扩展属性指定宽度。
 *
 * 不输出 XML 声明：DOM 解析已经丢掉了原始声明里的编码信息，再补一条 `encoding="UTF-8"` 只会是
 * 一句假话（结果在内存里是 `String`）。
 */
private fun transform(document: Document, indent: Int): String {
    val transformer = TransformerFactory.newInstance().newTransformer().apply {
        setOutputProperty(OutputKeys.INDENT, if (indent > 0) "yes" else "no")
        setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes")
        if (indent > 0) {
            setOutputProperty("{http://xml.apache.org/xslt}indent-amount", indent.toString())
        }
    }
    val writer = StringWriter()
    transformer.transform(DOMSource(document), StreamResult(writer))
    return writer.toString().trimEnd('\n')
}
