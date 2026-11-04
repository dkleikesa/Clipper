package com.qcmian.logviewer.core.engine

import com.qcmian.logviewer.core.doc.InMemoryDocument
import com.qcmian.logviewer.core.doc.DocumentSource
import com.qcmian.logviewer.core.doc.asciiLowercase
import com.qcmian.logviewer.core.model.FilterConfig
import com.qcmian.logviewer.core.model.FilterType
import com.qcmian.logviewer.core.model.MatchMode
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/* ==================== 过滤管道（3.3） ==================== */

/**
 * 处理管道：包含 → 排除 → 当前视图
 * - 有任一启用 Include 时：命中任一 Include 条件的行进入候选集；
 *   无启用 Include 时，全部行进入候选集（D7）
 * - Exclude 在候选集上执行，命中的行被隐藏
 * - 着色优先级：按列表顺序第一个命中的过滤器（F3-5）
 */
class LinePipeline(filters: List<FilterConfig>) {

    data class Entry(
        val config: FilterConfig,
        val matcher: Matcher?,
        /** 在**原始** filters 列表里的下标（含未启用的规则）：命中数与该行的着色都按它回填。 */
        val sourceIndex: Int,
    ) {
        val isInclude get() = config.type == FilterType.INCLUDE
    }

    val entries: List<Entry> = filters.mapIndexedNotNull { index, f ->
        if (f.enabled) Entry(f, buildMatcher(f), index) else null
    }

    /**
     * 是否存在**能生效**的包含规则。
     *
     * 判据里必须算上 `matcher != null`：一条刚建出来、pattern 还空着的包含规则（「＋ 新增过滤器」
     * 建的就是这种）本身匹配不了任何行，若把它算作「有包含规则」，候选集就成了空集——视图在看
     * 不到任何内容的情况下变白。
     */
    val hasInclude: Boolean = entries.any { it.isInclude && it.matcher != null }
    val hasInvalid: Boolean = entries.any { it.matcher == null }

    /**
     * 可复用的行评估结果（避免逐行分配；单协程使用，由调用方持有实例）。
     *
     * [hits] 记录本行**命中的所有规则**（原始下标），供命中数统计使用：只留「第一个命中的规则」
     * 会让同一行命中多条规则时少算，也会让排除规则永远是 0。
     */
    class LineEval(entryCount: Int) {
        val hits: IntArray = IntArray(entryCount)
        var hitCount: Int = 0
        var pass: Boolean = false
        var colorEntry: Entry? = null
    }

    /** 评估一行，结果写入 [out]（零分配）并返回 */
    fun eval(text: String, markers: Set<Int>?, out: LineEval): LineEval {
        var candidate = !hasInclude
        var color: Entry? = null
        var excluded = false
        var hits = 0
        out.hitCount = 0
        for (e in entries) {
            val m = e.matcher ?: continue
            if (!m.matches(text, markers)) continue
            if (hits < out.hits.size) out.hits[hits++] = e.sourceIndex
            if (color == null) color = e
            if (e.isInclude) candidate = true else excluded = true
        }
        out.hitCount = hits
        out.pass = candidate && !excluded
        out.colorEntry = if (out.pass) color else null
        return out
    }

    companion object {
        fun buildMatcher(f: FilterConfig): Matcher? = when (f.matchMode) {
            MatchMode.STRING ->
                if (f.pattern.isEmpty()) null
                else StringMatcher(f.pattern, f.caseSensitive)
            MatchMode.REGEX ->
                try {
                    RegexMatcher(f.pattern, f.caseSensitive)
                } catch (_: Exception) {
                    null
                }
            MatchMode.MARKER -> MarkerMatcher(f.markerIndex)
        }
    }
}

interface Matcher {
    fun matches(text: String, markers: Set<Int>?): Boolean
}

class StringMatcher(private val needle: String, private val caseSensitive: Boolean) : Matcher {
    private val lowerNeedle = if (caseSensitive) needle else needle.lowercase()

    override fun matches(text: String, markers: Set<Int>?): Boolean =
        if (caseSensitive) text.contains(needle)
        else containsIgnoreCase(text, lowerNeedle)

    /** 大小写不敏感包含：regionMatches 扫描，不为每行分配 lowercase 拷贝（百万行场景避免 GB 级临时分配） */
    private fun containsIgnoreCase(hay: String, lowerNeedle: String): Boolean {
        val n = lowerNeedle.length
        if (n == 0) return true
        val last = hay.length - n
        var i = 0
        while (i <= last) {
            if (hay.regionMatches(i, lowerNeedle, 0, n, ignoreCase = true)) return true
            i++
        }
        return false
    }
}

