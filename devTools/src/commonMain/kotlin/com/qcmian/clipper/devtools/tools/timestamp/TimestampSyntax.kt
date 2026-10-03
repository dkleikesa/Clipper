package com.qcmian.clipper.devtools.tools.timestamp

/** 「占位符速查」里的一行：怎么写、什么含义、长什么样。 */
internal data class TimestampSyntaxRow(
    val symbol: String,
    val meaning: String,
    val example: String,
)

/** 「占位符速查」里的一节。 */
internal data class TimestampSyntaxSection(
    val title: String,
    val rows: List<TimestampSyntaxRow>,
)

/**
 * 自定义模板支持的全部写法——界面上那页「占位符速查」的内容。
 *
 * 放在这里而不是写在界面的布局代码里，是为了让**写法与实现只隔一个文件**：`TimestampPattern`
 * 的说明直接指向这里，改一个符号时两处不会各改各的。用户照着速查试写法，写错了多半不会再回来
 * 查第二遍，所以那页错一处的代价比别处高。
 *
 * 分组只按用户的心智来（日期 / 时间 / 其它 / 写法要点），不按 [TimestampPattern] 里的字段枚举——
 * 枚举是实现的分类，这里要的是「我想找的那个字段在哪一行」。
 */
internal object TimestampSyntax {

    val sections: List<TimestampSyntaxSection> = listOf(
        TimestampSyntaxSection(
            title = "日期",
            rows = listOf(
                TimestampSyntaxRow("yyyy", "四位年", "2028"),
                TimestampSyntaxRow("yy", "两位年", "28"),
                TimestampSyntaxRow("MM", "月", "11"),
                TimestampSyntaxRow("dd", "日", "22"),
            ),
        ),
        TimestampSyntaxSection(
            title = "时间",
            rows = listOf(
                TimestampSyntaxRow("HH", "24 时制小时", "10"),
                TimestampSyntaxRow("hh", "12 时制小时，配 a 用", "10"),
                TimestampSyntaxRow("mm", "分", "12"),
                TimestampSyntaxRow("ss", "秒", "34"),
                TimestampSyntaxRow("S", "毫秒，输出固定三位", "123"),
                TimestampSyntaxRow("a", "上午 / 下午", "上午"),
            ),
        ),
        TimestampSyntaxSection(
            title = "其它",
            rows = listOf(
                TimestampSyntaxRow("E", "星期", "星期三"),
                TimestampSyntaxRow("Z", "时区偏移，无冒号", "+0800"),
                TimestampSyntaxRow("ZZ", "时区偏移", "+08:00"),
                TimestampSyntaxRow("z", "时区名", "Asia/Shanghai"),
            ),
        ),
        TimestampSyntaxSection(
            title = "写法要点",
            rows = listOf(
                TimestampSyntaxRow(
                    "yyyy年MM月dd日",
                    "别的字符按字面量原样出现",
                    "2028年11月22日",
                ),
                TimestampSyntaxRow(
                    "'T'",
                    "要写字面字母就用单引号括起来",
                    "2028-11-22T10:12:34",
                ),
                TimestampSyntaxRow(
                    ".000",
                    "秒之后的点加一串 0 也当毫秒",
                    "10:12:34.123",
                ),
                TimestampSyntaxRow(
                    "%Y-%m-%d %H:%M:%S",
                    "也接受 Python strftime 的写法",
                    "2028-11-22 10:12:34",
                ),
                TimestampSyntaxRow(
                    "Z / z",
                    "只用于输出，不能拿来解析输入",
                    "+08:00",
                ),
            ),
        ),
    )
}
