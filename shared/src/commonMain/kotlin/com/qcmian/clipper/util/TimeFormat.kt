package com.qcmian.clipper.util

/**
 * Formats a timestamp the way Maccy shows it: a compact, relative label such as
 * `now`, `5m`, `2h`, `3d`.
 */
fun formatRelativeTime(timestamp: Long, now: Long): String {
    val diff = now - timestamp
    if (diff < 0) return "now"
    val seconds = diff / 1_000
    return when {
        seconds < 45 -> "now"
        seconds < 60 * 60 -> "${seconds / 60}m"
        seconds < 60 * 60 * 24 -> "${seconds / (60 * 60)}h"
        seconds < 60 * 60 * 24 * 7 -> "${seconds / (60 * 60 * 24)}d"
        else -> "${seconds / (60 * 60 * 24 * 7)}w"
    }
}