class RegexMatcher(pattern: String, caseSensitive: Boolean) : Matcher {
    internal val regex = Regex(
        pattern,
        if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE),
    )

    override fun matches(text: String, markers: Set<Int>?): Boolean = regex.containsMatchIn(text)

    companion object {
        fun compile(pattern: String, caseSensitive: Boolean): RegexMatcher? = try {
            RegexMatcher(pattern, caseSensitive)
        } catch (_: Exception) {
            null
        }

        fun rangesOf(pattern: String, caseSensitive: Boolean, text: String): List<IntRange> {
            val m = compile(pattern, caseSensitive) ?: return emptyList()
            return m.regex.findAll(text).map { it.range }.toList()
        }
    }
}

class MarkerMatcher(private val markerIndex: Int) : Matcher {
    override fun matches(text: String, markers: Set<Int>?): Boolean =
        markers?.contains(markerIndex) == true
}

/* ==================== 过滤结果 ==================== */

class FilterResult(
    /** 通过管道的行索引（有序） */
    val matched: IntArray,
    /** 命中行 -> 第一个命中过滤器索引（对应应用时的 filters 列表），仅 Highlight/着色需要 */
    val colors: HashMap<Int, Int>?,
    /** 每个过滤器（含未启用，按原始列表下标）的命中行数 */
    val counts: IntArray,
    /** 结果生成时的过滤器指纹（列表内容 hash），用于判断是否需要重算 */
    val fingerprint: Long,
) {
    companion object {
        val Empty = FilterResult(IntArray(0), null, IntArray(0), 0L)
    }
}

fun filterFingerprint(filters: List<FilterConfig>): Long {
    var h = 1125899906842597L
    for (f in filters) {
        h = 31 * h + f.hashCode()
    }
    return h
}

/**
 * 执行过滤（后台、可取消）。
 *
 * 快路径：全部启用规则都是**文本**规则且 pattern 非空时，对全文做一次 indexOf 扫描（向量化
 * intrinsic），再把命中偏移二分回行号。其余情况（正则 / 标记 / 空 pattern）逐行评估。
 *
 * 正则**不进**快路径：逐行语义是「这一行是否命中」，而全文 `findAll` 里的 `^` `$` 只锚全文首尾、
 * `\s` 还能吃掉换行——`^ERROR` 这类最常见写法会静默少算（见 `FilterEngineTest`）。
 *
 * @param markerProvider 行索引 -> 该行标记集合（无 MARKER 过滤器时可传 null）
 */
suspend fun runFilter(
    doc: DocumentSource,
    filters: List<FilterConfig>,
    fingerprint: Long,
    markerProvider: (suspend (Int) -> Set<Int>?)?,
    onProgress: (Float) -> Unit,
): FilterResult {
    val active = filters.filter { it.enabled }
    if (active.isEmpty()) {
        onProgress(1f)
        return FilterResult(IntArray(0), null, IntArray(filters.size), fingerprint)
    }
    val fastEligible = doc is InMemoryDocument &&
        active.all { it.matchMode == MatchMode.STRING && it.pattern.isNotEmpty() }
    if (fastEligible) {
        return runFilterFastPath(doc as InMemoryDocument, filters, active, fingerprint, onProgress)
    }
    val pipeline = LinePipeline(filters)
    val needMarkers = filters.any { it.enabled && it.matchMode == MatchMode.MARKER }
    // 全程复用，零逐行分配
    val scratch = LinePipeline.LineEval(pipeline.entries.size)
    val total = doc.lineCount
    // 原始类型增长缓冲：避免 ArrayList<Int> 百万级装箱分配
    val matched = IntList(maxOf(64, total / 16))
    val colors = HashMap<Int, Int>()
    val counts = IntArray(filters.size)

    val batchSize = 8192
    var i = 0
    var lastProgress = 0f
    while (i < total) {
        val end = minOf(total, i + batchSize)
        for (lineIdx in i until end) {
            val line = doc.line(lineIdx)
            val markers = if (needMarkers) markerProvider?.invoke(lineIdx) else null
            val ev = pipeline.eval(line, markers, scratch)
            if (ev.pass) matched.add(lineIdx)
            // 命中数按规则各记一笔：与快路径同一口径，排除规则也照样计数
            for (k in 0 until ev.hitCount) counts[ev.hits[k]]++
            val colorEntry = ev.colorEntry
            // 只有配置了着色的过滤器才写行色映射（避免百万级无色命中撑爆 HashMap）
            if (colorEntry != null &&
                (colorEntry.config.background != null || colorEntry.config.foreground != null)
            ) {
                colors[lineIdx] = colorEntry.sourceIndex
            }
        }
        // 协作取消：被新过滤请求取代时尽早终止，不与新任务抢 CPU
        currentCoroutineContext().ensureActive()
        // 进度节流：每 2% 或完成时才回调，避免每批状态写触发上千次 UI 重组
        val p = end.toFloat() / total
        if (p - lastProgress >= 0.02f || end >= total) {
            onProgress(p)
            lastProgress = p
        }
        i = end
    }
    return FilterResult(matched.toArray(), colors, counts, fingerprint)
}

