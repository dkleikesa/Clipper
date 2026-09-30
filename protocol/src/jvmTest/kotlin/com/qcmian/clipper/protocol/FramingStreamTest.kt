package com.qcmian.clipper.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * 流上的分帧读写（JVM 侧的那一半）。
 *
 * 格式的**真值源**是 commonMain 的 [CliFraming]；这里验证的是「凑够 N 个字节」这一步：
 * 多条消息能依次读出、空载荷能往返、对端提前关闭不会给出半条消息。
 */
class FramingStreamTest {

    @Test
    fun `several frames written to a stream are read back in order`() {
        val out = ByteArrayOutputStream()
        val first = "hello".encodeToByteArray()
        val second = ByteArray(0)
        out.writeCliFrame(first)
        out.writeCliFrame(second)

        val input = ByteArrayInputStream(out.toByteArray())
        assertContentEquals(first, input.readCliFrame())
        assertContentEquals(second, input.readCliFrame())
        assertNull(input.readCliFrame(), "对端正常关闭 → null，而不是空数组")
    }

    @Test
    fun `a truncated payload reads as null instead of a partial frame`() {
        // 声明 10 字节，实际只给 3 字节。
        val bytes = CliFraming.frameHeader(10) + byteArrayOf(1, 2, 3)
        assertNull(ByteArrayInputStream(bytes).readCliFrame())
    }
}
