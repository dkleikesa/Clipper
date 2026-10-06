package com.qcmian.clipper.devtools.tools.regex

/** 速查表里点一下，写法接进哪个框。 */
internal enum class RegexSyntaxField(val title: String) {
    Pattern("模式"),
    Template("替换为"),
}

/** 速查表里的一行：怎么说、怎么写、长什么样。 */
internal data class RegexSyntaxRow(
    val meaning: String,
    /** 点一下接进输入框的那一串。 */
    val spelling: String,
    val example: String,
)

/**
 * 速查表里的一组。
 *
 * @param target 这一组的写法接进哪个框；`null` 表示**整组不可点**——开关是布尔量，不是能拼进模式
 *   里的串，它那一组只负责说明。
 */
internal data class RegexSyntaxGroup(
    val title: String,
    val target: RegexSyntaxField?,
    val rows: List<RegexSyntaxRow>,
)

/**
 * 速查表的内容——界面上那页「速查」画的就是它。
 *
 * 分三组，取舍各不相同：
 *
 *  - **替换模板**：`$1`、`${name}` 这一套。它只在替换页用得上，却最需要查——写法是引擎定的，且没有
 *    别的地方能顺手试出来。**排在最前**就是因为这个：它下面压着二十几行元字符，排在最后的话，
 *    替换页上得先把表滚到底才看得见。
 *  - **开关**：与 [RegexFlag] 一一对应（代码里就是拿它生成的，加一个开关这里自动多一行）。它原先
 *    只活在工具栏那枚开关的悬停提示里，鼠标一移开就没了；摊到表里才查得到。
 *  - **常用写法**：正则的元字符。**刻意不是大全**——`.`、`*`、`+` 这些列出来是噪声，而
 *    `\Q…\E`、`\p{…}`、`(?>…)`、占有量词这些只有部分平台认（与 `RegexRewrite` 同一条口径：凡是
 *    写进工具里的写法，都得是各平台都成立的那些）。所以这里只收「常用 + 想不起来就得查」的写法。
 *
 * 声明顺序就是面板里的顺序（匹配页看不到替换模板那一组，见 `RegexDevTool`）。
 *
 * 写法必须**形状是对的**：接进去之后用户可能还要改（`[...]` 得填内容、`{2,4}` 得改数字），但不能
 * 一接进去就是个语法错的串。判据见 `RegexSyntaxTest`。
 */
internal object RegexSyntax {

    val groups: List<RegexSyntaxGroup> = listOf(
        RegexSyntaxGroup(
            title = "替换模板 · 点一下接进「替换为」",
            target = RegexSyntaxField.Template,
            rows = listOf(
                RegexSyntaxRow("第 1 个捕获组", "\$1", "\$2-\$1"),
                RegexSyntaxRow("命名捕获组", "\${name}", "\${year}年"),
                RegexSyntaxRow("整段匹配", "\$0", "[\$0]"),
                RegexSyntaxRow("字面量「$」", "\\\$", "\\\$5"),
            ),
        ),
        RegexSyntaxGroup(
            title = "开关 · 在上面那排工具栏里拨",
            target = null,
            rows = RegexFlag.entries.map { RegexSyntaxRow(it.title, it.letter.toString(), it.effect) },
        ),
        RegexSyntaxGroup(
            title = "常用写法 · 点一下接进「模式」",
            target = RegexSyntaxField.Pattern,
            rows = listOf(
                RegexSyntaxRow("任意字符", ".", "a.c"),
                RegexSyntaxRow("字符类", "[...]", "[0-9]"),
                RegexSyntaxRow("排除这些字符", "[^...]", "[^0-9]"),
                RegexSyntaxRow("数字", "\\d", "\\d{4}"),
                RegexSyntaxRow("单词字符", "\\w", "\\w+"),
                RegexSyntaxRow("空白", "\\s", "\\s*"),
                RegexSyntaxRow("任意次（含 0 次）", "*", "a*"),
                RegexSyntaxRow("至少一次", "+", "a+"),
                RegexSyntaxRow("可有可无", "?", "a?"),
                RegexSyntaxRow("重复 2 到 4 次", "{2,4}", "\\d{2,4}"),
                RegexSyntaxRow("捕获组", "(...)", "(\\d+)-(\\d+)"),
                RegexSyntaxRow("不捕获的组", "(?:...)", "(?:ab)+"),
                RegexSyntaxRow("命名组", "(?<name>...)", "(?<year>\\d{4})"),
                RegexSyntaxRow("或", "|", "cat|dog"),
                RegexSyntaxRow("行首", "^", "^\\w+"),
                RegexSyntaxRow("行尾", "\$", "\\d+\$"),
                RegexSyntaxRow("单词边界", "\\b", "\\bcat\\b"),
                RegexSyntaxRow("前瞻", "(?=...)", "\\d+(?=元)"),
                RegexSyntaxRow("否定前瞻", "(?!...)", "\\d+(?!元)"),
                RegexSyntaxRow("后顾", "(?<=...)", "(?<=\\$)\\d+"),
                RegexSyntaxRow("否定后顾", "(?<!...)", "(?<!\\$)\\d+"),
                RegexSyntaxRow("引用前面的组", "\\1", "(\\w)\\1"),
            ),
        ),
    )
}
