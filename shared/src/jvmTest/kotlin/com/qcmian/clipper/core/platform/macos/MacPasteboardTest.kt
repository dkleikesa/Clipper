package com.qcmian.clipper.core.platform.macos

import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.testutil.LivePasteboardTest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `MacPasteboard` 的**真实剪贴板往返**验证。
 *
 * 剪贴板这条路上没有任何编译期能验证的东西：类型过滤、item 分组、`writeObjects:` 的语义，
 * 都只有真跑一遍才知道。因此这些测试直接读写系统粘贴板——**会把当前剪贴板换成测试内容**，
 * 因此默认不跑；见 [LivePasteboardTest]。
 */
class MacPasteboardTest : LivePasteboardTest() {

    /** 自定类型：系统不会对它做任何规范化，用来干净地验证 item 分组机制。 */
    private val selftestType = "com.qcmian.clipper.selftest"

    @Test
    fun `pasteboard is reachable`() {
        assertTrue(MacPasteboard.available, "NSPasteboard 不可用（AppKit 没加载？）")
        assertTrue(MacPasteboard.changeCount() >= 0, "changeCount 读不到")
    }

    @Test
    fun `legacy per-type write collapses duplicates`() {
        // 复现修复前的写法：直接对粘贴板逐条 setData:forType:。这条断言用来证实「必须按 item
        // 分组」不是臆测——它应当观察到两条同类型被压成一条。
        val pasteboard = MacNative.send(MacNative.clazz("NSPasteboard"), "generalPasteboard")
        assertNotNull(pasteboard, "拿不到 generalPasteboard")
        MacNative.sendLong(pasteboard, "clearContents")

        val typeName = MacNative.nsString(selftestType)
        assertNotNull(typeName)
        for (text in listOf("alpha", "beta")) {
            val data = MacNative.data(text.encodeToByteArray())
            MacNative.sendBool(pasteboard, "setData:forType:", data, typeName)
        }
        MacNative.send(typeName, "release")

        val read = MacPasteboard.readContents().filter { it.type == selftestType }
        assertEquals(1, read.size, "旧写法应把同类型压成一条（这条断言若失败，说明前提不成立）")
    }

    @Test
    fun `the same type in two items round-trips instead of overwriting`() {
        // 这是本轮修复的核心：逐条 setData:forType: 会让后写的那条覆盖先写的，只剩一个。
        val first = "alpha".encodeToByteArray()
        val second = "beta".encodeToByteArray()
        val contents = listOf(
            ClipboardContent(selftestType, first, itemIndex = 0),
            ClipboardContent(selftestType, second, itemIndex = 1),
        )

        assertTrue(MacPasteboard.write(contents), "写入失败")

        val read = MacPasteboard.readContents().filter { it.type == selftestType }
        assertEquals(2, read.size, "同类型的两份表示应各占一个 item，而不是互相覆盖")
        assertEquals(
            listOf("alpha", "beta"),
            read.sortedBy { it.itemIndex }.map { it.value?.decodeToString() },
            "两个 item 的内容与先后都应保留",
        )
    }

    @Test
    fun `several types inside one item keep sharing that item`() {
        val contents = listOf(
            ClipboardContent("public.utf8-plain-text", "hello".encodeToByteArray(), itemIndex = 0),
            ClipboardContent(selftestType, "extra".encodeToByteArray(), itemIndex = 0),
        )

        assertTrue(MacPasteboard.write(contents), "写入失败")

        val read = MacPasteboard.readContents()
        assertEquals(
            setOf("public.utf8-plain-text", selftestType),
            read.map { it.type }.toSet(),
            "两个类型都应读回来",
        )
        assertEquals(setOf(0), read.map { it.itemIndex }.toSet(), "同一 item 的多个类型共享序号")
        assertEquals(
            "hello",
            read.first { it.type == "public.utf8-plain-text" }.value?.decodeToString(),
            "文本内容应原样往返",
        )
    }

