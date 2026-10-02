package com.qcmian.clipper.devtools.tools.json

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 报错翻译：把库的英文 + 字符下标说成「第几行 第几列 + 中文原因」。
 *
 * 断言一律走**真实报错**（把坏 JSON 喂给 [JsonFormat]），而不是手写一句像样的英文：库的模板一变，
 * 这里就该红，而不是界面悄悄退回英文。
 */
class JsonErrorMessageTest {

    /** 喂一段坏 JSON，拿它的真实报错去翻译。 */
    private fun translate(badJson: String): String {
        val error = JsonFormat.format(badJson).exceptionOrNull()
            ?: error("这段 JSON 应该是坏的：$badJson")
        return jsonErrorMessage(badJson, error)
    }

    @Test
    fun `a missing quotation mark turns into a line and a column`() {
        // 库报的偏移 4 是 `name` 的 n：第 2 行从偏移 2 开始，故列是 4 - 2 + 1。
        val translated = translate("{\n  name: 1\n}")

        assertTrue(
            translated.startsWith("第 2 行 第 3 列：这里应该有一个双引号 \"，实际是 'n'"),
            translated,
        )
    }

    @Test
    fun `the column is counted inside its own line`() {
        // 少了逗号：库报的位置是下一个键的开头——第 3 行从偏移 11 开始，14 - 11 + 1。
        val translated = translate("{\n  \"a\": 1\n  \"b\": 2\n}")

        assertTrue(
            translated.startsWith("第 3 行 第 4 列：一个键值对之后应该是逗号 , 或右花括号 }"),
            translated,
        )
    }

    @Test
    fun `a trailing comma is called out as one`() {
        val translated = translate("""{"a": 1,}""")

        assertTrue(translated.startsWith("第 1 行 第 8 列：结尾多了一个逗号"), translated)
    }

    @Test
    fun `content after a complete value is reported as extra`() {
        // 这条库没给实际字符加引号（`but had e instead`），要单独认。
        val translated = translate("""{"a": "b"}extra""")

        assertTrue(
            translated.startsWith("第 1 行 第 12 列：一个完整的 JSON 值之后还有多余内容：'e'"),
            translated,
        )
    }

    @Test
    fun `a value that runs into the closing brace is reported as missing`() {
        val translated = translate("""{"a": }""")

        assertTrue(
            translated.startsWith("第 1 行 第 7 列：这里应该有一个值，实际碰上了右花括号 }"),
            translated,
        )
    }

    @Test
    fun `the library wording is kept for reporting back`() {
        val translated = translate("{\n  name: 1\n}")

        assertTrue(translated.contains("\n原文：Unexpected JSON token at offset 4:"), translated)
    }

    @Test
    fun `an unknown cause keeps the english but still gives a position`() {
        val translated = jsonErrorMessage(
            source = "{}",
            error = IllegalStateException(
                "Unexpected JSON token at offset 0: something brand new at path: $"
            ),
        )

        assertEquals("第 1 行 第 1 列：something brand new", translated)
    }

    @Test
    fun `a message without an offset is passed through`() {
        val translated = jsonErrorMessage("{}", IllegalStateException("Json {} builder exploded"))

        assertEquals("Json {} builder exploded", translated)
    }
}
