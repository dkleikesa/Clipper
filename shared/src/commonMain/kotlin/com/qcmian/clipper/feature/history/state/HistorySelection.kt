package com.qcmian.clipper.feature.history.state

/**
 * 当前高亮的是历史列表的哪一行——或是哪个页脚项。
 *
 * 由列表持有高亮时，`footerIndex` 为 `-1`。
 */
data class HistorySelection(
    val historyIndex: Int = 0,
    val footerIndex: Int = -1,
)

/**
 * 历史面板的高亮移动规则。
 *
 * 从 `ClipboardViewModel` 中抽离出来，使规则本身可以独立阅读——每个函数都是纯函数，
 * 由调用方决定何时把结果写回自己的状态，因而也决定了要触发哪些 `select` 副作用
 * （键盘导航标志、自动预览）。
 */
object HistoryNavigation {

    /** `NavigationManager.select(item:)`：列表重新接过高亮。 */
    fun history(index: Int, lastIndex: Int): HistorySelection =
        HistorySelection(historyIndex = index.coerceIn(0, maxOf(0, lastIndex)), footerIndex = -1)

    /** 把高亮移到某个页脚行，同时保留记住的列表位置。 */
    fun footer(current: HistorySelection, index: Int, footerCount: Int): HistorySelection =
        current.copy(footerIndex = index.coerceIn(0, maxOf(0, footerCount - 1)))

    /** 对应 `NavigationManager.highlightNext(allowCycle:)`：只在历史内移动（含置顶项），从不进入页脚。 */
    fun next(
        current: HistorySelection,
        lastIndex: Int,
        allowCycle: Boolean,
    ): HistorySelection = when {
        current.historyIndex < lastIndex -> history(current.historyIndex + 1, lastIndex)
        // 只在内容区循环：到底后回到第一条（含置顶）。
        allowCycle && lastIndex >= 0 -> history(0, lastIndex)
        else -> history(current.historyIndex, lastIndex)
    }

    /** `NavigationManager.highlightPrevious`：页脚被鼠标悬停高亮时，↑ 直接回到记住的列表位置。 */
    fun previous(current: HistorySelection, lastIndex: Int): HistorySelection =
        history(maxOf(0, current.historyIndex - 1), lastIndex)

    /** `NavigationManager.highlightLast`：停到最后一条历史，不再交棒给页脚。 */
    fun last(current: HistorySelection, lastIndex: Int): HistorySelection =
        history(maxOf(0, lastIndex), lastIndex)
}
