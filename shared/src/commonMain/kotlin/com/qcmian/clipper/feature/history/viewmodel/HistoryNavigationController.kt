package com.qcmian.clipper.feature.history.viewmodel

import com.qcmian.clipper.feature.history.state.ClipboardUiState
import com.qcmian.clipper.feature.history.state.HistoryNavigation
import com.qcmian.clipper.feature.history.state.HistorySelection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 掌管历史列表与页脚的键盘 / 鼠标导航，以及**选中集**的增删。
 *
 * 键盘导航只在内容区（历史 + 置顶）内移动，页脚永远不会被键盘选中；页脚高亮只来自鼠标悬停。
 *
 * 选中集有两条独立的输入通道：
 *
 * - **键盘 / 点击**——显式动作，[selectHistory] 折叠回单选，[extendSelectionTo]（`⇧`）与
 *   [toggleSelection]（`⌘`）在其上生长；
 * - **悬停**——多选态下只移动光标（鼠标划过列表不该把 `⌘` 攒出来的选中集冲掉），
 *   单选态下照旧直接接管选中（鼠标指哪就是哪）。
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

    /**
     * 鼠标悬停行：直接接管光标（鼠标指哪就是哪）。
     *
     * 只有两种情形下**不动选中集**，两者都是为了保住 `⌘` / `⇧` 点击的基准：
     *
     * - 已经攒出多选：鼠标划过列表不该把它冲掉；
     * - 此刻按住 `⌘` / `⇧`：用户正要用点击去改选中集，鼠标自己不能先把基准换掉
     *   （否则「选好第 9 条，再 `⌘` 点第 13 条」会因为划过第 13 行而变成空操作）。
     *
     * 与键盘导航一样，悬停**不**递增 `historyScrollToken`：鼠标划过列表时行只高亮、列表不动，
     * 否则会出现「悬停 → 选中变化 → 滚动 → 鼠标下换了行」的循环。
     */
    fun hoverHistory(index: Int, selectionModifierHeld: Boolean) {
        keyboardNavigating = false
        val current = state.value
        if (current.footerSelection < 0 && current.historySelection == index) return
        state.update { latest ->
            if (latest.selectedIds.size > 1 || selectionModifierHeld) {
                latest.copy(
                    historySelection = index.coerceIn(0, maxOf(0, latest.results.lastIndex)),
                    footerSelection = -1,
                )
            } else {
                latest.withSelection(
                    HistoryNavigation.single(index, latest.results.lastIndex, latest.resultIds),
                    scroll = false,
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

    /** 单选落点：光标、锚点与选中集一起落到 [index]（点击、普通方向键、`⌘1`… 都走这里）。 */
    fun selectHistory(index: Int) {
        keyboardNavigating = true
        state.update { latest ->
            latest.withSelection(
                HistoryNavigation.single(index, latest.results.lastIndex, latest.resultIds),
                // 键盘驱动的选中变化：允许界面把新选中的行滚进可视区。
                scroll = true,
            )
        }
    }

    /** `⇧` 连续选中到 [index]。 */
    fun extendSelectionTo(index: Int) {
        keyboardNavigating = true
        state.update { latest ->
            latest.withSelection(
                HistoryNavigation.range(latest.selection(), index, latest.results.lastIndex, latest.resultIds),
                scroll = true,
            )
        }
    }

    /** `⌘` 切换 [index] 的多选状态。 */
    fun toggleSelection(index: Int) {
        keyboardNavigating = true
        state.update { latest ->
            latest.withSelection(
                HistoryNavigation.toggle(latest.selection(), index, latest.results.lastIndex, latest.resultIds),
                scroll = false,
            )
        }
    }

    /** `⌘A`：全选。光标不动，列表也不滚。 */
    fun selectAll() {
        keyboardNavigating = true
        state.update { latest ->
            latest.withSelection(HistoryNavigation.all(latest.selection(), latest.resultIds), scroll = false)
        }
    }

    /** `Esc`：清空多选，退回光标那一条。 */
    fun clearSelection() {
        state.update { latest ->
            latest.withSelection(
                HistoryNavigation.collapse(latest.selection(), latest.results.lastIndex, latest.resultIds),
                scroll = false,
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

    private fun currentSelection(): HistorySelection = state.value.selection()

    /**
     * 把导航结果写回状态：移到列表会重新启用键盘导航（`selectHistory`，顺带折叠到单选），
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

/** 把状态里的「光标 / 锚点 / 选中集」取出来交给纯规则。 */
private fun ClipboardUiState.selection(): HistorySelection =
    HistorySelection(historySelection, footerSelection, selectionAnchor, selectedIds)

/** 把纯规则算出来的选中结果写回状态；[scroll] 决定界面要不要把光标行滚进可视区。 */
private fun ClipboardUiState.withSelection(selection: HistorySelection, scroll: Boolean) = copy(
    historySelection = selection.historyIndex,
    footerSelection = selection.footerIndex,
    selectionAnchor = selection.anchorIndex,
    selectedIds = selection.selectedIds,
    historyScrollToken = if (scroll) historyScrollToken + 1 else historyScrollToken,
)
