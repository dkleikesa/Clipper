package com.qcmian.clipper.devtools.tools.timestamp

import kotlin.math.abs
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * 用户自定义的日期时间模板：按同一串模板**输出**，也按它**解析**输入。
 *
 * 自动识别覆盖的是「常见写法」，遇到偏门格式（`2028/11/22 10-12-34`、`11/22/2028`）就无能为力；
 * 这里让用户直接把模板写出来。**模板风格**默认按 Java / Android `DateTimeFormatter` 的写法
 * （`yyyy-MM-dd`），也接受 **Python strftime**（`%Y-%m-%d`）与 **Go 参考时间**（`2006-01-02`）
 * 两种风格——入口先按风格归一成 Java 写法（见 `normalizePattern`），因此后面只有一套分词 /
 * 格式化逻辑。支持的符号：
 *
 * | 符号 | 含义 | 输出示例 |
 * |---|---|---|
 * | `y` / `yy` / `yyyy` | 年（`yy` 取两位，`yyyy` 四位） | `2028` / `28` |
 * | `M` / `MM` | 月 | `11` |
 * | `d` / `dd` | 日 | `22` |
 * | `H` / `HH` | 24 时制小时 | `10` |
 * | `h` / `hh` | 12 时制小时（配合 `a`） | `10` |
 * | `m` / `mm` | 分 | `12` |
 * | `s` / `ss` | 秒 | `34` |
 * | `S` / `SS` / `SSS` | 毫秒（输出固定三位） | `123` |
 * | `a` | 上午 / 下午 | `上午` |
 * | `E` / `EEE` | 星期 | `星期三` |
 * | `Z` / `ZZ` | 时区偏移（`Z` 无冒号） | `+08:00` |
 * | `z` | 时区 ID | `Asia/Shanghai` |
 *
 * 其它字符按**字面量**原样出现（`yyyy年MM月dd日` 里的「年 / 月 / 日」就是字面量）；要写字面字母
 * 用单引号括起来（`yyyy-MM-dd'T'HH:mm:ss`），连续两个单引号表示一个单引号本身。
 *
 * **毫秒还认 Go 的写法**：秒之后紧跟的 `.` 加一串 `0` / `9`（`.000`、`.000000`、`.999`，一到九位）
 * 一律当毫秒，**三种风格通用**。别的字段在三种风格里各有各的写法，只有毫秒不是——用户从别处抄
 * 格式时，`SSS` 与 `000` 两种都常见；不认它就落进字面量，原样打出 `000`，看着像毫秒恒为 0。
 *
 * 解析时 `Z` / `z` 不参与（偏移有多种写法、识别它反而更易出错），其余符号都支持；模板里没有的
 * 字段用默认值补齐（年 1970、月 1、日 1、时分秒 0）。小数秒按**位数**换算：`.5` 是 500 毫秒、
 * `.12` 是 120 毫秒，超过三位截到毫秒。
 */
internal object TimestampPattern {

    /** 输出：把 [instant] 在 [zone] 时区下按 [pattern] 写成字符串。 */
    fun format(instant: Instant, zone: TimeZone, pattern: String): String {
        val local = instant.toLocalDateTime(zone)
        // 偏移量从时区直接取（含夏令时），不要拿「本地时间与 UTC 的差」自己拼——那样在跨 DST 的
        // 日期上会差一小时。
        val offsetSeconds = zone.offsetAt(instant).totalSeconds
        return buildString {
            tokenize(normalizePattern(pattern)).forEach { token ->
                when (token) {
                    is PatternToken.Literal -> append(token.text)
                    is PatternToken.Field -> append(formatField(token, local, instant, offsetSeconds, zone))
                }
            }
        }
    }

