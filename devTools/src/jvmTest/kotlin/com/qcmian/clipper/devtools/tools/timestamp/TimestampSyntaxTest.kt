package com.qcmian.clipper.devtools.tools.timestamp

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * 「占位符速查」那份数据的守卫。
 *
 * 那页是给用户照着写的，写错一处多半不会被回来查第二遍——所以至少保证：两列里列出的**每一种写法
 * 都能被模板认出来**（不是被当成字面量原样回显）。含义与示例是文案，没法断言，只能靠改的时候看。
 */
class TimestampSyntaxTest {

    private val utc = TimeZone.UTC
    private val instant = Instant.parse("2028-11-22T10:12:34.123Z")

    private val fields = TimestampSyntax.groups.flatMap { it.fields }

    @Test
    fun `every java symbol is a field the template knows`() {
        assertTrue(fields.isNotEmpty(), "速查里至少要列出几个字段")
        fields.forEach { field ->
            assertTrue(
                TimestampPattern.format(instant, utc, field.java) != field.java,
                "速查的 Java 列写了 ${field.java}，但模板不认它（实现里多半改了名字）",
            )
        }
    }

    @Test
    fun `every python spec is a field the template knows`() {
        val specs = fields.mapNotNull { it.python }
        assertTrue(specs.isNotEmpty(), "速查里至少要列出一条 Python 写法")
        specs.forEach { spec ->
            assertTrue(
                TimestampPattern.format(instant, utc, spec) != spec,
                "速查的 Python 列写了 $spec，但模板不认它（多半是 pythonField 里漏了、或名字写错了）",
            )
        }
    }

    @Test
    fun `every row spells out its columns`() {
        fields.forEach { field ->
            assertTrue(
                field.meaning.isNotBlank() && field.java.isNotBlank() && field.example.isNotBlank(),
                "有一行缺列：$field",
            )
        }
    }
}
