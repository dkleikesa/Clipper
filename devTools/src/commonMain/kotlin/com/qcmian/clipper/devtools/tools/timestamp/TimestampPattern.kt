package com.qcmian.clipper.devtools.tools.timestamp

import kotlin.math.abs
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.offsetAt
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * 用户自定义的日期时间模板：按同一串模板**输出**，也按它**解析**输入。
 *
 * 写法**只有一种**——kotlinx-datetime 的 Unicode pattern（`yyyy-MM-dd HH:mm:ss`）。支持哪些字母、
 * 每个字母能写几位、零偏移写成什么样，一律以它为准：从 Java / ICU / NSDateFormatter 那边抄来的
 * 模板基本可以直接粘，行为也一致。支持哪些符号见 [TimestampSyntax]——界面上的「占位符速查」直接
 * 读它，那页与这里不会各写一份、各改各的。
 *
 * 年只对外说 `y` 一种写法：`u` 是 kotlinx-datetime 自己的记法，两者同义，这里**也认**——从它的
 * 文档抄来的 `uuuu-MM-dd` 得能直接用；只是速查表与预设里不再重复列一遍，同一个东西摆出两种写法，
 * 用户还得先判断手上那串是哪一种。
 *
 * 相对那套语法，这里两处**收紧**、一处**放宽**：
 *
 *  - 不支持的字母**当场报错**，而不是当字面量原样打出去。`hh`（12 小时制要配上午 / 下午的名字）
 *    若被原样输出，看着就像「结果里多了两个 h」，比报错难查得多；报错还会说清是「依赖语言环境」
 *    还是「没实现」，好让人换写法。
 *  - 模板里要输出**字母**得用单引号括起来（`'T'`），与 Unicode 语法一致。
 *  - 放宽的一处：秒后面的 `.000` 也认作小数秒（见下）——它不引入新符号，只是免得一个常见的抄写
 *    习惯静默出错。
 *
 * 除字母与 `.000` 之外都是**字面量**（`yyyy年MM月dd日` 里的「年 / 月 / 日」照原样出现）。连续两个
 * 单引号表示一个单引号本身。中文不算「字母」，不必加引号。
 *
 * **毫秒还认 `.000` 这种写法**：秒之后紧跟的 `.` 加一串 `0`（一到九位）当作小数秒。`SSS` 是规范
 * 写法，但 `.000` 从别处抄格式时太常见——不认它就会落进字面量被原样打出，看着像毫秒恒为 0。
 *
 * 小数秒的**位数就是字母个数**（与 kotlinx-datetime 一致）：`S` 是一位（`1`），`SSS` 是三位
 * （`123`），`SSSSSSSSS` 是九位。解析反过来：`.5` 读成 500 毫秒、`.12` 读成 120 毫秒，超过三位截到
 * 毫秒——这个工具只精确到毫秒，多出来的位数没有地方放。
 *
 * 解析时模板里没有的字段用默认值补齐（年 1970、月 1、日 1、时分秒 0）。模板里写了 `VV` 或 `X`
 * 一族时，**文本里的时区说了算**——`2026-10-03T21:19:54+08:00` 就该按那串 `+08:00` 解释；界面上
 * 那个「输入时区」只在文本没带时区时兜底。两者都写了也以偏移为准：偏移更具体，ISO 8601 也是这么
 * 定的。读偏移比写偏移**宽容**——模板写 `XXX` 而输入给 `+0800` 也收，反过来也收。
 */
internal object TimestampPattern {

    /** 输出：把 [instant] 在 [zone] 时区下按 [pattern] 写成字符串。 */
    fun format(instant: Instant, zone: TimeZone, pattern: String): String {
        val local = instant.toLocalDateTime(zone)
        // 偏移量从时区直接取（含夏令时），不要拿「本地时间与 UTC 的差」自己拼——那样在跨 DST 的
        // 日期上会差一小时。
        val offsetSeconds = zone.offsetAt(instant).totalSeconds
        return buildString {
            tokenize(pattern).forEach { token ->
                when (token) {
                    is PatternToken.Literal -> append(token.text)
                    is PatternToken.Field -> append(formatField(token, local, offsetSeconds, zone))
                }
            }
        }
    }