    /**
     * 解析：按 [pattern] 从 [text] 里读出一个时刻（缺时区的字段按 [zone] 解释）。
     *
     * 失败用 [Result] 带出原因（字面量对不上、该是数字的地方不是数字、模板里的日期非法等），
     * 界面上原样显示。
     */
    fun parse(text: String, zone: TimeZone, pattern: String): Result<Instant> = runCatching {
        var index = 0
        var year = 1970
        var month = 1
        var day = 1
        var hour = 0
        var minute = 0
        var second = 0
        var millis = 0
        var hour12: Int? = null
        var afternoon: Boolean? = null

        tokenize(normalizePattern(pattern)).forEach { token ->
            when (token) {
                is PatternToken.Literal -> {
                    require(text.startsWith(token.text, index)) { "这里应该是「${token.text}」" }
                    index += token.text.length
                }

                is PatternToken.Field -> when (token.kind) {
                    PatternField.YEAR -> {
                        val read = readNumber(text, index, if (token.count <= 1) YEAR_DIGITS else token.count)
                        year = if (token.count == 2) 2000 + read.value else read.value
                        index = read.next
                    }

                    PatternField.MONTH -> readNumber(text, index, 2).let { month = it.value; index = it.next }
                    PatternField.DAY -> readNumber(text, index, 2).let { day = it.value; index = it.next }
                    PatternField.HOUR24 -> readNumber(text, index, 2).let { hour = it.value; index = it.next }
                    PatternField.HOUR12 -> readNumber(text, index, 2).let { hour12 = it.value; index = it.next }
                    PatternField.MINUTE -> readNumber(text, index, 2).let { minute = it.value; index = it.next }
                    PatternField.SECOND -> readNumber(text, index, 2).let { second = it.value; index = it.next }
                    PatternField.MILLIS -> readNumber(text, index, MAX_FRACTION_DIGITS).let {
                        millis = scaleFraction(it.value, it.next - index)
                        index = it.next
                    }

                    PatternField.AMPM -> {
                        val matched = listOf("上午", "下午", "AM", "am", "PM", "pm")
                            .firstOrNull { text.startsWith(it, index) }
                            ?: throw IllegalArgumentException("这里应该是上午 / 下午")
                        afternoon = matched.startsWith("下") || matched.equals("PM", ignoreCase = true)
                        index += matched.length
                    }

                    PatternField.WEEKDAY -> {
                        val matched = TimestampConvert.WeekdayNames.firstOrNull { text.startsWith(it, index) }
                            ?: throw IllegalArgumentException("这里应该是星期几")
                        index += matched.length
                    }

                    PatternField.OFFSET, PatternField.ZONE_ID ->
                        throw IllegalArgumentException("模板里的 Z / z 只用于输出，暂不支持用它解析")
                }
            }
        }

        val resolvedHour = when {
            hour12 != null && afternoon != null -> (hour12 % 12) + if (afternoon) 12 else 0
            hour12 != null -> hour12
            else -> hour
        }
        LocalDateTime(year, month, day, resolvedHour, minute, second, millis * 1_000_000).toInstant(zone)
    }

    private fun formatField(
        field: PatternToken.Field,
        local: LocalDateTime,
        instant: Instant,
        offsetSeconds: Int,
        zone: TimeZone,
    ): String = when (field.kind) {
        PatternField.YEAR -> when {
            // `yy` 取年份后两位；`y` / `yyy` / `yyyy` 一律写四位——两位年只在 21 世纪内才是人们
            // 想的那样，写成四位更不容易误解。
            field.count == 2 -> pad(local.year.mod(100), 2)
            field.count <= 1 -> local.year.toString()
            else -> pad(local.year, field.count)
        }

        PatternField.MONTH -> pad(local.month.ordinal + 1, field.count)
        PatternField.DAY -> pad(local.day, field.count)
        PatternField.HOUR24 -> pad(local.hour, field.count)
        PatternField.HOUR12 -> pad((local.hour + 11) % 12 + 1, field.count)
        PatternField.MINUTE -> pad(local.minute, field.count)
        PatternField.SECOND -> pad(local.second, field.count)
        PatternField.MILLIS -> pad(local.nanosecond / 1_000_000, 3)
        PatternField.AMPM -> if (local.hour < 12) "上午" else "下午"
        PatternField.WEEKDAY -> TimestampConvert.weekdayName(instant)
        PatternField.OFFSET -> formatOffset(offsetSeconds, field.count)
        PatternField.ZONE_ID -> zone.id
    }

    /** `+0800`（`Z`）或 `+08:00`（`ZZ` 及以上）。 */
    private fun formatOffset(totalSeconds: Int, count: Int): String {
        val sign = if (totalSeconds < 0) "-" else "+"
        val magnitude = abs(totalSeconds)
        val hours = pad(magnitude / 3_600, 2)
        val minutes = pad(magnitude % 3_600 / 60, 2)
        return if (count <= 1) "$sign$hours$minutes" else "$sign$hours:$minutes"
    }

