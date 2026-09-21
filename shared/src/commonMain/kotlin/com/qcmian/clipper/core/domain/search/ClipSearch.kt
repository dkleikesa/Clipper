package com.qcmian.clipper.core.domain.search

import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.model.SearchResult
import com.qcmian.clipper.core.settings.SearchMode
import com.qcmian.clipper.core.util.currentTimeMillis

/**
 * 在当前窗口的元数据上执行一次查询。
 *
 * 搜索目标是 [ClipMeta.title]，**不触碰载荷**——这也是窗口可以只装元数据的原因：
 * 模糊匹配要读入每条标题，如果标题需要先把图片和正文反序列化出来，这个函数就没法在
 * 「内存里只有元数据」的前提下工作。
 *
 * 这个函数是**纯 CPU 计算，成本与历史条数成正比**（模糊模式是 O(查询长 × 标题长) 的编辑距离），
 * 因此调用方必须把它放在后台线程上跑，并且允许连续输入时取消上一次——见
 * `ClipboardViewModel.refresh`。
 */
object ClipSearch {
    fun search(query: String, items: List<ClipMeta>, mode: SearchMode): List<SearchResult> {
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

    private fun exact(query: String, items: List<ClipMeta>): List<SearchResult> =
        items.mapNotNull { meta ->
            val index = meta.title.indexOf(query, ignoreCase = true)
            if (index < 0) {
                null
            } else {
                SearchResult(meta, listOf(index until index + query.length))
            }
        }

    /**
     * 区分大小写。
     *
     * 用户输入的正则可能触发灾难性回溯（`(a+)+b` 这类），因此这里有两层保护：参与匹配的
     * 标题截断到 [REGEX_SEARCH_LIMIT]，以及一个挂在 `charAt` 上的软超时
     * （见 [DeadlineSequence]）。
     *
     * 预算用完时**返回已经拿到的结果**而不是清空——搜索框里的内容永远是用户自己敲的，
     * 让它突然全空比少几条更糟。
     */
    private fun regexp(query: String, items: List<ClipMeta>): List<SearchResult> {
        val regex = runCatching { Regex(query) }.getOrNull() ?: return emptyList()
        val deadline = currentTimeMillis() + REGEX_BUDGET_MILLIS
        val results = mutableListOf<SearchResult>()

        for (meta in items) {
            if (currentTimeMillis() > deadline) break
            val title = meta.title.take(REGEX_SEARCH_LIMIT)
            // 某一条回溯跑飞时只跳过它自己，不影响其余的条目。
            val ranges = runCatching {
                regex.findAll(DeadlineSequence(title, deadline)).map { it.range }.toList()
            }.getOrNull() ?: continue
            if (ranges.isNotEmpty()) results += SearchResult(meta, ranges)
        }
        return results
    }

    /**
     * 模糊匹配的打分公式：
     * `score = errors / pattern.length`，超过 `0.7` 阈值的直接拒绝。这里用近似的子串编辑距离
     * 复现同一度量，使命中的集合与排序与 `Fuse(threshold: 0.7)` 一致。
     */
    private fun fuzzy(query: String, items: List<ClipMeta>): List<SearchResult> {
        val needle = query.lowercase()
        if (needle.isEmpty()) return emptyList()

        return items
            .mapNotNull { meta ->
                val haystack = meta.title.take(FUZZY_SEARCH_LIMIT).lowercase()
                fuzzyMatch(needle, haystack)?.let { match ->
                    ScoredResult(SearchResult(meta, match.ranges), match.score)
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

    private const val FUZZY_SEARCH_LIMIT = 5_000

    private const val FUZZY_THRESHOLD = 0.7

    /** 参与正则匹配的标题长度上限。 */
    private const val REGEX_SEARCH_LIMIT = 4_096

    /** 一次正则搜索的总时间预算；用完就带上已有结果收工。 */
    private const val REGEX_BUDGET_MILLIS = 150L
}

/**
 * 给正则执行加一个软超时。
 *
 * Java 的正则引擎在匹配与回溯时反复通过 `charAt` 读取输入，因此把输入包一层、在其中定期
 * 检查时限，就能在灾难性回溯跑飞之前把它掐掉。代价是每读 [CHECK_INTERVAL] 个字符多一次
 * 时钟查询——相对于回溯本身的开销可以忽略。
 *
 * 超时抛出 [RegexTimeoutException]，由调用方按「这一条不匹配」处理。
 */
private class DeadlineSequence(
    private val source: CharSequence,
    private val deadlineMillis: Long,
) : CharSequence {
    private var ticks = 0

    override val length: Int get() = source.length

    override fun get(index: Int): Char {
        if (++ticks >= CHECK_INTERVAL) {
            ticks = 0
            if (currentTimeMillis() > deadlineMillis) throw RegexTimeoutException()
        }
        return source[index]
    }

    override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
        DeadlineSequence(source.subSequence(startIndex, endIndex), deadlineMillis)

    private companion object {
        /** 每读这么多个字符检查一次时限。 */
        const val CHECK_INTERVAL = 512
    }
}

/** [DeadlineSequence] 用它在回溯中途打断匹配；不需要堆栈（禁掉可以少一次分配）。 */
private class RegexTimeoutException : RuntimeException(null, null, false, false)
