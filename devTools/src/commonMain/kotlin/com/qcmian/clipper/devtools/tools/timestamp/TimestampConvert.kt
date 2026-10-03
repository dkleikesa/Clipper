package com.qcmian.clipper.devtools.tools.timestamp

import kotlin.math.abs
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

/**
 * 一次解析的结果：要么落到一个确定的时间点，要么说明为什么落不下来。
 *
 * 用密封接口而不是 `Result<Instant>`：这里的失败是**常规结局**（用户随手粘了一句话进来），
 * 附一句人话比带一个异常栈有用；调用方也不必去 `exceptionOrNull()` 里翻类型。
 */
internal sealed interface TimestampParse {
    data class Success(val instant: Instant) : TimestampParse

    data class Failure(val reason: String) : TimestampParse
}

/**
 * 结果里的一行：一个标签配一个值。
 *
 * [primary] 标出「这次转换最想要的那两个数」——秒级与毫秒级时间戳。界面据此给它们上主色，
 * 让眼睛先落在真正要拷走的东西上，而不是本地时间、星期这些附带信息。
 */
internal data class TimestampField(
    val label: String,
    val value: String,
    val primary: Boolean = false,
)

/**
 * 时间戳转换的全部逻辑：解析输入、把时间点写成各种写法。
 *
 * 与 `JsonFormat` / `MathFormat` 同一分工——纯函数、不碰 UI，既能被工具界面直接调用、也能被
 * 单测直接验。时间点一律用 [Instant]（一个绝对时刻）表示，**不预设时区**：同一个时刻在不同
 * 时区下是不同的一串日期，时区是「怎么写」而不是「是哪一刻」，所以它只出现在 [fields] 的入参里。
 */
internal object TimestampConvert {

    /**
     * 秒与毫秒的分界线（1e11）：绝对值到了 11 位就按毫秒解释。
     *
     * 判据不是「位数」而是「数值」——1e11 秒是公元 5138 年，真实的时间戳不会到；而 1e11 毫秒
     * 是 1973 年，作为毫秒完全合理。于是同一串数字（例如 13 位）不会再按长度去猜。
     */
    private const val MillisThreshold = 100_000_000_000L

    private const val SecondsPerDay = 86_400L

    /**
     * 星期一为 0，与 [weekdayIndex] 的输出对应。
     *
     * 内部可见：自定义模板（`TimestampPattern` 的 `E` 符号）要按同一套名字输出星期、解析时还得
     * 照着它反查——两边共用一份，改一种写法不会漏掉另一处。
     */
    val WeekdayNames =
        listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")

    /** 当前时刻。「当前时间」按钮与「相对现在」都用它，从而只有一处读系统时钟。 */
    fun now(): Instant = Clock.System.now()

    /** 这个时刻是星期几，如「星期四」。固定结果行与自定义模板共用它。 */
    fun weekdayName(instant: Instant): String = WeekdayNames[weekdayIndex(instant)]

    /**
     * 这段文本像不像一个 Unix 时间戳。
     *
     * 给探测器用，因此判据**收紧**：只认 10~13 位整数。太短（如 `123`）多半是别的数，太长
     * （如 16 位）也不是常见时间戳——探测跑在打开面板的同步路径上，宁可漏判、不可误判。
     * 用户**主动**粘进来的数字则由 [parse] 宽松接受，那里不受这条限制。
     */
    fun isEpochNumber(text: String): Boolean {
        val body = text.trim().removePrefix("-").removePrefix("+")
        return body.length in 10..13 && body.all { it in '0'..'9' }
    }

    /**
     * 把一段文本解析成一个时刻。
     *
     * 先按时间戳数字试，再按日期时间字符串试——顺序不能反：一串纯数字（`20240101`）两种解读
     * 都成立，而用户从剪贴板粘一个时间戳进来是这里最主流的用法。
     *
     * @param zone 解析**不带时区**的日期时间时，按哪个时区解释它（默认本机时区）。带偏移的
     *   ISO 8601（`...Z` / `...+08:00`）不受它影响。
     */
    fun parse(text: String, zone: TimeZone = TimeZone.currentSystemDefault()): TimestampParse {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return TimestampParse.Failure("请输入时间戳或日期时间")
        parseEpochNumber(trimmed)?.let { return TimestampParse.Success(it) }
        parseDateTime(trimmed, zone)?.let { return TimestampParse.Success(it) }
        return TimestampParse.Failure("认不出这是时间戳还是日期时间")
    }

