package com.qcmian.clipper.core.domain.search

import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.model.SearchResult
import kotlin.coroutines.cancellation.CancellationException

/**
 * 智能搜索：同一次查询里尝试「连续匹配」与「子序列匹配」，取更好的那一次。
 *
 * 没有搜索模式可选——**分档本身就是优先级**。排序用一个整数编码出来：
 *
 * ```
 * score = 档位 × 10_000 + 位置 + 连续度 + 大小写
 * ```
 *
 * 后三项各自不超过 [MAX_DETAIL]、合计不超过 2997，小于档位间隔 10000。因此**档位永远主导**，
 * 同档之内才轮到位置、连续度与大小写——这正是「先比匹配类型、同类型再比细节」的字典序。
 *
 * | 档位 | 含义 | 例（查询 `git`） |
 * | --- | --- | --- |
 * | [Tier.EXACT] | 标题整条就是查询词 | `git` |
 * | [Tier.WORD] | 查询词是标题里的一个完整词 | `git commit -m` |
 * | [Tier.PREFIX] | 命中在标题开头 | `gitignore` |
 * | [Tier.SUBSTRING] | 命中在标题中间 | `run git pull` |
 * | [Tier.SUBSEQUENCE] | 字符按序命中但不连续 | `my gist list` |
 *
 * 多词查询按空白拆分，**每个词都必须命中**（AND）。整体档位取各词里**最差**的那一个，
 * 细节分取平均——否则「一个词命中了整词档、另一个词只是子序列」的条目会盖过两个词都命中
 * 得不错的条目。
 *
 * 匹配目标是 [ClipMeta.title]，不触碰载荷。这是纯 CPU 计算，成本与历史条数成正比，
 * 因此调用方必须放在后台线程上、并允许被后一次输入取消——见 `ClipboardViewModel.refresh`。
 */
object ClipSearch {

    /**
     * @param isCancelled 每处理 [CANCELLATION_CHECK_INTERVAL] 条调用一次。返回 `true` 表示这次
     *   查询已经没人要了，本函数会立刻抛出 [CancellationException]。
     *
     *   这是必需的：整个匹配是一段**同步循环**，里面没有任何挂起点，协程的取消状态因此检查不到
     *   ——`job.cancel()` 只能让调用方在挂起点上抛异常，拦不住这里把整段扫描跑完。不传就永远
     *   不会中断（默认值），适合一次性脚本与基准测试。
     */
    fun search(
        query: String,
        items: List<ClipMeta>,
        isCancelled: () -> Boolean = { false },
    ): List<SearchResult> {
        if (query.isEmpty()) return items.map { SearchResult(it) }

        val terms = parseTerms(query)
        if (terms.isEmpty()) return items.map { SearchResult(it) }

        // 每个词都要命中，因此标题短于最长的词就不可能命中。
        val longestTerm = terms.maxOf { it.folded.size }
        val buffer = FoldBuffer()
        val scored = ArrayList<ScoredResult>()

        var scanned = 0
        for (meta in items) {
            if (scanned++ % CANCELLATION_CHECK_INTERVAL == 0 && isCancelled()) {
                throw CancellationException("search cancelled")
            }

            val title = meta.title
            if (title.length < longestTerm) continue

            buffer.ensure(title.length)
            val folded = buffer.fold(title)
            val match = match(title, folded, terms) ?: continue
            scored += ScoredResult(SearchResult(meta, match.ranges), match.score)
        }

        // 稳定排序：同分保持 SQL 给出的顺序——那正是用户选定的排序方式。
        scored.sortByDescending { it.score }
        return scored.map { it.result }
    }

    // ---------------------------------------------------------------------------------
    // 单条匹配
    // ---------------------------------------------------------------------------------

