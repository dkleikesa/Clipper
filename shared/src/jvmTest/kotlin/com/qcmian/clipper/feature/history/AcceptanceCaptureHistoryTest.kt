package com.qcmian.clipper.feature.history

import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.model.FILE_URL_CONTENT_TYPE
import com.qcmian.clipper.core.domain.model.PNG_CONTENT_TYPE
import com.qcmian.clipper.core.domain.model.SourceApplication
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.ClipFilterType
import com.qcmian.clipper.core.testutil.InProcessCluster
import com.qcmian.clipper.testing.TEST_PNG_BYTES
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * F2（A 层）：**捕获与历史**。
 *
 * 真 Room / SQLCipher 存储 + 假「系统输入」：每条用例都是一份 [ClipboardSnapshot] 从假剪贴板
 * 灌进来，再由真的 `CaptureClipboardUseCase` 决定它的去向。断言的是**可观察的历史状态**，
 * 而不是用例内部怎么调仓库。
 *
 * 覆盖：四类内容都进历史；三种「永不记录」的类型；暂停；自己写回不重复记录；重复复制只更新
 * 计数与时间戳；置顶豁免上限与淘汰；超限淘汰；两种清除；退出清空。
 */
class AcceptanceCaptureHistoryTest {

    private lateinit var cluster: InProcessCluster
    private lateinit var captureJob: Job

