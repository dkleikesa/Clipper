package com.qcmian.clipper.devtools.tools.timestamp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * 自定义模板的输出与解析。
 *
 * 断言值都对着 kotlinx-datetime 实测过（`byUnicodePattern` 打同一批字母的输出），写法与位数与
 * 它必须逐个一致——这个模板的卖点就是「从别处抄来的写法直接能用」。
 *
 * 时区用 UTC 与 `Asia/Kolkata`：前者给零偏移、后者给一个稳定的半点偏移（印度没有夏令时），
 * 带冒号与不带冒号、`Z` 与 `+0000` 这些差别都在它们身上看得出来。
 */
class TimestampPatternTest {

    private val utc = TimeZone.UTC
    private val kolkata = TimeZone.of("Asia/Kolkata")
    private val instant = Instant.parse("2028-11-22T10:12:34.123Z")

    @Test
    fun `formats the common fields`() {
        val epoch = Instant.fromEpochMilliseconds(0)
        assertEquals("1970-01-01 00:00:00", TimestampPattern.format(epoch, utc, "yyyy-MM-dd HH:mm:ss"))
        assertEquals("1970/01/01", TimestampPattern.format(epoch, utc, "yyyy/MM/dd"))
        // 中文标点是字面量，直接写在模板里（中文不算模板字母，不必加引号）。
        assertEquals("1970年01月01日", TimestampPattern.format(epoch, utc, "yyyy年MM月dd日"))
    }

    @Test
    fun `the year follows the letter count`() {
        assertEquals("2028", TimestampPattern.format(instant, utc, "y"))
        assertEquals("28", TimestampPattern.format(instant, utc, "yy"))
        assertEquals("2028", TimestampPattern.format(instant, utc, "yyyy"))
        // `u` 与 `y` 同义：它是 kotlinx-datetime 自己的记法，从它文档抄来的 `uuuu-MM-dd` 得能直接
        // 用，所以照收——只是速查表与预设里不再重复列一遍。
        assertEquals("2028", TimestampPattern.format(instant, utc, "uuuu"))
    }

    @Test
    fun `the day of year is available too`() {
        // 2028 是闰年，11-22 是第 327 天。
        assertEquals("327", TimestampPattern.format(instant, utc, "D"))
        assertEquals("327", TimestampPattern.format(instant, utc, "DDD"))
        assertEquals(
            "2028-11-22T00:00:00Z",
            TimestampPattern.parse("2028-327", utc, "yyyy-DDD").getOrThrow().toString(),
        )
    }

    @Test
    fun `the fraction has as many digits as the letter count`() {
        // 与 kotlinx-datetime 一致：位数就是字母个数，不是「最少几位」。
        assertEquals("1", TimestampPattern.format(instant, utc, "S"))
        assertEquals("12", TimestampPattern.format(instant, utc, "SS"))
        assertEquals("123", TimestampPattern.format(instant, utc, "SSS"))
        assertEquals("1230", TimestampPattern.format(instant, utc, "SSSS"))
        assertEquals("123000000", TimestampPattern.format(instant, utc, "SSSSSSSSS"))
    }

    @Test
    fun `a zero offset is written the way each letter asks for`() {
        // `X` 那一族写 `Z`（ISO 8601 的约定），`x` 与 `Z` 那两族仍写数字。
        assertEquals("Z", TimestampPattern.format(instant, utc, "X"))
        assertEquals("Z", TimestampPattern.format(instant, utc, "XXX"))
        assertEquals("Z", TimestampPattern.format(instant, utc, "XXXXX"))
        assertEquals("+00", TimestampPattern.format(instant, utc, "x"))
        assertEquals("+0000", TimestampPattern.format(instant, utc, "xx"))
        assertEquals("+00:00", TimestampPattern.format(instant, utc, "xxx"))
        assertEquals("+0000", TimestampPattern.format(instant, utc, "Z"))
        assertEquals("+0000", TimestampPattern.format(instant, utc, "ZZZ"))
        assertEquals("+00:00", TimestampPattern.format(instant, utc, "ZZZZZ"))
    }

    @Test
    fun `offset letters differ in colons and in width`() {
        // 同一个 +05:30。冒号不是「三个字母起就有」：`XXX` 带、`XXXX` 不带、`XXXXX` 又带——
        // 位数与冒号在这门写法里不是单调对应的。
        assertEquals("+0530", TimestampPattern.format(instant, kolkata, "XX"))
        assertEquals("+05:30", TimestampPattern.format(instant, kolkata, "XXX"))
        assertEquals("+0530", TimestampPattern.format(instant, kolkata, "XXXX"))
        assertEquals("+05:30", TimestampPattern.format(instant, kolkata, "XXXXX"))
        assertEquals("+0530", TimestampPattern.format(instant, kolkata, "xx"))
        assertEquals("+05:30", TimestampPattern.format(instant, kolkata, "xxx"))
        assertEquals("+0530", TimestampPattern.format(instant, kolkata, "xxxx"))
        assertEquals("+05:30", TimestampPattern.format(instant, kolkata, "xxxxx"))
        assertEquals("+0530", TimestampPattern.format(instant, kolkata, "Z"))
        assertEquals("+05:30", TimestampPattern.format(instant, kolkata, "ZZZZZ"))
        assertEquals("Asia/Kolkata", TimestampPattern.format(instant, kolkata, "VV"))
    }

