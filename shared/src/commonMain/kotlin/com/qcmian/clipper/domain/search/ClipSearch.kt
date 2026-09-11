package com.qcmian.clipper.domain.search

import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.domain.model.SearchResult
import com.qcmian.clipper.domain.model.SearchMode

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

    /** Port of `Search.simpleSearch(_:within:options: .regularExpression)`, case sensitive. */
    private fun regexp(query: String, items: List<ClipItem>): List<SearchResult> {
        val regex = runCatching { Regex(query) }.getOrNull() ?: return emptyList()
        return items.mapNotNull { item ->
            val ranges = regex.findAll(item.title).map { it.range }.toList()
            if (ranges.isEmpty()) null else SearchResult(item, ranges)
        }
    }

    /**
     * Port of `Search.fuzzySearch(_:within:)`. Maccy scores with Fuse's bitap algorithm:
     * `score = errors / pattern.length`, and anything above the `0.7` threshold is
     * rejected. The same metric is reproduced with an approximate substring edit distance,
     * so the accepted matches and the ranking line up with `Fuse(threshold: 0.7)`.
     */
    private fun fuzzy(query: String, items: List<ClipItem>): List<SearchResult> {
        val needle = query.lowercase()
        if (needle.isEmpty()) return emptyList()

        return items
            .mapNotNull { item ->
                val haystack = item.title.take(FUZZY_SEARCH_LIMIT).lowercase()
                fuzzyMatch(needle, haystack)?.let { match ->
                    ScoredResult(SearchResult(item, match.ranges), match.score)
                }
            }
            .sortedBy { it.score }
            .map { it.result }
    }

    private data class FuzzyMatch(val score: Double, val ranges: List<IntRange>)

    /**
     * Minimum number of edits (substitution / insertion / deletion) needed to turn
     * [needle] into some substring of [haystack], divided by `needle.length`.
     */
    private fun fuzzyMatch(needle: String, haystack: String): FuzzyMatch? {
        val m = needle.length
        val n = haystack.length
        if (m == 0 || n == 0) return null

        val maxErrors = (m * FUZZY_THRESHOLD).toInt()

        // Cheap rejection: a character that is entirely absent has to be paid for with at
        // least one edit, so more missing characters than `maxErrors` cannot match.
        val available = HashMap<Char, Int>()
        for (character in haystack) available[character] = (available[character] ?: 0) + 1
        var missing = 0
        for (character in needle) {
            val left = available[character] ?: 0
            if (left <= 0) missing++ else available[character] = left - 1
        }
        if (missing > maxErrors) return null

        // dp[i][j] = edits needed to match needle[0..i) with a suffix of haystack[0..j).
        // The empty pattern matches anywhere for free, which turns this into a substring
        // match rather than a full-string one.
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) dp[i][0] = i

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (needle[i - 1] == haystack[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j - 1] + cost,
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                )
            }
        }

        var bestEnd = 0
        var best = Int.MAX_VALUE
        for (j in 1..n) {
            if (dp[m][j] < best) {
                best = dp[m][j]
                bestEnd = j
            }
        }
        if (best > maxErrors) return null

        // Walk the table back to recover the matched character offsets for highlighting.
        val indices = mutableListOf<Int>()
        var i = m
        var j = bestEnd
        while (i > 0 && j > 0) {
            val cost = if (needle[i - 1] == haystack[j - 1]) 0 else 1
            when {
                dp[i][j] == dp[i - 1][j - 1] + cost -> {
                    if (cost == 0) indices += j - 1
                    i--
                    j--
                }

                dp[i][j] == dp[i - 1][j] + 1 -> i--
                else -> j--
            }
        }
        indices.reverse()

        return FuzzyMatch(best.toDouble() / m, mergeRanges(indices))
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

    private data class ScoredResult(val result: SearchResult, val score: Double)

    /** Maccy's `Search.fuzzySearchLimit`. */
    private const val FUZZY_SEARCH_LIMIT = 5_000

    /** Maccy's `Fuse(threshold: 0.7)`. */
    private const val FUZZY_THRESHOLD = 0.7
}
