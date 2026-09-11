package com.qcmian.clipper.util

/** 紧凑的字节数，相当于 Maccy 的 `Storage` 中使用 `ByteCountFormatter` 的效果。 */
fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024L * 1_024 -> "${bytes / 1_024} KB"
    bytes < 1_024L * 1_024 * 1_024 -> "${bytes / (1_024L * 1_024)} MB"
    else -> "${bytes / (1_024L * 1_024 * 1_024)} GB"
}
