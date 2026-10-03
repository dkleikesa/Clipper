package com.qcmian.clipper.devtools.tools.timestamp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * 「占位符速查」那份数据的守卫。
 *
 * 那页是给用户照着写的，写错一处多半不会再回来查第二遍——所以至少保证：表里列出的**每一种写法
 * 都能被模板用**（既不是被当成字面量原样回显，也不是写完就报错），而且**示例与实现一致**。
 * 含义那一列是文案，没法断言，只能靠改的时候看。
 */
class TimestampSyntaxTest {

    private val utc = TimeZone.UTC
    private val instant = Instant.parse("2028-11-22T10:12:34.123Z")

    private val fields = TimestampSyntax.groups.flatMap { it.fields }

    @Test
    fun `every spelling is a symbol the template knows`() {
        assertTrue(fields.isNotEmpty(), "速查里至少要列出几个字段")
        fields.forEach { field ->
            assertNull(
                TimestampPattern.problemOf(field.spelling),
                "速查里的 ${field.spelling} 模板不认（实现里多半改了名字）",
            )
            assertTrue(
                TimestampPattern.format(instant, utc, field.spelling) != field.spelling,
                "速查里的 ${field.spelling} 被当成字面量原样输出了",
            )
        }
    }

    @Test
    fun `every example is what the tool actually produces`() {
        // 示例那一列照着一个固定时刻写（2028-11-22 10:12:34.123 +08:00），这里按同一时刻重算一遍。
        // 表里最容易错的其实不是写法而是示例——写错了没人会去核对第二遍。
        val zone = TimeZone.of("Asia/Shanghai")
        val moment = Instant.parse("2028-11-22T02:12:34.123Z")
        fields.forEach { field ->
            assertEquals(
                field.example,
                TimestampPattern.format(moment, zone, field.spelling),
                "速查里 ${field.spelling} 的示例与实现不一致",
            )
        }
    }

    @Test
    fun `every row spells out its columns`() {
        fields.forEach { field ->
            assertTrue(
                field.meaning.isNotBlank() && field.spelling.isNotBlank() && field.example.isNotBlank(),
                "有一行缺列：$field",
            )
        }
    }
}
