package com.qcmian.clipper.core.domain.search

import com.qcmian.clipper.core.domain.model.ClipText
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 全文搜索的**批次与预算**。
 *
 * 这些行为只在「库很大 / 正文很长」时才显形，功能测试很难稳定复现，但它们决定了
 * 「一次调用会不会把整个库读完」以及「结果能不能当成完整结果」——因此单独测。
 */
class ClipDeepSearchTest {

    @Test
    fun `a blank query or no candidates returns nothing and does not truncate`() = runTest {
        val blank = ClipDeepSearch.search(
            query = "",
            candidateIds = listOf("a"),
            readTexts = { listOf(ClipText("a", "git")) },
        )
        assertEquals(emptyList<String>(), blank.ids)
        assertFalse(blank.truncated)

        val empty = ClipDeepSearch.search(
            query = "git",
            candidateIds = emptyList(),
            readTexts = { error("没有候选时不该读库") },
        )
        assertEquals(emptyList<String>(), empty.ids)
        assertFalse(empty.truncated)
    }

    @Test
    fun `body text is read in fixed size batches`() = runTest {
        val ids = (1..450).map { "id$it" }
        val batches = mutableListOf<Int>()

        val outcome = ClipDeepSearch.search(
            query = "needle",
            candidateIds = ids,
            readTexts = { chunk ->
                batches += chunk.size
                chunk.map { ClipText(it, "no match here") }
            },
        )

        assertEquals(listOf(200, 200, 50), batches)
        assertEquals(emptyList<String>(), outcome.ids)
        assertFalse(outcome.truncated, "没到预算就不该标记截断")
    }

    @Test
    fun `the same id read twice is reported once`() = runTest {
        // 同一个条目可能带着两段正文（用户复制的正文 + 图片识别原文）。
        val outcome = ClipDeepSearch.search(
            query = "git",
            candidateIds = listOf("a"),
            readTexts = { listOf(ClipText("a", "git"), ClipText("a", "git stuff")) },
        )
        assertEquals(listOf("a"), outcome.ids)
    }

    @Test
    fun `running past the hit budget truncates`() = runTest {
        val outcome = ClipDeepSearch.search(
            query = "git",
            candidateIds = (1..5).map { "id$it" },
            readTexts = { chunk -> chunk.map { ClipText(it, "git") } },
            maxHits = 2,
        )
        assertEquals(2, outcome.ids.size)
        assertTrue(outcome.truncated, "撞到命中上限 → 后面可能还有")
    }

    @Test
    fun `running past the character budget truncates`() = runTest {
        // 结果数没涨，只有字符预算能拦住它把整个库读完。
        val outcome = ClipDeepSearch.search(
            query = "git",
            candidateIds = listOf("a"),
            readTexts = { listOf(ClipText("a", "git" + "x".repeat(100))) },
            maxChars = 10,
        )
        assertEquals(listOf("a"), outcome.ids)
        assertTrue(outcome.truncated)
    }
}
