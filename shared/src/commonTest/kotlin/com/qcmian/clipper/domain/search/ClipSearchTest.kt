package com.qcmian.clipper.domain.search

import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.settings.SearchMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `ClipSearch` 是 (query, items, mode) 的纯函数，
 * 因此是开始构建架构重构所缺失的回归防线成本最低的地方。
 */
class ClipSearchTest {

    private fun item(id: String, title: String) = ClipItem(id = id, title = title)

    @Test
    fun emptyQueryKeepsEveryItemInOrder() {
        val items = listOf(item("1", "Alpha"), item("2", "Beta"))

        val results = ClipSearch.search("", items, SearchMode.EXACT)

        assertEquals(listOf("1", "2"), results.map { it.item.id })
        assertTrue(results.all { it.ranges.isEmpty() })
    }

    @Test
    fun exactMatchIsCaseInsensitiveAndReportsHighlightRanges() {
        val items = listOf(item("1", "Hello World"), item("2", "Unrelated"))

        val results = ClipSearch.search("world", items, SearchMode.EXACT)

        assertEquals(listOf("1"), results.map { it.item.id })
        assertEquals(listOf(6..10), results.single().ranges)
    }

    @Test
    fun regexpMatchIsCaseSensitiveAndReportsEveryHit() {
        val items = listOf(item("1", "abc-abc"), item("2", "ABC"))

        val results = ClipSearch.search("abc", items, SearchMode.REGEXP)

        assertEquals(listOf("1"), results.map { it.item.id })
        assertEquals(listOf(0..2, 4..6), results.single().ranges)
    }

    @Test
    fun invalidRegexpMatchesNothing() {
        val items = listOf(item("1", "anything"))

        assertTrue(ClipSearch.search("(", items, SearchMode.REGEXP).isEmpty())
    }

    @Test
    fun fuzzyAcceptsNearMissesWithinTheThreshold() {
        val items = listOf(item("1", "meeting notes"), item("2", "xyz"))

        val results = ClipSearch.search("meting", items, SearchMode.FUZZY)

        assertEquals(listOf("1"), results.map { it.item.id })
    }

    @Test
    fun fuzzyRejectsTitlesBeyondTheThreshold() {
        val items = listOf(item("1", "hello world"))

        assertTrue(ClipSearch.search("zzzz", items, SearchMode.FUZZY).isEmpty())
    }

    @Test
    fun fuzzyIsRankedByScore() {
        val items = listOf(item("worse", "abx"), item("better", "abc"))

        val results = ClipSearch.search("abc", items, SearchMode.FUZZY)

        assertEquals("better", results.first().item.id)
    }

    @Test
    fun mixedFallsBackToRegexpWhenExactFindsNothing() {
        val items = listOf(item("1", "abc"))

        val results = ClipSearch.search("^a.c$", items, SearchMode.MIXED)

        assertEquals(listOf("1"), results.map { it.item.id })
        assertEquals(listOf(0..2), results.single().ranges)
    }

    @Test
    fun mixedFallsBackToFuzzyWhenExactAndRegexpFindNothing() {
        val items = listOf(item("1", "abc"))

        val results = ClipSearch.search("abx", items, SearchMode.MIXED)

        assertEquals(listOf("1"), results.map { it.item.id })
    }
}