    private fun match(title: String, folded: CharArray, terms: List<SearchTerm>): TitleMatch? {
        var worst = Tier.EXACT
        var detail = 0
        val ranges = ArrayList<IntRange>(terms.size)

        for (term in terms) {
            val termMatch = matchTerm(title, folded, term) ?: return null
            if (termMatch.tier > worst) worst = termMatch.tier
            detail += termMatch.detail
            ranges += termMatch.ranges
        }

        // 词多时细节分求和会越过档位间隔，因此取平均，保证它始终小于 10000。
        return TitleMatch(worst.score + detail / terms.size, mergeRanges(ranges))
    }

    private fun matchTerm(title: String, folded: CharArray, term: SearchTerm): TermMatch? {
        val length = title.length
        val size = term.folded.size
        val original = term.original

        // 路径 A：连续匹配。子串档的最低分也高于子序列档的最高分，因此一旦命中就不必再试子序列。
        //
        // 同一个查询词可能在标题里出现多次，而不同出现位置会落在不同档位——`gitleaks: commit in git`
        // 查 `git`，开头那次后面紧跟着 `l`，只能算前缀；末尾那次前后都是边界，是整词。因此要
        // **枚举全部出现位置**取档位最高的一次，而不是拿第一次出现就定档。同档位时取细节分高的。
        var best: TermMatch? = null
        var start = indexOf(folded, length, term.folded, size)
        while (start >= 0) {
            val candidate = continuousMatch(title, start, size, original)
            if (best == null || candidate.betterThan(best)) best = candidate
            // 整词是连续匹配能达到的最高档；枚举从左到右，所以第一个整词就是最靠前的那个。
            if (candidate.tier == Tier.WORD) return candidate
            start = indexOf(folded, length, term.folded, size, start + 1)
        }
        if (best != null) return best

        // 路径 B：子序列。先向右确认能凑齐全部字符，再向左收缩到最紧凑的对齐（见 [subsequence]）。
        val positions = subsequence(folded, length, term.folded, size) ?: return null
        return TermMatch(
            tier = Tier.SUBSEQUENCE,
            detail = positionScore(positions[0]) +
                continuityScore(positions) +
                caseScore(title, positions, original),
            // 每个命中字符各成一个区间，交给 mergeRanges 把相邻的合成一段。
            ranges = positions.map { it..it },
        )
    }

    /** 落在 [index] 的这一次连续命中属于哪个档位，以及它的细节分。 */
    private fun continuousMatch(title: String, index: Int, size: Int, original: String): TermMatch {
        val tier = when {
            title.length == size -> Tier.EXACT
            isWordStart(title, index) && isWordEnd(title, index + size) -> Tier.WORD
            index == 0 -> Tier.PREFIX
            else -> Tier.SUBSTRING
        }
        return TermMatch(
            tier = tier,
            detail = positionScore(index) + continuityScore(size) + caseScore(title, index, original),
            ranges = listOf(index until index + size),
        )
    }

    // ---------------------------------------------------------------------------------
    // 细节分（三项合计不超过 MAX_DETAIL × 3，跨不过档位间隔）
    // ---------------------------------------------------------------------------------

    /** 命中越靠前越高。 */
    private fun positionScore(index: Int): Int = (MAX_DETAIL - index).coerceAtLeast(0)

    /** 连续命中的字符越多越高。 */
    private fun continuityScore(runLength: Int): Int =
        (runLength * CONTINUITY_PER_CHAR).coerceAtMost(MAX_DETAIL)

    /** 子序列版：只认最长的一段连续命中。 */
    private fun continuityScore(positions: IntArray): Int {
        var longest = 1
        var run = 1
        for (index in 1 until positions.size) {
            run = if (positions[index] == positions[index - 1] + 1) run + 1 else 1
            if (run > longest) longest = run
        }
        return continuityScore(longest)
    }

    /** 大小写：整个查询词逐字符一致才给分，纯微调。 */
    private fun caseScore(title: String, index: Int, original: String): Int {
        for (offset in original.indices) {
            if (title[index + offset] != original[offset]) return 0
        }
        return CASE_MATCH
    }

