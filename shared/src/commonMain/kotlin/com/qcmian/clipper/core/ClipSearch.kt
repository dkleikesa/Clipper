package com.qcmian.clipper.core

import com.qcmian.clipper.model.ClipItem
import com.qcmian.clipper.settings.SearchMode

/**
 * A match of the current query inside an item's title.
 * [ranges] are the character offsets that should be highlighted in the UI.
 */
data class SearchResult(
    val item: ClipItem,
    val ranges: List<IntRange> = emptyList(),
)

/** Port of Maccy's `Search`. */
object ClipSearch {
    fun search(query: String, items: List<ClipItem>, mode: SearchMode): List<SearchResult> {
        if (query.isEmpty()) return items.map { SearchResult(it) }

        return when (mode) {
            SearchMode.EXACT -> exact(query, items)
            SearchMode.REGEXP -> regexp(query, items)
            SearchMode.FUZZY -> fuzzy(query, items)
            SearchMode.MIXED -> {
                val exactMatches = exact(query, items)
                if (exactMatches.isNotEmpty()) {
                    exactMatches
                } else {
                    val regexpMatches = regexp(query, items)
                    if (regexpMatches.isNotEmpty()) regexpMatches else fuzzy(query, items)
                }
            }
        }
    }

    private fun exact(query: String, items: List<ClipItem>): List<SearchResult> =
        items.mapNotNull { item ->
            val index = item.title.indexOf(query, ignoreCase = true)
            if (index < 0) {
                null
            } else {
                SearchResult(item, listOf(index until index + query.length))
            }
        }

    private fun regexp(query: String, items: List<ClipItem>): List<SearchResult> {
        val regex = runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull() ?: return emptyList()
        return items.mapNotNull { item ->
            val ranges = regex.findAll(item.title).map { it.range }.toList()
            if (ranges.isEmpty()) null else SearchResult(item, ranges)
        }
    }

    private fun fuzzy(query: String, items: List<ClipItem>): List<SearchResult> {
        val needle = query.lowercase()
        val matches = items.mapNotNull { item ->
            val haystack = item.title.lowercase()
            val indices = mutableListOf<Int>()
            var needleIndex = 0
            var score = 0
            var lastMatch = -1

            for (index in haystack.indices) {
                if (needleIndex < needle.length && haystack[index] == needle[needleIndex]) {
                    if (lastMatch >= 0) score += index - lastMatch - 1
                    lastMatch = index
                    indices += index
                    needleIndex++
                }
            }

            if (needleIndex == needle.length) {
                ScoredResult(SearchResult(item, mergeRanges(indices)), score)
            } else {
                null
            }
        }
        return matches.sortedBy { it.score }.map { it.result }
    }

    private fun mergeRanges(indices: List<Int>): List<IntRange> {
        if (indices.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var start = indices.first()
        var previous = start
        for (index in indices.drop(1)) {
            if (index == previous + 1) {
                previous = index
            } else {
                ranges += start..previous
                start = index
                previous = index
            }
        }
        ranges += start..previous
        return ranges
    }

    private data class ScoredResult(val result: SearchResult, val score: Int)
}
