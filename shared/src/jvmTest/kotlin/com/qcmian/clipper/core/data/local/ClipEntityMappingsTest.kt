package com.qcmian.clipper.core.data.local

import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.model.ClipPayload
import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.domain.model.PNG_CONTENT_TYPE
import com.qcmian.clipper.core.domain.model.SourceApplication
import com.qcmian.clipper.core.settings.ClipFilterType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 领域模型 ↔ 数据库实体的**往返契约**。
 *
 * 这些映射只在「读一份旧数据、或读回刚写下的数据」时才跑到，任一处写歪都**静默**：界面照常
 * 渲染，只是某个字段悄悄丢了。因此逐字段钉住语义，而不是只看「能存取」。
 */
class ClipEntityMappingsTest {

    @Test
    fun `meta survives a round trip through the entity`() {
        val meta = meta(
            id = "m1",
            title = "标题",
            kind = ClipFilterType.RICH_TEXT,
            files = listOf("/tmp/a.txt", "/tmp/b c.txt"),
            application = SourceApplication("Safari", "com.apple.Safari"),
            pin = ClipItem.PINNED_MARKER,
        )

        assertEquals(meta, meta.toEntity().toModel())
    }

    @Test
    fun `files are nul separated and an empty list collapses to an empty string`() {
        val entity = meta(id = "m", files = listOf("/a", "/b")).toEntity()
        assertEquals("/a\u0000/b", entity.files, "POSIX 路径不可能含 NUL，用它分隔比 JSON 便宜")
        assertEquals(listOf("/a", "/b"), entity.toModel().files)

        val empty = meta(id = "m", files = emptyList()).toEntity()
        assertEquals("", empty.files)
        assertEquals(emptyList(), empty.toModel().files)
    }

    @Test
    fun `an out of range kind falls back to text instead of crashing`() {
        // 旧版本写下的枚举序号可能指向已被删除的成员。
        val entity = meta(id = "m").toEntity().copy(kind = 999)
        assertEquals(ClipFilterType.TEXT, entity.toModel().kind)
    }

    @Test
    fun `the pinned column maps to the marker and back`() {
        assertEquals(
            ClipItem.PINNED_MARKER,
            meta(id = "m", pin = ClipItem.PINNED_MARKER).toEntity().toModel().pin,
        )
        assertEquals(null, meta(id = "m", pin = null).toEntity().toModel().pin)
        assertEquals(
            PINNED,
            meta(id = "m", pin = ClipItem.PINNED_MARKER).toEntity().pinned,
            "非零即置顶",
        )
        assertEquals(0, meta(id = "m", pin = null).toEntity().pinned)
    }

    @Test
    fun `application name and bundle id live and die together`() {
        val entity = meta(id = "m", application = SourceApplication("Finder", null)).toEntity()
        assertEquals("Finder", entity.applicationName)
        assertEquals(null, entity.applicationBundleId)
        assertEquals(SourceApplication("Finder", null), entity.toModel().application)

        assertEquals(
            null,
            entity.copy(applicationName = null, applicationBundleId = "com.apple.finder").toModel().application,
            "name 为 null 即表示没有来源应用，bundleId 独活不下来",
        )
    }

    @Test
    fun `payload contents survive a cbor round trip and never duplicate the image column`() {
        val contents = listOf(
            ClipboardContent("public.utf8-plain-text", "hello".encodeToByteArray(), itemIndex = 0),
            ClipboardContent(PNG_CONTENT_TYPE, byteArrayOf(0x89.toByte(), 0x50), itemIndex = 1),
        )
        val payload = ClipPayload(contents = contents, text = "hello", recognizedText = "识别原文")

        val entity = payload.toEntity("p1")
        assertEquals(null, entity.image, "新数据的图片不再单独存列，只能从 contents 派生")

        val decoded = entity.toModel()
        assertEquals(contents, decoded.contents)
        assertEquals("hello", decoded.text)
        assertEquals("识别原文", decoded.recognizedText)
        assertEquals(null, decoded.legacyImage)
        assertEquals(ClipImage(byteArrayOf(0x89.toByte(), 0x50)), decoded.image)
    }

    @Test
    fun `an empty contents list writes no blob at all`() {
        val entity = ClipPayload().toEntity("p")

        assertEquals(null, entity.contents, "没有任何表示时连 BLOB 都不写")
        assertEquals(emptyList(), entity.toModel().contents)
        assertTrue(entity.toModel().isEmpty)
    }

    @Test
    fun `the legacy image column is read back as a fallback`() {
        val bytes = byteArrayOf(1, 2, 3)
        val entity = ClipPayloadEntity(
            id = "p",
            text = null,
            image = bytes,
            contents = null,
            recognizedText = null,
        )

        val decoded = entity.toModel()
        assertEquals(ClipImage(bytes), decoded.legacyImage)
        assertEquals(ClipImage(bytes), decoded.image, "contents 里没有图片表示时，回退到独立列")
    }

    @Test
    fun `meta plus payload assembles the full item`() {
        val meta = meta(
            id = "i1",
            kind = ClipFilterType.IMAGE,
            hasImage = true,
            hasRecognizedText = true,
        )
        val payload = ClipPayload(
            contents = listOf(ClipboardContent(PNG_CONTENT_TYPE, byteArrayOf(1, 2, 3))),
            text = null,
            recognizedText = "recognized",
        )

        val item = meta.toItem(payload)
        assertEquals("i1", item.id)
        assertEquals("recognized", item.recognizedText)
        assertEquals(true, item.hasRecognizedText, "标记取自存储，而不是按字段组合去猜")
        assertEquals(ClipImage(byteArrayOf(1, 2, 3)), item.image, "图片从载荷的 contents 里派生")
    }

    @Test
    fun `an item without a loaded payload keeps the empty placeholders`() {
        val item = meta(id = "m", files = listOf("/a"), title = "标题").toItem(null)

        assertEquals(listOf("/a"), item.files, "文件路径留在元数据里，不需要载荷就能读")
        assertEquals("标题", item.title)
        assertEquals(emptyList(), item.contents)
        assertEquals(null, item.text)
        assertEquals(null, item.image)
    }

    private fun meta(
        id: String,
        title: String = "t",
        kind: ClipFilterType = ClipFilterType.TEXT,
        files: List<String> = emptyList(),
        application: SourceApplication? = null,
        pin: String? = null,
        hasRecognizedText: Boolean = false,
        hasImage: Boolean = false,
    ) = ClipMeta(
        id = id,
        title = title,
        kind = kind,
        files = files,
        application = application,
        firstCopiedAt = 100L,
        lastCopiedAt = 200L,
        numberOfCopies = 3,
        pin = pin,
        payloadBytes = 42L,
        contentKey = "key-$id",
        hasRecognizedText = hasRecognizedText,
        hasImage = hasImage,
    )
}
