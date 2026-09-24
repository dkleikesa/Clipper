package com.qcmian.clipper.feature.history.state

import com.qcmian.clipper.core.domain.model.SearchResult

/**
 * 高亮在哪一行——或是哪个页脚项——以及选中了哪些条目。
 *
 * 由列表持有高亮时 `footerIndex` 为 `-1`。三个字段各管一件事：[historyIndex] 是**光标**
 * （预览与自动滚动只跟它走），[anchorIndex] 是 `⇧` 连续选中的起点，[selectedIds] 是**真值**。
 */
data class HistorySelection(
    val historyIndex: Int = 0,
    val footerIndex: Int = -1,
    val anchorIndex: Int = 0,
    val selectedIds: Set<String> = emptySet(),
)

/**
 * 列表的默认高亮落点：**内容区（未置顶）第一条**，而不是最顶上那条置顶项——置顶是常驻的
 * 参考项，默认该跟随最近复制的内容。全是置顶或列表为空时退回第一条。
 */
fun List<SearchResult>.defaultSelectionIndex(): Int =
    indexOfFirst { it.meta.isUnpinned }.coerceAtLeast(0)

/**
 * 历史面板的选中规则。
 *
 * 每个函数都是纯函数，由调用方决定何时把结果写回状态（因而也决定了触发哪些副作用）。
 * 都收一个 [ids]（按显示顺序的结果 id），因为**选中集按 id 记**，只有内部按下标取条目时才用得到。
 */
object HistoryNavigation {

    /** 只移动光标（页脚失焦），选中集原样保留——是否折叠由调用方决定。 */
    fun cursor(current: HistorySelection, index: Int, lastIndex: Int): HistorySelection =
        current.copy(
            historyIndex = index.coerceIn(0, maxOf(0, lastIndex)),
            footerIndex = -1,
        )

    /** 单选：光标、锚点与选中集一起落到 [index]。 */
    fun single(index: Int, lastIndex: Int, ids: List<String>): HistorySelection =
        collapse(HistorySelection(historyIndex = index.coerceIn(0, maxOf(0, lastIndex))), lastIndex, ids)

    /** `⇧`：锚点不动，选中集是锚点到 [index] 的整段（按 min/max 取，因此会原路伸缩）。 */
    fun range(current: HistorySelection, index: Int, lastIndex: Int, ids: List<String>): HistorySelection {
        if (ids.isEmpty()) return cursor(current, index, lastIndex)
        val last = ids.lastIndex
        val target = index.coerceIn(0, maxOf(0, lastIndex)).coerceAtMost(last)
        val anchor = current.anchorIndex.coerceIn(0, last)
        val from = minOf(anchor, target)
        val to = maxOf(anchor, target)
        return current.copy(
            historyIndex = target,
            footerIndex = -1,
            selectedIds = ids.subList(from, to + 1).toSet(),
        )
    }

    /** `⌘`：切换 [index] 的选中状态，光标与锚点都落到它。取消到不剩时保留光标那一条。 */
    fun toggle(current: HistorySelection, index: Int, lastIndex: Int, ids: List<String>): HistorySelection {
        if (ids.isEmpty()) return cursor(current, index, lastIndex)
        val target = index.coerceIn(0, maxOf(0, lastIndex)).coerceAtMost(ids.lastIndex)
        val id = ids[target]
        val selected = if (id in current.selectedIds) current.selectedIds - id else current.selectedIds + id
        return current.copy(
            historyIndex = target,
            footerIndex = -1,
            anchorIndex = target,
            selectedIds = selected.ifEmpty { setOf(id) },
        )
    }

    /** `Esc`：清空多选，退回光标那一条。 */
    fun collapse(current: HistorySelection, lastIndex: Int, ids: List<String>): HistorySelection {
        val target = current.historyIndex.coerceIn(0, maxOf(0, lastIndex))
        val id = ids.getOrNull(target)
        return current.copy(
            historyIndex = target,
            footerIndex = -1,
            anchorIndex = target,
            selectedIds = id?.let { setOf(it) }.orEmpty(),
        )
    }

    /** 把高亮移到某个页脚行，保留记住的列表位置与选中集。 */
    fun footer(current: HistorySelection, index: Int, footerCount: Int): HistorySelection =
        current.copy(footerIndex = index.coerceIn(0, maxOf(0, footerCount - 1)))

    /** 只在历史内移动（含置顶项），从不进入页脚。 */
    fun next(
        current: HistorySelection,
        lastIndex: Int,
        allowCycle: Boolean,
    ): HistorySelection = when {
        current.historyIndex < lastIndex -> cursor(current, current.historyIndex + 1, lastIndex)
        // 到底后回到第一条（含置顶）。
        allowCycle && lastIndex >= 0 -> cursor(current, 0, lastIndex)
        else -> cursor(current, current.historyIndex, lastIndex)
    }

    /** 页脚被鼠标悬停高亮时，↑ 直接回到记住的列表位置。 */
    fun previous(current: HistorySelection, lastIndex: Int): HistorySelection =
        cursor(current, maxOf(0, current.historyIndex - 1), lastIndex)

    /** 停到最后一条历史，不再交棒给页脚。 */
    fun last(current: HistorySelection, lastIndex: Int): HistorySelection =
        cursor(current, maxOf(0, lastIndex), lastIndex)
}
