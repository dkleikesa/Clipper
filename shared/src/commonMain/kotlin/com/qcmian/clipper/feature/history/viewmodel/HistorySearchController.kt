package com.qcmian.clipper.feature.history.viewmodel

import com.qcmian.clipper.core.util.currentTimeMillis
import com.qcmian.clipper.feature.history.state.ClipboardUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 掌管搜索框的**节流**，以及「清空」这一种变体。
 *
 * 它只把 [ClipboardUiState.appliedQuery] 落到位，并回调 [onQueryApplied]；**不自己算结果**。
 * 匹配的成本与历史条数成正比（模糊模式是 O(查询长 × 标题长) 的编辑距离），必须放到后台线程、
 * 并且允许被后一次输入取消——那两件事由状态持有者接手（见 `ClipboardViewModel.refresh`）。
 */
internal class HistorySearchController(
    private val state: MutableStateFlow<ClipboardUiState>,
    private val scope: CoroutineScope,
    /** [ClipboardUiState.appliedQuery] 落地时调用。 */
    private val onQueryApplied: () -> Unit,
) {
    private var searchJob: Job? = null
    private var lastSearchAt = 0L

    fun updateQuery(value: String) {
        state.update { it.copy(query = value) }

        val now = currentTimeMillis()
        val elapsed = now - lastSearchAt
        val throttle = state.value.settings.searchThrottleMillis
        searchJob?.cancel()

        // 清空是即时的：用户按了清空却要等一个节流周期，手感上是「没反应」。
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

    private fun applyQuery(value: String) {
        state.update { it.copy(appliedQuery = value) }
        onQueryApplied()
    }

    fun clearSearch() {
        searchJob?.cancel()
        val wasEmpty = state.value.appliedQuery.isEmpty()
        state.update { it.copy(query = "") }
        if (!wasEmpty) applyQuery("")
    }
}
