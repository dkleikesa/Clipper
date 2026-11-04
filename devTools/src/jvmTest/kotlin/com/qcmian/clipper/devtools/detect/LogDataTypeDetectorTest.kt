package com.qcmian.clipper.devtools.detect

import com.qcmian.clipper.devtools.api.DataTypes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 日志探测：判据是**行头**（时间戳 + 级别、logcat 行头），不是内容里出现 "error"。
 *
 * 误判的代价比漏判大——「打开任何一段带 error 字样的文字都跳进日志工具」比「日志没被自动选中」
 * 烦人得多，用户手工点一下侧边栏即可。
 */
class LogDataTypeDetectorTest {

    private val logcat = """
        08-14 12:00:00.123  1234  5678 I ActivityManager: start proc
        08-14 12:00:00.456  1234  5678 E AndroidRuntime: FATAL EXCEPTION: main
        java.lang.IllegalStateException: boom
        	at com.example.Foo.bar(Foo.kt:12)
        08-14 12:00:01.000  1234  5678 W Choreographer: Skipped 42 frames
    """.trimIndent()

    private val timestamped = """
        2024-08-14 12:00:00.123 INFO  [main] com.example.App - starting
        2024-08-14 12:00:00.456 WARN  [worker-1] com.example.Pool - queue full
        2024-08-14 12:00:01.789 ERROR [worker-1] com.example.Job - failed
    """.trimIndent()

    @Test
    fun `logcat 与时间戳加级别都认`() {
        assertTrue(LogDataTypeDetector.matches(logcat))
        assertTrue(LogDataTypeDetector.matches(timestamped))
        assertEquals(DataTypes.LOG, LogDataTypeDetector.typeName)
    }

    @Test
    fun `普通多行文本不算日志`() {
        assertFalse(
            LogDataTypeDetector.matches(
                """
                这是一段普通的说明文字。
                它有好几行，但没有任何行头。
                第三行也只是普通的话。
                """.trimIndent()
            )
        )
    }

    @Test
    fun `代码与 JSON 不算日志`() {
        assertFalse(
            LogDataTypeDetector.matches(
                """
                fun main() {
                    println("hello")
                    println("world")
                }
                """.trimIndent()
            )
        )
        assertFalse(
            LogDataTypeDetector.matches(
                """
                {
                  "level": "error",
                  "message": "boom"
                }
                """.trimIndent()
            )
        )
    }

    @Test
    fun `单行不算日志`() {
        assertFalse(LogDataTypeDetector.matches("2024-08-14 12:00:00 ERROR something failed"))
    }
}
