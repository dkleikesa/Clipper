package com.qcmian.clipper.core.domain.sort

import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.settings.PinPosition
import com.qcmian.clipper.core.settings.SortBy

/**。 */
object ClipSorter {
    fun sort(items: List<ClipItem>, by: SortBy, pinTo: PinPosition): List<ClipItem> {
        val sorted = items.sortedWith(comparator(by))
        // `sortedWith` 是稳定排序，因此第二趟排序会在置顶 / 未置顶分组内保留上面的顺序。
        return sorted.sortedWith { lhs, rhs -> pinnedFirst(lhs, rhs, pinTo) }
    }

    private fun comparator(by: SortBy): Comparator<ClipItem> = when (by) {
        SortBy.FIRST_COPIED_AT -> compareByDescending { it.firstCopiedAt }
        SortBy.NUMBER_OF_COPIES -> compareByDescending { it.numberOfCopies }
        SortBy.LAST_COPIED_AT -> compareByDescending { it.lastCopiedAt }
    }

    private fun pinnedFirst(lhs: ClipItem, rhs: ClipItem, pinTo: PinPosition): Int = when (pinTo) {
        PinPosition.TOP -> {
            if (lhs.isPinned == rhs.isPinned) 0 else if (lhs.isPinned) -1 else 1
        }

        PinPosition.BOTTOM -> {
            if (lhs.isPinned == rhs.isPinned) 0 else if (lhs.isPinned) 1 else -1
        }
    }
}
