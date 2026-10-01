package com.qcmian.clipper.feature.history

import com.qcmian.clipper.core.domain.action.ClipAction
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.domain.model.PNG_CONTENT_TYPE
import com.qcmian.clipper.core.domain.usecase.SelectResult
import com.qcmian.clipper.core.testutil.InProcessCluster
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * F4（A 层）：**激活与写回**。
 *
 * 真存储 + 记账用的假剪贴板；`SelectClipUseCase` 的延迟在 `runTest` 的虚拟时间下即时推进，
 * 因此「逐条粘贴」这条带真实等待的路径也能被确定性断言。
 *
 * 覆盖：单条原样写回（保 HTML / RTF）；去格式只写纯文本；多条复制合成一段纯文本；有一条拿不出
 * 文本时退回最后一条；多条粘贴逐条进行且单条不补回车；激活后计数 +1 并按最后复制时间重排。
 */
class AcceptanceActivationTest {

    @Test
    fun `单条原样写回 保住 HTML 与 RTF`() = runTest {
        val cluster = InProcessCluster(scope = backgroundScope)
        try {
            val contents = listOf(
                ClipboardContent("public.utf8-plain-text", "hello".encodeToByteArray()),
                ClipboardContent("public.html", "<b>hello</b>".encodeToByteArray()),
                ClipboardContent("public.rtf", "{\\rtf1 hello}".encodeToByteArray()),
            )
            cluster.seed(textItem(id = "rich", text = "hello", copiedAt = 1, contents = contents))

            val result = cluster.useCases.selectClip(listOf("rich"), ClipAction.COPY) {}

            assertEquals(SelectResult.COPIED, result)
            assertEquals(
                contents,
                cluster.clipboard.written?.contents,
                "原样写回必须把全部原始表示逐类型搬回去，而不是只写一份纯文本",
            )
            assertEquals("hello", cluster.clipboard.written?.text)
            assertEquals(0, cluster.clipboard.pasteCount, "复制不合成粘贴")
            assertEquals(2, cluster.repository.meta("rich")?.numberOfCopies, "写回要计入复制次数")
        } finally {
            cluster.close()
        }
    }

    @Test
    fun `去格式只写纯文本`() = runTest {
        val cluster = InProcessCluster(scope = backgroundScope)
        try {
            cluster.seed(
                textItem(
                    id = "rich",
                    text = "第一行\n第二行",
                    copiedAt = 1,
                    contents = listOf(
                        ClipboardContent("public.utf8-plain-text", "第一行\n第二行".encodeToByteArray()),
                        ClipboardContent("public.html", "<p>第一行<br>第二行</p>".encodeToByteArray()),
                    ),
                ),
            )

            cluster.useCases.selectClip(listOf("rich"), ClipAction.COPY_WITHOUT_FORMATTING) {}

            assertEquals("第一行\n第二行", cluster.clipboard.written?.text)
            assertTrue(
                cluster.clipboard.written?.contents.orEmpty().isEmpty(),
                "去格式就不能再带上 HTML / RTF 这些附加表示",
            )
        } finally {
            cluster.close()
        }
    }

    @Test
    fun `多条复制合成一段纯文本`() = runTest {
        val cluster = InProcessCluster(scope = backgroundScope)
        try {
            cluster.seed(textItem("a", "第一段", copiedAt = 1))
            cluster.seed(textItem("b", "第二段", copiedAt = 2))
            cluster.seed(textItem("c", "第三段", copiedAt = 3))

            val result = cluster.useCases.selectClip(listOf("a", "b", "c"), ClipAction.COPY) {}

            assertEquals(SelectResult.COPIED, result)
            assertEquals("第一段\n第二段\n第三段", cluster.clipboard.written?.text)
            assertTrue(
                cluster.clipboard.written?.contents.orEmpty().isEmpty(),
                "多条刻意不保留附加表示：混合快照在不同目标端会粘出完全不同的东西",
            )
            assertEquals(1, cluster.clipboard.writes.size, "多条复制是一次写回")
            for (id in listOf("a", "b", "c")) {
                assertEquals(2, cluster.repository.meta(id)?.numberOfCopies, "$id 应各计一次复制")
            }
        } finally {
            cluster.close()
        }
    }

    @Test
    fun `有一条拿不出文本时退回最后一条`() = runTest {
        val cluster = InProcessCluster(scope = backgroundScope)
        try {
            // 纯图片条目（标题为空、没有可提取文字）：它贡献不出任何文本。
            cluster.seed(
                ClipItem(
                    id = "img",
                    contents = listOf(ClipboardContent(PNG_CONTENT_TYPE, PNG_BYTES.copyOf())),
                    firstCopiedAt = 1,
                    lastCopiedAt = 1,
                ),
            )
            cluster.seed(textItem("txt", "能拿出来的文本", copiedAt = 2))

            cluster.useCases.selectClip(listOf("img", "txt"), ClipAction.COPY) {}

            assertEquals(
                "能拿出来的文本",
                cluster.clipboard.written?.text,
                "有一条拿不出文本时，退回只写最后一条",
            )
            assertEquals(1, cluster.repository.meta("img")?.numberOfCopies, "拿不出文本的那条不该被记成已复制")
            assertEquals(2, cluster.repository.meta("txt")?.numberOfCopies)
        } finally {
            cluster.close()
        }
    }