    private fun caseScore(title: String, positions: IntArray, original: String): Int {
        for (offset in positions.indices) {
            if (title[positions[offset]] != original[offset]) return 0
        }
        return CASE_MATCH
    }

    // ---------------------------------------------------------------------------------
    // 匹配原语
    // ---------------------------------------------------------------------------------

    /** 朴素子串查找。查询词通常只有几个字符、标题上限 1000，不值得上 Boyer-Moore。 */
    private fun indexOf(
        haystack: CharArray,
        haystackLength: Int,
        needle: CharArray,
        needleLength: Int,
        from: Int = 0,
    ): Int {
        val limit = haystackLength - needleLength
        if (limit < from) return -1
        outer@ for (start in from..limit) {
            for (offset in 0 until needleLength) {
                if (haystack[start + offset] != needle[offset]) continue@outer
            }
            return start
        }
        return -1
    }

    /**
     * 贪心判定子序列并返回每个字符命中的位置；不构成子序列时返回 `null`。
     *
     * 分两步。先从左到右确认「能凑齐全部字符」并记下终点，再从终点**向左**取每个字符
     * 最晚出现的位置。
     *
     * 第二步不是多余的：只做第一步会得到「最早但不紧凑」的对齐——在
     * `https://example.com/docs/clipper` 里查 `clp`，它会把 `c` 落在 `example.com` 上，
     * 跨越十几个字符才凑齐 `l` 与 `p`。向左收缩后对齐落回 `clipper`，连续度更高、
     * 高亮也才说明得清「为什么这条会命中」。
     */
    private fun subsequence(
        haystack: CharArray,
        haystackLength: Int,
        needle: CharArray,
        needleLength: Int,
    ): IntArray? {
        var matched = 0
        var index = 0
        while (matched < needleLength && index < haystackLength) {
            if (haystack[index] == needle[matched]) matched++
            index++
        }
        if (matched < needleLength) return null

        val positions = IntArray(needleLength)
        var cursor = index - 1
        for (position in needleLength - 1 downTo 0) {
            while (cursor >= 0 && haystack[cursor] != needle[position]) cursor--
            positions[position] = cursor
            cursor--
        }
        return positions
    }

    /** 命中处是不是「词的开头」：标题开头、前一位是分隔符，或 camelCase 的大小写交界。 */
    private fun isWordStart(title: String, index: Int): Boolean {
        if (index == 0) return true
        val previous = title[index - 1]
        if (previous.isSeparator()) return true
        return previous.isLowerCase() && title[index].isUpperCase()
    }

    /** 命中处的后一位是不是词的结束：标题末尾，或下一个字符是分隔符。 */
    private fun isWordEnd(title: String, index: Int): Boolean =
        index >= title.length || title[index].isSeparator()

    private fun Char.isSeparator(): Boolean = isWhitespace() || this in SEPARATORS

