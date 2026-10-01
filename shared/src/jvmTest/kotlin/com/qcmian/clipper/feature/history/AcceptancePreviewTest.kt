@file:OptIn(ExperimentalCoroutinesApi::class)

package com.qcmian.clipper.feature.history

import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.model.PNG_CONTENT_TYPE
import com.qcmian.clipper.core.settings.ClipFilterType
import com.qcmian.clipper.core.testutil.InProcessCluster
import com.qcmian.clipper.feature.history.state.ClipboardUiAction
import com.qcmian.clipper.feature.history.viewmodel.ClipboardViewModel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * F5（A 层）：**预览与展示**。
 *
 * 真存储 + 假原生能力（`ClipboardViewModel` 驱动）：断言的是用户能看到的那几样——图片条目的
 * 标题是否来自识别结果、识别原文能否完整取回、只写 HTML 的条目是否有标题、预览拿到的是不是**全文**。
 *
 * 识别跑在采集器的子协程里、预览按选中项读库（还带一次防抖），因此断言一律轮询而不是固定等待。
 */
class AcceptancePreviewTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `图片识别的结果作标题 完整原文留在载荷里`() = runBlocking<Unit> {
        val cluster = InProcessCluster()
        try {
            cluster.native.supportsTextRecognition = true
            cluster.native.recognizedText = RECOGNIZED
            cluster.start()
            startCapture(cluster)

            cluster.clipboard.emit(imageSnapshot())
            await { cluster.repository.unpinned.value.singleOrNull()?.hasRecognizedText == true }

            val meta = cluster.repository.unpinned.value.single()
            assertEquals(ClipFilterType.IMAGE, meta.kind)
            assertEquals(OCR_TITLE, meta.title, "标题取识别原文的前一段——它要能当列表里的一行")
            assertEquals(
                RECOGNIZED,
                cluster.repository.payload(meta.id)?.recognizedText,
                "完整原文留在载荷里：标题截断过，「复制图片文字」与预览读的都是载荷这一份",
            )
            assertTrue(RECOGNIZED.length > OCR_TITLE.length, "构造的就是「标题装不下」的那种长识别结果")
        } finally {
            cluster.close()
        }
    }

    @Test
    fun `复制识别原文写的是完整原文而不是截断过的标题`() = runBlocking<Unit> {
        val cluster = InProcessCluster()
        try {
            cluster.native.supportsTextRecognition = true
            cluster.native.recognizedText = RECOGNIZED
            cluster.start()
            val viewModel = ClipboardViewModel(cluster.repository, cluster.repository, cluster.useCases)

            cluster.clipboard.emit(imageSnapshot())
            await { cluster.repository.unpinned.value.singleOrNull()?.hasRecognizedText == true }

            viewModel.onAction(ClipboardUiAction.SelectOnly(0))
            viewModel.onAction(ClipboardUiAction.CopyExtractedText)

            await { cluster.clipboard.written?.text == RECOGNIZED }
            assertEquals(
                RECOGNIZED,
                cluster.clipboard.written?.text,
                "「复制图片文字」写的是完整识别原文，不是列表里那一行标题",
            )
        } finally {
            cluster.close()
        }
    }

    @Test
    fun `只写 HTML 不写纯文本的条目 从附加表示里提取标题`() = runBlocking<Unit> {
        val cluster = InProcessCluster()
        try {
            cluster.start()
            startCapture(cluster)

            cluster.clipboard.emit(
                ClipboardSnapshot(
                    types = listOf("public.html"),
                    contents = listOf(ClipboardContent("public.html", "<p>富文本标题</p>".encodeToByteArray())),
                ),
            )

            await { cluster.repository.unpinned.value.singleOrNull()?.title == "富文本标题" }
            assertEquals(
                ClipFilterType.RICH_TEXT,
                cluster.repository.unpinned.value.single().kind,
                "只有附加表示、没有纯文本的条目归入富文本",
            )
        } finally {
            cluster.close()
        }
    }

    @Test
    fun `预览显示全文 不被标题的上限截断`() = runBlocking<Unit> {
        val cluster = InProcessCluster()
        try {
            val body = "甲".repeat(ClipItem.MAX_TITLE_LENGTH + 500)
            cluster.start()
            cluster.seed(
                ClipItem(
                    id = "long",
                    text = body,
                    contents = listOf(ClipboardContent("public.utf8-plain-text", body.encodeToByteArray())),
                    firstCopiedAt = 1,
                    lastCopiedAt = 1,
                ),
            )
            // 落库的标题被截到上限。
            assertEquals(ClipItem.MAX_TITLE_LENGTH, cluster.repository.unpinned.value.single().title.length)

            val viewModel = ClipboardViewModel(cluster.repository, cluster.repository, cluster.useCases)
            viewModel.onAction(ClipboardUiAction.SelectOnly(0))
            await { viewModel.uiState.value.previewItem?.id == "long" }

            val preview = viewModel.uiState.value.previewItem
            assertEquals(body, preview?.previewText, "预览给的是全文")
            assertEquals(
                body.length,
                preview?.previewText?.length,
                "预览不能被标题的 1000 字符上限截断",
            )
        } finally {
            cluster.close()
        }
    }

    @Test
    fun `没有识别能力的平台不会给图片造标题`() = runBlocking<Unit> {
        val cluster = InProcessCluster()
        try {
            // 默认 supportsTextRecognition = false：连识别协程都不该起。
            cluster.native.recognizedText = RECOGNIZED
            cluster.start()
            startCapture(cluster)

            cluster.clipboard.emit(imageSnapshot())
            cluster.awaitHistorySize(1)
            cluster.settle()

            val meta = cluster.repository.unpinned.value.single()
            assertEquals("", meta.title, "平台不做识别时标题保持为空")
            assertEquals(false, meta.hasRecognizedText)
        } finally {
            cluster.close()
        }
    }

    // -------------------------------------------------------------------------------------

    /** 让采集器订阅上 `snapshots`，于是假剪贴板发出的快照会被真的捕获用例处理。 */
    private suspend fun startCapture(cluster: InProcessCluster): Job =
        cluster.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            cluster.useCases.captureClipboard.run()
        }

    private suspend fun await(timeoutMillis: Long = 10_000L, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(5L)
        }
    }

    private fun imageSnapshot(): ClipboardSnapshot {
        val bytes = PNG_BYTES.copyOf()
        return ClipboardSnapshot(
            image = ClipImage(bytes),
            types = listOf(PNG_CONTENT_TYPE),
            contents = listOf(ClipboardContent(PNG_CONTENT_TYPE, bytes)),
        )
    }

    private companion object {
        /** 一段比标题上限还长的识别原文。 */
        val RECOGNIZED: String = "甲".repeat(ClipItem.MAX_TITLE_LENGTH + 200) + "\n第二段正文"

        /** 落库标题 = 识别原文截断到上限。 */
        val OCR_TITLE: String = "甲".repeat(ClipItem.MAX_TITLE_LENGTH)

        val PNG_BYTES: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01)
    }
}
