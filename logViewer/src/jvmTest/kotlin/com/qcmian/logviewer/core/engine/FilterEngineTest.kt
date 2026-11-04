package com.qcmian.logviewer.core.engine

import com.qcmian.logviewer.core.doc.InMemoryDocument
import com.qcmian.logviewer.core.model.FilterConfig
import com.qcmian.logviewer.core.model.FilterType
import com.qcmian.logviewer.core.model.MatchMode
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 过滤管道。这里每一条用例都对应一次真实的坑：
 * 空规则把视图筛空、`^` 正则只有一行命中、命中数在两条路径上口径不同。
 *
 * 断言一律跑**两遍**：一遍走全文 indexOf 快路径，一遍加一条空规则把它挤到逐行慢路径。
 * 两条路径的结果必须一字不差——这层「两条实现」的分歧不会被界面暴露出来，只会被单测暴露。
 */
class FilterEngineTest {

    private val text = (1..5).joinToString("\n") { "2024-08-14 12:00:0$it ERROR line $it" } +
        "\n2024-08-14 12:00:06 INFO done"

    private val doc = InMemoryDocument("测试", "memory://t", text, "UTF-8", text.length.toLong())

    @Test
    fun `空规则不筛掉任何行`() {
        // 「＋ 新增过滤器」建出来的就是一条 pattern 还空着的包含规则：它匹配不了任何行，
        // 但不能因此把候选集判成空集（那等于用户一点按钮，日志就白屏）。
        forEachPath(listOf(FilterConfig())) { result ->
            assertEquals(6, result.matched.size)
        }
    }

    @Test
    fun `锚点正则逐行生效`() {
        // 快路径原先是对整篇文本跑 findAll：`^` 只锚全文开头，于是只有第一处命中。
        forEachPath(listOf(FilterConfig(pattern = "^.*ERROR", matchMode = MatchMode.REGEX))) { result ->
            assertEquals(5, result.matched.size)
        }
        forEachPath(listOf(FilterConfig(pattern = "^2024", matchMode = MatchMode.REGEX))) { result ->
            assertEquals(6, result.matched.size)
        }
        // 行尾锚点同理：没有一行以 ERROR 结尾。
        forEachPath(listOf(FilterConfig(pattern = "ERROR$", matchMode = MatchMode.REGEX))) { result ->
            assertEquals(0, result.matched.size)
        }
    }

    @Test
    fun `命中数按规则各自统计`() {
        val filters = listOf(
            FilterConfig(pattern = "ERROR", background = 0x33FF0000),
            FilterConfig(pattern = "line 3"),
            FilterConfig(pattern = "INFO", type = FilterType.EXCLUDE),
        )
        forEachPath(filters) { result ->
            assertEquals(5, result.counts[0], "ERROR 命中 5 行")
            assertEquals(1, result.counts[1], "line 3 命中 1 行")
            assertEquals(1, result.counts[2], "排除规则也要计数（与快路径同口径）")
            // 包含规则取并集（5 行 ERROR + 1 行 line 3），排除规则把 INFO 那一行挡在外面。
            assertEquals(5, result.matched.size)
        }
    }

    @Test
    fun `着色取列表顺序里第一个命中的规则`() {
        val filters = listOf(
            FilterConfig(pattern = "ERROR", background = 0x33FF0000),
            FilterConfig(pattern = "line 3", background = 0x3300FF00),
        )
        forEachPath(filters) { result ->
            // 两行都命中时，位置靠前的规则赢（着色优先级 = 列表顺序）。
            assertEquals(0, result.colors?.get(0))
            assertEquals(0, result.colors?.get(2))
        }
    }

    @Test
    fun `排除规则把行从视图里拿掉`() {
        val filters = listOf(FilterConfig(pattern = "INFO", type = FilterType.EXCLUDE))
        forEachPath(filters) { result ->
            // 没有包含规则 → 全部行进入候选，再减掉命中的那行。
            assertEquals(5, result.matched.size)
        }
    }

    /**
     * 同一组规则跑两遍：先走快路径，再追加一条空规则把它挤到逐行慢路径。
     *
     * 空规则匹配不了任何行，因此两次的 matched / counts / colors 必须完全一致。
     */
    private fun forEachPath(filters: List<FilterConfig>, assert: (FilterResult) -> Unit) {
        val fast = runBlocking { runFilter(doc, filters, 1L, null) {} }
        val slowFilters = filters + FilterConfig()
        val slow = runBlocking { runFilter(doc, slowFilters, 2L, null) {} }
        assert(fast)
        assert(slow)
        assertEquals(
            fast.matched.toList(),
            slow.matched.toList(),
            "快路径与慢路径的匹配结果必须一致",
        )
        assertEquals(
            fast.counts.toList(),
            slow.counts.take(filters.size),
            "快路径与慢路径的命中数必须一致",
        )
    }
}