    /**
     * 把这个时刻写成若干种常见写法，供结果面板逐行展示。
     *
     * @param zone 算「本地时间」与相对时间时用的时区（默认本机时区）。UTC 那一行永远是 UTC，
     *   与它无关。
     * @param now 「相对现在」的参照点。做成入参而不是内部读时钟，是为了让这一列可测——否则
     *   测试只能断言「大概是几分钟前」。
     */
    fun fields(
        instant: Instant,
        zone: TimeZone = TimeZone.currentSystemDefault(),
        now: Instant = Clock.System.now(),
    ): List<TimestampField> {
        val local = instant.toLocalDateTime(zone)
        val utc = instant.toLocalDateTime(TimeZone.UTC)
        return listOf(
            TimestampField("秒级时间戳", instant.epochSeconds.toString(), primary = true),
            TimestampField("毫秒级时间戳", instant.toEpochMilliseconds().toString(), primary = true),
            TimestampField("本地时间", "${formatDateTime(local)} (${zone.id})"),
            TimestampField("UTC 时间", formatDateTime(utc)),
            TimestampField("ISO 8601", formatIso(utc)),
            TimestampField("星期", weekdayName(instant)),
            TimestampField("相对现在", relative(now, instant)),
        )
    }

    /**
     * 一串纯数字 → 时刻；不是纯数字返回 `null`。
     *
     * 这里**不**限制位数：用户主动输入的 `0`、`86400` 也该转换，哪怕它不像日常时间戳。
     */
    private fun parseEpochNumber(text: String): Instant? {
        val body = text.removePrefix("-").removePrefix("+")
        if (body.isEmpty() || body.any { it !in '0'..'9' }) return null
        val value = text.toLongOrNull() ?: return null
        return if (abs(value) >= MillisThreshold) {
            Instant.fromEpochMilliseconds(value)
        } else {
            Instant.fromEpochSeconds(value)
        }
    }

    /**
     * 日期时间字符串 → 时刻；认不出来返回 `null`。
     *
     * 依次试：
     *  1. 带时区偏移的 ISO 8601（`2024-01-01T12:00:00Z`、`+08:00`）——直接落到一个绝对时刻，
     *     与 [zone] 无关；
     *  2. **ISO 变体**（见 [parseIsoLike]）：空格 / `T` 分隔、`/` 或 `:` 拼的日期（`2026/10/03`、
     *     EXIF 的 `2026:10:03 14:30:00`）、时间用 `-` 分隔（`10-12-34`），以及**纯日期**；
     *  3. 互联网与日志里常见的标准写法（RFC 1123 / 5322、asctime、ISO 基本格式），见
     *     [TimestampStandards]。
     *
     * 都用 `runCatching` 兜住：`parse` 抛的是 `IllegalArgumentException`，在这一层它就是
     * 「不是这个格式」。
     */
    private fun parseDateTime(text: String, zone: TimeZone): Instant? {
        runCatching { Instant.parse(text) }.getOrNull()?.let { return it }
        parseIsoLike(text, zone)?.let { return it }
        return TimestampStandards.parse(text, zone)
    }

    /**
     * ISO 的几种变体写法 → 时刻；认不出来返回 `null`。
     *
     * 先按第一个 `T` / 空格把日期与时间分开（没有分隔符就是**纯日期**，按当天 00:00）：
     *  - **日期**里的 `/` 与 `:` 一律当 `-`（`2026/10/03`、EXIF 的 `2026:10:03`）；
     *  - **时间**里的 `-` 当 `:`（`2028-11-22 10-12-34`）。
     *
     * 只换各自的符号、不整串替换：日期本身也用 `-` 拼，整串替换会把日期拆坏。
     */
    private fun parseIsoLike(text: String, zone: TimeZone): Instant? {
        val separator = text.indexOfFirst { it == 'T' || it == 't' || it == ' ' }
        if (separator < 0) {
            val date = runCatching { LocalDate.parse(normalizeDateSeparators(text)) }.getOrNull()
                ?: return null
            return runCatching {
                LocalDateTime(date.year, date.month.ordinal + 1, date.day, 0, 0, 0).toInstant(zone)
            }.getOrNull()
        }
        val date = normalizeDateSeparators(text.substring(0, separator))
        val time = text.substring(separator + 1).replace('-', ':')
        return runCatching { LocalDateTime.parse("${date}T$time").toInstant(zone) }.getOrNull()
    }