/** 原始类型 Int 增长缓冲（免装箱） */
private class IntList(initialCapacity: Int) {
    private var arr = IntArray(initialCapacity.coerceAtLeast(16))
    private var n = 0

    fun add(v: Int) {
        if (n == arr.size) arr = arr.copyOf(arr.size * 2)
        arr[n++] = v
    }

    fun toArray(): IntArray = if (n == arr.size) arr else arr.copyOf(n)
}

/** 快路径：每规则一次全文 indexOf 扫描（向量化）+ 命中位置二分行号 + 位图组合 */
private suspend fun runFilterFastPath(
    doc: InMemoryDocument,
    filters: List<FilterConfig>,
    active: List<FilterConfig>,
    fingerprint: Long,
    onProgress: (Float) -> Unit,
): FilterResult {
    val text = doc.text
    val totalLines = doc.lineCount
    val counts = IntArray(filters.size)

    // 存在忽略大小写的文本规则时构建（并缓存）小写全文——与原文等长，行偏移直接复用。
    // 进度：构建占 8%→55%（每完成一个并行段推进），等锁（预热协程先建）时跳到 55%
    val anyIgnoreCase = active.any { !it.caseSensitive }
    var lowerText: String? = null
    if (anyIgnoreCase) {
        onProgress(0.08f)
        lowerText = doc.lowerText { segP -> onProgress(0.08f + 0.47f * segP) }
    }

    // 每规则：全文命中行（有序去重 IntArray）；hits 扫描占 55%→90%
    // 规则在**原始**列表里的下标随 hits 一起带下去，着色与命中数都按它回填（不按 id 查表：
    // 导入的规则集合可能带着重复 id，按 id 会串成同一条）。
    val includeRules = ArrayList<RuleHits>(4)
    val excludeRules = ArrayList<RuleHits>(4)
    filters.forEachIndexed { fi, f ->
        if (!f.enabled) return@forEachIndexed
        currentCoroutineContext().ensureActive()
        val hay = if (f.caseSensitive) text else lowerText!!
        val needle = if (f.caseSensitive) f.pattern else asciiLowercase(f.pattern)
        val hits = collectHitLines(hay, doc, needle)
        counts[fi] = hits.size
        (if (f.type == FilterType.INCLUDE) includeRules else excludeRules)
            .add(RuleHits(fi, f, hits))
        onProgress(0.55f + 0.35f * (fi + 1) / filters.size)
    }

    // 位图组合：candidate = include 命中并集（无 include 则全行）；matched = candidate - exclude 命中
    val words = (totalLines + 63) / 64
    val inc = LongArray(words)
    val hasInclude = includeRules.isNotEmpty()
    if (hasInclude) {
        for (rule in includeRules) for (line in rule.hits) setBit(inc, line)
    }
    val exc = LongArray(words)
    for (rule in excludeRules) for (line in rule.hits) setBit(exc, line)

    // 有色规则才需要 colors
    val colorRules = includeRules.filter {
        it.config.background != null || it.config.foreground != null
    }
    val colors = if (colorRules.isEmpty()) null else HashMap<Int, Int>()

    val matched = IntList(maxOf(64, totalLines / 8))
    for (line in 0 until totalLines) {
        val candidate = !hasInclude || getBit(inc, line)
        if (!candidate || getBit(exc, line)) continue
        matched.add(line)
        if (colors != null) {
            for (rule in colorRules) {
                if (containsSorted(rule.hits, line)) {
                    colors[line] = rule.sourceIndex
                    break
                }
            }
        }
    }
    onProgress(1f)
    return FilterResult(matched.toArray(), colors, counts, fingerprint)
}

/** 一条规则在全文扫描里的命中行，连同它在原始 filters 列表里的下标。 */
private class RuleHits(
    val sourceIndex: Int,
    val config: FilterConfig,
    val hits: IntArray,
)

/** 全文命中 → 命中行号（有序去重；lineOfPos 单调非降） */
private fun collectHitLines(text: String, doc: InMemoryDocument, needle: String): IntArray {
    val out = IntList(256)
    var from = 0
    var last = -1
    while (true) {
        val idx = text.indexOf(needle, from)
        if (idx < 0) break
        val line = doc.lineOfPos(idx)
        if (line != last) {
            out.add(line)
            last = line
        }
        from = idx + needle.length
    }
    return out.toArray()
}

private fun setBit(bits: LongArray, i: Int) {
    bits[i ushr 6] = bits[i ushr 6] or (1L shl (i and 63))
}

private fun getBit(bits: LongArray, i: Int): Boolean =
    (bits[i ushr 6] and (1L shl (i and 63))) != 0L