    @BeforeTest
    fun setUp() {
        cluster = InProcessCluster()
        runBlocking {
            cluster.start()
            // UNDISPATCHED：先同步订阅上 `snapshots`，第一条快照才不会因为「还没有收集者」而丢。
            captureJob = cluster.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                cluster.useCases.captureClipboard.run()
            }
        }
    }

    @AfterTest
    fun tearDown() {
        captureJob.cancel()
        cluster.close()
    }

    // -------------------------------------------------------------------------------------

    @Test
    fun `文本 图片 文件 富文本 都进历史`() = runBlocking {
        cluster.clipboard.emit(textSnapshot("普通文本"))
        cluster.clipboard.emit(imageSnapshot())
        cluster.clipboard.emit(fileSnapshot("/tmp/一个文件.txt"))
        cluster.clipboard.emit(richTextSnapshot("<p>你好世界</p>"))

        cluster.awaitHistorySize(4)

        val kinds = cluster.repository.unpinned.value.map { it.kind }.toSet()
        assertEquals(setOf(ClipFilterType.TEXT, ClipFilterType.IMAGE, ClipFilterType.FILE, ClipFilterType.RICH_TEXT), kinds)
        assertTrue(
            cluster.repository.unpinned.value.any { it.hasImage },
            "图片条目的 hasImage 必须为真——它决定行高，而载荷按需才加载",
        )
    }

    @Test
    fun `机密 临时 自动生成 类型永不记录`() = runBlocking {
        for (type in AppSettings.ALWAYS_IGNORED_PASTEBOARD_TYPES) {
            cluster.clipboard.emit(textSnapshot("危险-$type", types = listOf(type)))
        }
        cluster.clipboard.emit(textSnapshot("正常内容"))

        cluster.awaitHistorySize(1)
        assertEquals(
            listOf("正常内容"),
            cluster.repository.unpinned.value.map { it.title },
            "安全底线的三种类型任何情况下都不该进历史",
        )
    }

    @Test
    fun `暂停期间不记录 恢复后继续`() = runBlocking {
        cluster.repository.setSettings(AppSettings(ignoreEvents = true))
        cluster.clipboard.emit(textSnapshot("暂停中"))

        cluster.settle()
        assertEquals(0, cluster.historySize, "暂停期间的新复制一条都不该进历史")

        cluster.repository.setSettings(AppSettings(ignoreEvents = false))
        cluster.clipboard.emit(textSnapshot("恢复后"))
        cluster.awaitHistorySize(1)
    }

    @Test
    fun `自己写回的剪贴板变化不重复记录`() = runBlocking {
        // `withoutCapturing` 期间发一次快照：采集器必须把它当成「本应用自己造成的」丢弃。
        cluster.repository.withoutCapturing {
            cluster.clipboard.emit(textSnapshot("本应用写回"))
            // 等到确实被采集器读走（读走即丢弃）再解除抑制，否则它会留到解除之后才被处理。
            delay(200)
        }

        cluster.settle()
        assertEquals(0, cluster.historySize, "自己写回的内容不该被当成一次新的复制")
    }

    @Test
    fun `重复复制只更新计数与时间戳`() = runBlocking {
        cluster.clipboard.emit(textSnapshot("重复复制"))
        cluster.awaitHistorySize(1)
        val first = cluster.repository.unpinned.value.single()

        cluster.clipboard.emit(textSnapshot("重复复制"))
        cluster.await { cluster.repository.unpinned.value.singleOrNull()?.numberOfCopies == 2 }
        val second = cluster.repository.unpinned.value.single()

        assertEquals(1, cluster.historySize, "内容相同不该新增一条")
        assertEquals(first.id, second.id, "必须保留原条目的身份（识别协程按旧 id 找回自己）")
        assertEquals(first.firstCopiedAt, second.firstCopiedAt, "首次复制时间不变")
        assertEquals(2, second.numberOfCopies)
        assertTrue(second.lastCopiedAt > first.lastCopiedAt, "最后复制时间要前移")
    }

    @Test
    fun `超限按最后复制时间淘汰 置顶项豁免`() = runBlocking {
        cluster.repository.setSettings(AppSettings(historyMaxCount = 2))

        cluster.clipboard.emit(textSnapshot("第一条"))
        cluster.clipboard.emit(textSnapshot("第二条"))
        cluster.clipboard.emit(textSnapshot("第三条"))

        cluster.awaitHistorySize(2)
        assertEquals(
            listOf("第三条", "第二条"),
            cluster.repository.unpinned.value.map { it.title },
            "超限时按最后复制时间从新到旧保留最近 N 条",
        )

        // 把当前最旧的一条钉住，再灌两条：上限只数未置顶，置顶项既不计入也不会被淘汰。
        val pinnedId = cluster.repository.unpinned.value.last().id
        cluster.repository.setPinned(listOf(pinnedId), true)
        assertEquals(1, cluster.repository.pinned.value.size)

        cluster.clipboard.emit(textSnapshot("第四条"))
        cluster.clipboard.emit(textSnapshot("第五条"))

        cluster.await { cluster.historySize == 3 }
        assertEquals(
            listOf(pinnedId),
            cluster.repository.pinned.value.map { it.id },
            "置顶项从不因为超限被淘汰",
        )
        assertEquals(2, cluster.repository.unpinned.value.size, "上限只约束未置顶条目")
        assertEquals(listOf("第五条", "第四条"), cluster.repository.unpinned.value.map { it.title })
    }

    @Test
    fun `清除只清未置顶 全部清除连置顶一起清`() = runBlocking {
        cluster.clipboard.emit(textSnapshot("甲"))
        cluster.clipboard.emit(textSnapshot("乙"))
        cluster.clipboard.emit(textSnapshot("丙"))
        cluster.awaitHistorySize(3)

        val pinnedId = cluster.repository.unpinned.value.first().id
        cluster.repository.setPinned(listOf(pinnedId), true)

        cluster.useCases.clearHistory(all = false)
        assertEquals(listOf(pinnedId), cluster.repository.pinned.value.map { it.id })
        assertTrue(cluster.repository.unpinned.value.isEmpty(), "普通清除保留置顶项，只清未置顶")

        cluster.useCases.clearHistory(all = true)
        assertEquals(0, cluster.historySize, "全部清除连置顶项一起清")
    }

    @Test
    fun `退出清空只在开启时生效 且不清置顶项`() = runBlocking {
        cluster.clipboard.emit(textSnapshot("甲"))
        cluster.clipboard.emit(textSnapshot("乙"))
        cluster.awaitHistorySize(2)
        val pinnedId = cluster.repository.unpinned.value.first().id
        cluster.repository.setPinned(listOf(pinnedId), true)

        // 关掉这项偏好：退出什么都不清。
        cluster.repository.setSettings(AppSettings(clearOnQuit = false))
        cluster.useCases.handleQuit()
        assertEquals(2, cluster.historySize, "未开启「退出时清空」就必须原样保留")

        // 打开：退出只清未置顶，置顶项留下（与「清除」同一条路径）。
        cluster.repository.setSettings(AppSettings(clearOnQuit = true))
        cluster.useCases.handleQuit()
        assertEquals(listOf(pinnedId), cluster.repository.pinned.value.map { it.id })
        assertTrue(cluster.repository.unpinned.value.isEmpty())
    }

    @Test
    fun `来源应用随复制一起记下`() = runBlocking {
        cluster.native.supportsApplicationInfo = true
        cluster.native.sourceApplication = SourceApplication("Safari", "com.apple.Safari")

        cluster.clipboard.emit(textSnapshot("来自网页"))
        cluster.awaitHistorySize(1)

        assertEquals(
            SourceApplication("Safari", "com.apple.Safari"),
            cluster.repository.unpinned.value.single().application,
            "平台能给出前台应用时，条目的来源要一起落库",
        )
    }

    @Test
    fun `连续复制的时间戳严格递增`() = runBlocking {
        // 两次连续复制必须落出**不同**的时间戳：墙钟只有毫秒，不足以区分快速连按。
        cluster.clipboard.emit(textSnapshot("第一次"))
        cluster.awaitHistorySize(1)
        val first = cluster.repository.unpinned.value.single()

        cluster.clipboard.emit(textSnapshot("第二次"))
        cluster.awaitHistorySize(2)

        val stamps = cluster.repository.unpinned.value.map { it.lastCopiedAt }
        assertNotEquals(stamps[0], stamps[1], "同一毫秒内的连续复制也要有确定的先后")
    }

    // -------------------------------------------------------------------------------------
    // 造快照
    // -------------------------------------------------------------------------------------

    private fun textSnapshot(text: String, types: List<String> = emptyList()) = ClipboardSnapshot(
        text = text,
        types = types,
        contents = listOf(ClipboardContent("public.utf8-plain-text", text.encodeToByteArray())),
    )

    private fun imageSnapshot(): ClipboardSnapshot {
        val bytes = TEST_PNG_BYTES.copyOf()
        return ClipboardSnapshot(
            image = ClipImage(bytes),
            types = listOf(PNG_CONTENT_TYPE),
            contents = listOf(ClipboardContent(PNG_CONTENT_TYPE, bytes)),
        )
    }

    private fun fileSnapshot(path: String) = ClipboardSnapshot(
        files = listOf(path),
        types = listOf(FILE_URL_CONTENT_TYPE),
        contents = listOf(ClipboardContent(FILE_URL_CONTENT_TYPE, "file://$path".encodeToByteArray())),
    )

    private fun richTextSnapshot(html: String) = ClipboardSnapshot(
        types = listOf("public.html"),
        contents = listOf(ClipboardContent("public.html", html.encodeToByteArray())),
    )
}
