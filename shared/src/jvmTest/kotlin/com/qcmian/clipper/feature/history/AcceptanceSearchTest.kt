@file:OptIn(ExperimentalCoroutinesApi::class)

package com.qcmian.clipper.feature.history

import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.domain.model.PNG_CONTENT_TYPE
import com.qcmian.clipper.core.domain.search.ClipSearch
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.ClipFilterType
import com.qcmian.clipper.core.settings.SortBy
import com.qcmian.clipper.core.settings.SortOrder
import com.qcmian.clipper.core.testutil.InProcessCluster
import com.qcmian.clipper.feature.history.state.ClipboardUiAction
import com.qcmian.clipper.feature.history.state.ClipboardUiState
import com.qcmian.clipper.feature.history.state.DeepSearchState
import com.qcmian.clipper.feature.history.viewmodel.ClipboardViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * F3（A 层）：**搜索**。
 *
 * 这条链的用户可见结果由 `ClipboardViewModel` 决定：标题匹配、类型筛选、排序、以及「全文搜索
 * 结果追加在标题命中之后、并在删除 / 筛选变化后收敛」。因此这里驱动**真的 ViewModel**（真
 * 存储、真导航状态），而不是把它的规则抄一遍。
 *
 * `viewModelScope` 需要主调度器，测试里换成 `Dispatchers.Default`（真实线程，非虚拟时间），
 * 断言一律用「轮询到条件成立」而不是固定等待。
 */
class AcceptanceSearchTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `五档顺序在同一次查询里成立`() = runBlocking<Unit> {
        val (cluster, viewModel) = startClusterWithViewModel()
        try {
            cluster.seedText("subsequence", "my gist list", lastCopiedAt = 1)
            cluster.seedText("substring", "xgitx", lastCopiedAt = 2)
            cluster.seedText("prefix", "gitignore", lastCopiedAt = 3)
            cluster.seedText("word", "git commit", lastCopiedAt = 4)
            cluster.seedText("exact", "git", lastCopiedAt = 5)

            viewModel.onAction(ClipboardUiAction.UpdateQuery("git"))

            awaitIds(
                viewModel,
                listOf("exact", "word", "prefix", "substring", "subsequence"),
            )
        } finally {
            cluster.close()
        }
    }

    @Test
    fun `多词查询是 AND`() = runBlocking<Unit> {
        val (cluster, viewModel) = startClusterWithViewModel()
        try {
            cluster.seedText("both", "gi co", lastCopiedAt = 1)
            cluster.seedText("only-first", "gi only", lastCopiedAt = 2)
            cluster.seedText("only-second", "co only", lastCopiedAt = 3)

            viewModel.onAction(ClipboardUiAction.UpdateQuery("gi co"))

            awaitIds(viewModel, listOf("both"))
        } finally {
            cluster.close()
        }
    }

    @Test
    fun `类型筛选生效`() = runBlocking<Unit> {
        val (cluster, viewModel) = startClusterWithViewModel()
        try {
            cluster.seedText("text", "一段文本", lastCopiedAt = 1)
            cluster.seed(
                ClipItem(
                    id = "image",
                    contents = listOf(ClipboardContent(PNG_CONTENT_TYPE, PNG_BYTES.copyOf())),
                    firstCopiedAt = 2,
                    lastCopiedAt = 2,
                ),
            )

            // 空查询下两条都在。
            awaitIds(viewModel, listOf("image", "text"))

            viewModel.onAction(
                ClipboardUiAction.UpdateSettings { it.copy(filterTypes = setOf(ClipFilterType.TEXT)) },
            )
            awaitIds(viewModel, listOf("text"))

            // 空集 = 用户取消了所有类型：未置顶内容一条都不显示。
            viewModel.onAction(
                ClipboardUiAction.UpdateSettings { it.copy(filterTypes = emptySet()) },
            )
            awaitIds(viewModel, emptyList())
        } finally {
            cluster.close()
        }
    }

    @Test
    fun `排序字段与方向生效`() = runBlocking<Unit> {
        val (cluster, viewModel) = startClusterWithViewModel()
        try {
            cluster.seedText("a", "甲", lastCopiedAt = 1, copies = 1)
            cluster.seedText("b", "乙", lastCopiedAt = 1, copies = 3)
            cluster.seedText("c", "丙", lastCopiedAt = 1, copies = 5)

            viewModel.onAction(
                ClipboardUiAction.UpdateSettings {
                    it.copy(sortBy = SortBy.NUMBER_OF_COPIES, sortOrder = SortOrder.DESCENDING)
                },
            )
            awaitIds(viewModel, listOf("c", "b", "a"))

            viewModel.onAction(
                ClipboardUiAction.UpdateSettings { it.copy(sortOrder = SortOrder.ASCENDING) },
            )
            awaitIds(viewModel, listOf("a", "b", "c"))
        } finally {
            cluster.close()
        }
    }

    @Test
    fun `深搜结果追加在标题命中之后 并在删除后收敛`() = runBlocking<Unit> {
        val (cluster, viewModel) = startClusterWithViewModel()
        try {
            cluster.seedText("title-hit", "量子纠缠 笔记", lastCopiedAt = 1)
            // 标题是正文按 MAX_TITLE_LENGTH 截断后落库的：查询词只在第 1000 字符之外才出现。
            val bodyOnly = "甲".repeat(ClipItem.MAX_TITLE_LENGTH) + " 量子纠缠"
            cluster.seed(
                ClipItem(
                    id = "body-only",
                    text = bodyOnly,
                    contents = listOf(ClipboardContent("public.utf8-plain-text", bodyOnly.encodeToByteArray())),
                    firstCopiedAt = 2,
                    lastCopiedAt = 2,
                ),
            )

            viewModel.onAction(ClipboardUiAction.UpdateQuery("量子"))
            // 只搜标题：正文深处那条还没被碰到。
            awaitIds(viewModel, listOf("title-hit"))

            viewModel.onAction(ClipboardUiAction.RunDeepSearch)
            awaitState(viewModel) {
                it.deepSearch == DeepSearchState.DONE &&
                    it.deepSearchHits == 1 &&
                    it.results.map { result -> result.meta.id } == listOf("title-hit", "body-only")
            }

            // 删掉正文命中那条：它必须从结果里消失，入口上的计数也要跟着收敛。
            cluster.repository.delete(listOf("body-only"))
            awaitState(viewModel) {
                it.results.map { result -> result.meta.id } == listOf("title-hit") &&
                    it.deepSearchHits == 0
            }
        } finally {
            cluster.close()
        }
    }

    @Test
    fun `筛选变化让上一次的深搜结果作废`() = runBlocking<Unit> {
        val (cluster, viewModel) = startClusterWithViewModel()
        try {
            cluster.seedText("title-hit", "量子纠缠 笔记", lastCopiedAt = 1)
            val bodyOnly = "甲".repeat(ClipItem.MAX_TITLE_LENGTH) + " 量子纠缠"
            cluster.seed(
                ClipItem(
                    id = "body-only",
                    text = bodyOnly,
                    contents = listOf(ClipboardContent("public.utf8-plain-text", bodyOnly.encodeToByteArray())),
                    firstCopiedAt = 2,
                    lastCopiedAt = 2,
                ),
            )

            viewModel.onAction(ClipboardUiAction.UpdateQuery("量子"))
            awaitIds(viewModel, listOf("title-hit"))
            viewModel.onAction(ClipboardUiAction.RunDeepSearch)
            awaitState(viewModel) { it.deepSearch == DeepSearchState.DONE && it.deepSearchHits == 1 }

            // 换成只显示图片：文本条目全部退出结果，深搜那一条也随之作废。
            viewModel.onAction(
                ClipboardUiAction.UpdateSettings { it.copy(filterTypes = setOf(ClipFilterType.IMAGE)) },
            )
            awaitState(viewModel) {
                it.results.isEmpty() && it.deepSearchHits == 0 && it.deepSearch == DeepSearchState.AVAILABLE
            }
        } finally {
            cluster.close()
        }
    }

    @Test
    fun `正文命中的高亮区间落在正文对应位置`() = runBlocking<Unit> {
        val cluster = InProcessCluster()
        try {
            val body = "0123456789 量子纠缠 收尾"
            cluster.seed(
                ClipItem(
                    id = "body",
                    text = body,
                    contents = listOf(ClipboardContent("public.utf8-plain-text", body.encodeToByteArray())),
                    firstCopiedAt = 1,
                    lastCopiedAt = 1,
                ),
            )

            val texts = cluster.repository.texts(listOf("body"))
            val hit = ClipSearch.searchTexts("量子", texts.map { it.text }).single()
            val range = hit.ranges.first()

            assertEquals(
                "量子",
                texts[hit.index].text.substring(range.first, range.last + 1),
                "区间是相对**正文**算的，必须能直接切出命中的那几个字",
            )
        } finally {
            cluster.close()
        }
    }

    // -------------------------------------------------------------------------------------

    /**
     * 起一份真存储，并把 `ClipboardViewModel` 装起来。
     *
     * 先 `start()` 等偏好载入，再改节流——否则仓库启动时会用存储里的默认值把这次改动覆盖掉。
     */
    private suspend fun startClusterWithViewModel(): Pair<InProcessCluster, ClipboardViewModel> {
        val cluster = InProcessCluster()
        cluster.start()
        cluster.repository.setSettings(AppSettings(searchThrottleMillis = 0))
        val viewModel = ClipboardViewModel(cluster.repository, cluster.repository, cluster.useCases)
        return cluster to viewModel
    }

    /** 等结果按 id 恰好等于 [expected]。 */
    private suspend fun awaitIds(viewModel: ClipboardViewModel, expected: List<String>) {
        awaitState(viewModel) { state ->
            state.results.map { it.meta.id } == expected
        }
    }

    private suspend fun awaitState(
        viewModel: ClipboardViewModel,
        predicate: (ClipboardUiState) -> Boolean,
    ) {
        withTimeout(AWAIT_TIMEOUT_MILLIS) { viewModel.uiState.first(predicate) }
    }

    private companion object {
        const val AWAIT_TIMEOUT_MILLIS = 10_000L

        val PNG_BYTES: ByteArray = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01)
    }
}
