package com.qcmian.clipper.devtools.tools.hex

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 二进制查看的版面：偏移量、十六进制与字符三列。
 *
 * 期望值是**照着 `hexdump -C` 的版面手写的**，不是「跑出来就这样」的回归值——空格落在哪里、组间
 * 空几格、末行怎么补，这些正是要盯死的东西，跟着实现抄一遍就白测了。
 */
class HexFormatTest {

    private fun dump(
        bytes: ByteArray,
        bytesPerRow: Int = 16,
        uppercase: Boolean = false,
        charColumn: Boolean = true,
        maxBytes: Int = HexFormat.MaxDumpBytes,
    ) = HexFormat.dump(bytes, bytesPerRow, uppercase, charColumn, maxBytes)

    @Test
    fun `一行是偏移量、十六进制与字符三列`() {
        val row = dump("Hello".encodeToByteArray()).text
        // 偏移量 + 两空格；五个字节各「xx 」；其余十一格用空格补齐（其中第 8 字节后多空一格）；
        // 字符列把「Hello」左对齐、后面补到 16 格，两侧用竖线框住。
        val expected = "00000000  48 65 6c 6c 6f " +
            "   ".repeat(3) + " " + "   ".repeat(8) +
            " |Hello" + " ".repeat(11) + "|"
        assertEquals(expected, row)
    }

    @Test
    fun `每 8 字节之间多空一格`() {
        // 九个字节：第 8 个（下标 7）之后要空出一格再排第 9 个。
        val row = dump(ByteArray(9) { it.toByte() }).text.split('\n').first()
        assertTrue(row.contains("07  08 "), "第 8 字节后应多空一格，实际是：$row")
    }

    @Test
    fun `偏移量按行累加，第二行从 0x10 起`() {
        val lines = dump(ByteArray(17) { it.toByte() }).text.split('\n')
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("00000010  "))
    }

    @Test
    fun `不可打印的字节在字符列里显示成点`() {
        val bytes = byteArrayOf(0x00, 0x41, 0x0A, 0x7F, 0x80.toByte(), 0xFF.toByte())
        val chars = dump(bytes).text.substringAfter('|').substringBefore('|')
        // 0x41 是 'A'，其余五个都不可打印——补空格补到整行宽度。
        assertEquals(".A" + ".".repeat(4) + " ".repeat(10), chars)
    }

    @Test
    fun `大写同时作用于偏移量与字节`() {
        assertTrue(dump(byteArrayOf(0xAB.toByte()), uppercase = true).text.startsWith("00000000  AB "))
        // 十一行、末行偏移 0xA0：大写档的偏移量字母也该是大写。
        assertTrue(dump(ByteArray(176), uppercase = true).text.contains("000000A0  "))
        assertTrue(dump(ByteArray(176), uppercase = false).text.contains("000000a0  "))
    }

    @Test
    fun `关掉字符列后不再有竖线与字符`() {
        val row = dump("Hi".encodeToByteArray(), charColumn = false).text
        assertFalse(row.contains('|'))
        assertFalse(row.contains('H'))
    }

    @Test
    fun `每行字节数可调，偏移量按行宽推进`() {
        assertEquals(1, dump(ByteArray(8), bytesPerRow = 8).text.split('\n').size)
        // 33 个字节排 32 一行：两行，第二行从 0x20 起。
        val lines = dump(ByteArray(33), bytesPerRow = 32).text.split('\n')
        assertEquals(2, lines.size)
        assertTrue(lines[1].startsWith("00000020  "))
    }

    @Test
    fun `超过上限只排前一段，并如实报告总量`() {
        val result = dump(ByteArray(100) { it.toByte() }, maxBytes = 32)
        assertEquals(100, result.totalBytes)
        assertEquals(32, result.shownBytes)
        assertEquals(2, result.rows)
        assertTrue(result.truncated)
    }

    @Test
    fun `没超过上限时不算截断`() {
        val result = dump(ByteArray(16))
        assertFalse(result.truncated)
        assertEquals(16, result.shownBytes)
        assertEquals(1, result.rows)
    }

    @Test
    fun `空输入排出来是空的`() {
        val result = dump(ByteArray(0))
        assertEquals("", result.text)
        assertEquals(0, result.rows)
        assertFalse(result.truncated)
    }

    @Test
    fun `字符列只认可打印的 ASCII`() {
        assertEquals(' ', HexFormat.textChar(0x20))
        assertEquals('~', HexFormat.textChar(0x7E))
        assertEquals('.', HexFormat.textChar(0x1F))
        assertEquals('.', HexFormat.textChar(0x7F))
        assertEquals('.', HexFormat.textChar(0xFF.toByte()))
    }
}
