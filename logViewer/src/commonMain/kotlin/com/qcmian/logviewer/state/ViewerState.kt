package com.qcmian.logviewer.state

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.qcmian.logviewer.core.doc.DocumentSource
import com.qcmian.logviewer.platform.OpenedLogSource
import com.qcmian.logviewer.core.doc.InMemoryDocument
import com.qcmian.logviewer.core.doc.TextEncoding
import com.qcmian.logviewer.core.doc.decodeBytes
import com.qcmian.logviewer.core.doc.loadDocument
import com.qcmian.logviewer.core.doc.splitLines
import com.qcmian.logviewer.core.engine.FilterResult
import com.qcmian.logviewer.core.engine.SearchSpec
import com.qcmian.logviewer.core.engine.filterFingerprint
import com.qcmian.logviewer.core.engine.runFilter
import com.qcmian.logviewer.core.engine.runSearch
import com.qcmian.logviewer.core.model.DisplayLine
import com.qcmian.logviewer.core.model.DisplayMode
import com.qcmian.logviewer.core.model.FileRef
import com.qcmian.logviewer.core.model.FilterConfig
import com.qcmian.logviewer.core.model.FilterSet
import com.qcmian.logviewer.core.model.FilterType
import com.qcmian.logviewer.core.model.FindSnapshot
import com.qcmian.logviewer.core.model.MatchMode
import com.qcmian.logviewer.core.model.SessionData
import com.qcmian.logviewer.platform.LogViewerPlatform
import com.qcmian.logviewer.platform.nowMillis
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * 单个文件标签的完整查看器状态：
 * 文档加载、过滤管道、查找、标记、当前/选中行、跳转历史、列模式。
 */