    @Test
    fun `image bytes are not rewritten`() {
        val png = Base64.getDecoder().decode(ONE_PIXEL_PNG)

        assertTrue(MacPasteboard.write(listOf(ClipboardContent("public.png", png))), "写入失败")

        val read = MacPasteboard.readContents().firstOrNull { it.type == "public.png" }
        assertNotNull(read, "PNG 表示没有读回来")
        assertTrue(png.contentEquals(read.value), "图片字节被改写了（长度 ${read.value?.size}）")
    }

    @Test
    fun `two images in two items both survive`() {
        val png = Base64.getDecoder().decode(ONE_PIXEL_PNG)

        assertTrue(
            MacPasteboard.write(
                listOf(
                    ClipboardContent("public.png", png, itemIndex = 0),
                    // 末尾多一个字节，让两张图的内容确实不同（粘贴板不校验图片完整性）。
                    ClipboardContent("public.png", png + byteArrayOf(0), itemIndex = 1),
                ),
            ),
            "写入失败",
        )

        val read = MacPasteboard.readContents().filter { it.type == "public.png" }
        assertEquals(2, read.size, "两张图应各占一个 item")
    }

    @Test
    fun `file urls survive as file urls`() {
        val path = "file:///tmp/clipper-selftest-${System.nanoTime()}.txt"
        assertTrue(
            MacPasteboard.write(listOf(ClipboardContent("public.file-url", path.encodeToByteArray()))),
            "写入失败",
        )

        val read = MacPasteboard.readContents().firstOrNull { it.type == "public.file-url" }
        assertNotNull(read, "file-url 没有读回来")
        assertEquals(path, read.value?.decodeToString())
    }

    @Test
    fun `utf16 text keeps its own encoding`() {
        // 纯文本在剪贴板上通常同时有 UTF-8 与 UTF-16 两份表示。写回时若把 UTF-16 的字节按
        // UTF-8 解成字符串再交给 `setString:forType:`，这条表示就变成了乱码。
        val sample = "你好 clipper"
        assertTrue(
            MacPasteboard.write(
                listOf(
                    ClipboardContent("public.utf8-plain-text", sample.encodeToByteArray(), itemIndex = 0),
                    ClipboardContent("public.utf16-plain-text", sample.toByteArray(Charsets.UTF_16LE), itemIndex = 0),
                ),
            ),
            "写入失败",
        )

        val read = MacPasteboard.readContents()
        val utf8 = read.firstOrNull { it.type == "public.utf8-plain-text" }
        val utf16 = read.firstOrNull { it.type == "public.utf16-plain-text" }
        assertNotNull(utf8, "UTF-8 表示没有读回来")
        assertNotNull(utf16, "UTF-16 表示没有读回来")
        assertEquals(sample, utf8.value?.decodeToString(), "UTF-8 那条应原样往返")
        assertEquals(
            sample,
            utf16.value?.let { String(it, Charsets.UTF_16LE) },
            "UTF-16 那条应按它自己的编码往返，而不是先被当成 UTF-8 解一遍",
        )
    }

    @Test
    fun `a skipped type still leaves its name behind`() {
        // 不值得留存的类型连 `dataForType:` 都不调——密码管理器声明的类型因此**连读都不读**，
        // 而不是读进来再丢。但类型名必须留下：`ClipboardSnapshot.types` 要靠它识别它们。
        assertTrue(
            MacPasteboard.write(listOf(ClipboardContent(selftestType, "alpha".encodeToByteArray()))),
            "写入失败",
        )

        val read = MacPasteboard.readContents(keepBytes = { it != selftestType })
        val entry = read.firstOrNull { it.type == selftestType }
        assertNotNull(entry, "被跳过字节的类型仍应留下名字")
        assertNull(entry.value, "跳过的类型不该被拷出来")
    }

    private companion object {
        /** 1×1 全透明 PNG：够用来验证「字节原样往返」，又不必依赖任何图片库。 */
        const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
