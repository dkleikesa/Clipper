package com.qcmian.clipper.core.util

import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * 按预览面板的显示方式格式化时间戳，例如 `2026年9月11日 14:50`。
 * 渲染风格与系统「日期 + 时间」组件一致，采用 zh-Hans 取值。
 */
fun formatDateTime(epochMillis: Long): String {
    val local = Instant.fromEpochMilliseconds(epochMillis)
        .toLocalDateTime(TimeZone.currentSystemDefault())
    val month = local.month.ordinal + 1
    val hour = local.hour.toString().padStart(2, '0')
    val minute = local.minute.toString().padStart(2, '0')
    return "${local.year}年${month}月${local.day}日 $hour:$minute"
}