    /**
     * 模板本身能不能用：能用返回 `null`，否则返回一句人话。
     *
     * 分词就是「报错」的那一步（不认识的字母、写错的位数都在这里拦下）。界面先问它、把错误显示
     * 出来，[format] 与 [parse] 因此不会再撞上非法模板——前者直接抛，后者把它包成 [Result] 的失败。
     */
    fun problemOf(pattern: String): String? =
        runCatching { tokenize(pattern) }.exceptionOrNull()?.message

    /**
     * 模板里写没写时区（`VV` 或 `X` 一族）。写了的话，解析以文本里那个为准，「输入时区」就用不上了。
     *
     * 界面据此在时区那一行加一句提示：下拉改了却什么都没发生，最容易让人以为工具坏了。模板本身
     * 写错（=分词会抛）时返回 `false`——那时界面上显示的是模板错误，不必再叠一句时区提示。
     */
    fun carriesZone(pattern: String): Boolean = runCatching {
        tokenize(pattern).any { it is PatternToken.Field && it.letter in "VXxZ" }
    }.getOrDefault(false)

    /**
     * 解析：按 [pattern] 从 [text] 里读出一个时刻。
     *
     * [zone] 只用来解释**没带时区**的文本；模板里写了时区（`VV` / `X` 一族）而文本里也给了时，
     * 以文本为准。
     *
     * 失败用 [Result] 带出原因（字面量对不上、该是数字的地方不是数字、模板里的日期非法等），
     * 界面上原样显示。
     */
    fun parse(text: String, zone: TimeZone, pattern: String): Result<Instant> = runCatching {
        var index = 0
        var year = 1970
        var month = 1
        var day = 1
        var dayOfYear: Int? = null
        var hour = 0
        var minute = 0
        var second = 0
        var millis = 0
        // 文本里自带的时区：有就按它定位时刻，「输入时区」只在两者都没有时才用得上。
        var offsetSeconds: Int? = null
        var zoneId: String? = null

        tokenize(pattern).forEach { token ->
            when (token) {
                is PatternToken.Literal -> {
                    require(text.startsWith(token.text, index)) { "这里应该是「${token.text}」" }
                    index += token.text.length
                }

                is PatternToken.Field -> when (token.letter) {
                    // 时区单独一条路：它读出来的不是「日期里的一个数」，而是「这一刻在哪条经线上」。
                    'X', 'x', 'Z' -> readOffset(text, index).let {
                        offsetSeconds = it.value
                        index = it.next
                    }

                    'V' -> readZoneId(text, index).let {
                        zoneId = it.value
                        index = it.next
                    }

                    // 数字字段一律按**两位**读，不按模板写了几位：模板写 `M` 而输入是 `11` 时，按一位
                    // 读只会拿到 `1`。位数在输出侧才要紧（`MM` 把 `1` 补成 `01`）。
                    else -> {
                        val read: NumberRead = when (token.letter) {
                            'u', 'y' -> readNumber(text, index, if (token.count <= 1) YEAR_DIGITS else token.count)
                                .also { year = if (token.count == 2) 2000 + it.value else it.value }

                            'M', 'L' -> readNumber(text, index, 2).also { month = it.value }
                            'd' -> readNumber(text, index, 2).also { day = it.value }
                            'D' -> readNumber(text, index, 3).also { dayOfYear = it.value }
                            'H' -> readNumber(text, index, 2).also { hour = it.value }
                            'm' -> readNumber(text, index, 2).also { minute = it.value }
                            's' -> readNumber(text, index, 2).also { second = it.value }
                            'S' -> readNumber(text, index, token.count)
                                .also { millis = scaleFraction(it.value, it.next - index) }

                            else -> error("分词漏掉了字母 ${token.letter}")
                        }
                        index = read.next
                    }
                }
            }
        }

        // `DDD` 给了年内第几天就以它为准：模板里同时写了月日时那两处只是陪衬，按天数算才不会把
        // 「月日与天数互相矛盾」的输入悄悄解释成其中一种。
        val date = if (dayOfYear != null) {
            LocalDate.fromEpochDays(LocalDate(year, 1, 1).toEpochDays() + dayOfYear - 1)
        } else {
            LocalDate(year, month, day)
        }
        val local =
            LocalDateTime(date.year, date.month.ordinal + 1, date.day, hour, minute, second, millis * 1_000_000)
        // 文本自带的时区优先：输入里那句 `+08:00` 说的就是「我是哪个时刻」，比界面上选的那个更具体。
        val offset = offsetSeconds
        val id = zoneId
        when {
            offset != null -> local.toInstant(offsetOf(offset))
            id != null -> local.toInstant(zoneOf(id))
            else -> local.toInstant(zone)
        }
    }

