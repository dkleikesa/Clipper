package com.qcmian.clipper.devtools.tools.timestamp

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * 「占位符速查」那份数据的守卫。
 *
 * 那页是给用户照着写的，写错一处多半不会被回来查第二遍——所以至少保证：**列出来的每个字段符号
 * 都能被模板认出来**（不是被当成字面量原样回显）。含义与示例是文案，没法断言，只能靠改的时候看。
 */
class TimestampSyntaxTest {

    private val utc = TimeZone.UTC
    private val instant = Instant.parse("2028-11-22T10:12:34.123Z")

    /** 只由字段字母组成的写法；带引号、中文、`%`、空格的那些属于「写法要点」，不在此列。 */
    private val plainSymbol = Regex("^[yMdhHmsSaEZz]+$")

    @Test
    fun `every field symbol is understood by the template`() {
        val symbols = TimestampSyntax.sections
            .flatMap { it.rows }
            .map { it.symbol }
            .filter { plainSymbol.matches(it) }

        assertTrue(symbols.isNotEmpty(), "速查里至少要列出几个字段符号")
        symbols.forEach { symbol ->
            assertTrue(
                TimestampPattern.format(instant, utc, symbol) != symbol,
                "速查列了 $symbol，但模板不认它（实现里多半改了名字）",
            )
        }
    }

    @Test
    fun `every row spells out all three columns`() {
        TimestampSyntax.sections.forEach { section ->
            assertTrue(section.title.isNotBlank(), "有一节没写标题")
            assertTrue(section.rows.isNotEmpty(), "「${section.title}」是空节")
            section.rows.forEach { row ->
                assertTrue(
                    row.symbol.isNotBlank() && row.meaning.isNotBlank() && row.example.isNotBlank(),
                    "「${section.title}」里有一行缺列：$row",
                )
            }
        }
    }
}
