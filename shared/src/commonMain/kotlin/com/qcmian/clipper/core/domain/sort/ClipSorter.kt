package com.qcmian.clipper.core.domain.sort

import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.settings.PinPosition
import com.qcmian.clipper.core.settings.SortBy
import com.qcmian.clipper.core.settings.SortOrder

/**。 */
object ClipSorter {
    /**
     * 排序只作用于未置顶的内容区；置顶项固定按 `lastCopiedAt` 降序（最近复制的在前），
     * 不随排序方式 / 方向变化——置顶的语义就是「固定在那」，不该被筛选栏左右。
     */
    fun sort(items: List<ClipItem>, by: SortBy, order: SortOrder, pinTo: PinPosition): List<ClipItem> {
        val pinned = items.filter { it.isPinned }.sortedByDescending { it.lastCopiedAt }
        val unpinned = items.filterNot { it.isPinned }.sortedWith(comparator(by, order))
        return when (pinTo) {
            PinPosition.TOP -> pinned + unpinned
            PinPosition.BOTTOM -> unpinned + pinned
        }
    }

    private fun comparator(by: SortBy, order: SortOrder): Comparator<ClipItem> {
        val ascending = order == SortOrder.ASCENDING
        val base: Comparator<ClipItem> = when (by) {
            SortBy.FIRST_COPIED_AT -> compareBy { it.firstCopiedAt }
            SortBy.NUMBER_OF_COPIES -> compareBy { it.numberOfCopies }
            SortBy.LAST_COPIED_AT -> compareBy { it.lastCopiedAt }
            SortBy.FILE_SIZE -> compareBy { it.approximateSizeBytes }
        }
        return if (ascending) base else base.reversed()
    }
}