    /** 秒数 → [UtcOffset]：时 / 分 / 秒必须同号，西半球的偏移得三个都带上负号。 */
    private fun offsetOf(totalSeconds: Int): UtcOffset {
        val sign = if (totalSeconds < 0) -1 else 1
        val magnitude = abs(totalSeconds)
        return UtcOffset(
            hours = sign * (magnitude / 3_600),
            minutes = sign * (magnitude % 3_600 / 60),
            seconds = sign * (magnitude % 60),
        )
    }

    /** 时区 ID → [TimeZone]；不认识的写法给一句人话，而不是把库里的英文异常甩到界面上。 */
    private fun zoneOf(id: String): TimeZone =
        runCatching { TimeZone.of(id) }.getOrElse { throw IllegalArgumentException("不认识的时区：$id") }

    private fun formatField(
        field: PatternToken.Field,
        local: LocalDateTime,
        offsetSeconds: Int,
        zone: TimeZone,
    ): String = when (field.letter) {
        // `y` 与 `u` 同义（见类注释）：一位不补零、两位取后两位、四位补到四位。
        'u', 'y' -> when (field.count) {
            2 -> pad(local.year.mod(100), 2)
            1 -> local.year.toString()
            else -> pad(local.year, 4)
        }

        'M', 'L' -> pad(local.month.ordinal + 1, field.count)
        'd' -> pad(local.day, field.count)
        'D' -> pad(local.dayOfYear, field.count)
        'H' -> pad(local.hour, field.count)
        'm' -> pad(local.minute, field.count)
        's' -> pad(local.second, field.count)
        'S' -> fractionText(local.nanosecond, field.count)
        'V' -> zone.id
        'X', 'x', 'Z' -> formatOffset(offsetSeconds, field.letter, field.count)
        else -> error("未处理的字母 ${field.letter}")
    }

    /**
     * 小数秒：位数就是字母个数，取自纳秒那九位的前几位。
     *
     * `SSS` 是毫秒（`123`），`S` 是十分之一秒（`1`）——与 kotlinx-datetime 一致，不是「最少几位」。
     * 位数写得比九位还多也没关系：纳秒只到九位，多出来的位置本来就无数可写。
     */
    private fun fractionText(nanosecond: Int, count: Int): String =
        nanosecond.toString().padStart(9, '0').take(count)

    /**
     * 时区偏移。形状照 kotlinx-datetime 的语法表来——同一个 `+03:30`，`XXX` 与 `XXXXX` 都写冒号、
     * `XX` 与 `XXXX` 都不写；`XXXX` 起（`Z` 那一族是 `ZZZZZ`）还会输出**秒**，秒为零时省掉。
     *
     * 零偏移的写法随字母而变：`X` 那一族写 `Z`（ISO 8601 的约定），`x` 与 `Z` 那两族仍写数字，
     * 只是补零的档不同（`+00` / `+0000` / `+00:00`）。
     */
    private fun formatOffset(totalSeconds: Int, letter: Char, count: Int): String {
        if (totalSeconds == 0) return zeroOffsetText(letter, count)
        val sign = if (totalSeconds < 0) "-" else "+"
        val magnitude = abs(totalSeconds)
        val separator = if (offsetUsesColon(letter, count)) ":" else ""
        // `X` / `x` 只写一位时，分钟为零就不写——`+03` 正是 `+0300` 的简写。
        val minutes = if (count == 1 && letter != 'Z' && magnitude % 3_600 == 0) {
            ""
        } else {
            pad(magnitude % 3_600 / 60, 2)
        }
        val seconds = if (offsetShowsSeconds(letter, count) && magnitude % 60 != 0) {
            pad(magnitude % 60, 2)
        } else {
            ""
        }
        // 分隔符跟着它后面那一段走：省掉分钟或秒时，冒号不能单独留下（`+05:30` 不是 `+05:30:`）。
        return buildString {
            append(sign)
            append(pad(magnitude / 3_600, 2))
            if (minutes.isNotEmpty()) append(separator).append(minutes)
            if (seconds.isNotEmpty()) append(separator).append(seconds)
        }
    }

