package com.qcmian.clipper.feature.history.viewmodel

import com.qcmian.clipper.feature.history.state.ClipboardUiState
import com.qcmian.clipper.feature.history.state.HistoryNavigation
import com.qcmian.clipper.feature.history.state.HistorySelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 掌管历史列表与页脚的键盘 / 鼠标导航。
 *
 * 键盘导航只在内容区（历史 + 置顶）内移动，页脚永远不会被键盘选中；页脚高亮只来自鼠标悬停。
 * 鼠标悬停总是直接接管高亮——它不需要「等下一次移动」的中转，因此键盘把高亮留在了哪里都不影响
 * 悬停恢复工作。
 *
 * 它直接读写 [ClipboardViewModel] 持有的同一份 [ClipboardUiState]，不碰预览面板——
 * 预览开关是持久化的用户选择，鼠标划过页脚不该改变它（见 `AppSettings.previewOpen`）。
 */
internal class HistoryNavigationController(
    private val state: MutableStateFlow<ClipboardUiState>,
    private val footerCount: () -> Int,
) {
    /**
     * 最近一次导航输入是否来自键盘。
     *
     * 键盘移动置 `true`，任何鼠标输入（移动、悬停）置 `false`。它只在本类内部参与判断，
     * 界面从不渲染，因此留在这里而不是 [ClipboardUiState]。
     * 查询结果更新、面板重新打开时由外部调用 [resetKeyboardNavigation] 复位。
     */
    private var keyboardNavigating = true

    /** 新的查询结果 / 面板重新打开：键盘导航重新接管选择。 */
    fun resetKeyboardNavigation() {
        keyboardNavigating = true
    }

    /** 鼠标移动会结束键盘导航，悬停从此直接接管选择。 */
    fun onPointerMoved() {
        keyboardNavigating = false
    }

    /** 鼠标悬停行：直接接管高亮（清掉可能残留的页脚选中），列表随鼠标走。 */
    fun hoverHistory(index: Int) {
        keyboardNavigating = false
        val current = state.value
        if (current.footerSelection >= 0 || current.historySelection != index) {
            state.update {
                it.copy(
                    historySelection = index.coerceIn(0, maxOf(0, it.results.lastIndex)),
                    footerSelection = -1,
                )
            }
        }
    }

    /** `FooterItemView.onHover`；`index < 0` 表示鼠标已离开页脚。 */
    fun hoverFooter(index: Int) {
        if (index < 0) {
            if (state.value.footerSelection >= 0) {
                state.update { it.copy(footerSelection = -1) }
            }
            return
        }
        selectFooter(index)
    }

    fun selectHistory(index: Int) {
        keyboardNavigating = true
        val target = HistoryNavigation.history(index, state.value.results.lastIndex)
        state.update {
            it.copy(
                historySelection = target.historyIndex,
                footerSelection = target.footerIndex,
                // 键盘驱动的选中变化：允许界面把新选中的行滚进可视区。
                historyScrollToken = it.historyScrollToken + 1,
            )
        }
    }

    /** 只在历史内移动 / 循环，不进页脚。 */
    fun moveNext(allowCycle: Boolean) {
        applyMove(HistoryNavigation.next(currentSelection(), state.value.results.lastIndex, allowCycle))
    }

    fun movePrevious() {
        applyMove(HistoryNavigation.previous(currentSelection(), state.value.results.lastIndex))
    }

    /** 停到最后一条历史。 */
    fun moveToLast() {
        applyMove(HistoryNavigation.last(currentSelection(), state.value.results.lastIndex))
    }

    private fun selectFooter(index: Int) {
        val target = HistoryNavigation.footer(currentSelection(), index, footerCount())
        state.update { it.copy(footerSelection = target.footerIndex) }
    }

    private fun currentSelection(): HistorySelection =
        HistorySelection(state.value.historySelection, state.value.footerSelection)

    /**
     * 把导航结果写回状态：移到列表会重新启用键盘导航（`selectHistory`），
     * 而在页脚内部移动只移动高亮。
     */
    private fun applyMove(target: HistorySelection) {
        val current = currentSelection()
        if (target == current) return
        if (target.footerIndex < 0 || target.historyIndex != current.historyIndex) {
            selectHistory(target.historyIndex)
        } else {
            state.update { it.copy(footerSelection = target.footerIndex) }
        }
    }
}
