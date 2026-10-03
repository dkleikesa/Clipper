package com.qcmian.clipper.devtools.tools.timestamp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * 时间戳解析与多格式输出。
 *
 * 全部用 [TimeZone.UTC] 固定时区、用固定的 `now`：这一层没有平台时钟的概念，把时区与参照点都
 * 作为入参传进去，测试才能断言精确到秒的结果。
 */
class TimestampConvertTest {

    private val utc = TimeZone.UTC

    @Test
    fun `seconds and milliseconds are told apart by magnitude`() {
        val seconds = TimestampConvert.parse("1759468800", utc) as TimestampParse.Success
        assertEquals(1759468800L, seconds.instant.epochSeconds)

        val millis = TimestampConvert.parse("1759468800000", utc) as TimestampParse.Success
        assertEquals(1759468800L, millis.instant.epochSeconds)
    }

    @Test
    fun `a millisecond timestamp keeps its sub-second part`() {
        // 13 位、数值超过 1e11 → 按毫秒解释；末三位 123 要一路保留到可读时间，而不是只留在数值列里。
        val result = TimestampConvert.parse("1759468800123", utc) as TimestampParse.Success
        val fields = TimestampConvert.fields(result.instant, utc, result.instant)
        val byLabel = fields.associate { it.label to it.value }

        assertEquals("1759468800", byLabel["秒级时间戳"])
        assertEquals("1759468800123", byLabel["毫秒级时间戳"])
        assertTrue(byLabel.getValue("ISO 8601").endsWith(".123Z"))
        assertTrue(byLabel.getValue("UTC 时间").endsWith(".123"))
    }

    @Test
    fun `iso strings with an offset parse to the same instant regardless of zone`() {
        val zulu = TimestampConvert.parse("2024-01-01T00:00:00Z", utc) as TimestampParse.Success
        assertEquals(1704067200L, zulu.instant.epochSeconds)

        // +08:00 的同一时刻：本地写着 08:00，换算成 UTC 是 2024-01-01 00:00。
        val offset = TimestampConvert.parse("2024-01-01T08:00:00+08:00", utc) as TimestampParse.Success
        assertEquals(1704067200L, offset.instant.epochSeconds)
    }

    @Test
    fun `zone-less date times are read in the given zone`() {
        val result = TimestampConvert.parse("2024-01-01 12:00:00", utc) as TimestampParse.Success
        assertEquals(1704110400L, result.instant.epochSeconds)
    }

    @Test
    fun `a time section written with hyphens is recognized`() {
        // 空格分隔的 `10-12-34` 与标准写法应落到同一时刻。
        val hyphen = TimestampConvert.parse("2028-11-22 10-12-34", utc) as TimestampParse.Success
        val colon = TimestampConvert.parse("2028-11-22 10:12:34", utc) as TimestampParse.Success
        assertEquals(colon.instant, hyphen.instant)

        // 用 T 分隔、时间仍是短横线的变体也认。
        val tSeparated = TimestampConvert.parse("2028-11-22T10-12-34", utc) as TimestampParse.Success
        assertEquals(colon.instant, tSeparated.instant)
    }

    @Test
    fun `anything else is a failure`() {
        assertTrue(TimestampConvert.parse("hello", utc) is TimestampParse.Failure)
        assertTrue(TimestampConvert.parse("   ", utc) is TimestampParse.Failure)
    }

    @Test
    fun `epoch detection only accepts plausible lengths`() {
        assertTrue(TimestampConvert.isEpochNumber("1759468800"))
        assertTrue(TimestampConvert.isEpochNumber("1759468800000"))
        assertFalse(TimestampConvert.isEpochNumber("123"))
        assertFalse(TimestampConvert.isEpochNumber("2024-01-01"))
        assertFalse(TimestampConvert.isEpochNumber("17040672001234567"))
    }

