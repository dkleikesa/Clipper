package com.qcmian.clipper.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * 分帧的**字节格式**：`4 字节大端长度 + 载荷`。
 *
 * 两侧（JVM 的服务端与原生客户端）用的是不同的 IO 机制，但对字节的解释必须是同一份。
 * 这里没有用户可见的行为面——写歪了不会有编译错误，只会在运行时静默读歪，因此只能对着
 * 字节本身断言。
 */
class CliFramingTest {

    @Test
    fun `the header is a big-endian length prefix`() {
        assertContentEquals(byteArrayOf(0, 0, 0, 1), CliFraming.frameHeader(1))
        // 0x000A0B0C：高位在前。
        assertContentEquals(byteArrayOf(0x00, 0x0A, 0x0B, 0x0C), CliFraming.frameHeader(0x0A0B0C))
    }

    @Test
    fun `a size survives the header round trip`() {
        for (size in listOf(0, 1, 255, 256, 65_535, 65_536, CliFraming.MAX_FRAME_BYTES)) {
            assertEquals(size, CliFraming.frameSize(CliFraming.frameHeader(size)))
        }
    }

    @Test
    fun `a size outside the cap is rejected`() {
        assertFailsWith<IllegalArgumentException> { CliFraming.frameHeader(CliFraming.MAX_FRAME_BYTES + 1) }
        assertFailsWith<IllegalArgumentException> { CliFraming.frameHeader(-1) }
    }

    @Test
    fun `a short header is rejected`() {
        assertFailsWith<IllegalArgumentException> { CliFraming.frameSize(byteArrayOf(0, 0, 1)) }
    }

    @Test
    fun `a header claiming an oversized payload is rejected`() {
        // 0xFFFFFFFF 按有符号 Int 读出来是 -1，落在合法区间之外——不能当成一个巨大的正数。
        assertFailsWith<IllegalArgumentException> { CliFraming.frameSize(byteArrayOf(-1, -1, -1, -1)) }
    }
}
