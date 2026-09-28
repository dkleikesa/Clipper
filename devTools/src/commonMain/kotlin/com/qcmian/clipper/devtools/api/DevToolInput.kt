package com.qcmian.clipper.devtools.api

import com.qcmian.clipper.core.domain.model.ClipItem
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/**
 * 把一条剪贴板记录解析成工具要吃的**文本**。
 *
 * 非文件条目沿用 [ClipItem.previewText]；**文件条目改为读文件内容**——[ClipItem.previewText]
 * 对文件只给出路径（见 `ClipItem.previewableText`），格式化工具拿一段路径去解析只会报错。
 *
 * 读不出来的文件（二进制、过大、无权限）退回路径：与其让输入区一片空白，不如让用户看见自己
 * 复制的是哪个文件。多个文件按行拼接。
 *
 * 探测（`DevToolsRegistry.detectTypes`）与工具界面都必须走它，否则会出现「按文件内容匹配了工具、
 * 工具却只拿到路径」的错位。
 */
internal fun ClipItem.devToolText(): String {
    if (files.isEmpty()) return previewText
    return files.joinToString("\n") { readTextFile(it) ?: it }
}

/** 单个文件读入内存的上限：再大就不是「粘贴一段内容」的用法，读进来只会卡住界面。 */
private const val MAX_TEXT_FILE_BYTES = 4L * 1024 * 1024

/** 判二进制时只看开头这一小段：文本文件几乎不会在开头出现 NUL 字节。 */
private const val BINARY_SNIFF_BYTES = 8_000

/**
 * 读一个文本文件；不是可读文本时返回 `null`（不存在、是目录、是二进制、超过大小上限）。
 *
 * 走 kotlinx-io 的 [SystemFileSystem] 而不是 `java.io.File`：读文件因此留在 commonMain，
 * 不必为文件系统开一对 `expect`/`actual`。
 */
private fun readTextFile(path: String): String? = runCatching {
    val file = Path(path)
    val metadata = SystemFileSystem.metadataOrNull(file) ?: return@runCatching null
    if (!metadata.isRegularFile || metadata.size > MAX_TEXT_FILE_BYTES) return@runCatching null
    val source = SystemFileSystem.source(file).buffered()
    val bytes = try {
        source.readByteArray()
    } finally {
        source.close()
    }
    if (looksBinary(bytes)) return@runCatching null
    bytes.decodeToString()
}.getOrNull()

private fun looksBinary(bytes: ByteArray): Boolean {
    val limit = minOf(bytes.size, BINARY_SNIFF_BYTES)
    for (index in 0 until limit) {
        if (bytes[index] == 0.toByte()) return true
    }
    return false
}