    @Test
    fun `literal letters have to be quoted`() {
        assertEquals(
            "2028-11-22T10:12:34",
            TimestampPattern.format(instant, utc, "yyyy-MM-dd'T'HH:mm:ss"),
        )
        // 没加引号的字母不是「原样输出」，而是明确报错——静默输出会让人以为模板生效了。
        assertTrue(TimestampPattern.problemOf("yyyy-MM-ddTHH:mm:ss") != null)
    }

    @Test
    fun `letters kotlinx-datetime refuses are refused here too`() {
        // 这几个都是从别处抄模板时最容易带上的写法，报错要说清该换成什么。
        assertTrue(TimestampPattern.problemOf("MMM")?.contains("语言环境") == true)
        assertTrue(TimestampPattern.problemOf("hh")?.contains("24 小时制") == true)
        assertTrue(TimestampPattern.problemOf("zzz") != null)
        assertTrue(TimestampPattern.problemOf("a") != null)
        assertTrue(TimestampPattern.problemOf("EEEE") != null)
        assertTrue(TimestampPattern.problemOf("GG") != null)
    }

    @Test
    fun `lengths kotlinx-datetime refuses are refused here too`() {
        // 年只收 1 / 2 / 4 位，`Z` 不收四位（那是语言环境相关的写法），时区 ID 必须写两位。
        listOf("yyy", "yyyyy", "ZZZZ", "V", "HHH", "DD").forEach { pattern ->
            assertTrue(TimestampPattern.problemOf(pattern) != null, "$pattern 应当被拒绝")
        }
    }

    @Test
    fun `an unsupported letter is an error instead of silent text`() {
        // 这一条是刻意的取舍：`hh` 若被原样打出来，看着就像「结果里多了两个 h」，比报错难查得多。
        assertFailsWith<IllegalArgumentException> { TimestampPattern.format(instant, utc, "hh") }
    }

    @Test
    fun `parses a pattern back to the same instant`() {
        val exact = Instant.parse("2028-11-22T10:12:34Z")
        val text = TimestampPattern.format(exact, utc, "yyyy-MM-dd HH:mm:ss")
        assertEquals("2028-11-22 10:12:34", text)
        assertEquals(exact, TimestampPattern.parse(text, utc, "yyyy-MM-dd HH:mm:ss").getOrThrow())
    }

    @Test
    fun `parsing fills the fields the pattern omits with defaults`() {
        val parsed = TimestampPattern.parse("10:12:34", utc, "HH:mm:ss").getOrThrow()
        assertEquals("1970-01-01T10:12:34Z", parsed.toString())
    }

    @Test
    fun `milliseconds survive a round trip`() {
        val text = TimestampPattern.format(instant, utc, "yyyy-MM-dd HH:mm:ss.SSS")
        assertEquals("2028-11-22 10:12:34.123", text)
        assertEquals(instant, TimestampPattern.parse(text, utc, "yyyy-MM-dd HH:mm:ss.SSS").getOrThrow())
    }

    @Test
    fun `milliseconds also accept the dot zero notation`() {
        // `.000` 就是 `SSS` 的另一种写法：不认它就会落进字面量被原样打出，看着像毫秒恒为 0。
        assertEquals("2028-11-22 10:12:34.123", TimestampPattern.format(instant, utc, "yyyy-MM-dd HH:mm:ss.000"))
        // 位数照字母个数走，`.000…` 与 `SSS…` 完全等价——写九位就是九位，不做特殊照顾。
        assertEquals(
            TimestampPattern.format(instant, utc, "yyyy-MM-dd HH:mm:ss.SSSSSSSSS"),
            TimestampPattern.format(instant, utc, "yyyy-MM-dd HH:mm:ss.000000000"),
        )
        // 秒以外的 `.000` 仍是普通字面量（版本号那种）。
        assertEquals("2028-11-22 1.000", TimestampPattern.format(instant, utc, "yyyy-MM-dd 1.000"))
        // 一串 9 不认：那是别的工具的风格。
        assertEquals("2028-11-22 10:12:34.999", TimestampPattern.format(instant, utc, "yyyy-MM-dd HH:mm:ss.999"))
    }

