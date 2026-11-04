package com.qcmian.logviewer.core.export

import com.qcmian.logviewer.core.doc.DocumentSource
import com.qcmian.logviewer.core.model.DisplayLine

enum class ExportFormat(val zh: String, val ext: String) {
    TXT("文本 (TXT)", "txt"),
    HTML("网页 (HTML，带颜色)", "html"),
}

data class ExportSpec(
    val includeLineNumbers: Boolean = true,
)

/**
 * 导出（F8-3）：基于完整行文本；导出前由 UI 展示行数、范围。
 */
object Exporter {

    /** 导出指定行集合为字符串（TXT / HTML） */
    suspend fun export(
        doc: DocumentSource,
        lines: List<DisplayLine>,
        format: ExportFormat,
        spec: ExportSpec,
        rowColors: ((Int) -> Pair<Long?, Long?>)? = null, // 行 -> (fg, bg) 用于 HTML
    ): String = when (format) {
        ExportFormat.TXT -> buildString {
            for (dl in lines) {
                val text = doc.line(dl.index)
                if (spec.includeLineNumbers) {
                    append((dl.index + 1).toString()).append('\t')
                }
                append(text).append('\n')
            }
        }
        ExportFormat.HTML -> buildHtml(doc, lines, spec, rowColors)
    }

    /** 复制原始文本（F8-2 保真：保持换行、Tab，基于完整文本） */
    suspend fun copyText(
        doc: DocumentSource,
        lineIndexes: List<Int>,
        spec: ExportSpec,
    ): String = buildString {
        for (idx in lineIndexes) {
            append(doc.line(idx)).append('\n')
        }
    }

    /** 带 HTML 格式复制（P2） */
    suspend fun copyHtml(
        doc: DocumentSource,
        lineIndexes: List<Int>,
        rowColors: ((Int) -> Pair<Long?, Long?>)? = null,
    ): String = buildHtml(
        doc,
        lineIndexes.map { DisplayLine(it, true) },
        ExportSpec(includeLineNumbers = true), rowColors,
    )

    private suspend fun buildHtml(
        doc: DocumentSource,
        lines: List<DisplayLine>,
        spec: ExportSpec,
        rowColors: ((Int) -> Pair<Long?, Long?>)? ,
    ): String = buildString {
        append("<html><head><meta charset=\"utf-8\"><style>")
        append("body{font-family:Menlo,Consolas,monospace;font-size:13px;background:#10141b;color:#e6ecf5;}")
        append("table{border-collapse:collapse;}td{padding:2px 8px;white-space:pre;}")
        append(".ln{color:#5a6478;text-align:right;user-select:none;}")
        append("</style></head><body><table>")
        for (dl in lines) {
            val text = doc.line(dl.index)
            val colors = rowColors?.invoke(dl.index)
            val style = buildString {
                val bg = colors?.second
                val fg = colors?.first
                if (bg != null) append("background:").append(cssColor(bg)).append(';')
                if (fg != null) append("color:").append(cssColor(fg)).append(';')
            }
            append("<tr>")
            if (spec.includeLineNumbers) append("<td class=\"ln\">").append(dl.index + 1).append("</td>")
            if (style.isEmpty()) append("<td>").append(htmlEscape(text)).append("</td>")
            else append("<td style=\"").append(style).append("\">").append(htmlEscape(text)).append("</td>")
            append("</tr>")
        }
        append("</table></body></html>")
    }

    /** 定位证据复制（F10-1）：文件名、原始行号、启用规则、命中片段与上下文 */
    suspend fun copyEvidence(
        doc: DocumentSource,
        lineIndex: Int,
        contextBefore: Int,
        contextAfter: Int,
        activeRuleNames: List<String>,
    ): String = buildString {
        appendLine("【LogViewer 定位证据】")
        appendLine("文件：${doc.name}（${doc.fileUri}）")
        appendLine("原始行号：${lineIndex + 1}")
        appendLine("编码：${doc.encodingName}")
        val cur = doc.line(lineIndex)
        if (activeRuleNames.isNotEmpty()) {
            appendLine("启用规则：${activeRuleNames.joinToString("、")}")
        }
        appendLine("---- 命中行 ----")
        appendLine(cur)
        if (contextBefore > 0 || contextAfter > 0) {
            appendLine("---- 上下文 ----")
            val from = (lineIndex - contextBefore).coerceAtLeast(0)
            val to = (lineIndex + contextAfter).coerceAtMost(doc.lineCount - 1)
            for (i in from..to) {
                if (i == lineIndex) continue
                append(i + 1).append('\t').appendLine(doc.line(i))
            }
        }
    }

    /* ---------- 工具 ---------- */

    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> fmt1(bytes / 1024.0) + "KB"
        bytes < 1024L * 1024 * 1024 -> fmt1(bytes / 1048576.0) + "MB"
        else -> fmt2(bytes / 1073741824.0) + "GB"
    }

    fun formatCount(n: Int): String = when {
        n < 10_000 -> n.toString()
        n < 100_000_000 -> {
            val w = n / 10000
            val rem = (n % 10000) / 1000
            if (rem == 0) "${w} 万" else "${w}.${rem} 万"
        }
        else -> fmt2(n / 100000000.0) + " 亿"
    }

    private fun fmt1(v: Double): String {
        val r = (v * 10 + 0.5).toLong()
        return "${r / 10}.${r % 10}"
    }

    private fun fmt2(v: Double): String {
        val r = (v * 100 + 0.5).toLong()
        return "${r / 100}.${(r % 100).toString().padStart(2, '0')}"
    }

    private fun htmlEscape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                else -> append(c)
            }
        }
    }

    private fun cssColor(argb: Long): String =
        "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0').uppercase()
}
