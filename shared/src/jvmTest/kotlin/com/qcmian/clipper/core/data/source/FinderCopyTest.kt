package com.qcmian.clipper.core.data.source

import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.platform.macos.MacPasteboard
import com.qcmian.clipper.core.testutil.LivePasteboardTest
import com.qcmian.clipper.core.testutil.copyFilesInFinder
import com.qcmian.clipper.core.testutil.pasteInFinder
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * **真实 Finder 复制**的端到端验证。
 *
 * 之前验证「多个同类型表示各占一个 item」用的是自造的粘贴板；这里走的是它的真实来源：
 * Finder 选中多个文件 → `⌘C` → 我们轮询读到 → 解出快照。
 *
 * 会抢 Finder 焦点并替换系统剪贴板，因此默认不跑；见 [LivePasteboardTest]。
 */
class FinderCopyTest : LivePasteboardTest() {

    @Test
    fun `finder copying several files yields every path`() = runBlocking<Unit> {
        val dir = File(System.getProperty("java.io.tmpdir"), "clipper-finder-selftest").apply { mkdirs() }
        val names = listOf("a.txt", "b.txt", "c.txt")
        names.forEach { File(dir, it).writeText("clipper") }
        // 比 canonicalPath 而不是 absolutePath：Finder 给的是「文件引用 URL」，解析出来的
        // 是符号链接展开后的真实路径（`/var/…` → `/private/var/…`），两者指向同一个文件。
        val expected = names.map { File(dir, it).canonicalPath }.sorted()

        val source = createClipboardDataSource()
        val captured = CompletableDeferred<ClipboardSnapshot>()
        source.start { captured.complete(it) }
        try {
            copyFilesInFinder(dir, names)

            val snapshot = withTimeout(10_000) { captured.await() }

            assertEquals(
                expected,
                snapshot.files.map { File(it).canonicalPath }.sorted(),
                "Finder 复制的每个文件都应在快照里",
            )

            val urls = snapshot.contents.filter { it.type == "public.file-url" }
            assertEquals(names.size, urls.size, "每个文件各占一条 file-url")
            assertEquals(
                names.size,
                urls.map { it.itemIndex }.toSet().size,
                "三个文件 URL 必须分属三个 item——否则写回时同类型会互相覆盖",
            )
            assertTrue(snapshot.image == null, "复制纯文本文件不该产出图片")
            // Finder 复制文件时会在粘贴板上**声明**一整套图标表示：`icns` 实测约 830 KB，外加
            // TIFF 4.2 MB、BMP 4.2 MB、8BPS 939 KB、PNG 206 KB……合计约 11 MB。它们都只出现在
            // `NSPasteboard.types` 上、不在 `pasteboardItems` 里（下面 `readTypes` 那条断言钉住
            // 这个事实），这里先守住「即使成了真实载荷也不会被留存」。
            val iconTypes = setOf(
                // icns 两种命名都守着：NSPasteboard 用 UTI，Carbon 侧叫四字符码 `icns`。
                "com.apple.icns",
                "icns",
                "public.tiff",
                "public.png",
                "com.compuserve.gif",
                "public.jpeg",
                "com.microsoft.bmp",
                "public.avif",
            )
            assertTrue(
                snapshot.contents.none { it.type in iconTypes },
                "图标表示不该进 contents；实际保留：${snapshot.contents.map { it.type }}",
            )

            // Finder 复制普通文件时，条目里**只有**文件引用和文件名的文本表示：实测 `icns`
            // （约 830 KB）和一整套 TIFF / BMP / 8BPS / PNG 图标图像（合计约 11 MB）都只声明在
            // `NSPasteboard.types` 上，不在 `pasteboardItems` 里，本来就读不到。这条断言钉住
            // 这个事实：哪天它们成了条目里真实的载荷，复制一个文件就会存下十几 MB 的图标，
            // 条目还会被当成图片。
            val readTypes = MacPasteboard.readContents().map { it.type }
            assertTrue(
                readTypes.none { it in iconTypes },
                "图标表示不该成为条目里的载荷；实际读到：$readTypes",
            )

            // Finder 用 `public.utf16-external-plain-text` 放文件名列表。实测它的字节以 `ff fe`
            // （小端 BOM）开头——所以解码必须走 BOM 分支，不能想当然按某个固定字节序解。
            val nameBytes = MacPasteboard.readContents()
                .firstOrNull { it.type == "public.utf16-external-plain-text" }
                ?.value
            assertNotNull(nameBytes, "Finder 复制文件时应带上文件名的 UTF-16 表示")
            assertTrue(
                String(nameBytes, Charsets.UTF_16).startsWith(names.first()),
                "该类型带着 BOM；实际字节：${nameBytes.take(12).joinToString(" ") { it.toUByte().toString(16).padStart(2, '0') }}",
            )
        } finally {
            source.stop()
        }
    }

    @Test
    fun `finder pastes back every file we wrote`() = runBlocking<Unit> {
        val root = File(System.getProperty("java.io.tmpdir"), "clipper-paste-selftest")
        val sourceDir = File(root, "src").apply { mkdirs() }
        val targetDir = File(root, "dst").apply { deleteRecursively(); mkdirs() }
        val names = listOf("a.txt", "b.txt", "c.txt")
        names.forEach { File(sourceDir, it).writeText("clipper") }

        // 每个文件一个 item——这正是 Finder 复制多文件时的结构。
        val clipboard = createClipboardDataSource()
        assertTrue(
            clipboard.write(
                ClipboardSnapshot(
                    contents = names.mapIndexed { index, name ->
                        ClipboardContent(
                            type = "public.file-url",
                            value = "file://${File(sourceDir, name).absolutePath}".encodeToByteArray(),
                            itemIndex = index,
                        )
                    },
                ),
            ),
            "写回剪贴板失败",
        )

        pasteInFinder(targetDir)

        assertEquals(
            names.sorted(),
            targetDir.listFiles().orEmpty().map { it.name }.sorted(),
            "Finder 粘出来的文件数应与写进去的一致——少一个就说明同类型被覆盖了",
        )
    }
}
