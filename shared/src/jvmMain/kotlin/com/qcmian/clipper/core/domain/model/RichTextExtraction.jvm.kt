package com.qcmian.clipper.core.domain.model

import com.fleeksoft.ksoup.Ksoup
import java.io.ByteArrayInputStream
import javax.swing.text.DefaultStyledDocument
import javax.swing.text.rtf.RTFEditorKit

/** 能从中提取文字的附加表示类型；两种拼写都收，不同应用写的 UTI 不完全一致。 */
private val HTML_TYPES = setOf("public.html", "text/html")
private val RTF_TYPES = setOf("public.rtf", "text/rtf")

actual fun extractReadableText(contents: List<ClipboardContent>): String =
    // 固定优先级，而不是跟着 `contents` 的顺序走：那个顺序来自粘贴板声明类型的顺序，
    // 并不稳定。HTML 优先——它更常见，解析规则也更贴近浏览器。
    contents.firstParsed(HTML_TYPES, ::parseHtml)
        .ifBlank { contents.firstParsed(RTF_TYPES, ::parseRtf) }

/** 按给定类型顺序取第一个能解析出非空文本的表示。 */
private fun List<ClipboardContent>.firstParsed(
    types: Set<String>,
    parse: (ByteArray) -> String,
): String {
    for (content in this) {
        if (content.type !in types) continue
        val raw = content.value ?: continue
        val text = parse(raw)
        if (text.isNotBlank()) return text
    }
    return ""
}

/**
 * 畸形 HTML 不该让整条记录报废：解析失败就当作「没有这段富文本」，继续试下一种表示。
 */
private fun parseHtml(bytes: ByteArray): String =
    runCatching { Ksoup.parse(bytes.decodeToString()).text() }
        .getOrDefault("")
        .foldWhitespace()

/**
 * `RTFEditorKit` 把 RTF 读进一个 `StyledDocument`，再取纯文本。
 *
 * 用 ISO-8859-1 做 char↔byte 的一一对应：RTF 是 ASCII 字节流（中文靠 `\uN` / `\'xx`
 * 转义），用 UTF-8 反而会把非 ASCII 字符编成多字节、破坏样本。
 */
private fun parseRtf(bytes: ByteArray): String = runCatching {
    val document = DefaultStyledDocument()
    RTFEditorKit().read(ByteArrayInputStream(bytes), document, 0)
    document.getText(0, document.length).foldWhitespace()
}.getOrDefault("")
