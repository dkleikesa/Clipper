package com.qcmian.clipper.devtools.tools.regex

/**
 * 交给引擎的那份模式，外加它与「用户写的那份」之间的下标对应。
 *
 * 为什么需要对应：`x` 会删掉空白与注释、`s` 会把一个 `.` 摊成六个字符，改写之后引擎报的下标已经
 * 对不上用户眼前的模式了。报错必须指**用户写的那个位置**，所以每输出一个字符都记一笔它来自原文
 * 哪里。
 */
internal class PreparedPattern(
    /** 用户写的那份原始模式。 */
    val source: String,
    /** 真正交给引擎的那份。 */
    val text: String,
    /** 改写后的下标（`0..text.length`）→ 原文下标。 */
    private val origin: IntArray,
) {
    /** 改写后的下标 → 原文下标；越界时夹到两端（引擎爱把「模式末尾」报成 `text.length`）。 */
    fun sourceIndexAt(index: Int): Int = origin[index.coerceIn(0, origin.lastIndex)]
}

/**
 * 按标志位把模式改写成「引擎只需要认识 `i` 与 `m` 就能照样执行」的样子。
 *
 * 只有 [RegexFlag.DotAll] 与 [RegexFlag.Comments] 会真的改写：
 *  - `s`：字符类之外、没被转义的 `.` 换成 `[\s\S]`；
 *  - `x`：字符类之外、没被转义的空白，以及 `#` 到行尾的注释，删掉。
 *
 * 这两个为什么不交给引擎的 `RegexOption`：`COMMENTS` 与 `DOT_MATCHES_ALL` 在 JS 上根本没有
 * （`RegexOption` 是 expect/actual，只有 `IGNORE_CASE` 与 `MULTILINE` 每个平台都认）。而本地只编
 * jvm target 时看不出来——引用它们照样编得过，等哪天加了别的 target 才会在编译期炸掉。自己改写
 * 之后语义由我们定义、到处一致，代价是这一层下标映射。
 *
 * 转义与字符类里的东西一律原样搬：`\.`、`[.]`、`[ a]`、`\Q.\E` 都不该被这两个标志位碰到。
 *
 * **`x` 与引擎自带的 `(?x)` / `Pattern.COMMENTS` 是两回事**，这一点是刻意的：JDK 那一个会把字符类
 * 里的空白也忽略掉（`[ a]` 不再匹配空格），连 `[#]` 都因为 `#]` 被当成注释而**编译不过**。我们
 * 既不开那个选项（引擎看到的是改写后的模式），也不照抄它的脾气：字符类里的一切照旧。用户要是自己
 * 在模式里写 `(?x)`，走的仍是引擎那套——那是他的选择，不是这个开关的语义。
 */
internal fun rewritePattern(source: String, flags: Set<RegexFlag>): PreparedPattern {
    val dotAll = RegexFlag.DotAll in flags
    val comments = RegexFlag.Comments in flags
    if (!dotAll && !comments) {
        // 一个字都不用改：下标对应就是恒等映射。
        return PreparedPattern(source, source, IntArray(source.length + 1) { it })
    }

    val out = StringBuilder(source.length)
    // 每个输出字符记一笔它来自原文哪里；末尾再补一格，代表「模式末尾」。
    val origin = ArrayList<Int>(source.length + 1)

    var i = 0
    var inClass = false
    // 紧跟 `[`（或 `[^`）的那个 `]` 是普通字符，不是类的结尾；记下位置才判得出来。
    var classStart = 0
    while (i < source.length) {
        val c = source[i]
        when {
            // `\Q…\E` 之间整段都是字面量，一起搬——里面出现 `#` 或空白也不算注释与空白。
            c == '\\' && source.getOrNull(i + 1) == 'Q' -> {
                val end = source.indexOf("\\E", i + 2)
                val stop = if (end < 0) source.length else end + 2
                out.append(source, i, stop)
                repeat(stop - i) { origin.add(i) }
                i = stop
            }

            // 其余转义：连同后一个字符原样搬（`\ ` 是被转义的空格，`\.` 是字面量点）。
            c == '\\' -> {
                val stop = minOf(i + 2, source.length)
                out.append(source, i, stop)
                repeat(stop - i) { origin.add(i) }
                i = stop
            }

            inClass -> {
                if (c == ']' && i > classStart) inClass = false
                out.append(c)
                origin.add(i)
                i++
            }

            c == '[' -> {
                inClass = true
                classStart = if (source.getOrNull(i + 1) == '^') i + 2 else i + 1
                out.append(c)
                origin.add(i)
                i++
            }

            comments && c == '#' -> {
                val end = source.indexOf('\n', i)
                i = if (end < 0) source.length else end
            }

            comments && c.isWhitespace() -> i++

            dotAll && c == '.' -> {
                out.append("[\\s\\S]")
                repeat(6) { origin.add(i) }
                i++
            }

            else -> {
                out.append(c)
                origin.add(i)
                i++
            }
        }
    }
    origin.add(source.length)
    return PreparedPattern(source, out.toString(), origin.toIntArray())
}
