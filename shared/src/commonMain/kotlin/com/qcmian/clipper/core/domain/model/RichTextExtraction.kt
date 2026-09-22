package com.qcmian.clipper.core.domain.model

/**
 * 从附加表示（HTML / RTF）里提取可读文字；没有可提取的表示时返回空串。
 *
 * 这是 `expect`/`actual` 而不是就地实现，因为解析器都带平台属性：
 *
 * - **HTML** 用 Ksoup（jsoup 的多平台移植）。`Element.text()` 已按浏览器的空白规则实现——
 *   块级元素换行、行内元素不换行、连续空白折叠、`&nbsp;` 不折叠，并自动跳过
 *   `<script>` / `<style>` 与注释。手写剥离器在这些边界上一定会出偏差。
 * - **RTF** 用 JDK 自带的 `javax.swing.text.rtf.RTFEditorKit`。它连中文的 `\'xx` 双字节
 *   转义都能按 `\ansicpg` 正确还原——这正是它的价值所在，手写剥离控制字才会得到乱码。
 *   但它在 `javax.swing` 里，属于 JVM 专属 API，只能放在 `jvmMain`。
 *
 * 返回的文本一律把连续空白折叠成一个空格（见 [foldWhitespace]）：它要同时充当列表标题与
 * 搜索源，而标题是**单行**的——留着换行只会让渲染时多出一堆 `⏎` 符号。
 */
expect fun extractReadableText(contents: List<ClipboardContent>): String

/**
 * 把连续空白折叠成一个空格并去掉首尾空白。
 *
 * 两种格式的段落分隔方式不同（HTML 解析结果用空格，RTF 的 `\par` 是换行），统一在这里
 * 收口，免得同一份内容因为来源格式不同而显示成两个样子。
 */
internal fun String.foldWhitespace(): String {
    if (none { it.isWhitespace() }) return this

    val out = StringBuilder(length)
    var pending = false
    for (character in this) {
        if (character.isWhitespace()) {
            pending = out.isNotEmpty()
            continue
        }
        if (pending) {
            out.append(' ')
            pending = false
        }
        out.append(character)
    }
    return out.toString()
}