    /** 数字补零到 [count] 位：位数已够就原样，`count <= 1` 不补。 */
    private fun pad(value: Int, count: Int): String {
        val text = value.toString()
        return if (count <= 1 || text.length >= count) text else "0".repeat(count - text.length) + text
    }

    /** 从 [index] 起读至多 [maxDigits] 位连续数字；一位都没有就报错。 */
    private fun readNumber(text: String, index: Int, maxDigits: Int): NumberRead {
        var end = index
        while (end < text.length && end - index < maxDigits && text[end].isDigit()) end++
        require(end > index) { "这里应该是数字" }
        return NumberRead(text.substring(index, end).toInt(), end)
    }

    private class NumberRead(val value: Int, val next: Int)

    /**
     * 小数秒按**位数**换算成毫秒：`.5` 是 500 而不是 5，`.12` 是 120。
     *
     * 位数不足三位就补零、超过三位就往后截——这个工具只精确到毫秒（[format] 也只写三位），
     * 多出来的位数没有地方放。
     */
    private fun scaleFraction(value: Int, digits: Int): Int =
        if (digits >= 3) value / pow10(digits - 3) else value * pow10(3 - digits)

    private fun pow10(exponent: Int): Int {
        var result = 1
        repeat(exponent) { result *= 10 }
        return result
    }

