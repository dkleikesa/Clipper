package com.qcmian.clipper.core.data.source

import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.testutil.LivePasteboardTest
import com.qcmian.clipper.core.testutil.runAppleScript
import java.io.File
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **系统侧写入 → 我们读取**的对齐验证。
 *
 * 内容由 AppleScript 放上去，也就是走系统自己的粘贴板写入路径——因此这条方向独立于我们的
 * `write`，能回答「别人写的东西我们读得对不对」。
 *
 * 文本这条尤其重要：系统给纯文本时会**同时**写 UTF-16 与 UTF-8 两种编码，而且常常把 UTF-16
 * 排在前面；照 UTF-8 硬解就是乱码。我们的 `readText` 因此把解码交给系统（`stringForType:`）。
 *
 * 会替换系统剪贴板，因此默认不跑；见 [LivePasteboardTest]。
 */
class SystemWrittenPasteboardTest : LivePasteboardTest() {

    @Test
    fun `system written unicode text is decoded correctly`() = runBlocking<Unit> {
        val source = createClipboardDataSource()
        val captured = CompletableDeferred<ClipboardSnapshot>()
        source.start { captured.complete(it) }
        try {
            runAppleScript("set the clipboard to \"$UNICODE_TEXT\"")

            val snapshot = withTimeout(5_000) { captured.await() }
            assertEquals(UNICODE_TEXT, snapshot.text, "多编码的文本应由系统解码，而不是硬按 UTF-8 解")
            assertTrue(
                snapshot.contents.any { it.type == "public.utf8-plain-text" },
                "纯文本的原始表示应被保留下来",
            )
        } finally {
            source.stop()
        }
    }

    @Test
    fun `system written png keeps its bytes`() = runBlocking<Unit> {
        val pngFile = File(System.getProperty("java.io.tmpdir"), "clipper-selftest.png").apply {
            writeBytes(Base64.getDecoder().decode(ONE_PIXEL_PNG))
        }
        val expected = pngFile.readBytes()

        val source = createClipboardDataSource()
        val captured = CompletableDeferred<ClipboardSnapshot>()
        source.start { captured.complete(it) }
        try {
            runAppleScript("set the clipboard to (read (POSIX file \"${pngFile.absolutePath}\") as «class PNGf»)")

            val snapshot = withTimeout(5_000) { captured.await() }
            val stored = snapshot.contents.firstOrNull { it.type == "public.png" }
            assertNotNull(stored, "PNG 表示没有被保留")
            assertTrue(expected.contentEquals(stored.value), "图片字节不应被改写")
            assertNotNull(snapshot.image, "图片应从原始表示派生出来")
        } finally {
            source.stop()
        }
    }

    private companion object {
        const val UNICODE_TEXT = "你好 clipper 测试"

        /** 1×1 全透明 PNG；用来验证「字节原样往返」，不依赖任何图片库。 */
        const val ONE_PIXEL_PNG =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
    }
}