private fun containsSorted(arr: IntArray, v: Int): Boolean {
    var lo = 0
    var hi = arr.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        when {
            arr[mid] == v -> return true
            arr[mid] < v -> lo = mid + 1
            else -> hi = mid - 1
        }
    }
    return false
}

class SearchSpec(
    val query: String,
    val regex: Boolean,
    val caseSensitive: Boolean,
    val wholeWord: Boolean = false,
) {
    private val stringMatcher: StringMatcher? =
        if (!regex && query.isNotEmpty()) StringMatcher(query, caseSensitive) else null
    private val regexMatcher: RegexMatcher? =
        if (regex && query.isNotEmpty()) {
            // 全词：在用户正则两侧包 \b…\b（用户已含 \b 则不重复包）
            val wrapped =
                if (wholeWord && query.isNotEmpty() && !query.startsWith("\\b") && !query.endsWith("\\b"))
                    "\\b$query\\b"
                else query
            RegexMatcher.compile(wrapped, caseSensitive)
        } else null

    val valid: Boolean get() = query.isNotEmpty() &&
        (!regex || regexMatcher != null)

    fun matches(text: String): Boolean = when {
        stringMatcher != null -> {
            if (!wholeWord) stringMatcher.matches(text, null)
            else wholeWordContains(text)
        }
        regexMatcher != null -> regexMatcher.matches(text, null)
        else -> false
    }

    /** 行内命中片段（高亮用） */
    fun rangesIn(text: String): List<IntRange> = when {
        stringMatcher != null -> {
            if (!wholeWord) findAllIndexOf(text)
            else wholeWordRanges(text)
        }
        regexMatcher != null -> regexMatcher!!.regex.findAll(text).map { it.range }.toList()
        else -> emptyList()
    }

    private fun findAllIndexOf(text: String): List<IntRange> {
        val out = ArrayList<IntRange>(4)
        if (query.isEmpty()) return out
        val hay = if (caseSensitive) text else text.lowercase()
        val needle = if (caseSensitive) query else query.lowercase()
        var from = 0
        while (true) {
            val idx = hay.indexOf(needle, from)
            if (idx < 0) break
            out.add(idx until (idx + needle.length))
            from = idx + needle.length
        }
        return out
    }

    private fun isWordChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    /** 全词匹配：检索 needle 必须被非单词字符（或文本边界）包围 */
    private fun wholeWordContains(text: String): Boolean {
        if (query.isEmpty()) return false
        val needle = if (caseSensitive) query else query.lowercase()
        val hay = if (caseSensitive) text else text.lowercase()
        var from = 0
        while (true) {
            val idx = hay.indexOf(needle, from)
            if (idx < 0) return false
            val beforeOk = idx == 0 || !isWordChar(hay[idx - 1])
            val afterOk = idx + needle.length == hay.length || !isWordChar(hay[idx + needle.length])
            if (beforeOk && afterOk) return true
            from = idx + 1
        }
    }

    private fun wholeWordRanges(text: String): List<IntRange> {
        val out = ArrayList<IntRange>(4)
        if (query.isEmpty()) return out
        val needle = if (caseSensitive) query else query.lowercase()
        val hay = if (caseSensitive) text else text.lowercase()
        var from = 0
        while (true) {
            val idx = hay.indexOf(needle, from)
            if (idx < 0) break
            val beforeOk = idx == 0 || !isWordChar(hay[idx - 1])
            val afterOk = idx + needle.length == hay.length || !isWordChar(hay[idx + needle.length])
            if (beforeOk && afterOk) out.add(idx until (idx + needle.length))
            from = idx + 1
        }
        return out
    }
}

/**
 * 执行搜索（后台、可取消）。
 * @param scope null = 全文件；否则在该行索引序列中搜索
 */
suspend fun runSearch(
    doc: DocumentSource,
    spec: SearchSpec,
    scope: IntArray?,
    onProgress: (Float) -> Unit,
): IntArray {
    if (!spec.valid) return IntArray(0)
    val total = scope?.size ?: doc.lineCount
    if (total == 0) return IntArray(0)
    val out = ArrayList<Int>(minOf(total, 4096))
    val batchSize = 4096
    var i = 0
    while (i < total) {
        coroutineContext.ensureActive()
        val end = minOf(total, i + batchSize)
        if (scope != null) {
            // 作用域行：逐行访问（连续段）
            var j = i
            while (j < end) {
                val segStart = j
                while (j + 1 < end && scope[j + 1] == scope[j] + 1) j++
                for (k in segStart..j) {
                    val idx = scope[k]
                    if (spec.matches(doc.line(idx))) out.add(idx)
                }
                j++
            }
        } else {
            for (idx in i until end) {
                if (spec.matches(doc.line(idx))) out.add(idx)
            }
        }
        onProgress(end.toFloat() / total)
        i = end
    }
    return out.toIntArray()
}
