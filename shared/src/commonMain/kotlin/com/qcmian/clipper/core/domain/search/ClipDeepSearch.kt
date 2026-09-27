package com.qcmian.clipper.core.domain.search

import com.qcmian.clipper.core.domain.model.ClipText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 全文（正文）搜索：在**标题之外**的那部分文字里再找一遍同一个查询。
 *
 * 界面与 CLI 共用这一段，因为它的形状由**存储**决定，而不是由调用方决定：正文不在内存里
 * （一条可能几十 KB，一万条就是几百 MB），只能**分批读库、匹配完即弃**。于是峰值内存只与
 * [BATCH_SIZE] 成正比、与历史总量无关——这个性质两边都必须有，各写一份迟早会漂。
 *
 * 匹配本身不在这里：它复用 [ClipSearch.searchTexts]，与标题搜索共用同一套拆词、档位与
 * 细节分，因此正文命中的分数与标题命中**可以放在一起比较**。
 *
 * 调用方负责决定「拿哪些条目来扫」——已经按标题命中的、被类型筛选掉的都该在外面排除，
 * 这里只回答「给定这些 id，正文里命中了哪些」。
 */
object ClipDeepSearch {

    /**
     * 每批取多少条正文。
     *
     * 一批就是一次 `IN` 查询的规模。几百条让往返次数可接受，又保证单批的正文（哪怕都是
     * 长文本）只占几 MB——这就是「不全读进内存」的落点。
     */
    const val BATCH_SIZE: Int = 200

    /**
     * 最多返回多少条正文命中。
     *
     * 调用方要的是「再看看有没有」，不是一份新列表；CLI 一次最多也只有 200 条出口
     * （见 `MAX_LIMIT`），再多拿不到。
     */
    const val MAX_HITS: Int = 200

    /**
     * 最多读多少字符的正文。
     *
     * [MAX_HITS] 限的是**结果数**，这一条限的是**工作量**：正文里没有命中时结果数永远不涨，
     * 只有字符预算能拦住它把整个库读完。
     */
    const val MAX_CHARS: Long = 16L * 1024 * 1024

    /**
     * 一次全文搜索的结果。
     *
     * @param ids 命中的条目 id，按匹配分数降序。同一个条目的两段正文（用户复制的正文与图片
     *   识别原文）是两条候选，只留匹配更好的那一条。
     * @param truncated 扫描是否因 [MAX_HITS] / [MAX_CHARS] 提前结束。为 `true` 时
     *   **后面可能还有命中**——调用方应如实告知，而不是把它当成完整结果。
     */
    class Outcome(val ids: List<String>, val truncated: Boolean)

    /**
     * 在 [candidateIds] 对应的正文里搜 [query]。
     *
     * @param readTexts 按 id 批量取正文。同一个 id 可能回来两段（见 [ClipText]），调用方不必
     *   自己去重——这里按 id 只留分数最高的那一段。
     */
    suspend fun search(
        query: String,
        candidateIds: List<String>,
        readTexts: suspend (List<String>) -> List<ClipText>,
        maxHits: Int = MAX_HITS,
        maxChars: Long = MAX_CHARS,
    ): Outcome {
        if (query.isBlank() || candidateIds.isEmpty()) return Outcome(emptyList(), truncated = false)

        // 取消检查分两层：批次之间用 [ensureActive]（挂起点上协程天然会取消），批内那段同步
        // 循环则靠这个 lambda 自检——`ClipSearch.searchTexts` 收不到协程的取消状态，它是一整段
        // 没有挂起点的纯 CPU 循环。
        val context = currentCoroutineContext()
        val found = ArrayList<String>(minOf(maxHits, 64))
        val seen = HashSet<String>()
        var scannedChars = 0L

        for (chunk in candidateIds.chunked(BATCH_SIZE)) {
            context.ensureActive()

            val texts = readTexts(chunk)
            if (texts.isEmpty()) continue

            scannedChars += texts.sumOf { it.text.length.toLong() }
            // 匹配放到后台线程：成本与「这一批的字符数」成正比，不该占着调用方的线程
            // （界面那条路径的调用方正是主线程）。
            val hits = withContext(Dispatchers.Default) {
                ClipSearch.searchTexts(query, texts.map { it.text }) { !context.isActive }
            }
            for (hit in hits) {
                if (found.size >= maxHits) break
                val id = texts[hit.index].id
                if (!seen.add(id)) continue
                found += id
            }

            // 两道闸门：结果够多，或正文读得够多。后者防的是一条超长正文（或一个很大的库）
            // 把一次调用拖成几秒——要的是「再看看有没有」，不是把整个库读完。
            if (found.size >= maxHits || scannedChars >= maxChars) {
                return Outcome(found, truncated = true)
            }
        }

        return Outcome(found, truncated = false)
    }
}
