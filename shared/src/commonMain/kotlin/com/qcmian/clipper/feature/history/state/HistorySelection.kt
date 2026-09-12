package com.qcmian.clipper.feature.history.state

/**
 * 当前高亮的是历史列表的哪一行——或是哪个页脚项。
 *
 * 由列表持有高亮时，`footerIndex` 为 `-1`。
 */
data class HistorySelection(
    val historyIndex: Int = 0,
    val footerIndex: Int = -1,
) {
    val isHistoryHighlighted: Boolean get() = footerIndex < 0
}

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

    /** `NavigationManager.highlightNext(allowCycle:)`。 */
    fun next(
        current: HistorySelection,
        lastIndex: Int,
        footerCount: Int,
        allowCycle: Boolean,
    ): HistorySelection = when {
        current.footerIndex >= 0 -> when {
            current.footerIndex < footerCount - 1 -> footer(current, current.footerIndex + 1, footerCount)
            // 在页脚内部循环。
            footerCount > 0 -> footer(current, 0, footerCount)
            else -> current
        }

        current.historyIndex < lastIndex -> history(current.historyIndex + 1, lastIndex)
        footerCount > 0 -> footer(current, 0, footerCount)
        allowCycle && lastIndex >= 0 -> history(0, lastIndex)
        else -> current
    }

    /** `NavigationManager.highlightPrevious`。 */
    fun previous(current: HistorySelection, lastIndex: Int): HistorySelection = when {
        current.footerIndex > 0 -> current.copy(footerIndex = current.footerIndex - 1)
        current.footerIndex == 0 -> history(maxOf(0, lastIndex), lastIndex)
        current.historyIndex > 0 -> current.copy(historyIndex = current.historyIndex - 1)
        else -> current
    }

    /** `NavigationManager.highlightLast`：最后一条历史会交棒给页脚。 */
    fun last(current: HistorySelection, lastIndex: Int, footerCount: Int): HistorySelection = when {
        current.footerIndex >= 0 -> current.copy(footerIndex = maxOf(0, footerCount - 1))
        lastIndex >= 0 && current.historyIndex == lastIndex && footerCount > 0 -> current.copy(footerIndex = 0)
        else -> HistorySelection(historyIndex = maxOf(0, lastIndex), footerIndex = -1)
    }
}