class ViewerState(
    val tabId: Int,
    val platform: LogViewerPlatform,
    val scope: CoroutineScope,
) {
    enum class LoadPhase { Idle, Loading, NeedEncoding, Ready }

    /* ===== 文档 ===== */
    var document by mutableStateOf<DocumentSource?>(null); private set
    var fileRef by mutableStateOf<FileRef?>(null); private set
    var fileName by mutableStateOf(""); private set
    var encodingZh by mutableStateOf("UTF-8"); private set
    var encodingEnum: TextEncoding? = null; private set
    var fileSizeBytes by mutableStateOf(0L); private set
    var lineCount by mutableStateOf(0); private set
    var loadPhase by mutableStateOf(LoadPhase.Idle); private set
    var loadProgress by mutableStateOf(0f); private set
    var loadError by mutableStateOf<String?>(null); private set
    private var activeReader: OpenedLogSource? = null

    /* ===== 过滤 ===== */
    val filters = mutableStateListOf<FilterConfig>()
    var displayMode by mutableStateOf(DisplayMode.SHOW_ONLY_FILTERED)
    var filterResult by mutableStateOf<FilterResult?>(null); private set
    var filterBusy by mutableStateOf(false); private set
    var filterProgress by mutableStateOf(0f); private set
    private var filterJob: Job? = null
    private var filterGeneration = 0L
    private var lastFingerprint = 0L

    /* ===== 标记 ===== */
    val markers = mutableStateMapOf<Int, Set<Int>>()
    var activeMarker by mutableStateOf(0)

    /* ===== 查找（独立于 Filter） ===== */
    var findQuery by mutableStateOf("")
    var findRegex by mutableStateOf(false)
    var findCaseSensitive by mutableStateOf(false)
    var findWholeWord by mutableStateOf(false)
    var findBusy by mutableStateOf(false); private set
    var findMatches: IntArray? by mutableStateOf(null); private set
    var findCursor by mutableStateOf(-1)
    val findHistory = mutableStateListOf<String>()
    private var findJob: Job? = null

    /* ===== 当前行 / 选择 ===== */
    var currentLine by mutableStateOf(-1)
    var selectionAnchor by mutableStateOf(-1)
    val selectedLines = mutableStateListOf<Int>()

    /* ===== 可见区与行文本缓存（大文件按需） ===== */
    var firstVisible by mutableStateOf(0)
    var lastVisible by mutableStateOf(0)
    val lineCache = mutableStateMapOf<Int, String>()
    var scrollTarget by mutableStateOf<Pair<Int, Long>?>(null)
    var atBottom by mutableStateOf(true)

    /* ===== 跳转历史（F7-2 位置保持；浏览器式后退/前进双栈） ===== */
    private val navStack = ArrayDeque<Int>()
    private val forwardStack = ArrayDeque<Int>()
    var canGoBack by mutableStateOf(false); private set
    var canGoForward by mutableStateOf(false); private set

    /* ===== 详情侧栏 ===== */
    var detailLine by mutableStateOf(-1)

    /* ===== 会话 ===== */
    var sessionRestored = false

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    /* ==================== 加载 ==================== */

    fun loadRef(ref: FileRef, encoding: TextEncoding? = null, session: SessionData? = null) {
        closeReader()
        fileRef = ref
        fileName = ref.name
        loadPhase = LoadPhase.Loading
        loadError = null
        loadProgress = 0f
        scope.launch(Dispatchers.Default) {
            try {
                val reader = platform.openReader(ref)
                if (reader == null) {
                    withContext(Dispatchers.Main) {
                        loadError = "当前平台无法读取该文件：${ref.uri}"
                        loadPhase = LoadPhase.Idle
                    }
                    return@launch
                }
                activeReader = reader
                val result = loadDocument(ref.name, ref.uri, reader.source, reader.sizeBytes, encoding) { p ->
                    loadProgress = p
                }
                withContext(Dispatchers.Main) {
                    when (result) {
                        is com.qcmian.logviewer.core.doc.LoadResult.NeedEncodingChoice -> {
                            loadPhase = LoadPhase.NeedEncoding
                        }
                        is com.qcmian.logviewer.core.doc.LoadResult.Ok -> onDocumentReady(result.document, encoding)
                        is com.qcmian.logviewer.core.doc.LoadResult.Failed -> {
                            loadError = result.message
                            loadPhase = LoadPhase.Idle
                        }
                    }
                    if (loadPhase == LoadPhase.Ready && session != null) {
                        restoreSession(session)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    loadError = e.message ?: e::class.simpleName
                    loadPhase = LoadPhase.Idle
                }
            }
        }
    }

    /** 用户在编码对话框中确认编码后重载 */
    fun confirmEncoding(encoding: TextEncoding) {
        val ref = fileRef ?: return
        loadRef(ref, encoding)
    }

    /** 粘贴导入 / 示例日志 */
    fun loadText(name: String, text: String) {
        closeReader()
        fileRef = null
        fileName = name
        // 粘贴/示例导入是全新内容：清掉旧文件的过滤器（避免新文本被无关规则过滤成空视图）
        filters.clear()
        encodingEnum = TextEncoding.UTF8
        encodingZh = "UTF-8"
        fileSizeBytes = text.length.toLong()
        val doc = InMemoryDocument(name, "memory://$name", text, "UTF-8", fileSizeBytes)
        onDocumentReady(doc, TextEncoding.UTF8)
    }

    /** 采用聚合文档（多文件聚合视图） */
    fun adoptAggregate(doc: DocumentSource) {
        closeReader()
        fileRef = null
        fileName = doc.name
        encodingEnum = TextEncoding.UTF8
        onDocumentReady(doc, TextEncoding.UTF8)
    }

    private fun onDocumentReady(doc: DocumentSource, enc: TextEncoding?) {
        document?.close()
        document = doc
        encodingEnum = enc ?: TextEncoding.UTF8
        encodingZh = doc.encodingName
        fileSizeBytes = doc.sizeBytes
        lineCount = doc.lineCount
        loadProgress = 1f
        loadPhase = LoadPhase.Ready
        // 后台预热小写全文（忽略大小写过滤快路径；大文件首次构建 ~1-2s）
        // delay 1.2s：让首次过滤（scheduleRefilter(0) 立即启动）先抢到构建权，
        // 进度回调全程有效；预热退化为"打开后暂不过滤"场景的提前构建
        if (doc is InMemoryDocument && doc.sizeBytes > 256L * 1024) {
            scope.launch(Dispatchers.Default) {
                kotlinx.coroutines.delay(1200)
                runCatching { doc.lowerText() }
            }
        }
        lineCache.clear()
        currentLine = -1
        selectedLines.clear()
        selectionAnchor = -1
        markers.clear()
        findMatches = null
        findCursor = -1
        if (sessionRestored.not()) {
            // 显示模式是「打开新内容」时的固定默认值：外观与默认行为都由宿主决定，不读偏好
            displayMode = DisplayMode.SHOW_ONLY_FILTERED
        }
        scheduleRefilter(0)
        requestVisibleRange(0, 50)
    }

    /** F1-5 重新加载：保留过滤规则、标记与滚动位置 */
    fun reload() {
        val ref = fileRef ?: return
        val keepCurrent = currentLine
        val keepFirst = firstVisible
        loadRef(ref, encodingEnum)
        scope.launch {
            // 等待就绪后恢复位置
            var waited = 0
            while (loadPhase == LoadPhase.Loading && waited < 30_000) {
                delay(100); waited += 100
            }
            withContext(Dispatchers.Main) {
                if (lineCount > 0) {
                    currentLine = keepCurrent.coerceAtMost(lineCount - 1)
                    scrollTarget = (min(keepFirst, (lineCount - 1).coerceAtLeast(0))) to nowMillis()
                }
            }
        }
    }

    private fun closeReader() {
        runCatching { activeReader?.source?.close() }
        activeReader = null
    }

    fun close() {
        closeReader()
        filterJob?.cancel()
        findJob?.cancel()
        document?.close()
    }

    /* ==================== 过滤管理 ==================== */

    fun addFilter(filter: FilterConfig) {
        filters.add(filter)
        onFiltersChanged()
    }

    fun updateFilter(filter: FilterConfig) {
        val i = filters.indexOfFirst { it.id == filter.id }
        if (i >= 0) {
            filters[i] = filter
            onFiltersChanged()
        }
    }

    fun removeFilter(id: Long) {
        filters.removeAll { it.id == id }
        onFiltersChanged()
    }

    fun toggleFilter(id: Long) {
        val i = filters.indexOfFirst { it.id == id }
        if (i >= 0) {
            filters[i] = filters[i].copy(enabled = !filters[i].enabled)
            onFiltersChanged()
        }
    }

    /** 调整优先级：delta = -1 上移 / +1 下移（拖拽 Reorder 的等价操作） */
    fun moveFilter(id: Long, delta: Int) {
        val i = filters.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= filters.size) return
        val item = filters.removeAt(i)
        filters.add(j, item)
        onFiltersChanged()
    }

    fun moveFilterTo(id: Long, toIndex: Int) {
        val i = filters.indexOfFirst { it.id == id }
        if (i < 0) return
        val item = filters.removeAt(i)
        filters.add(toIndex.coerceIn(0, filters.size), item)
        onFiltersChanged()
    }

    fun clearFilters() {
        filters.clear()
        onFiltersChanged()
    }

    /**
     * 装载一组规则（导入文件 / 预设）。
     *
     * 导入的规则带着**文件里那份 id**：直接装进来会和现有规则撞号，而 `updateFilter` / `removeFilter`
     * 都按 id 找规则——改一条会同时动另一条。因此一律重新分配。
     */
    fun applyFilterSet(set: FilterSet) {
        filters.clear()
        filters.addAll(set.filters.map { it.copy(id = FilterConfig.nextId()) })
        if (set.showOnlyFiltered) displayMode = DisplayMode.SHOW_ONLY_FILTERED
        onFiltersChanged()
    }

    fun filterSetSnapshot(): FilterSet = FilterSet(
        name = fileName.ifEmpty { "未命名" },
        showOnlyFiltered = displayMode == DisplayMode.SHOW_ONLY_FILTERED,
        filters = filters.toList(),
    )

    /** 双击行 / 查找词一键转过滤器 */
    fun quickFilter(text: String, name: String? = null): FilterConfig {
        val trimmed = text.trim().take(120)
        val f = FilterConfig(
            name = name ?: trimmed.ifEmpty { "新规则" },
            pattern = trimmed,
            type = FilterType.INCLUDE,
            matchMode = MatchMode.STRING,
        )
        addFilter(f)
        return f
    }

    private fun onFiltersChanged() {
        scheduleRefilter(300)
    }

    fun scheduleRefilter(delayMs: Long = 0) {
        filterJob?.cancel()
        val gen = ++filterGeneration
        filterJob = scope.launch {
            if (delayMs > 0) delay(delayMs)
            executeFilter(gen)
        }
    }

    private suspend fun executeFilter(gen: Long) = withContext(Dispatchers.Default) {
        val doc = document ?: return@withContext
        val fp = filterFingerprint(filters)
        if (fp == lastFingerprint && filterResult != null && filters.isNotEmpty()) {
            rebuildShowOnly()
            return@withContext
        }
        if (filters.none { it.enabled }) {
            // 写回前校验：期间若有更新的过滤请求（gen 落后），放弃本次状态写入
            if (gen != filterGeneration || !isActive) return@withContext
            lastFingerprint = fp
            filterResult = null
            filterBusy = false
            filterProgress = 1f
            rebuildShowOnly()
            return@withContext
        }
        filterBusy = true
        filterProgress = 0f
        val fpSnapshot = fp
        val result = runFilter(doc, filters.toList(), fpSnapshot, { idx -> markers[idx] }) {
            filterProgress = it
        }
        // 写回前校验：旧任务的过期结果不得覆盖新任务（多过滤器快速连续修改的竞态）
        if (gen != filterGeneration || !isActive) return@withContext
        lastFingerprint = fpSnapshot
        filterResult = result
        filterBusy = false
        filterProgress = 1f
        rebuildShowOnly()
    }

    /* ==================== 显示序列 ==================== */
    // "显示命中"模式直接使用 filterResult.matched（升序 IntArray），不构建中间列表

    private fun rebuildShowOnly() {
        // matched 即显示序列（升序 IntArray），无需重建
    }

    private fun binaryContains(arr: IntArray, v: Int): Boolean {
        var lo = 0; var hi = arr.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            when {
                arr[mid] == v -> return true
                arr[mid] < v -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return false
    }

    fun hasActiveFilters(): Boolean = filters.any { it.enabled }

    /** 当前视图行数 */
    fun displayedCount(): Int = when (displayMode) {
        DisplayMode.SHOW_ALL -> lineCount
        DisplayMode.SHOW_ONLY_FILTERED ->
            if (hasActiveFilters()) filterResult?.matched?.size ?: 0 else lineCount
    }

    /** 视图位置 -> 显示行 */
    fun displayAt(pos: Int): DisplayLine {
        return when (displayMode) {
            DisplayMode.SHOW_ALL -> DisplayLine(pos, isMatchLine(pos))
            DisplayMode.SHOW_ONLY_FILTERED -> {
                val matched = filterResult?.matched
                if (hasActiveFilters() && matched != null) {
                    DisplayLine(matched.getOrNull(pos) ?: pos, true)
                } else {
                    DisplayLine(pos, isMatchLine(pos))
                }
            }
        }
    }

    fun isMatchLine(idx: Int): Boolean {
        val fr = filterResult ?: return false
        return binaryContains(fr.matched, idx)
    }

    /** 行号 -> 视图位置（跳转用）；-1 表示该行当前不可见 */
    fun positionOfLine(idx: Int): Int {
        return when (displayMode) {
            DisplayMode.SHOW_ALL -> idx
            DisplayMode.SHOW_ONLY_FILTERED -> {
                if (!hasActiveFilters()) return idx
                // matched 升序 IntArray，二分查找行号
                val arr = filterResult?.matched ?: return -1
                var lo = 0
                var hi = arr.size - 1
                while (lo <= hi) {
                    val mid = (lo + hi) ushr 1
                    val v = arr[mid]
                    when {
                        v == idx -> return mid
                        v < idx -> lo = mid + 1
                        else -> hi = mid - 1
                    }
                }
                -1
            }
        }
    }

    /** 行的着色（fg, bg）：按列表顺序第一个命中的过滤器 */
    fun lineColors(idx: Int): Pair<Long?, Long?> {
        val fr = filterResult ?: return null to null
        val fi = fr.colors?.get(idx) ?: return null to null
        val f = filters.getOrNull(fi) ?: return null to null
        return f.foreground to f.background
    }

    /* ==================== 行文本访问 ==================== */

    fun textOf(idx: Int): String? {
        val doc = document ?: return null
        return if (doc is InMemoryDocument) doc.line(idx) else lineCache[idx]
    }

    fun requestVisibleRange(first: Int, last: Int) {
        firstVisible = first
        lastVisible = last
        val doc = document ?: return
        if (doc is InMemoryDocument) return
        scope.launch(Dispatchers.Default) {
            try {
                val from = max(0, first - 60)
                val to = min(doc.lineCount - 1, last + 60)
                if (from > to) return@launch
                val lines = (from..to).map { doc.line(it) }
                withContext(Dispatchers.Main) {
                    for (k in lines.indices) lineCache[from + k] = lines[k]
                    trimLineCache(from, to)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun trimLineCache(from: Int, to: Int) {
        if (lineCache.size < 30_000) return
        val keepFrom = from - 20_000
        val keepTo = to + 20_000
        val keys = lineCache.keys.toList()
        for (k in keys) {
            if (k < keepFrom || k > keepTo) lineCache.remove(k)
        }
    }

    /* ==================== 查找 ==================== */

    /**
     * 跑一次查找。
     *
     * [rememberQuery] 只该在「用户确实要搜这一句」时置真（回车、切开关）：边打边搜的中间态
     * （`e` / `er` / `err`…）记进历史，等于把历史刷成前缀树。
     */
    fun runFind(resetCursor: Boolean = true, rememberQuery: Boolean = true) {
        val doc = document ?: return
        if (findQuery.isEmpty()) {
            findMatches = null
            findCursor = -1
            return
        }
        if (rememberQuery) rememberFindHistory()
        findJob?.cancel()
        findBusy = true
        val spec = SearchSpec(findQuery, findRegex, findCaseSensitive, findWholeWord)
        if (!spec.valid) {
            findBusy = false
            findMatches = IntArray(0)
            findCursor = -1
            return
        }
        findJob = scope.launch(Dispatchers.Default) {
            // 所见即所搜："显示命中"模式且有过滤器 → 只搜命中行；"全部显示"或无过滤器 → 搜全文
            val scopeArr: IntArray? =
                if (hasActiveFilters() && displayMode == DisplayMode.SHOW_ONLY_FILTERED) filterResult?.matched
                else null
            val matches = runSearch(doc, spec, scopeArr) { }
            withContext(Dispatchers.Main) {
                findMatches = matches
                findBusy = false
                if (resetCursor) {
                    findCursor = -1
                    if (matches.isNotEmpty()) findNext(wrap = false)
                }
            }
        }
    }

    /** 把当前查询记进历史：回车提交、点历史项、存为规则时调用（边打边搜的中间态不记）。 */
    fun commitFindQuery() = rememberFindHistory()

    private fun rememberFindHistory() {
        val q = findQuery
        if (q.isBlank()) return
        findHistory.removeAll { it == q }
        findHistory.add(0, q)
        while (findHistory.size > 20) findHistory.removeAt(findHistory.lastIndex)
    }

    fun findNext(wrap: Boolean = true) {
        val m = findMatches ?: return
        if (m.isEmpty()) return
        var next = findCursor + 1
        if (next >= m.size) {
            if (!wrap) return
            next = 0
        }
        findCursor = next
        jumpToLine(m[next], pushHistory = true)
    }

    fun findPrev() {
        val m = findMatches ?: return
        if (m.isEmpty()) return
        var prev = findCursor - 1
        if (prev < 0) prev = m.size - 1
        findCursor = prev
        jumpToLine(m[prev], pushHistory = true)
    }

    fun clearFind() {
        findMatches = null
        findCursor = -1
    }

    /** 查找高亮片段（当前行） */
    /* ===== 查找规格缓存（避免每行重组重新构建/编译正则） ===== */
    private var specCache: SearchSpec? = null
    private var specKeyQuery: String? = null
    private var specKeyRegex = false
    private var specKeyCase = false
    private var specKeyWhole = false

    private fun findSpec(): SearchSpec {
        val cached = specCache
        if (cached != null && specKeyQuery == findQuery && specKeyRegex == findRegex &&
            specKeyCase == findCaseSensitive && specKeyWhole == findWholeWord
        ) {
            return cached
        }
        val s = SearchSpec(findQuery, findRegex, findCaseSensitive, findWholeWord)
        specCache = s
        specKeyQuery = findQuery
        specKeyRegex = findRegex
        specKeyCase = findCaseSensitive
        specKeyWhole = findWholeWord
        return s
    }

    fun findRanges(text: String): List<IntRange> {
        if (findQuery.isEmpty()) return emptyList()
        return findSpec().rangesIn(text)
    }

    /* ==================== 跳转与导航 ==================== */

    /** 跳转到原始行号（1-based 输入） */
    fun goToLineNumber(lineNo: Int): Boolean {
        val idx = lineNo - 1
        if (idx < 0 || idx >= lineCount) return false
        jumpToLine(idx, pushHistory = true)
        return true
    }

    /** 跳转到行索引并滚动定位（F7-1 当前行保持高亮） */
    fun jumpToLine(idx: Int, pushHistory: Boolean = false) {
        if (pushHistory && currentLine >= 0) {
            navStack.addLast(currentLine)
            if (navStack.size > 64) navStack.removeFirst()
            // 新跳转清空前进栈（浏览器语义）
            forwardStack.clear()
            canGoBack = true
            canGoForward = false
        }
        currentLine = idx
        detailLine = idx
        selectedLines.clear()
        selectionAnchor = idx
        val pos = positionOfLine(idx)
        if (pos >= 0) {
            scrollTarget = pos to nowMillis()
        }
    }

    /** F7-2：后退（回到上一次跳转前的位置；当前行入前进栈） */
    fun goBack() {
        val last = navStack.removeLastOrNull() ?: return
        if (currentLine >= 0) forwardStack.addLast(currentLine)
        canGoBack = navStack.isNotEmpty()
        canGoForward = true
        currentLine = last
        detailLine = last
        val pos = positionOfLine(last)
        if (pos >= 0) scrollTarget = pos to nowMillis()
    }

    /** 前进（后退的逆操作；当前行入后退栈） */
    fun goForward() {
        val next = forwardStack.removeLastOrNull() ?: return
        if (currentLine >= 0) navStack.addLast(currentLine)
        canGoBack = true
        canGoForward = forwardStack.isNotEmpty()
        currentLine = next
        detailLine = next
        val pos = positionOfLine(next)
        if (pos >= 0) scrollTarget = pos to nowMillis()
    }

    /* ==================== 标记 ==================== */

    fun toggleMarker(idx: Int, marker: Int = activeMarker) {
        val cur = markers[idx] ?: emptySet()
        if (cur.contains(marker)) {
            val next = cur - marker
            if (next.isEmpty()) markers.remove(idx) else markers[idx] = next
        } else {
            markers[idx] = cur + marker
        }
        // MARKER 过滤器依赖标记集合
        if (filters.any { it.enabled && it.matchMode == MatchMode.MARKER }) {
            scheduleRefilter(100)
        }
    }

    fun markersOf(idx: Int): Set<Int> = markers[idx] ?: emptySet()

    /* ==================== 行交互 ==================== */

    fun onLineClicked(idx: Int, ctrl: Boolean, shift: Boolean) {
        if (ctrl) {
            if (selectedLines.contains(idx)) selectedLines.remove(idx) else selectedLines.add(idx)
            selectionAnchor = idx
            currentLine = idx
            detailLine = idx
        } else if (shift && selectionAnchor >= 0) {
            val from = min(selectionAnchor, idx)
            val to = max(selectionAnchor, idx)
            selectedLines.clear()
            for (i in from..to) selectedLines.add(i)
            currentLine = idx
            detailLine = idx
        } else {
            selectedLines.clear()
            selectedLines.add(idx)
            selectionAnchor = idx
            currentLine = idx
            detailLine = idx
        }
    }

    /** 拖动多选：anchor（按下行）→ to 的连续区间替换当前选择 */
    fun selectRange(anchor: Int, to: Int) {
        val from = min(anchor, to)
        val last = max(anchor, to)
        selectedLines.clear()
        for (i in from..last) selectedLines.add(i)
        selectionAnchor = anchor
        currentLine = to
        detailLine = to
    }

    /* ==================== 复制与导出 ==================== */

    suspend fun copyLinesText(indexes: List<Int>): String {
        val doc = document ?: return ""
        return com.qcmian.logviewer.core.export.Exporter.copyText(
            doc, indexes,
            com.qcmian.logviewer.core.export.ExportSpec(),
        )
    }

    /** 当前视图的全部行（F8-3 Save Current View） */
    fun currentViewLines(): List<DisplayLine> {
        val n = displayedCount()
        val out = ArrayList<DisplayLine>(min(n, 100_000))
        for (i in 0 until n) out.add(displayAt(i))
        return out
    }

    suspend fun exportView(
        format: com.qcmian.logviewer.core.export.ExportFormat,
        includeLineNumbers: Boolean,
    ): String {
        val doc = document ?: return ""
        return com.qcmian.logviewer.core.export.Exporter.export(
            doc, currentViewLines(), format,
            com.qcmian.logviewer.core.export.ExportSpec(includeLineNumbers),
        ) { idx -> lineColors(idx) }
    }


    /* ==================== 会话（F9-5） ==================== */

    fun sessionSnapshot(scrollLine: Int): SessionData = SessionData(
        fileName = fileName,
        fileUri = fileRef?.uri ?: "",
        encoding = encodingEnum?.name ?: "UTF8",
        filters = filters.toList(),
        displayMode = displayMode,
        markers = markers.toMap(),
        scrollLine = scrollLine,
        currentLine = currentLine,
        find = FindSnapshot(findQuery, findRegex, findCaseSensitive),
        updatedAt = nowMillis(),
    )

    fun restoreSession(s: SessionData) {
        sessionRestored = true
        filters.clear()
        filters.addAll(s.filters)
        displayMode = s.displayMode
        markers.clear()
        s.markers.forEach { (k, v) -> markers[k] = v }
        findQuery = s.find.query
        findRegex = s.find.regex
        findCaseSensitive = s.find.caseSensitive
        currentLine = s.currentLine
        lastFingerprint = -1
        scheduleRefilter(0)
        scope.launch {
            delay(200)
            withContext(Dispatchers.Main) {
                if (s.scrollLine > 0) {
                    scrollTarget = s.scrollLine to nowMillis()
                }
            }
        }
    }

}