    /** 日期里的 `/` 与 `:` 一律当 `-`。 */
    private fun normalizeDateSeparators(date: String): String = date.replace('/', '-').replace(':', '-')

    /** `2026-10-03 14:30:00`；带毫秒时写成 `2026-10-03 14:30:00.123`。commonMain 没有 `String.format`，补零只能自己来。 */
    private fun formatDateTime(local: LocalDateTime): String =
        "${local.year.toString().padStart(4, '0')}-${pad(local.month.ordinal + 1)}-${pad(local.day)} " +
            "${pad(local.hour)}:${pad(local.minute)}:${pad(local.second)}${fraction(local)}"

    /** `2026-10-03T06:30:00Z`；带毫秒时写成 `2026-10-03T06:30:00.123Z`。 */
    private fun formatIso(utc: LocalDateTime): String =
        "${utc.year.toString().padStart(4, '0')}-${pad(utc.month.ordinal + 1)}-${pad(utc.day)}" +
            "T${pad(utc.hour)}:${pad(utc.minute)}:${pad(utc.second)}${fraction(utc)}Z"

    private fun pad(value: Int): String = value.toString().padStart(2, '0')

    /**
     * 秒以下那一段，`.123` 的形式；整秒时为空串。
     *
     * 只写到毫秒三位——这个工具服务的是 Unix 时间戳，毫秒是它常见的最小刻度。
     * **整秒不补 `.000`**：秒级时间戳本来就没有毫秒可取，硬补一串 0 反而像是哪里取错了；
     * 只有真正带毫秒时（例如输入 13 位毫秒时间戳）才写出来，一眼就能看出精度到哪儿。
     */
    private fun fraction(local: LocalDateTime): String =
        if (local.nanosecond == 0) "" else "." + (local.nanosecond / 1_000_000).toString().padStart(3, '0')

    /**
     * 这个时刻是星期几，星期一为 0。
     *
     * 由「距离 1970-01-01 的天数」直接推，而不是读 `LocalDateTime.dayOfWeek`：那个枚举的类型
     * 归属在 kotlinx-datetime 各版本间搬过家，而这里只是模 7 的算术，不值得为此绑一个枚举。
     * 1970-01-01 是星期四，因此偏移 3。
     */
    private fun weekdayIndex(instant: Instant): Int =
        (floorDiv(instant.epochSeconds, SecondsPerDay) + 3).mod(7L).toInt()

    /** 向下取整的整除：`epochSeconds` 为负（1970 年之前）时，普通 `/` 会朝 0 取整而算错一天。 */
    private fun floorDiv(value: Long, divisor: Long): Long {
        val quotient = value / divisor
        return if (value % divisor != 0L && (value < 0) != (divisor < 0)) quotient - 1 else quotient
    }

    /**
     * 把「现在到那一刻的差」写成一句人话，如 `3 小时前`。
     *
     * 只取一个粒度，不做「1 小时 20 分钟前」那种拼接：这一行的用途是让人**扫一眼**知道远近，
     * 精确值就在上面几行里。
     */
    private fun relative(now: Instant, instant: Instant): String {
        val seconds = now.epochSeconds - instant.epochSeconds
        val magnitude = abs(seconds)
        val phrase = when {
            magnitude < 60 -> return "刚刚"
            magnitude < 3_600 -> "${magnitude / 60} 分钟"
            magnitude < SecondsPerDay -> "${magnitude / 3_600} 小时"
            magnitude < SecondsPerDay * 30 -> "${magnitude / SecondsPerDay} 天"
            magnitude < SecondsPerDay * 365 -> "${magnitude / (SecondsPerDay * 30)} 个月"
            else -> "${magnitude / (SecondsPerDay * 365)} 年"
        }
        // 「前 / 后」紧贴单位：中文里写「1 小时前」，「1 小时 前」会多出一个空格。
        return if (seconds >= 0) "${phrase}前" else "${phrase}后"
    }
}