    /**
     * `+08:00` 还是 `+0800`：`XXX` 与 `XXXXX` 带冒号，中间那个 `XXXX`（写成 `+053045` 的那种）
     * 不带——位数与冒号不是单调对应的，这一档得单独说。`Z` 那边要到 `ZZZZZ` 才带。
     */
    private fun offsetUsesColon(letter: Char, count: Int): Boolean =
        if (letter == 'Z') count >= 5 else count == 3 || count == 5

    /** 要不要写秒：`XXXX` 起，`Z` 那边要到 `ZZZZZ`。 */
    private fun offsetShowsSeconds(letter: Char, count: Int): Boolean =
        if (letter == 'Z') count >= 5 else count >= 4

    /** 零偏移的写法：补零与冒号的档跟别处一样（`count` 说了算），只有 `X` 那一族改用 `Z`。 */
    private fun zeroOffsetText(letter: Char, count: Int): String = when (letter) {
        'X' -> "Z"
        'x' -> if (offsetUsesColon(letter, count)) "+00:00" else if (count >= 2) "+0000" else "+00"
        else -> if (count >= 5) "+00:00" else "+0000"
    }

    /** 数字补零到 [count] 位：位数已够就原样，`count <= 1` 不补。 */
    private fun pad(value: Int, count: Int): String {
        val text = value.toString()
        return if (count <= 1 || text.length >= count) text else "0".repeat(count - text.length) + text
    }

    /** 从 [index] 起读至多 [maxDigits] 位连续数字；一位都没有就报错。 */
    private fun readNumber(text: String, index: Int, maxDigits: Int): NumberRead {
        val digits = readDigits(text, index, maxDigits)
        require(digits.isNotEmpty()) { "这里应该是数字" }
        return NumberRead(digits.toInt(), index + digits.length)
    }

    /** 从 [index] 起读至多 [maxDigits] 位连续数字；一位都没有就是空串（偏移的分段用它拼）。 */
    private fun readDigits(text: String, index: Int, maxDigits: Int): String {
        var end = index
        while (end < text.length && end - index < maxDigits && text[end].isDigit()) end++
        return text.substring(index, end)
    }

    /**
     * 从 [index] 起读一个时区偏移，返回它的**秒数**。
     *
     * 收 `Z`（零偏移）、`+08`、`+0800`、`+08:00`、`+080045`——比模板里那个字母写的位数**宽容**：
     * 模板写 `XXX` 而输入给 `+0800` 也认。读的是「这里是偏移」，不是「偏移必须长成哪一副样子」；
     * 严格按字母写的是输出那一侧。
     */
    private fun readOffset(text: String, index: Int): NumberRead {
        if (index < text.length && (text[index] == 'Z' || text[index] == 'z')) {
            return NumberRead(0, index + 1)
        }
        require(index < text.length && (text[index] == '+' || text[index] == '-')) {
            "这里应该是时区偏移，如 +08:00 或 Z"
        }
        val sign = if (text[index] == '-') -1 else 1
        var i = index + 1
        val hours = readDigits(text, i, 2)
        require(hours.isNotEmpty()) { "时区偏移的小时没写" }
        i += hours.length

        var minutes = 0
        var seconds = 0
        if (i < text.length && text[i] == ':') {
            i++
            val minutesPart = readDigits(text, i, 2)
            require(minutesPart.isNotEmpty()) { "时区偏移的分钟没写" }
            minutes = minutesPart.toInt()
            i += minutesPart.length
            if (i < text.length && text[i] == ':') {
                i++
                val secondsPart = readDigits(text, i, 2)
                require(secondsPart.isNotEmpty()) { "时区偏移的秒没写" }
                seconds = secondsPart.toInt()
                i += secondsPart.length
            }
        } else {
            // 不带冒号的写法：`+0800` 是「时 + 分」，`+080045` 再多两位秒。
            val rest = readDigits(text, i, 4)
            when (rest.length) {
                4 -> {
                    minutes = rest.substring(0, 2).toInt()
                    seconds = rest.substring(2, 4).toInt()
                }

                2 -> minutes = rest.toInt()
                0 -> Unit
                else -> throw IllegalArgumentException("时区偏移写得不全（应当是 +HH、+HHMM 或 +HH:MM）")
            }
            i += rest.length
        }
        return NumberRead(sign * (hours.toInt() * 3_600 + minutes * 60 + seconds), i)
    }