    @Test
    fun `fields describe the same instant in several ways`() {
        val fields = TimestampConvert.fields(
            instant = Instant.fromEpochSeconds(0),
            zone = utc,
            now = Instant.fromEpochSeconds(0),
        )
        val byLabel = fields.associate { it.label to it.value }

        assertEquals("0", byLabel["秒级时间戳"])
        assertEquals("0", byLabel["毫秒级时间戳"])
        assertEquals("1970-01-01 00:00:00", byLabel["UTC 时间"])
        assertEquals("1970-01-01T00:00:00Z", byLabel["ISO 8601"])
        assertEquals("星期四", byLabel["星期"])
        assertEquals("刚刚", byLabel["相对现在"])
    }

    @Test
    fun `relative time is phrased from now`() {
        val fields = TimestampConvert.fields(
            instant = Instant.fromEpochSeconds(0),
            zone = utc,
            now = Instant.fromEpochSeconds(3600),
        )
        assertEquals("1 小时前", fields.first { it.label == "相对现在" }.value)

        val future = TimestampConvert.fields(
            instant = Instant.fromEpochSeconds(3600),
            zone = utc,
            now = Instant.fromEpochSeconds(0),
        )
        assertEquals("1 小时后", future.first { it.label == "相对现在" }.value)
    }

    @Test
    fun `a date without a time is midnight in the given zone`() {
        val parsed = TimestampConvert.parse("2026-10-03", utc) as TimestampParse.Success
        assertEquals(Instant.parse("2026-10-03T00:00:00Z"), parsed.instant)
    }

    @Test
    fun `slashes and colons can stand in for date separators`() {
        val slashed = TimestampConvert.parse("2026/10/03 14:30:00", utc) as TimestampParse.Success
        assertEquals(Instant.parse("2026-10-03T14:30:00Z"), slashed.instant)

        val exif = TimestampConvert.parse("2026:10:03 14:30:00", utc) as TimestampParse.Success
        assertEquals(Instant.parse("2026-10-03T14:30:00Z"), exif.instant)
    }

    @Test
    fun `rfc 1123 and 5322 style dates are recognized`() {
        val httpDate = TimestampConvert.parse("Sat, 03 Oct 2026 06:30:00 GMT", utc) as TimestampParse.Success
        assertEquals(Instant.parse("2026-10-03T06:30:00Z"), httpDate.instant)

        val mail = TimestampConvert.parse("Sat, 03 Oct 2026 14:30:00 +0800", utc) as TimestampParse.Success
        assertEquals(Instant.parse("2026-10-03T06:30:00Z"), mail.instant)
    }

    @Test
    fun `asctime and the basic ISO format are recognized`() {
        val asctime = TimestampConvert.parse("Sat Oct  3 06:30:00 2026", utc) as TimestampParse.Success
        assertEquals(Instant.parse("2026-10-03T06:30:00Z"), asctime.instant)

        val basic = TimestampConvert.parse("20261003T063000Z", utc) as TimestampParse.Success
        assertEquals(Instant.parse("2026-10-03T06:30:00Z"), basic.instant)
    }

    @Test
    fun `the zone list offers the full IANA set`() {
        // JVM 上 `TimeZone.availableZoneIds` 有几百项；列表若只剩常用项，说明全量没取到。
        val zones = TimestampZones.all()
        assertTrue(zones.size > 100)
        assertTrue("Asia/Shanghai" in zones)
        assertEquals(zones.size, zones.distinct().size)
    }

    @Test
    fun `weekdays advance one per day around the epoch`() {
        // 1970-01-01 是星期四，往前一天是星期三——负的 epoch 秒不能按朝向零的整除去算。
        val before = TimestampConvert.fields(
            instant = Instant.fromEpochSeconds(-86_400),
            zone = utc,
            now = Instant.fromEpochSeconds(0),
        )
        assertEquals("星期三", before.first { it.label == "星期" }.value)
    }
}
