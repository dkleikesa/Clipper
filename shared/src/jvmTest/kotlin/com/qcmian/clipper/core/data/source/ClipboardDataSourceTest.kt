package com.qcmian.clipper.core.data.source

import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.testutil.LivePasteboardTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `MacClipboardDataSource` 的端到端验证：走一遍「写进剪贴板 → 轮询发现变化 → 回调解出快照」。
 *
 * 覆盖数据源自己那部分逻辑——派生字段（文本 / 图片 / 文件）与 `write` 的「没有原始表示时合成」
 * 分支。**会替换系统剪贴板**，因此默认不跑；见 [LivePasteboardTest]。
 */
class ClipboardDataSourceTest : LivePasteboardTest() {

    @Test
    fun `captured snapshot derives its fields from the raw representation`() = runBlocking {
        val source = createClipboardDataSource()
        val captured = CompletableDeferred<ClipboardSnapshot>()
        source.start { captured.complete(it) }
        try {
            // 传的是「合成快照」（只有 text，没有原始表示）：数据源应据此生成一条
            // public.utf8-plain-text 内容写进去。
            assertTrue(source.write(ClipboardSnapshot(text = SELFTEST_TEXT)), "写回剪贴板失败")

            val snapshot = withTimeout(5_000) { captured.await() }
            assertEquals(SELFTEST_TEXT, snapshot.text, "文本应从原始表示派生出来")
            assertEquals(
                setOf("public.utf8-plain-text"),
                snapshot.contents.map { it.type }.toSet(),
                "合成快照应落成一条纯文本原始表示",
            )
            assertTrue(snapshot.contents.none { it.type == "public.png" }, "不该凭空多出图片表示")
        } finally {
            source.stop()
        }
    }

    @Test
    fun `captured snapshot keeps an image as raw bytes`() = runBlocking {
        val source = createClipboardDataSource()
        val captured = CompletableDeferred<ClipboardSnapshot>()
        source.start { captured.complete(it) }
        try {
            val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            assertTrue(
                source.write(ClipboardSnapshot(contents = listOf(ClipboardContent("public.png", png)))),
                "写回剪贴板失败",
            )

            val snapshot = withTimeout(5_000) { captured.await() }
            val stored = snapshot.contents.firstOrNull { it.type == "public.png" }
            assertTrue(stored != null, "图片表示没有被保留")
            assertTrue(png.contentEquals(stored.value), "图片字节被改写了")
            assertTrue(snapshot.image != null, "图片应从原始表示派生出来")
        } finally {
            source.stop()
        }
    }

    @Test
    fun `file icons are neither kept nor treated as the item's image`() = runBlocking<Unit> {
        val source = createClipboardDataSource()
        val captured = CompletableDeferred<ClipboardSnapshot>()
        source.start { captured.complete(it) }
        try {
            // Finder 一类应用复制文件时会在粘贴板上声明文件图标（`NSPasteboard.types` 上有
            // `icns`，实测约 830 KB）。那种**声明**读不到，这里手工造一条真实载荷，确认即使它
            // 真出现也不会被留存，更不会被当成条目的图片。
            assertTrue(
                source.write(
                    ClipboardSnapshot(
                        contents = listOf(
                            ClipboardContent("public.file-url", "file:///tmp/clipper-selftest".encodeToByteArray()),
                            ClipboardContent("com.apple.icns", byteArrayOf(1, 2, 3, 4)),
                        ),
                    ),
                ),
                "写回剪贴板失败",
            )

            val snapshot = withTimeout(5_000) { captured.await() }
            assertEquals(listOf("/tmp/clipper-selftest"), snapshot.files, "文件路径应被解出来")
            assertTrue(snapshot.contents.none { it.type == "com.apple.icns" }, "文件图标不该被留存")
            assertTrue(snapshot.image == null, "文件图标不该被当成条目的图片")
        } finally {
            source.stop()
        }
    }

    @Test
    fun `text without a utf8 representation is decoded by its own type`() = runBlocking<Unit> {
        // 实测 Finder 复制文件时用 `public.utf16-external-plain-text` 放文件名列表，而且**不给**
        // UTF-8 表示。这时只能按类型自己的编码解——照 UTF-8 硬解出来是 `a` + NUL 这样的乱码。
        // 这里用 `Charsets.UTF_16`（大端 + BOM）覆盖「按 BOM 判字节序」那条分支；真实数据是小端
        // BOM，见 `FinderCopyTest` 里对真实字节的守卫。
        val source = createClipboardDataSource()
        val captured = CompletableDeferred<ClipboardSnapshot>()
        source.start { captured.complete(it) }
        try {
            val text = "a.txt\nb.txt"
            assertTrue(
                source.write(
                    ClipboardSnapshot(
                        contents = listOf(
                            ClipboardContent(
                                "public.utf16-external-plain-text",
                                text.toByteArray(Charsets.UTF_16),
                            ),
                        ),
                    ),
                ),
                "写回剪贴板失败",
            )

            val snapshot = withTimeout(5_000) { captured.await() }
            assertEquals(text, snapshot.text, "没有 UTF-8 表示时，应按该类型自己的编码解")
        } finally {
            source.stop()
        }
    }

    private companion object {
        const val SELFTEST_TEXT = "clipper-selftest"
    }
}
