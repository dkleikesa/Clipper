package com.qcmian.clipper.core.domain.model

import com.qcmian.clipper.core.util.ImageFormat
import com.qcmian.clipper.core.util.imageFormatOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 载荷模型的纯逻辑验证：内容摘要、图片派生、魔数识别。都不碰系统剪贴板。
 */
class ClipboardModelTest {

    @Test
    fun `content key ignores order but keeps the item structure`() {
        val a = ClipboardContent("type.a", "1".encodeToByteArray(), itemIndex = 0)
        val b = ClipboardContent("type.b", "2".encodeToByteArray(), itemIndex = 0)
        val movedToSecondItem = ClipboardContent("type.a", "1".encodeToByteArray(), itemIndex = 1)

        assertEquals(
            contentKeyOf(listOf(a, b)),
            contentKeyOf(listOf(b, a)),
            "粘贴板给出的顺序不该影响摘要",
        )
        assertNotEquals(
            contentKeyOf(listOf(a)),
            contentKeyOf(listOf(movedToSecondItem)),
            "item 序号是内容结构的一部分，必须参与摘要",
        )
        assertNotEquals(
            contentKeyOf(listOf(a)),
            contentKeyOf(listOf(a, b)),
            "多一条表示就该是不同的内容",
        )
    }

    @Test
    fun `clip image prefers png and skips empty payloads`() {
        val png = byteArrayOf(1, 2, 3)
        val tiff = byteArrayOf(9, 9, 9)

        assertEquals(
            png.toList(),
            listOf(
                ClipboardContent("public.tiff", tiff),
                ClipboardContent("public.png", png),
            ).toClipImage()?.toByteArray()?.toList(),
            "同一张图有多种编码时应优先取 PNG",
        )
        assertEquals(
            tiff.toList(),
            listOf(ClipboardContent("public.tiff", tiff)).toClipImage()?.toByteArray()?.toList(),
            "没有 PNG 时取首个图片类型",
        )
        assertEquals(
            null,
            listOf(ClipboardContent("public.png", ByteArray(0))).toClipImage(),
            "空字节不算图片",
        )
        assertEquals(
            null,
            listOf(ClipboardContent("public.utf8-plain-text", "x".encodeToByteArray())).toClipImage(),
            "文本表示不是图片",
        )
        assertEquals(
            null,
            listOf(ClipboardContent("com.apple.icns", byteArrayOf(1, 2, 3))).toClipImage(),
            "文件图标不是条目的图片：Finder 复制普通文件时会附带的那个 800 KB 的 icns",
        )
    }

    @Test
    fun `clip item derives its image from contents`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val item = ClipItem(
            id = "i",
            contents = listOf(
                ClipboardContent("public.utf8-plain-text", "x".encodeToByteArray()),
                ClipboardContent(PNG_CONTENT_TYPE, png),
            ),
        )

        assertEquals(
            png.toList(),
            item.image?.toByteArray()?.toList(),
            "图片必须从 contents 派生——它已经不再单独存一份字段（与 ClipPayload.image 同口径）",
        )
    }

    @Test
    fun `clip item falls back to the legacy image column`() {
        val legacy = ClipImage(byteArrayOf(1, 2, 3))
        // 早期版本把图片单独存在 `clip_payload.image` 列；那种数据 contents 里没有图片表示。
        val item = ClipItem(id = "old", legacyImage = legacy)

        assertEquals(legacy, item.image, "旧数据的图片必须仍能从独立列取到")
    }

    @Test
    fun `image format comes from the magic bytes`() {
        assertEquals(ImageFormat.PNG, imageFormatOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D)))
        assertEquals(ImageFormat.JPEG, imageFormatOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals(ImageFormat.TIFF, imageFormatOf(byteArrayOf(0x49, 0x49, 0x2A, 0x00)))
        assertEquals(ImageFormat.TIFF, imageFormatOf(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A)))
        assertEquals(ImageFormat.GIF, imageFormatOf(byteArrayOf(0x47, 0x49, 0x46, 0x38)))
        // ISO-BMFF：偏移 4 处是 `ftyp`。
        assertEquals(
            ImageFormat.HEIC,
            imageFormatOf(byteArrayOf(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63)),
        )
        assertEquals(ImageFormat.UNKNOWN, imageFormatOf(byteArrayOf(1, 2, 3, 4)))
        assertEquals(ImageFormat.UNKNOWN, imageFormatOf(ByteArray(0)))
        assertTrue(imageFormatOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)).uti != null, "PNG 应能推出 UTI")
    }
}
