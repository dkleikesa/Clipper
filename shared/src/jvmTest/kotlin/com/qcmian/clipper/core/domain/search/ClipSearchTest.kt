package com.qcmian.clipper.core.domain.search

import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.settings.ClipFilterType
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 搜索打分内核。
 *
 * 只断言**排序与命中区间**这类可观察结果，不碰分数怎么算：档位主导、同档细节、多词 AND、
 * 子序列对齐、折叠后索引对齐——这些是「用户看到哪条排前面、哪几个字被高亮」，改内部算式
 * 不该让它们变。
 */
class ClipSearchTest {

    @Test
    fun `tiers dominate the score, from exact down to subsequence`() {
        val items = listOf(
            meta("subsequence", "my gist list"),
            meta("substring", "xgitx"),
            meta("prefix", "gitignore"),
            meta("word", "git commit"),
            meta("exact", "git"),
        )

        assertEquals(
            listOf("exact", "word", "prefix", "substring", "subsequence"),
            ClipSearch.search("git", items).map { it.meta.id },
        )
    }

    @Test
    fun `within a tier, an earlier hit ranks higher`() {
        val items = listOf(meta("late", "xxxxgit"), meta("early", "xgitxxx"))
        assertEquals(listOf("early", "late"), ClipSearch.search("git", items).map { it.meta.id })
    }

    @Test
    fun `within a tier, matching case ranks higher`() {
        val items = listOf(meta("lower", "gitxxx"), meta("cased", "Gitxxx"))
        assertEquals(listOf("cased", "lower"), ClipSearch.search("Git", items).map { it.meta.id })
    }

    @Test
    fun `multi word queries require every term`() {
        val items = listOf(meta("both", "gi co"), meta("one", "gi only"))
        assertEquals(listOf("both"), ClipSearch.search("gi co", items).map { it.meta.id })
    }

    @Test
    fun `a multi term match is ranked by its worst tier`() {
        // 「两个词都命中整词档」必须排在「一个整词 + 一个子串」之前。
        val items = listOf(meta("substring", "xgi xco"), meta("word", "gi co"))
        assertEquals(listOf("word", "substring"), ClipSearch.search("gi co", items).map { it.meta.id })
    }

    @Test
    fun `a subsequence aligns to the tightest run, not the first characters`() {
        // `clp` 在 example.com 上也能凑齐，但向左收缩后应当落在 clipper 上。
        val result = ClipSearch.search("clp", listOf(meta("url", "https://example.com/docs/clipper"))).single()
        assertEquals(listOf(25..26, 28..28), result.ranges)
    }

    @Test
    fun `a term appearing twice is scored by its best occurrence`() {
        // 开头那次是前缀档、末尾那次是整词档，应当高亮末尾。
        val result = ClipSearch.search("git", listOf(meta("t", "gitleaks: commit in git"))).single()
        assertEquals(listOf(20..22), result.ranges)
    }

    @Test
    fun `full width input folds to half width and keeps indices aligned`() {
        val result = ClipSearch.search("ＧＩＴ", listOf(meta("t", "git"))).single()
        assertEquals(listOf(0..2), result.ranges, "折叠必须等长，区间才能直接用在原文上")
    }

    @Test
    fun `a full width space separates terms`() {
        // 若全角空格不参与拆词，`a　b` 会整体折叠成 `a b`，匹配不到 `aXb`。
        assertEquals(1, ClipSearch.search("a\u3000b", listOf(meta("t", "aXb"))).size)
    }

    @Test
    fun `an empty query keeps every item in order and highlights nothing`() {
        val result = ClipSearch.search("", listOf(meta("a", "one"), meta("b", "two")))
        assertEquals(listOf("a", "b"), result.map { it.meta.id })
        assertTrue(result.all { it.ranges.isEmpty() })
    }

    @Test
    fun `cancellation is honoured inside the scan`() {
        val items = List(500) { meta("id$it", "git") }
        assertFailsWith<CancellationException> { ClipSearch.search("git", items) { true } }
    }

    @Test
    fun `text search reports indices into the batch, best first`() {
        val hits = ClipSearch.searchTexts("git", listOf("nothing", "has git here", "git"))
        assertEquals(listOf(2, 1), hits.map { it.index })
        assertEquals(listOf(0..2), hits.first().ranges)
    }

    @Test
    fun `text search finds nothing for a blank query`() {
        assertTrue(ClipSearch.searchTexts("", listOf("git")).isEmpty())
        assertTrue(ClipSearch.searchTexts("   ", listOf("git")).isEmpty())
    }

    private fun meta(id: String, title: String) = ClipMeta(
        id = id,
        title = title,
        kind = ClipFilterType.TEXT,
        files = emptyList(),
        application = null,
        firstCopiedAt = 0L,
        lastCopiedAt = 0L,
        numberOfCopies = 1,
        pin = null,
        payloadBytes = 0L,
        contentKey = id,
        hasRecognizedText = false,
        hasImage = false,
    )
}
