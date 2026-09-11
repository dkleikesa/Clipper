package com.qcmian.clipper.domain.search

import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.domain.model.SearchResult
import com.qcmian.clipper.settings.SearchMode

/** 对应 Maccy 的 `Search`。 */
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

    /** 对应 `Search.simpleSearch(_:within:options: .regularExpression)`，区分大小写。 */
    private fun regexp(query: String, items: List<ClipItem>): List<SearchResult> {
        val regex = runCatching { Regex(query) }.getOrNull() ?: return emptyList()
        return items.mapNotNull { item ->
            val ranges = regex.findAll(item.title).map { it.range }.toList()
            if (ranges.isEmpty()) null else SearchResult(item, ranges)
        }
    }

    /**
     * 对应 `Search.fuzzySearch(_:within:)`。Maccy 用 Fuse 的 bitap 算法打分：
     * `score = errors / pattern.length`，超过 `0.7` 阈值的直接拒绝。这里用近似的子串编辑距离
     * 复现同一度量，使命中的集合与排序与 `Fuse(threshold: 0.7)` 一致。
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
     * 把 [needle] 变成 [haystack] 的某个子串所需的最少编辑次数（替换 / 插入 / 删除），
     * 再除以 `needle.length`。
     */
    private fun fuzzyMatch(needle: String, haystack: String): FuzzyMatch? {
        val m = needle.length
        val n = haystack.length
        if (m == 0 || n == 0) return null

        val maxErrors = (m * FUZZY_THRESHOLD).toInt()

        // 低成本预筛：完全缺失的字符至少要付一次编辑代价，因此缺失字符数超过 `maxErrors`
        // 就不可能命中。
        val available = HashMap<Char, Int>()
        for (character in haystack) available[character] = (available[character] ?: 0) + 1
        var missing = 0
        for (character in needle) {
            val left = available[character] ?: 0
            if (left <= 0) missing++ else available[character] = left - 1
        }
        if (missing > maxErrors) return null

        // dp[i][j] = 用 haystack[0..j) 的某个后缀匹配 needle[0..i) 所需的编辑次数。
        // 空前缀可以零代价匹配任意位置，因此这变成子串匹配而非整串匹配。
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

        // 回溯表格，还原出匹配到的字符偏移，用于高亮。
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

    /** Maccy 的 `Search.fuzzySearchLimit`。 */
    private const val FUZZY_SEARCH_LIMIT = 5_000

    /** Maccy 的 `Fuse(threshold: 0.7)`。 */
    private const val FUZZY_THRESHOLD = 0.7
}