    /**
     * 把模板切成字面量与字段两类记号。
     *
     * 连续相同的字段字符合成一个字段（`MM` 是一个「两位月」而不是两个「一位月」）；单引号对内部
     * 的内容一律按字面量处理，`''` 表示一个单引号。秒字段之后紧跟的 `.` 加一串 `0` / `9` 另认作
     * 毫秒（见 [fractionRunLength]）。
     */
    private fun tokenize(pattern: String): List<PatternToken> {
        val tokens = mutableListOf<PatternToken>()
        val literal = StringBuilder()
        // 最近落下的那个记号。「秒后面那串 0」要认成毫秒，就得知道前面确实是秒。
        var previous: PatternToken? = null

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                val token = PatternToken.Literal(literal.toString())
                tokens.add(token)
                previous = token
                literal.clear()
            }
        }

        fun add(token: PatternToken) {
            tokens.add(token)
            previous = token
        }

        var i = 0
        while (i < pattern.length) {
            val ch = pattern[i]
            if (ch == '\'') {
                if (i + 1 < pattern.length && pattern[i + 1] == '\'') {
                    literal.append('\'')
                    i += 2
                    continue
                }
                val end = pattern.indexOf('\'', i + 1)
                if (end < 0) {
                    // 没有配对的引号：把剩下的都当字面量，而不是丢掉——模板写错了也该尽量显示出来。
                    literal.append(pattern, i + 1, pattern.length)
                    i = pattern.length
                } else {
                    literal.append(pattern, i + 1, end)
                    i = end + 1
                }
                continue
            }

            // Go 那种小数秒写法（`.000` / `.999`）：`.` 照旧是字面量，后面那串数字记成毫秒。
            // 只在**秒之后**成立——别处的 `000` 仍是普通字面量（`v1.000` 这种版本号不该变成毫秒）。
            if (ch == '.') {
                val run = fractionRunLength(pattern, i + 1)
                val before = previous
                if (run > 0 && before is PatternToken.Field && before.kind == PatternField.SECOND) {
                    literal.append('.')
                    flushLiteral()
                    add(PatternToken.Field(PatternField.MILLIS, run))
                    i += 1 + run
                    continue
                }
            }

            val kind = patternFieldOf(ch)
            if (kind == null) {
                literal.append(ch)
                i++
                continue
            }

            flushLiteral()
            var j = i
            while (j < pattern.length && pattern[j] == ch) j++
            add(PatternToken.Field(kind, j - i))
            i = j
        }
        flushLiteral()
        return tokens
    }

    /**
     * 从 [start] 起那串 `0` 或 `9` 的长度（1~9）；不是这种写法返回 0。
     *
     * 两种数字都要认：Go 的参考时间用 `.000` 表示小数秒、`.999` 表示「末尾的零去掉」。这里只关心
     * 「这是一段小数秒」，具体几位各自截到毫秒。
     */
    private fun fractionRunLength(pattern: String, start: Int): Int {
        if (start >= pattern.length) return 0
        val mark = pattern[start]
        if (mark != '0' && mark != '9') return 0
        var end = start
        while (end < pattern.length && pattern[end] == mark) end++
        return (end - start).coerceAtMost(MAX_FRACTION_DIGITS)
    }

    private fun patternFieldOf(ch: Char): PatternField? = when (ch) {
        'y' -> PatternField.YEAR
        'M' -> PatternField.MONTH
        'd' -> PatternField.DAY
        'H' -> PatternField.HOUR24
        'h' -> PatternField.HOUR12
        'm' -> PatternField.MINUTE
        's' -> PatternField.SECOND
        'S' -> PatternField.MILLIS
        'a' -> PatternField.AMPM
        'E' -> PatternField.WEEKDAY
        'Z' -> PatternField.OFFSET
        'z' -> PatternField.ZONE_ID
        else -> null
    }

    // ------------------------------------------------------------------ 模板风格归一化

    /**
     * 把模板归一成 Java 写法再分词：三种风格只是「同一件事的三种记法」，转一次就够了，不必让
     * 分词器认识三套语法。
     *
     * 归一化时把**字面量**用单引号括起来（`-`、`:`、`年`…），免得 Go / Python 模板里的普通字符
     * 恰好是 Java 的字段字母（例如字面量 `T`）被误当成字段。
     */
    private fun normalizePattern(pattern: String): String = when (detectStyle(pattern)) {
        TemplateStyle.JAVA -> pattern
        TemplateStyle.PYTHON -> pythonToJava(pattern)
        TemplateStyle.GO -> goToJava(pattern)
    }

    private enum class TemplateStyle { JAVA, PYTHON, GO }

    /** Python 模板带 `%X`；Go 模板以参考年 `2006` 为标志；都不是就按 Java。 */
    private fun detectStyle(pattern: String): TemplateStyle = when {
        pythonFieldSpec.containsMatchIn(pattern) -> TemplateStyle.PYTHON
        pattern.contains(GO_REFERENCE_YEAR) -> TemplateStyle.GO
        else -> TemplateStyle.JAVA
    }

    private val pythonFieldSpec = Regex("%[A-Za-z]")

    private const val GO_REFERENCE_YEAR = "2006"

    /** `%Y-%m-%d` → `yyyy'-'MM'-'dd`。不认识的 `%X` 原样当字面量。 */
    private fun pythonToJava(pattern: String): String {
        val out = StringBuilder()
        val literal = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val char = pattern[i]
            if (char == '%' && i + 1 < pattern.length) {
                val spec = pattern[i + 1]
                val mapped = pythonField(spec)
                when {
                    mapped != null -> {
                        out.appendLiteral(literal)
                        out.append(mapped)
                        i += 2
                    }

                    spec == '%' -> {
                        literal.append('%')
                        i += 2
                    }

                    else -> {
                        literal.append(char)
                        i++
                    }
                }
                continue
            }
            // 小数秒（Go 的 `.000`）：照原样写出去、**不进字面量**，否则会被引号包起来，
            // 分词器就认不出它是毫秒了。
            if (char == '.' && appendFraction(pattern, i, literal, out)) {
                i += 1 + fractionRunLength(pattern, i + 1)
                continue
            }
            literal.append(char)
            i++
        }
        out.appendLiteral(literal)
        return out.toString()
    }

    /**
     * [index] 处若是「`.` + 一串 `0` / `9`」，把已攒下的字面量、这个 `.` 与那串数字依次写进 [out]
     * （后两者不加引号），返回 `true`；否则什么都不动。
     *
     * 不加引号是刻意的：认不认这串数字得看**它前面是不是「秒」**，只有分词器知道，所以这里只把
     * 它原样放行，由分词器决定（见 `tokenize`）。
     */
    private fun appendFraction(
        pattern: String,
        index: Int,
        literal: StringBuilder,
        out: StringBuilder,
    ): Boolean {
        val run = fractionRunLength(pattern, index + 1)
        if (run == 0) return false
        out.appendLiteral(literal)
        out.append('.')
        out.append(pattern, index + 1, index + 1 + run)
        return true
    }

    /** Python `%X` → Java 符号；不认识返回 `null`。 */
    private fun pythonField(spec: Char): String? = when (spec) {
        'Y' -> "yyyy"
        'y' -> "yy"
        'm' -> "MM"
        'd' -> "dd"
        'H' -> "HH"
        'I' -> "hh"
        'M' -> "mm"
        'S' -> "ss"
        'f' -> "SSS"
        'p' -> "a"
        'A', 'a' -> "EEE"
        'z' -> "Z"
        'Z' -> "z"
        else -> null
    }

    /** `2006-01-02 15:04:05` → `yyyy'-'MM'-'dd' 'HH':'mm':'ss`。 */
    private fun goToJava(pattern: String): String {
        val out = StringBuilder()
        val literal = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val token = goTokens.firstOrNull { pattern.startsWith(it.first, i) }
            if (token == null) {
                // 小数秒（`.000` / `.999`）照原样放行，交给分词器认（见 [appendFraction]）。
                if (pattern[i] == '.' && appendFraction(pattern, i, literal, out)) {
                    i += 1 + fractionRunLength(pattern, i + 1)
                    continue
                }
                literal.append(pattern[i])
                i++
                continue
            }
            out.appendLiteral(literal)
            out.append(token.second)
            i += token.first.length
        }
        out.appendLiteral(literal)
        return out.toString()
    }

    /**
     * Go 参考时间片段 → Java 符号，**长的排在前面**：`2006` 要先于 `20` / `06` 匹配到，
     * `-07:00` 要先于 `-0700`。
     *
     * 小数秒（`.000` / `.999`）不在表里：它有 1~9 位多种写法，穷举既长又漏，改成任何一个
     * 位数都认（见两个转换器里对 [fractionRunLength] 的处理）。
     *
     * Go 的英文月份（`Jan`）与英文星期全名不在表里——本工具不产英文月份，遇到就原样当字面量，
     * 而不是硬凑一个错误的映射。
     */
    private val goTokens: List<Pair<String, String>> = listOf(
        "-07:00" to "ZZ",
        "Z07:00" to "ZZ",
        "-0700" to "Z",
        // Go 里没有 `S`（它的小数秒写成 `.000`），所以 `S` 只可能是用户按 Java 的写法写的；
        // 认下来，免得 Go 模板里混一句 `SSS` 就被当字面量原样打出。
        "SSS" to "SSS",
        "SS" to "SSS",
        "S" to "SSS",
        GO_REFERENCE_YEAR to "yyyy",
        "MST" to "z",
        "PM" to "a",
        "pm" to "a",
        "15" to "HH",
        "01" to "MM",
        "02" to "dd",
        "03" to "hh",
        "04" to "mm",
        "05" to "ss",
        "06" to "yy",
        "1" to "M",
        "2" to "d",
        "3" to "h",
        "4" to "m",
        "5" to "s",
    )

    /** 把累积的字面量以单引号写入 [this]（内部的单引号写成两个），并清空累积器。 */
    private fun StringBuilder.appendLiteral(literal: StringBuilder) {
        if (literal.isEmpty()) return
        append('\'')
        append(literal.toString().replace("'", "''"))
        append('\'')
        literal.clear()
    }

    /** 月份 / 日这些不给 `yy` 的两位年留位置，四位足够覆盖到 9999 年。 */
    private const val YEAR_DIGITS = 4

    /** 小数秒最多读 / 认这么多位（Go 的纳秒写法正好九位）；超过的截到毫秒。 */
    private const val MAX_FRACTION_DIGITS = 9
}

/** 模板里的一类字段。 */
private enum class PatternField {
    YEAR, MONTH, DAY, HOUR24, HOUR12, MINUTE, SECOND, MILLIS, AMPM, WEEKDAY, OFFSET, ZONE_ID
}

/** 模板记号：一段字面量，或一个带重复次数的字段。 */
private sealed interface PatternToken {
    data class Literal(val text: String) : PatternToken

    data class Field(val kind: PatternField, val count: Int) : PatternToken
}
