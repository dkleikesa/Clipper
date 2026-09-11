package com.qcmian.clipper.ui

import com.qcmian.clipper.ui.state.ClipboardUiState
import com.qcmian.clipper.ui.state.HistoryNavigation
import com.qcmian.clipper.ui.state.HistorySelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 掌管历史列表与页脚的键盘 / 鼠标导航，包括「键盘导航」与「悬停」之间的交接
 * （Maccy 的 `NavigationManager` + `HoverSelectionModifier`）。
 *
 * 它直接读写 [ClipboardViewModel] 持有的同一份 [ClipboardUiState]；只有「悬停页脚会关闭预览」
 * 这一个副作用以 [onFooterHovered] 注入，因此本类无需知道预览面板的其余细节。
 */
internal class HistoryNavigationController(
    private val state: MutableStateFlow<ClipboardUiState>,
    private val footerCount: () -> Int,
    private val onFooterHovered: () -> Unit,
) {
    /** 对应 `NavigationManager.isKeyboardNavigating` 中待处理的悬停。 */
    private var pendingHoverSelection = -1

    fun onPointerMoved() {
        val current = state.value
        if (!current.keyboardNavigating) return
        val pending = pendingHoverSelection
        pendingHoverSelection = -1
        state.update { latest ->
            val shouldApply = pending >= 0 && latest.footerSelection < 0
            latest.copy(
                keyboardNavigating = false,
                historySelection = if (shouldApply) {
                    pending.coerceIn(0, maxOf(0, latest.results.lastIndex))
                } else {
                    latest.historySelection
                },
            )
        }
    }

    /** 对应 `HoverSelectionModifier`。 */
    fun hoverHistory(index: Int) {
        val current = state.value
        if (!current.keyboardNavigating) {
            if (current.footerSelection >= 0 || current.historySelection != index) {
                state.update {
                    it.copy(
                        historySelection = index.coerceIn(0, maxOf(0, it.results.lastIndex)),
                        footerSelection = -1,
                    )
                }
            }
        } else {
            pendingHoverSelection = index
        }
    }

    /** `FooterItemView.onHover`：悬停页脚会关闭预览。 */
    fun hoverFooter(index: Int) {
        selectFooter(index)
        if (state.value.previewOpen) onFooterHovered()
    }

    fun selectHistory(index: Int) {
        val target = HistoryNavigation.history(index, state.value.results.lastIndex)
        state.update {
            it.copy(
                keyboardNavigating = true,
                historySelection = target.historyIndex,
                footerSelection = target.footerIndex,
            )
        }
    }

    /** 对应 `NavigationManager.highlightNext(allowCycle:)`。 */
    fun moveNext(allowCycle: Boolean) {
        applyMove(HistoryNavigation.next(currentSelection(), state.value.results.lastIndex, footerCount(), allowCycle))
    }

    /** 对应 `NavigationManager.highlightPrevious`。 */
    fun movePrevious() {
        applyMove(HistoryNavigation.previous(currentSelection(), state.value.results.lastIndex))
    }

    /** 对应 `NavigationManager.highlightLast`：最后一条历史会交棒给页脚。 */
    fun moveToLast() {
        applyMove(HistoryNavigation.last(currentSelection(), state.value.results.lastIndex, footerCount()))
    }

    /** `⌃K` 只在第一个条目未被高亮时向上移动，见 Maccy #1055。 */
    fun canMovePreviousWithCtrlK(): Boolean = !isFirstItemHighlighted()

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

    /** 对应 `NavigationManager.isFirstItemHighlighted`。 */
    private fun isFirstItemHighlighted(): Boolean =
        currentSelection().let { it.isHistoryHighlighted && it.historyIndex == 0 }
}
