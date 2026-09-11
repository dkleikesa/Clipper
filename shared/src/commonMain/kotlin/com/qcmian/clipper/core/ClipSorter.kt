package com.qcmian.clipper.core

import com.qcmian.clipper.model.ClipItem
import com.qcmian.clipper.settings.PinPosition
import com.qcmian.clipper.settings.SortBy

/** Port of Maccy's `Sorter`. */
object ClipSorter {
    fun sort(items: List<ClipItem>, by: SortBy, pinTo: PinPosition): List<ClipItem> {
        val sorted = items.sortedWith(comparator(by))
        // `sortedWith` is stable, so a second pass keeps the order produced above
        // within the pinned / unpinned groups.
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
