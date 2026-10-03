package com.qcmian.clipper.devtools.tools.timestamp

import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant

/**
 * 互联网 / 邮件 / 日志里常见的几个**标准写法**的解析：
 *
 *  - **RFC 1123 / HTTP-date**：`Sat, 03 Oct 2026 06:30:00 GMT`（HTTP 头的 `Date`）；
 *  - **RFC 5322 / 2822**（邮件）：`Sat, 03 Oct 2026 14:30:00 +0800`；
 *  - **asctime / ctime**（C 的 `asctime()`，不少日志）：`Sat Oct  3 06:30:00 2026`；
 *  - **ISO 8601 基本格式**（无分隔符）：`20261003T063000Z`。
 *
 * 与 [TimestampConvert] 的「常见本地写法」分开：这里的共同点是**带英文星期 / 月份名、或没有分隔符**，
 * 需要单独一份正则与月份表；堆进主解析里只会让那条主路径更难读。识别不出的一律返回 `null`，由调用方
 * 继续尝试别的写法，最终交给自定义格式。
 */
internal object TimestampStandards {

    /** 依次尝试，命中即返回；都不是返回 `null`。 */
    fun parse(text: String, zone: TimeZone): Instant? {
        val trimmed = text.trim()
        parseRfc1123(trimmed)?.let { return it }
        parseAsctime(trimmed, zone)?.let { return it }
        parseBasicIso(trimmed, zone)?.let { return it }
        return null
    }

    /** `Sat, 03 Oct 2026 06:30:00 GMT` / `... +0800`，星期先忽略、只按后面的日期时间与时区算。 */
    private val rfc1123 = Regex(
        """^[A-Za-z]{3},\s+(\d{1,2})\s+([A-Za-z]{3})\s+(\d{4})\s+(\d{1,2}):(\d{2}):(\d{2})\s+([+-]\d{4}|[A-Za-z]{2,3})$"""
    )

    private fun parseRfc1123(text: String): Instant? {
        val match = rfc1123.matchEntire(text) ?: return null
        val (day, monthName, year, hour, minute, second, zoneToken) = match.destructured
        val month = monthNumber(monthName) ?: return null
        val offset = utcOffset(zoneToken) ?: return null
        return runCatching {
            LocalDateTime(year.toInt(), month, day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
                .toInstant(offset)
        }.getOrNull()
    }

    /** `Sat Oct  3 06:30:00 2026`（月份与日期之间可能有两个空格）。没有时区，按 [zone] 解释。 */
    private val asctime = Regex(
        """^[A-Za-z]{3}\s+([A-Za-z]{3})\s+(\d{1,2})\s+(\d{2}):(\d{2}):(\d{2})\s+(\d{4})$"""
    )

    private fun parseAsctime(text: String, zone: TimeZone): Instant? {
        val match = asctime.matchEntire(text) ?: return null
        val (monthName, day, hour, minute, second, year) = match.destructured
        val month = monthNumber(monthName) ?: return null
        return runCatching {
            LocalDateTime(year.toInt(), month, day.toInt(), hour.toInt(), minute.toInt(), second.toInt())
                .toInstant(zone)
        }.getOrNull()
    }

    /** `20261003T063000Z` / `20261003T063000`（无时区按 [zone]）。 */
    private val basicIso = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})(Z|[+-]\d{4})?$""")

    private fun parseBasicIso(text: String, zone: TimeZone): Instant? {
        val match = basicIso.matchEntire(text) ?: return null
        val (year, month, day, hour, minute, second, zoneToken) = match.destructured
        val offset = if (zoneToken.isEmpty()) null else utcOffset(zoneToken) ?: return null
        return runCatching {
            val local = LocalDateTime(
                year.toInt(), month.toInt(), day.toInt(), hour.toInt(), minute.toInt(), second.toInt(),
            )
            if (offset == null) local.toInstant(zone) else local.toInstant(offset)
        }.getOrNull()
    }

    /** 英文月份（前三字母，`sept` 也收）→ 月号；不认识返回 `null`。 */
    private val monthNumbers = mapOf(
        "jan" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "may" to 5, "jun" to 6,
        "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dec" to 12,
    )

    private fun monthNumber(name: String): Int? = monthNumbers[name.lowercase()]

    /**
     * 时区记号 → [UtcOffset]：`Z` / `GMT` / `UTC` / `UT` → 零偏移；`+0800` / `-0500` → 对应偏移；
     * 其它返回 `null`。
     *
     * 交回 `UtcOffset` 而不是自己算秒数：后者的构造（按小时 / 分）很容易在「毫秒 / 秒」这种单位上
     * 踩坑，直接 `parse` 一个标准偏移串最稳。
     */
    private fun utcOffset(token: String): UtcOffset? {
        if (token.equals("Z", true) || token.equals("GMT", true) ||
            token.equals("UTC", true) || token.equals("UT", true)
        ) {
            return UtcOffset.ZERO
        }
        if (token.length != 5 || (token[0] != '+' && token[0] != '-')) return null
        return runCatching { UtcOffset.parse("${token[0]}${token.substring(1, 3)}:${token.substring(3, 5)}") }
            .getOrNull()
    }
}
