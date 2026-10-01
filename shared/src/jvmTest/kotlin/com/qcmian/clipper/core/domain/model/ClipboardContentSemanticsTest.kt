package com.qcmian.clipper.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * [ClipboardContent] 的**内容语义**。
 *
 * [ClipboardContent.value] 是 [ByteArray]，若按引用比较，`ClipPayload` / `ClipItem` 这些
 * 持有它的值对象就会在列表 diff 与内容摘要（[contentKeyOf]）里**静默**把内容相同的两份数据
 * 判成不同——去重失效、界面整列重建，都不会报错。
 */
class ClipboardContentSemanticsTest {

    @Test
    fun `two instances with equal bytes are equal and hash the same`() {
        val a = ClipboardContent(PNG_CONTENT_TYPE, byteArrayOf(1, 2, 3), itemIndex = 0)
        val b = ClipboardContent(PNG_CONTENT_TYPE, byteArrayOf(1, 2, 3), itemIndex = 0)

        assertEquals(a, b, "内容相同、引用不同必须相等")
        assertEquals(a.hashCode(), b.hashCode(), "equals 为真时 hashCode 必须一致")
    }

    @Test
    fun `type item index and payload all participate in equality`() {
        val base = ClipboardContent(PNG_CONTENT_TYPE, byteArrayOf(1, 2, 3), itemIndex = 0)

        assertNotEquals(base, ClipboardContent("public.tiff", byteArrayOf(1, 2, 3), itemIndex = 0))
        assertNotEquals(base, ClipboardContent(PNG_CONTENT_TYPE, byteArrayOf(1, 2, 3), itemIndex = 1))
        assertNotEquals(base, ClipboardContent(PNG_CONTENT_TYPE, byteArrayOf(1, 2, 4), itemIndex = 0))
        assertNotEquals(
            base,
            ClipboardContent(PNG_CONTENT_TYPE, null, itemIndex = 0),
            "null 表示「只有类型、没有载荷」，与空字节是不同的声明",
        )
        assertNotEquals(base, ClipboardContent(PNG_CONTENT_TYPE, ByteArray(0), itemIndex = 0))
    }

    @Test
    fun `contentKey depends only on the content itself`() {
        val png = ClipboardContent(PNG_CONTENT_TYPE, byteArrayOf(9, 8, 7), itemIndex = 0)
        val text = ClipboardContent("public.utf8-plain-text", "hi".encodeToByteArray(), itemIndex = 1)

        assertEquals(
            contentKeyOf(listOf(png, text)),
            contentKeyOf(
                listOf(
                    ClipboardContent(PNG_CONTENT_TYPE, byteArrayOf(9, 8, 7), itemIndex = 0),
                    ClipboardContent("public.utf8-plain-text", "hi".encodeToByteArray(), itemIndex = 1),
                ),
            ),
            "内容相同、引用不同必须得到同一个摘要，否则重复复制去不掉",
        )
        assertNotEquals(
            contentKeyOf(listOf(png, text)),
            contentKeyOf(
                listOf(
                    ClipboardContent(PNG_CONTENT_TYPE, byteArrayOf(9, 8, 6), itemIndex = 0),
                    text,
                ),
            ),
            "字节不同就是不同内容",
        )
        assertNotEquals(
            contentKeyOf(listOf(text)),
            contentKeyOf(
                listOf(ClipboardContent("public.utf8-plain-text", "hi".encodeToByteArray(), itemIndex = 2)),
            ),
            "item 序号是内容结构的一部分，必须参与摘要",
        )
    }

    @Test
    fun `contentKey ignores the declaration order of the representations`() {
        val a = ClipboardContent("type.a", "1".encodeToByteArray(), itemIndex = 0)
        val b = ClipboardContent("type.b", "2".encodeToByteArray(), itemIndex = 0)

        assertEquals(
            contentKeyOf(listOf(a, b)),
            contentKeyOf(listOf(b, a)),
            "粘贴板声明类型的顺序不稳定，摘要不能跟着漂",
        )
    }

    @Test
    fun `size and toByteArray expose the raw payload`() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val content = ClipboardContent(PNG_CONTENT_TYPE, bytes)

        assertEquals(4, content.size)
        assertEquals(bytes.toList(), content.toByteArray()?.toList())
        assertEquals(0, ClipboardContent(PNG_CONTENT_TYPE).size, "没有载荷时大小是 0，而不是崩溃")
    }
}
