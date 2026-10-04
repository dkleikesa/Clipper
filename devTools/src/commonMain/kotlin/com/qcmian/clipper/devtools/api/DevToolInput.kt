package com.qcmian.clipper.devtools.api

import com.qcmian.clipper.core.domain.model.ClipItem
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.io.write
import kotlinx.io.writeString

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
    return files.joinToString("\n") { readTextFileOrNull(it) ?: it }
}

/** 单个文件读入内存的上限：再大就不是「粘贴一段内容」的用法，读进来只会卡住界面。 */
private const val MAX_TEXT_FILE_BYTES = 4L * 1024 * 1024

/** 判二进制时只看开头这一小段：文本文件几乎不会在开头出现 NUL 字节。 */
private const val BINARY_SNIFF_BYTES = 8_000

/**
 * 读一个文本文件；不是可读文本时返回 `null`（不存在、是目录、是二进制、超过大小上限）。
 *
 * 走 kotlinx-io 的 [SystemFileSystem] 而不是 `java.io.File`：读文件因此留在 commonMain，
 * 不必为文件系统开一对 `expect`/`actual`。工具界面的「打开文件」与「拖文件进来」也走它，
 * 于是「剪贴板里的文件条目」与「用户自己挑的文件」在工具看来完全一样。
 */
internal fun readTextFileOrNull(path: String): String? = runCatching {
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

/**
 * 把 [text] 写到 [path]；写成返回 `true`。
 *
 * 与读一样走 kotlinx-io，留在 commonMain。写不进去（没权限、目录不存在、磁盘满、路径其实是个
 * 目录）时返回 `false`，由调用方给一句提示——静默失败比报错更难查。
 *
 * 不设大小上限：这是用户自己指定要保存的文件，拦下来反而莫名其妙。
 */
internal fun writeTextFile(path: String, text: String): Boolean = runCatching {
    SystemFileSystem.sink(Path(path)).buffered().use { it.writeString(text) }
    true
}.getOrDefault(false)

/**
 * 把一个文件的**原始字节**读进内存；读不了时返回 `null`。
 *
 * 与 [readTextFileOrNull] 的分工：那个给「要当文本用」的工具（JSON / XML / 数学），会拒掉二进制；
 * 这个给「字节本身就是内容」的工具（Base64 编码图片 / 任意文件），二进制正是它要的。
 *
 * [maxBytes] 是硬上限：整块读进内存再编码，没有上限就等于让用户用一个超大文件把窗口拖死。
 * 超限与读不到都返回 `null`，由调用方给一句提示——两者对用户是同一件事（这个文件现在用不了）。
 */
internal fun readBytesOrNull(path: String, maxBytes: Long = MAX_BINARY_FILE_BYTES): ByteArray? =
    runCatching {
        val file = Path(path)
        val metadata = SystemFileSystem.metadataOrNull(file) ?: return@runCatching null
        if (!metadata.isRegularFile || metadata.size > maxBytes) return@runCatching null
        val source = SystemFileSystem.source(file).buffered()
        try {
            source.readByteArray()
        } finally {
            source.close()
        }
    }.getOrNull()

/** 把 [bytes] 原样写到 [path]；写成返回 `true`。与 [writeTextFile] 同一套错误约定。 */
internal fun writeBytesFile(path: String, bytes: ByteArray): Boolean = runCatching {
    SystemFileSystem.sink(Path(path)).buffered().use { it.write(bytes) }
    true
}.getOrDefault(false)

/** 二进制文件读入内存的默认上限（见 [readBytesOrNull]）。 */
private const val MAX_BINARY_FILE_BYTES = 16L * 1024 * 1024

private fun looksBinary(bytes: ByteArray): Boolean {
    val limit = minOf(bytes.size, BINARY_SNIFF_BYTES)
    for (index in 0 until limit) {
        if (bytes[index] == 0.toByte()) return true
    }
    return false
}
