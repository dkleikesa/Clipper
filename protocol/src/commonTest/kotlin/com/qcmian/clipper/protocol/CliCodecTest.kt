package com.qcmian.clipper.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 线上 JSON 契约：几处配置都是为「两个可能不同版本的产物互相对话」定的，因此值得钉死——
 * 它们没有用户可见的行为面，改坏了只表现为「某个版本读不懂对面」。
 */
class CliCodecTest {

    @Serializable
    private data class Sample(val a: Int, val b: String? = null)

    @Test
    fun `encoding omits default and null fields`() {
        // encodeDefaults = false 且 explicitNulls = false：只留下真正带信息的字段。
        assertEquals("""{"cmd":"ping"}""", text(CliCodec.encodeRequest(CliRequest(cmd = CliCommand.PING))))
    }

    @Test
    fun `a failure envelope carries code and message only`() {
        val response = CliCodec.failure(CliErrorCode.NOT_FOUND, "no such id")
        assertEquals(
            """{"ok":false,"error":{"code":"NOT_FOUND","message":"no such id"}}""",
            text(CliCodec.encodeResponse(response)),
        )
    }

    @Test
    fun `a request survives a round trip with every field set`() {
        val request = CliRequest(
            cmd = CliCommand.LIST,
            kind = "image",
            sort = "copies",
            order = "asc",
            pinned = true,
            limit = 5,
        )
        assertEquals(request, CliCodec.decodeRequest(CliCodec.encodeRequest(request)))
    }

    @Test
    fun `unknown fields from a newer peer are ignored rather than fatal`() {
        val request = CliCodec.decodeRequest(
            """{"cmd":"search","v":99,"fromTheFuture":true,"query":"x"}""".encodeToByteArray(),
        )
        assertEquals(CliCommand.SEARCH, request.cmd)
        assertEquals(99, request.v, "版本号要原样读到，供双方判断")
        assertEquals("x", request.query)
    }

    @Test
    fun `an omitted version decodes as the current protocol version`() {
        assertEquals(PROTOCOL_VERSION, CliCodec.decodeRequest("""{"cmd":"ping"}""".encodeToByteArray()).v)
    }

    @Test
    fun `success wraps the payload and dataAs reads it back`() {
        val response = CliCodec.success(Sample(a = 7))
        assertTrue(response.ok)
        assertEquals(Sample(7), CliCodec.dataAs<Sample>(response))
    }

    @Test
    fun `dataAs returns null instead of throwing when the payload does not match`() {
        assertNull(CliCodec.dataAs<Sample>(CliResponse(ok = true, data = JsonPrimitive(5))))
    }

    @Test
    fun `dataAs returns null when there is no payload at all`() {
        assertNull(CliCodec.dataAs<Sample>(CliResponse(ok = true)))
    }

    private fun text(bytes: ByteArray) = bytes.decodeToString()
}
