package com.qcmian.clipper.host.cli

import com.qcmian.clipper.core.domain.model.ClipItem
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * 把二进制内容落到临时目录，返回路径。
 *
 * **二进制从不走线上协议。** base64 会带来 33% 膨胀、不可读、还白烧 token；裸字节又没法
 * 塞进 JSON。落盘给路径是最省事的一种：agent 拿到路径后可以交给任何工具（图片查看、
 * OCR、上传），也可以让 CLI 自己 `--raw` 读出来送进管道。
 *
 * 文件以**条目 id** 命名，因此同一个条目反复导出会命中同一个文件——调用多少次都不会
 * 在 `/tmp` 里越堆越多。
 *
 * 目录选系统临时目录而不是 `~/.clipper/`：这些是**派生出来的**中间产物，不该和用户的
 * 数据放在一起，也不该让「清空历史」之后还留着它们的残骸。macOS 会自行回收临时目录。
 */
internal object ClipExporter {

    private val directory: Path by lazy {
        val base = System.getProperty("java.io.tmpdir") ?: "/tmp"
        Path.of(base, "clipper").also { Files.createDirectories(it) }
    }

    /** 图片条目的原始字节；没有图片时返回 `null`。 */
    fun exportImage(item: ClipItem): Path? {
        val bytes = item.image?.toByteArray() ?: return null
        return write("${safeId(item.id)}.${imageExtension(bytes)}", bytes)
    }

    /**
     * 按 [format] 抽一种附加表示（`html` / `rtf` / `pdf`，或完整 UTI）；没有匹配的表示时
     * 返回 `null`——调用方据此回 `NOT_FOUND` 并列出实际有哪些。
     */
    fun exportAttachment(item: ClipItem, format: String): Path? {
        val content = item.contents.firstOrNull { matches(it.type, format) } ?: return null
        val bytes = content.toByteArray() ?: return null
        return write("${safeId(item.id)}.${extensionFor(content.type, format)}", bytes)
    }

    /** 这条实际携带哪些附加表示；用于「你要的没有，但它有这些」的提示。 */
    fun availableFormats(item: ClipItem): List<String> = item.contents.map { it.type }

    /**
     * 写文件，已存在且大小一致就跳过。
     *
     * 大小一致就当作内容一致：同一个 id 的载荷**永不改变**（重复复制只更新统计列，
     * 见 `ClipStorageDataSource.updateStats`），所以这里省下的是一次无谓的全量重写。
     */
    private fun write(name: String, bytes: ByteArray): Path {
        val target = directory.resolve(name)
        if (Files.exists(target) && runCatching { Files.size(target) }.getOrNull() == bytes.size.toLong()) {
            return target
        }
        Files.write(target, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)
        return target
    }

    private fun matches(type: String, format: String): Boolean =
        type.equals(format, ignoreCase = true) ||
            type.substringAfterLast('.').equals(format, ignoreCase = true)

    private fun extensionFor(type: String, format: String): String {
        val fromType = type.substringAfterLast('.')
        val candidate = if (fromType.isNotBlank() && !fromType.contains('.')) fromType else format
        return candidate.lowercase().filter { it.isLetterOrDigit() }.ifBlank { "bin" }
    }

    /**
     * 按魔数判断图片格式。
     *
     * 值得多这几行：扩展名会被下游工具用来决定怎么解析，而 `ClipImage` 的注释明说
     * 字节可能是 PNG 也可能是 JPEG——一律写成 `.png` 会让看图工具解不出来。
     */
    private fun imageExtension(bytes: ByteArray): String = when {
        bytes.startsWith(0x89, 0x50, 0x4E, 0x47) -> "png"
        bytes.startsWith(0xFF, 0xD8, 0xFF) -> "jpg"
        bytes.startsWith(0x47, 0x49, 0x46, 0x38) -> "gif"
        bytes.startsWith(0x42, 0x4D) -> "bmp"
        bytes.startsWith(0x52, 0x49, 0x46, 0x46) -> "webp"
        else -> "bin"
    }

    private fun ByteArray.startsWith(vararg signature: Int): Boolean {
        if (size < signature.size) return false
        return signature.withIndex().all { (index, byte) -> this[index].toInt() and 0xFF == byte }
    }

    /** id 会被用作文件名，因此只放行明确安全的字符。 */
    private fun safeId(id: String): String =
        id.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }.joinToString("")
}
