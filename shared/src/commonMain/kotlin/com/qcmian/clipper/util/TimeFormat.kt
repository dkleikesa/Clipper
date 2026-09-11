package com.qcmian.clipper.util

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Formats a timestamp the way the preview pane shows it, e.g. `2026年9月11日 14:50`.
 * Maccy renders `Text(date, style: .date)` + `Text(date, style: .time)`; this is the
 * zh-Hans rendering of the same value.
 */
fun formatDateTime(epochMillis: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    val month = local.month.ordinal + 1
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "${local.year}年${month}月${local.day}日 $hour:$minute"
}
