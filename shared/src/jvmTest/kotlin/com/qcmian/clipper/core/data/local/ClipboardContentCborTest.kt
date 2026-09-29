package com.qcmian.clipper.core.data.local

import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.domain.model.PNG_CONTENT_TYPE
import com.qcmian.clipper.core.domain.model.toClipImage
import com.qcmian.clipper.core.util.decodeCborOrNull
import com.qcmian.clipper.core.util.encodeCbor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 载荷的 CBOR 往返。
 *
 * 这条链是新数据取图的**唯一**通路：`toEntity` 不再单独存图片列（`image = null`），图片只能从
 * `contents` 里派生（见 `ClipPayload.image`）。而 `decodeCborOrNull` 一旦失败就**静默**返回
 * 空列表——条目的 `hasImage` 仍然是 1，界面却永远拿不到图。
 */
class ClipboardContentCborTest {

    @Test
    fun `clipboard contents survive a cbor round trip`() {
        val original = listOf(
            ClipboardContent(
                type = "public.png",
                // PNG 魔数：0x89 是有符号 byte 里的负数，最容易在编码里被写坏。
                value = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
                itemIndex = 0,
            ),
            ClipboardContent("public.utf8-plain-text", "hello".encodeToByteArray(), itemIndex = 1),
        )

        val encoded = encodeCbor(original)
        val decoded = decodeCborOrNull<List<ClipboardContent>>(encoded)

        assertNotNull(decoded, "CBOR 应当解得出来（实际字节 ${encoded.take(24).joinToString(" ") { "%02x".format(it) }}）")
        assertEquals(original, decoded, "往返必须无损")
        assertEquals(
            original[0].value?.toList(),
            decoded[0].value?.toList(),
            "图片字节必须原样还原",
        )
    }

    @Test
    fun `decoded contents still yield the image`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        val original = listOf(ClipboardContent(PNG_CONTENT_TYPE, png))

        val decoded = decodeCborOrNull<List<ClipboardContent>>(encodeCbor(original))
        assertNotNull(decoded, "CBOR 应当解得出来")

        val image = decoded.toClipImage()
        assertNotNull(image, "解出的 contents 必须能派生出图片——否则 hasImage 为真却永远显示不出来")
        assertEquals(png.toList(), image.toByteArray().toList())
    }
}
