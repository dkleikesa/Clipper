package com.qcmian.clipper.util

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 按预览面板的显示方式格式化时间戳，例如 `2026年9月11日 14:50`。
 * Maccy 用 `Text(date, style: .date)` + `Text(date, style: .time)` 渲染；这里是同一取值的
 * zh-Hans 渲染结果。
 */
fun formatDateTime(epochMillis: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    val month = local.month.ordinal + 1
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "${local.year}年${month}月${local.day}日 $hour:$minute"
}
