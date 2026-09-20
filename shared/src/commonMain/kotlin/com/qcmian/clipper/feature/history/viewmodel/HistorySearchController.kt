package com.qcmian.clipper.feature.history.viewmodel

import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.SearchResult
import com.qcmian.clipper.core.domain.search.ClipSearch
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.feature.history.state.ClipboardUiState
import com.qcmian.clipper.feature.history.state.defaultSelectionIndex
import com.qcmian.clipper.core.util.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 掌管搜索框：查询节流、搜索本身，
 * 以及「清空 / 删一个字符 / 删一个词」这几种变体。
 *
 * 它直接读写 [ClipboardViewModel] 持有的同一份 [ClipboardUiState]，因此不需要注入
 * 「读状态 / 写状态」的回调。
 */
internal class HistorySearchController(
    private val state: MutableStateFlow<ClipboardUiState>,
    private val settings: () -> AppSettings,
    private val items: () -> List<ClipItem>,
    private val scope: CoroutineScope,
    /** 新的查询结果落地时调用：键盘导航重新接管选择。 */
    private val onQueryApplied: () -> Unit = {},
) {
    private var searchJob: Job? = null
    private var lastSearchAt = 0L

    /** [query] 针对当前历史的结果，不触碰状态。类型筛选在这里先于搜索生效。 */
    fun resultsFor(query: String): List<SearchResult> {
        val filtered = filterByType(items())
        return ClipSearch.search(query, filtered, settings().searchMode)
    }

    /** 按设置里的类型集合过滤；置顶项永远保留，不受类型筛选影响。 */
    private fun filterByType(items: List<ClipItem>): List<ClipItem> {
        val types = settings().filterTypes
        // 空集表示用户取消了所有类型：未置顶内容一条都不显示，置顶项仍保留。
        return items.filter { it.isPinned || it.clipType in types }
    }

    fun updateQuery(value: String) {
        state.update { it.copy(query = value) }

        val now = currentTimeMillis()
        val elapsed = now - lastSearchAt
        val throttle = state.value.settings.searchThrottleMillis
        searchJob?.cancel()

        if (value.isEmpty() || elapsed >= throttle) {
            lastSearchAt = now
            applyQuery(value)
        } else {
            searchJob = scope.launch {
                delay(throttle - elapsed)
                lastSearchAt = currentTimeMillis()
                applyQuery(value)
            }
        }
    }

    /** 新查询会高亮第一个匹配项。 */
    private fun applyQuery(value: String) {
        val results = resultsFor(value)
        // 清空搜索时落回内容区第一条，与面板打开时的默认落点一致（见 `defaultSelectionIndex`）。
        val firstUnpinned = results.defaultSelectionIndex()

        onQueryApplied()
        state.update {
            it.copy(
                appliedQuery = value,
                results = results,
                footerSelection = -1,
                historySelection = if (value.isEmpty()) firstUnpinned else 0,
                // 新查询重置了选中项：允许界面把它滚进可视区。
                historyScrollToken = it.historyScrollToken + 1,
            )
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        val wasEmpty = state.value.appliedQuery.isEmpty()
        state.update { it.copy(query = "") }
        if (!wasEmpty) applyQuery("")
    }
}
