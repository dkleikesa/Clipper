package com.qcmian.clipper.devtools.tools.timestamp

/**
 * 速查表里的一行：一个字段怎么写、是什么意思、长什么样。
 */
internal data class TimestampSyntaxField(
    val meaning: String,
    val spelling: String,
    val example: String,
)

/** 速查表里的一组字段。 */
internal data class TimestampSyntaxGroup(val title: String, val fields: List<TimestampSyntaxField>)

/**
 * 自定义模板支持的全部写法——界面上那页「占位符速查」的内容。
 *
 * 写法**只有一种**：kotlinx-datetime 的 Unicode pattern。原先并排摆着 Java 与 Python 两列，同一个
 * 字段有两种记法，用户得先判断手上这串属于哪种、再回来对列——那正是「两套语法」最费神的地方；
 * 现在只剩一种，就没有这个问题。表里的字母与位数也与它逐个对齐（见 [TimestampPattern] 的
 * `SupportedLengths`），从别处抄来的模板照着对得上。
 *
 * 同一个东西只列一条：年写 `y`（`u` 是 kotlinx-datetime 自己的记法，两者同义，模板里也认，但不
 * 在这张表里再占一行）。
 *
 * 放在这里而不是写在界面的布局代码里，是为了让**写法与实现只隔一个文件**：[TimestampPattern]
 * 的说明直接指向这里，改一个符号时两处不会各改各的。用户照着速查试写法，写错了多半不会再回来
 * 查第二遍，所以那页错一处的代价比别处高。
 */
internal object TimestampSyntax {

    val groups: List<TimestampSyntaxGroup> = listOf(
        TimestampSyntaxGroup(
            title = "日期",
            fields = listOf(
                TimestampSyntaxField("四位年", "yyyy", "2028"),
                TimestampSyntaxField("两位年", "yy", "28"),
                TimestampSyntaxField("月", "MM", "11"),
                TimestampSyntaxField("日", "dd", "22"),
                TimestampSyntaxField("年内第几天", "DDD", "327"),
            ),
        ),
        TimestampSyntaxGroup(
            title = "时间",
            fields = listOf(
                TimestampSyntaxField("24 时制小时", "HH", "10"),
                TimestampSyntaxField("分", "mm", "12"),
                TimestampSyntaxField("秒", "ss", "34"),
                // 位数就是字母个数：`S` 是十分之一秒，`SSS` 才是毫秒。
                TimestampSyntaxField("毫秒", "SSS", "123"),
            ),
        ),
        TimestampSyntaxGroup(
            title = "时区",
            fields = listOf(
                TimestampSyntaxField("时区 ID", "VV", "Asia/Shanghai"),
                TimestampSyntaxField("时区偏移", "Z", "+0800"),
                TimestampSyntaxField("偏移（带冒号）", "ZZZZZ", "+08:00"),
                // 零偏移的写法随字母而变：`X` 那一族写 `Z`，`Z` / `x` 那两族写数字。
                TimestampSyntaxField("偏移（零值写 Z）", "XXX", "+08:00"),
            ),
        ),
    )
}