    /** 把可能重叠或相邻的区间合并成最少的不重叠区间，并按位置升序。 */
    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.size <= 1) return ranges
        val sorted = ranges.sortedBy { it.first }
        val merged = ArrayList<IntRange>(sorted.size)
        var start = sorted[0].first
        var end = sorted[0].last

        for (index in 1 until sorted.size) {
            val range = sorted[index]
            if (range.first <= end + 1) {
                if (range.last > end) end = range.last
            } else {
                merged += start..end
                start = range.first
                end = range.last
            }
        }

        merged += start..end
        return merged
    }

    // ---------------------------------------------------------------------------------
    // 查询预处理
    // ---------------------------------------------------------------------------------

    /** 查询词：`original` 用于比较大小写，`folded` 用于匹配。 */
    private class SearchTerm(val original: String, val folded: CharArray)

    /**
     * 拆词并折叠。只做**等长**变换（全角转半角、取小写），因此匹配位置与原文索引一一对应，
     * 返回的高亮区间可以直接用在原始标题上——这是高亮不错位的前提。
     */
    private fun parseTerms(query: String): List<SearchTerm> =
        query.split(*WHITESPACE)
            .filter { it.isNotEmpty() }
            .map { SearchTerm(it, CharArray(it.length) { index -> it[index].folded() }) }

    private fun Char.folded(): Char = when (this) {
        '\u3000' -> ' '
        in FULL_WIDTH_START..FULL_WIDTH_END -> (this - FULL_WIDTH_OFFSET).lowercaseChar()
        else -> lowercaseChar()
    }

    /**
     * 可复用的折叠缓冲。
     *
     * 它由 `search` 每次调用时新建，**绝不能**做成 object 的字段：`refresh` 取消上一次任务
     * 并不保证它立刻停下，两块并发搜索共用一块缓冲会读到彼此的数据。
     */
    private class FoldBuffer {
        private var chars = CharArray(0)

        fun ensure(size: Int) {
            if (chars.size < size) chars = CharArray(size)
        }

        fun fold(title: String): CharArray {
            for (index in title.indices) chars[index] = title[index].folded()
            return chars
        }
    }

    // ---------------------------------------------------------------------------------
    // 中间结果
    // ---------------------------------------------------------------------------------

    /**
     * 匹配档位。序号即等级，因此比较大小就是比较档位强弱。
     *
     * 档位之间留出 10000 的间隔，而 [MAX_DETAIL] × 3 = 2997 远小于它，所以细节分再高也
     * 越不过一个档位。
     */
    private enum class Tier(val score: Int) {
        EXACT(40_000),
        WORD(30_000),
        PREFIX(20_000),
        SUBSTRING(10_000),
        SUBSEQUENCE(0),
    }

    private class TermMatch(val tier: Tier, val detail: Int, val ranges: List<IntRange>) {
        /** 档位高者优先；同档位时细节分高者优先。 */
        fun betterThan(other: TermMatch): Boolean =
            tier > other.tier || (tier == other.tier && detail > other.detail)
    }

    private class TitleMatch(val score: Int, val ranges: List<IntRange>)

    private class ScoredResult(val result: SearchResult, val score: Int)

    // ---------------------------------------------------------------------------------
    // 常量
    // ---------------------------------------------------------------------------------

    /** 细节分（位置 / 连续度 / 大小写）各自的取值范围上限。 */
    private const val MAX_DETAIL = 999

    /** 每多一个连续命中的字符加多少分。 */
    private const val CONTINUITY_PER_CHAR = 32

    /** 大小写完全一致时的加分；相对档位间隔可忽略，只用于同分区间内微调。 */
    private const val CASE_MATCH = 9

    /**
     * 两次取消检查之间最多处理多少条。
     *
     * 检查本身只是一次布尔读取，但每一条都问一次在 10 万条的规模上也是白搭；256 条一轮，
     * 一万条也只问 40 次，被取消到真正退出之间最多再多扫 256 条。
     */
    private const val CANCELLATION_CHECK_INTERVAL = 256

    /** 全角 ASCII 区间（`！` 到 `～`）与它到半角的偏移。 */
    private const val FULL_WIDTH_START = '\uFF01'
    private const val FULL_WIDTH_END = '\uFF5E'
    private const val FULL_WIDTH_OFFSET = 0xFEE0

    /**
     * 词与词的分隔符。
     *
     * 全角空格 [\u3000] 也在其中：它同时也是折叠目标，但拆分发生在折叠之前，
     * 所以这里必须单独列出来，否则「全角空格分隔的两个词」会被当成一个词。
     */
    private val WHITESPACE = charArrayOf(' ', '\t', '\n', '\r', '\u3000')

    /** 词的边界。与 [isWordStart] / [isWordEnd] 一起决定「整词」与「前缀」两档。 */
    private const val SEPARATORS = "_-/\\.,:;()[]{}+=*&%$#@!?\"'<>|~`"
}