    /** 读一个时区 ID（`Asia/Shanghai`、`Etc/GMT+8`、`UTC`）：读到不像 ID 的字符为止（`]`、空格…）。 */
    private fun readZoneId(text: String, index: Int): IdRead {
        var end = index
        while (end < text.length && (text[end].isLetterOrDigit() || text[end] in "-_+/")) end++
        require(end > index) { "这里应该是时区 ID，如 Asia/Shanghai" }
        return IdRead(text.substring(index, end), end)
    }

    private class NumberRead(val value: Int, val next: Int)

    private class IdRead(val value: String, val next: Int)

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
     * 连续相同的字段字符合成一个字段（`MM` 是一个「两位月」而不是两个「一位月」）；单引号对内部的
     * 内容一律按字面量处理，`''` 表示一个单引号。秒字段之后紧跟的 `.` 加一串 `0` 另认作小数秒
     * （见 [fractionRunLength]）。
     *
     * 不认识的字母、写错的位数在这里抛——由 [problemOf] 转成给用户看的那句话。
     */
    private fun tokenize(pattern: String): List<PatternToken> {
        val tokens = mutableListOf<PatternToken>()
        val literal = StringBuilder()
        // 最近落下的那个记号。「秒后面那串 0」要认成小数秒，就得知道前面确实是秒。
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

            // `.000` 那种小数秒写法：`.` 照旧是字面量，后面那串 0 记成小数秒。只在**秒之后**成立
            // ——别处的 `000` 仍是普通字面量（`v1.000` 这种版本号不该变成小数秒）。
            if (ch == '.') {
                val run = fractionRunLength(pattern, i + 1)
                val before = previous
                if (run > 0 && before is PatternToken.Field && before.letter == 's') {
                    literal.append('.')
                    flushLiteral()
                    add(PatternToken.Field('S', run))
                    i += 1 + run
                    continue
                }
            }

            if (isPatternLetter(ch)) {
                flushLiteral()
                var j = i
                while (j < pattern.length && pattern[j] == ch) j++
                val count = j - i
                checkSupported(ch, count)
                add(PatternToken.Field(ch, count))
                i = j
                continue
            }

            literal.append(ch)
            i++
        }
        flushLiteral()
        return tokens
    }

    /**
     * 这个字母、这个位数能不能用；不能用就抛一句人话。
     *
     * 判据照 kotlinx-datetime 抄：它认得的字母比它实现的多，认得却实现不了的（`E` 星期名、`z`
     * 时区名…）会当场报错。这里照做，并把「为什么」写进消息里，用户才知道该换成哪个写法。
     */
    private fun checkSupported(letter: Char, count: Int) {
        RejectedLetters[letter]?.let { throw IllegalArgumentException("'$letter' 用不了：$it") }
        // 小数秒的位数就是「你要几位」，写几位都成立，因此不在这张表里。
        if (letter == 'S') return
        val allowed = SupportedLengths[letter] ?: throw IllegalArgumentException(
            "'$letter' 不是支持的模板字母（要原样输出字母，请写成 '$letter'）"
        )
        if (count in allowed) return
        throw IllegalArgumentException(
            when {
                (letter == 'M' || letter == 'L') && count >= 3 ->
                    "月份名依赖语言环境，这里不支持（数字月份写 ${letter}${letter}）"

                letter == 'Z' && count == 4 ->
                    "ZZZZ 依赖语言环境，这里不支持（偏移写 ZZZ 或 ZZZZZ）"

                else -> "'${letter.toString().repeat(count)}' 不是支持的写法：$letter 只能写 " +
                    "${allowed.joinToString("、")} 位"
            }
        )
    }

    /**
     * 算不算模板字母：只看 ASCII。
     *
     * 中文（`年` / `月` / `日`）因此在模板里天然是字面量，不必加引号——那是这个工具最常见的模板
     * 写法，按 `Char.isLetter()` 判会把它们全判成「不认识的字母」。
     */
    private fun isPatternLetter(ch: Char): Boolean = ch in 'a'..'z' || ch in 'A'..'Z'

    /**
     * 每个字母允许的长度——照 kotlinx-datetime 的实现抄。写错位数当场报错，而不是照单全收。
     */
    private val SupportedLengths: Map<Char, List<Int>> = mapOf(
        'u' to listOf(1, 2, 4),
        'y' to listOf(1, 2, 4),
        'M' to listOf(1, 2),
        'L' to listOf(1, 2),
        'd' to listOf(1, 2),
        'D' to listOf(1, 3),
        'H' to listOf(1, 2),
        'm' to listOf(1, 2),
        's' to listOf(1, 2),
        'V' to listOf(2),
        'X' to (1..5).toList(),
        'x' to (1..5).toList(),
        'Z' to listOf(1, 2, 3, 5),
    )

    /**
     * kotlinx-datetime **认得但用不了**的字母，以及为什么。
     *
     * 报错时说清原因，用户才知道该换成哪个写法——这几个恰恰是从别处抄模板时最容易带上的
     * （`MMM` 月名、`hh` 12 小时制、`zzz` 时区名）。
     */
    private val RejectedLetters: Map<Char, String> = mapOf(
        'G' to "纪元依赖语言环境",
        'E' to "星期名依赖语言环境",
        'e' to "星期名依赖语言环境",
        'c' to "星期名依赖语言环境",
        'a' to "上午 / 下午的标记依赖语言环境",
        'h' to "12 小时制要配上午 / 下午的名字，这里没有（24 小时制写 HH）",
        'z' to "时区名依赖语言环境（时区 ID 写 VV）",
        'v' to "时区名依赖语言环境（时区 ID 写 VV）",
        'O' to "本地化偏移依赖语言环境",
        'Q' to "季度没有实现",
        'q' to "季度没有实现",
        'A' to "毫秒日没有实现",
        'N' to "纳秒日没有实现",
        'n' to "纳秒秒没有实现",
        'F' to "月内第几个星期几没有实现",
        'g' to "简化儒略日没有实现",
        'W' to "月内第几周没有实现",
        'w' to "年内第几周没有实现",
        'U' to "周期年名没有实现",
        'r' to "相关格里历年份没有实现",
    )

    /**
     * 从 [start] 起那串 `0` 的长度（1~9）；不是这种写法返回 0。
     *
     * 位数只决定读几位，输出时由小数秒那个字段按字母个数写（见 [fractionText]），所以超出的部分
     * 截掉即可。
     */
    private fun fractionRunLength(pattern: String, start: Int): Int {
        if (start >= pattern.length) return 0
        if (pattern[start] != '0') return 0
        var end = start
        while (end < pattern.length && pattern[end] == '0') end++
        return (end - start).coerceAtMost(MAX_FRACTION_DIGITS)
    }

    /** 四位年足够覆盖到 9999 年；模板写一位 `u` 时也按最多四位读。 */
    private const val YEAR_DIGITS = 4

    /** `.000` 那种写法最多认九位（纳秒正好九位）。 */
    private const val MAX_FRACTION_DIGITS = 9
}

/** 模板记号：一段字面量，或一个带重复次数的字段（`MM` 是「两位月」）。 */
private sealed interface PatternToken {
    data class Literal(val text: String) : PatternToken

    /** [letter] 保留原始字母，位数与输出形状由 `TimestampPattern` 按它决定。 */
    data class Field(val letter: Char, val count: Int) : PatternToken
}