    @Test
    fun `fractions shorter than three digits are scaled`() {
        assertEquals(
            "2028-11-22T10:12:34.500Z",
            TimestampPattern.parse("2028-11-22 10:12:34.5", utc, "yyyy-MM-dd HH:mm:ss.SSS").getOrThrow().toString(),
        )
        assertEquals(
            "2028-11-22T10:12:34.120Z",
            TimestampPattern.parse("2028-11-22 10:12:34.12", utc, "yyyy-MM-dd HH:mm:ss.SSS").getOrThrow().toString(),
        )
        // 超过三位只留到毫秒，不四舍五入。
        assertEquals(
            "2028-11-22T10:12:34.123Z",
            TimestampPattern.parse(
                "2028-11-22 10:12:34.123456", utc, "yyyy-MM-dd HH:mm:ss.SSS",
            ).getOrThrow().toString(),
        )
    }

    @Test
    fun `a time zone in the text decides the instant`() {
        // 文本自带偏移时以它为准：「输入时区」只是文本没写时区时的兜底。
        assertEquals(
            "2026-10-03T13:19:54Z",
            TimestampPattern.parse("2026-10-03T21:19:54+08:00", utc, "yyyy-MM-dd'T'HH:mm:ssZZZZZ")
                .getOrThrow().toString(),
        )
        // `Z` 就是零偏移。
        assertEquals(
            "2026-10-03T21:19:54Z",
            TimestampPattern.parse("2026-10-03T21:19:54Z", utc, "yyyy-MM-dd'T'HH:mm:ssXXX").getOrThrow().toString(),
        )
        // 读偏移比写偏移宽容：模板写 `XXX`，输入给不带冒号的 `+0800` 也认。
        assertEquals(
            "2026-10-03T13:19:54Z",
            TimestampPattern.parse("2026-10-03T21:19:54+0800", utc, "yyyy-MM-dd'T'HH:mm:ssXXX")
                .getOrThrow().toString(),
        )
    }

    @Test
    fun `a time zone id in the text decides the instant too`() {
        assertEquals(
            "2026-10-03T13:19:54Z",
            TimestampPattern.parse(
                "2026-10-03T21:19:54[Asia/Shanghai]", utc, "yyyy-MM-dd'T'HH:mm:ss'['VV']'",
            ).getOrThrow().toString(),
        )
        // 认不出来的时区名报错，而不是把库里的英文异常甩到界面上。
        assertTrue(
            TimestampPattern.parse(
                "2026-10-03T21:19:54[Nowhere/Nothing]", utc, "yyyy-MM-dd'T'HH:mm:ss'['VV']'",
            ).isFailure,
        )
    }

    @Test
    fun `without a time zone in the pattern the chosen zone is used`() {
        // 模板里没写时区，才轮到界面上选的那个（+05:30）。
        assertEquals(
            "2026-10-03T15:49:54Z",
            TimestampPattern.parse("2026-10-03 21:19:54", kolkata, "yyyy-MM-dd HH:mm:ss").getOrThrow().toString(),
        )
    }

    @Test
    fun `a broken offset is a failure`() {
        assertTrue(
            TimestampPattern.parse("2026-10-03T21:19:54+", utc, "yyyy-MM-dd'T'HH:mm:ssXXX").isFailure,
        )
    }

    @Test
    fun `the iso pattern round trips`() {
        // 界面预设里那一串、也是用户最常粘的那种：输入自带偏移，格式带偏移。
        val pattern = "yyyy-MM-dd'T'HH:mm:ssZZZZZ"
        val text = "2026-10-03T21:19:54+08:00"
        val parsed = TimestampPattern.parse(text, utc, pattern).getOrThrow()
        assertEquals("2026-10-03T13:19:54Z", parsed.toString())
        // 换到上海打回去，与输入一字不差（解析时用的是文本里的 +08:00，不是上面那个 utc）。
        assertEquals(text, TimestampPattern.format(parsed, TimeZone.of("Asia/Shanghai"), pattern))
    }

    @Test
    fun `the pattern tells whether the text carries the time zone`() {
        // 界面据此决定要不要提示「上面的输入时区用不上了」。
        assertTrue(TimestampPattern.carriesZone("yyyy-MM-dd'T'HH:mm:ssZZZZZ"))
        assertTrue(TimestampPattern.carriesZone("yyyy-MM-dd'T'HH:mm:ssXXX"))
        assertTrue(TimestampPattern.carriesZone("yyyy-MM-dd HH:mm:ss'['VV']'"))
        assertFalse(TimestampPattern.carriesZone("yyyy-MM-dd HH:mm:ss"))
        // 模板本身写错时不该跟着炸：那一刻界面上显示的是模板错误。
        assertFalse(TimestampPattern.carriesZone("hh"))
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
