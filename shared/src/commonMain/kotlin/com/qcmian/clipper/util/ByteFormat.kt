package com.qcmian.clipper.util

/** Compact byte count, the equivalent of Maccy's `ByteCountFormatter` usage in `Storage`. */
fun formatBytes(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_024L * 1_024 -> "${bytes / 1_024} KB"
    bytes < 1_024L * 1_024 * 1_024 -> "${bytes / (1_024L * 1_024)} MB"
    else -> "${bytes / (1_024L * 1_024 * 1_024)} GB"
}