    @Test
    fun `多条粘贴逐条进行 每条之后补回车`() = runTest {
        val cluster = InProcessCluster(scope = backgroundScope)
        try {
            cluster.seed(textItem("a", "第一段", copiedAt = 1))
            cluster.seed(textItem("b", "第二段", copiedAt = 2))
            cluster.seed(textItem("c", "第三段", copiedAt = 3))

            val result = cluster.useCases.selectClip(listOf("a", "b", "c"), ClipAction.PASTE) {}

            assertEquals(SelectResult.PASTING, result)
            assertEquals(3, cluster.clipboard.writes.size, "多条粘贴是逐条写、逐条按")
            assertEquals(
                listOf("第一段", "第二段", "第三段"),
                cluster.clipboard.writes.map { it.text },
            )
            assertEquals(3, cluster.clipboard.pasteCount)
            assertEquals(3, cluster.clipboard.returnCount, "连续粘贴每条之后要补一个裸回车")
        } finally {
            cluster.close()
        }
    }

    @Test
    fun `单条粘贴绝不补回车`() = runTest {
        val cluster = InProcessCluster(scope = backgroundScope)
        try {
            cluster.seed(textItem("a", "唯一一条", copiedAt = 1))

            cluster.useCases.selectClip(listOf("a"), ClipAction.PASTE) {}

            assertEquals(1, cluster.clipboard.pasteCount)
            assertEquals(
                0,
                cluster.clipboard.returnCount,
                "单条粘贴是最常用的操作，而 ↵ 在 Finder 这类应用里是重命名",
            )
        } finally {
            cluster.close()
        }
    }

    @Test
    fun `激活后计数加一 并按最后复制时间重排`() = runTest {
        val cluster = InProcessCluster(scope = backgroundScope)
        try {
            cluster.seed(textItem("a", "最旧", copiedAt = 1))
            cluster.seed(textItem("b", "中间", copiedAt = 2))
            cluster.seed(textItem("c", "最新", copiedAt = 3))
            assertEquals(listOf("c", "b", "a"), cluster.repository.unpinned.value.map { it.id })

            cluster.useCases.selectClip(listOf("a"), ClipAction.COPY) {}

            assertEquals(2, cluster.repository.meta("a")?.numberOfCopies)
            assertTrue(
                (cluster.repository.meta("a")?.lastCopiedAt ?: 0L) > 3L,
                "激活要刷新最后复制时间",
            )
            assertEquals(
                listOf("a", "c", "b"),
                cluster.repository.unpinned.value.map { it.id },
                "被激活的条目按「最后复制」回到最前",
            )
        } finally {
            cluster.close()
        }
    }

    @Test
    fun `平台写不了时返回 UNSUPPORTED 且不计数`() = runTest {
        val cluster = InProcessCluster(scope = backgroundScope)
        try {
            cluster.clipboard.writeSucceeds = false
            cluster.seed(textItem("a", "写不进去", copiedAt = 1))

            val result = cluster.useCases.selectClip(listOf("a"), ClipAction.COPY) {}

            assertEquals(SelectResult.UNSUPPORTED, result)
            assertEquals(1, cluster.repository.meta("a")?.numberOfCopies, "一条都没写出去就不该记成已复制")
            assertTrue(
                cluster.repository.statusMessage.value != null,
                "要么给用户一句可读的失败原因",
            )
        } finally {
            cluster.close()
        }
    }

    @Test
    fun `条目已不在时静默忽略`() = runTest {
        val cluster = InProcessCluster(scope = backgroundScope)
        try {
            assertEquals(SelectResult.IGNORED, cluster.useCases.selectClip(emptyList(), ClipAction.COPY) {})
            assertEquals(
                SelectResult.IGNORED,
                cluster.useCases.selectClip(listOf("不存在"), ClipAction.COPY) {},
                "条目在界面上停留期间被删掉时不该往剪贴板写一份空内容",
            )
            assertNull(cluster.clipboard.written, "一次都不该写")
        } finally {
            cluster.close()
        }
    }

    // -------------------------------------------------------------------------------------

    private fun textItem(
        id: String,
        text: String,
        copiedAt: Long,
        contents: List<ClipboardContent> = listOf(
            ClipboardContent("public.utf8-plain-text", text.encodeToByteArray()),
        ),
    ) = ClipItem(
        id = id,
        text = text,
        contents = contents,
        firstCopiedAt = copiedAt,
        lastCopiedAt = copiedAt,
    )

    private companion object {
        val PNG_BYTES: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01)
    }
}
