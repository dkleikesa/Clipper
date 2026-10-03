package com.qcmian.clipper.devtools.tools.timestamp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * 自定义模板的输出与解析。
 *
 * 全部用 [TimeZone.UTC]：模板里 `Z` / `z` 的结果与时区有关，用 UTC 才能断言成固定字符串；
 * 解析出的时刻也才好与 `Instant.parse` 的直读结果对照。
 */
class TimestampPatternTest {

    private val utc = TimeZone.UTC

    @Test
    fun `formats the common fields`() {
        val epoch = Instant.fromEpochMilliseconds(0)
        assertEquals("1970-01-01 00:00:00", TimestampPattern.format(epoch, utc, "yyyy-MM-dd HH:mm:ss"))
        assertEquals("1970/01/01", TimestampPattern.format(epoch, utc, "yyyy/MM/dd"))
        // 中文标点是字面量，直接写在模板里。
        assertEquals("1970年01月01日", TimestampPattern.format(epoch, utc, "yyyy年MM月dd日"))
        assertEquals("星期四", TimestampPattern.format(epoch, utc, "EEE"))
        assertEquals("上午 12:00", TimestampPattern.format(epoch, utc, "a hh:mm"))
        assertEquals("+0000", TimestampPattern.format(epoch, utc, "Z"))
        assertEquals("+00:00", TimestampPattern.format(epoch, utc, "ZZ"))
        assertEquals("UTC", TimestampPattern.format(epoch, utc, "z"))
    }

    @Test
    fun `milliseconds are always three digits`() {
        assertEquals("00.123", TimestampPattern.format(Instant.fromEpochMilliseconds(123), utc, "ss.SSS"))
        assertEquals("00.000", TimestampPattern.format(Instant.fromEpochMilliseconds(0), utc, "ss.SSS"))
    }

    @Test
    fun `literal letters have to be quoted`() {
        val epoch = Instant.fromEpochMilliseconds(0)
        assertEquals("1970-01-01T00:00:00", TimestampPattern.format(epoch, utc, "yyyy-MM-dd'T'HH:mm:ss"))
    }

    @Test
    fun `parses a pattern back to the same instant`() {
        val instant = Instant.parse("2028-11-22T10:12:34Z")
        val text = TimestampPattern.format(instant, utc, "yyyy-MM-dd HH:mm:ss")
        assertEquals("2028-11-22 10:12:34", text)
        assertEquals(instant, TimestampPattern.parse(text, utc, "yyyy-MM-dd HH:mm:ss").getOrThrow())
    }

    @Test
    fun `parsing fills the fields the pattern omits with defaults`() {
        val parsed = TimestampPattern.parse("10:12:34", utc, "HH:mm:ss").getOrThrow()
        assertEquals("1970-01-01T10:12:34Z", parsed.toString())
    }

    @Test
    fun `twelve hour clocks combine with am pm`() {
        assertEquals(
            "1970-01-01T13:00:00Z",
            TimestampPattern.parse("下午 01:00", utc, "a hh:mm").getOrThrow().toString(),
        )
        assertEquals(
            "1970-01-01T00:00:00Z",
            TimestampPattern.parse("上午 12:00", utc, "a hh:mm").getOrThrow().toString(),
        )
    }

    @Test
    fun `milliseconds survive a round trip`() {
        val instant = Instant.parse("2028-11-22T10:12:34.123Z")
        val text = TimestampPattern.format(instant, utc, "yyyy-MM-dd HH:mm:ss.SSS")
        assertEquals("2028-11-22 10:12:34.123", text)
        assertEquals(instant, TimestampPattern.parse(text, utc, "yyyy-MM-dd HH:mm:ss.SSS").getOrThrow())
    }

    @Test
    fun `python style templates are accepted`() {
        val instant = Instant.parse("2028-11-22T10:12:34Z")
        val text = TimestampPattern.format(instant, utc, "%Y-%m-%d %H:%M:%S")
        assertEquals("2028-11-22 10:12:34", text)
        assertEquals(instant, TimestampPattern.parse(text, utc, "%Y-%m-%d %H:%M:%S").getOrThrow())

        val millis = Instant.parse("2028-11-22T10:12:34.123Z")
        assertEquals("2028-11-22 10:12:34.123", TimestampPattern.format(millis, utc, "%Y-%m-%d %H:%M:%S.%f"))
    }

    @Test
    fun `go style templates are accepted`() {
        val instant = Instant.parse("2028-11-22T10:12:34Z")
        val text = TimestampPattern.format(instant, utc, "2006-01-02 15:04:05")
        assertEquals("2028-11-22 10:12:34", text)
        assertEquals(instant, TimestampPattern.parse(text, utc, "2006-01-02 15:04:05").getOrThrow())

        val millis = Instant.parse("2028-11-22T10:12:34.123Z")
        assertEquals("2028-11-22 10:12:34.123", TimestampPattern.format(millis, utc, "2006-01-02 15:04:05.000"))
    }

    @Test
    fun `a mismatching literal is a failure`() {
        assertTrue(TimestampPattern.parse("2028/11/22", utc, "yyyy-MM-dd").isFailure)
    }

    @Test
    fun `non-numeric input is a failure`() {
        assertTrue(TimestampPattern.parse("不是数字", utc, "yyyy-MM-dd").isFailure)
    }
}
