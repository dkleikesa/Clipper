package com.qcmian.clipper.ui.state

/**
 * Which row of the history list — or which footer item — is highlighted.
 *
 * `footerIndex` is `-1` while the list owns the highlight, exactly like Maccy's
 * `NavigationManager`/`Footer` pair.
 */
data class HistorySelection(
    val historyIndex: Int = 0,
    val footerIndex: Int = -1,
) {
    val isHistoryHighlighted: Boolean get() = footerIndex < 0
}

/**
 * Port of Maccy's `NavigationManager`: the highlight movement rules of the history panel.
 *
 * Extracted from `ClipboardViewModel` so the rules read on their own — every function is pure
 * and the caller decides when to write the result back to its state, and therefore which of
 * the `select` side effects (keyboard navigation flag, auto preview) that implies.
 */
object HistoryNavigation {

    /** `NavigationManager.select(item:)`: the list takes the highlight back. */
    fun history(index: Int, lastIndex: Int): HistorySelection =
        HistorySelection(historyIndex = index.coerceIn(0, maxOf(0, lastIndex)), footerIndex = -1)

    /** Moves the highlight to a footer row, keeping the remembered list position. */
    fun footer(current: HistorySelection, index: Int, footerCount: Int): HistorySelection =
        current.copy(footerIndex = index.coerceIn(0, maxOf(0, footerCount - 1)))

    /** `NavigationManager.highlightNext(allowCycle:)`. */
    fun next(
        current: HistorySelection,
        lastIndex: Int,
        footerCount: Int,
        allowCycle: Boolean,
    ): HistorySelection = when {
        current.footerIndex >= 0 -> when {
            current.footerIndex < footerCount - 1 -> footer(current, current.footerIndex + 1, footerCount)
            // Maccy wraps around inside the footer.
            footerCount > 0 -> footer(current, 0, footerCount)
            else -> current
        }

        current.historyIndex < lastIndex -> history(current.historyIndex + 1, lastIndex)
        footerCount > 0 -> footer(current, 0, footerCount)
        allowCycle && lastIndex >= 0 -> history(0, lastIndex)
        else -> current
    }

    /** `NavigationManager.highlightPrevious`. */
    fun previous(current: HistorySelection, lastIndex: Int): HistorySelection = when {
        current.footerIndex > 0 -> current.copy(footerIndex = current.footerIndex - 1)
        current.footerIndex == 0 -> history(maxOf(0, lastIndex), lastIndex)
        current.historyIndex > 0 -> current.copy(historyIndex = current.historyIndex - 1)
        else -> current
    }

    /** `NavigationManager.highlightLast`: the last history item hands over to the footer. */
    fun last(current: HistorySelection, lastIndex: Int, footerCount: Int): HistorySelection = when {
        current.footerIndex >= 0 -> current.copy(footerIndex = maxOf(0, footerCount - 1))
        lastIndex >= 0 && current.historyIndex == lastIndex && footerCount > 0 -> current.copy(footerIndex = 0)
        else -> HistorySelection(historyIndex = maxOf(0, lastIndex), footerIndex = -1)
    }
}
