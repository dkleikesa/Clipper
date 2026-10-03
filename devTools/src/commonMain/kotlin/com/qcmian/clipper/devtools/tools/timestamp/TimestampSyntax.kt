package com.qcmian.clipper.devtools.tools.timestamp

/**
 * 速查表里的一行：一个字段在**两种风格**里各怎么写。
 *
 * 两种写法并排放，而不是分成两张表：它们对的是同一个字段，摆在一起才看得出「`%m` 是月、`%M` 是
 * 分」这种最容易记混的地方——分成两节，用户反而要来回对。
 */
internal data class TimestampSyntaxField(
    val meaning: String,
    val java: String,
    /** Python 那边没有对应写法时留 `null`，界面上显示成一道「—」。 */
    val python: String?,
    val example: String,
)

/** 速查表里的一组字段。 */
internal data class TimestampSyntaxGroup(val title: String, val fields: List<TimestampSyntaxField>)

/**
 * 自定义模板支持的全部写法——界面上那页「占位符速查」的内容。
 *
 * 放在这里而不是写在界面的布局代码里，是为了让**写法与实现只隔一个文件**：`TimestampPattern`
 * 的说明直接指向这里，改一个符号时两处不会各改各的。用户照着速查试写法，写错了多半不会再回来
 * 查第二遍，所以那页错一处的代价比别处高。
 */
internal object TimestampSyntax {

    val groups: List<TimestampSyntaxGroup> = listOf(
        TimestampSyntaxGroup(
            title = "日期",
            fields = listOf(
                TimestampSyntaxField("四位年", "yyyy", "%Y", "2028"),
                TimestampSyntaxField("两位年", "yy", "%y", "28"),
                TimestampSyntaxField("月", "MM", "%m", "11"),
                TimestampSyntaxField("日", "dd", "%d", "22"),
                TimestampSyntaxField("月份缩写", "MMM", "%b", "11月"),
                TimestampSyntaxField("月份全称", "MMMM", "%B", "十一月"),
            ),
        ),
        TimestampSyntaxGroup(
            title = "时间",
            fields = listOf(
                TimestampSyntaxField("24 时制小时", "HH", "%H", "10"),
                TimestampSyntaxField("12 时制小时", "hh", "%I", "10"),
                TimestampSyntaxField("分", "mm", "%M", "12"),
                TimestampSyntaxField("秒", "ss", "%S", "34"),
                // Java 的 `S`（一个字母）是「最少位数」的一个数字，123 毫秒会写成 1；毫秒要写三个。
                TimestampSyntaxField("毫秒", "SSS", "%f", "123"),
                TimestampSyntaxField("上午 / 下午", "a", "%p", "上午"),
            ),
        ),
        TimestampSyntaxGroup(
            title = "其它",
            fields = listOf(
                TimestampSyntaxField("星期缩写", "EEE", "%a", "周三"),
                TimestampSyntaxField("星期全称", "EEEE", "%A", "星期三"),
                TimestampSyntaxField("时区偏移", "Z", "%z", "+0800"),
                // Python 的 `%z` 只能写成 `+0800` 那种，带冒号的没有对应写法。
                TimestampSyntaxField("时区偏移（带冒号）", "ZZ", null, "+08:00"),
                TimestampSyntaxField("时区名", "z", "%Z", "Asia/Shanghai"),
            ),
        ),
    )
}
