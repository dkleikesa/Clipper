package com.qcmian.clipper.domain.sort

import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.settings.PinPosition
import com.qcmian.clipper.settings.SortBy
import kotlin.test.Test
import kotlin.test.assertEquals

class ClipSorterTest {

    private fun item(
        id: String,
        lastCopiedAt: Long = 0L,
        firstCopiedAt: Long = 0L,
        numberOfCopies: Int = 1,
        pin: String? = null,
    ) = ClipItem(
        id = id,
        title = id,
        lastCopiedAt = lastCopiedAt,
        firstCopiedAt = firstCopiedAt,
        numberOfCopies = numberOfCopies,
        pin = pin,
    )

    @Test
    fun sortsByLastCopiedAtDescending() {
        val items = listOf(
            item("a", lastCopiedAt = 10),
            item("b", lastCopiedAt = 30),
            item("c", lastCopiedAt = 20),
        )

        val sorted = ClipSorter.sort(items, SortBy.LAST_COPIED_AT, PinPosition.TOP)

        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test
    fun sortsByFirstCopiedAtDescending() {
        val items = listOf(
            item("a", firstCopiedAt = 10),
            item("b", firstCopiedAt = 30),
            item("c", firstCopiedAt = 20),
        )

        val sorted = ClipSorter.sort(items, SortBy.FIRST_COPIED_AT, PinPosition.TOP)

        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test
    fun sortsByNumberOfCopiesDescending() {
        val items = listOf(
            item("a", numberOfCopies = 2),
            item("b", numberOfCopies = 9),
            item("c", numberOfCopies = 5),
        )

        val sorted = ClipSorter.sort(items, SortBy.NUMBER_OF_COPIES, PinPosition.TOP)

        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test
    fun pinnedItemsComeFirstWhenPinToTop() {
        val items = listOf(
            item("a", lastCopiedAt = 10),
            item("pinned", lastCopiedAt = 5, pin = "d"),
            item("b", lastCopiedAt = 30),
        )

        val sorted = ClipSorter.sort(items, SortBy.LAST_COPIED_AT, PinPosition.TOP)

        assertEquals(listOf("pinned", "b", "a"), sorted.map { it.id })
    }

    @Test
    fun pinnedItemsComeLastWhenPinToBottom() {
        val items = listOf(
            item("a", lastCopiedAt = 10),
            item("pinned", lastCopiedAt = 5, pin = "d"),
            item("b", lastCopiedAt = 30),
        )

        val sorted = ClipSorter.sort(items, SortBy.LAST_COPIED_AT, PinPosition.BOTTOM)

        assertEquals(listOf("b", "a", "pinned"), sorted.map { it.id })
    }
}
