package com.qcmian.clipper.feature.history.viewmodel

import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.SearchResult
import com.qcmian.clipper.core.domain.search.ClipSearch
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.feature.history.state.ClipboardUiState
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
) {
    private var searchJob: Job? = null
    private var lastSearchAt = 0L

    /** [query] 针对当前历史的结果，不触碰状态。 */
    fun resultsFor(query: String): List<SearchResult> =
        ClipSearch.search(query, items(), settings().searchMode)

    /** 对应 `History.searchQuery.didSet` + `Throttler(minimumDelay: 0.2)`。 */
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

    /** 对应 `History.searchQuery.didSet`：新查询会高亮第一个匹配项。 */
    private fun applyQuery(value: String) {
        val results = resultsFor(value)
        val firstUnpinned = results.indexOfFirst { it.item.isUnpinned }.coerceAtLeast(0)

        state.update {
            it.copy(
                appliedQuery = value,
                results = results,
                keyboardNavigating = true,
                footerSelection = -1,
                historySelection = if (value.isEmpty()) firstUnpinned else 0,
            )
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        val wasEmpty = state.value.appliedQuery.isEmpty()
        state.update { it.copy(query = "") }
        if (!wasEmpty) applyQuery("")
    }

    /** 对应 `KeyChord.deleteOneCharFromSearch`（⌃H）。 */
    fun deleteSearchChar() {
        val query = state.value.query
        if (query.isNotEmpty()) updateQuery(query.dropLast(1))
    }

    /** 对应 `KeyChord.deleteLastWordFromSearch`（⌃W）。 */
    fun deleteSearchWord() {
        val words = state.value.query.split(" ").filter { it.isNotEmpty() }.dropLast(1)
        updateQuery(if (words.isEmpty()) "" else words.joinToString(" ") + " ")
    }
}
