package com.qcmian.logviewer.state

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.qcmian.logviewer.core.doc.DocumentSource
import com.qcmian.logviewer.core.model.FileRef
import com.qcmian.logviewer.core.model.FilterSet
import com.qcmian.logviewer.core.model.RecentEntry
import com.qcmian.logviewer.core.model.SessionData
import com.qcmian.logviewer.platform.LogViewerPlatform
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** 一个标签页 = 一个 ViewerState + 标题 */
class TabState(val id: Int, val viewer: ViewerState) {
    var title by mutableStateOf("")
}

/**
 * 应用级状态：多标签（F9-6）、会话与最近文件（F9-5）、偏好（3.9）、脱敏规则（F10-3）。
 * 会话自动保存到本地；纯本地，不离开设备（F10-4）。
 */
class AppState(val platform: LogViewerPlatform) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /* ===== 标签 ===== */
    val tabs = mutableStateListOf<TabState>()
    var activeTabId by mutableStateOf(-1)

    /* ===== 最近文件 / 会话 ===== */
    val recentFiles = mutableStateListOf<RecentEntry>()

    private val json = Json { ignoreUnknownKeys = true }
    private var tabSeq = 0
    private var saveJob: kotlinx.coroutines.Job? = null

    val active: ViewerState? get() = tabs.firstOrNull { it.id == activeTabId }?.viewer

    init {
        loadPersisted()
        // 启动即为干净状态：不自动恢复上次会话（每次打开均为欢迎页）
        newEmptyTab()
        // 监听外部打开（iOS onOpenURL / 分享）
        scope.launch {
            while (true) {
                com.qcmian.logviewer.platform.SharedBus.consumeAll().forEach { path ->
                    val name = path.substringAfterLast('/')
                    openFile(FileRef(name, path, path))
                }
                delay(500)
            }
        }
    }

    /* ==================== 持久化 ==================== */

    private fun loadPersisted() {
        runCatching {
            platform.getPref("recent")?.let {
                recentFiles.clear()
                recentFiles.addAll(json.decodeFromString(ListSerializer(RecentEntry.serializer()), it))
            }
        }
    }

    /* ==================== 标签管理 ==================== */

    fun newEmptyTab(): TabState {
        val v = createViewer()
        // 新 tab 继承当前 tab 的全部过滤器（含启停），排障中途换文件不丢规则
        val current = tabs.firstOrNull { it.id == activeTabId }?.viewer
        if (current != null && current.filters.isNotEmpty()) {
            v.filters.addAll(current.filters)
        }
        val tab = TabState(tabSeq, v)
        tabSeq++
        tab.title = "未打开文件"
        tabs.add(tab)
        activeTabId = tab.id
        return tab
    }

    private fun createViewer(): ViewerState = ViewerState(tabSeq, platform, scope)

    /**
     * 打开文件：默认在当前 tab 替换内容（打开按钮/菜单/最近列表）；
     * [newTab]=true 时新建 tab（拖拽文件）。
     * 当前 tab 的过滤器保留：新文件若有记忆过滤器则覆盖恢复，否则沿用当前规则。
     */
    fun openFile(ref: FileRef, session: SessionData? = null, newTab: Boolean = false) {
        val tab = if (newTab) {
            newEmptyTab()
        } else {
            tabs.firstOrNull { it.id == activeTabId } ?: newEmptyTab()
        }
        tab.title = ref.name
        // 过滤器按文件记忆：该文件上次使用过的过滤器自动恢复（显示模式默认"显示命中"）
        val remembered = runCatching {
            platform.getPref("filters:${ref.uri}")?.let {
                json.decodeFromString(FilterSet.serializer(), it)
            }
        }.getOrNull()
        val effectiveSession = when {
            session != null -> session
            remembered != null && remembered.filters.isNotEmpty() ->
                SessionData(fileName = ref.name, fileUri = ref.uri, filters = remembered.filters)
            else -> null
        }
        tab.viewer.loadRef(ref, session = effectiveSession)
        addRecent(ref, 0, 0)
    }

    /** 文本导入（粘贴/示例）：默认当前 tab 替换 */
    fun openTextTab(name: String, text: String, newTab: Boolean = false) {
        val tab = if (newTab) {
            newEmptyTab()
        } else {
            tabs.firstOrNull { it.id == activeTabId } ?: newEmptyTab()
        }
        tab.title = name
        tab.viewer.loadText(name, text)
    }

    /** 加载示例日志（F1-6） */
    fun loadSample() {
        openTextTab(com.qcmian.logviewer.core.SampleLogs.fileName, com.qcmian.logviewer.core.SampleLogs.text)
    }

    /** 粘贴导入（F1-2） */
    fun importFromClipboard() {
        val text = platform.getClipboardText()
        if (text.isNullOrBlank()) return
        val name = "剪贴板导入 ${timeLabel()}.log"
        openTextTab(name, text)
    }

    private fun timeLabel(): String {
        val t = com.qcmian.logviewer.platform.nowMillis() / 1000
        return t.toString()
    }

    fun closeTab(tabId: Int) {
        val idx = tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return
        val tab = tabs.removeAt(idx)
        scheduleSaveSession()
        tab.viewer.close()
        if (activeTabId == tabId) {
            activeTabId = tabs.getOrNull((idx - 1).coerceAtLeast(0))?.id ?: -1
        }
        if (tabs.isEmpty()) newEmptyTab()
    }

    fun activateTab(tabId: Int) {
        activeTabId = tabId
    }

    /* ==================== 多文件聚合（F9-7 基础版） ==================== */

    /** 将所有已就绪标签合并为聚合视图标签 */
    fun openAggregateView() {
        val docs = tabs.mapNotNull { t ->
            t.viewer.document?.takeIf { t.viewer.loadPhase == ViewerState.LoadPhase.Ready }
        }
        if (docs.size < 2) return
        val agg = AggregateDocument(docs)
        val tab = newEmptyTab()
        tab.title = "聚合视图（${docs.size} 个文件）"
        tab.viewer.adoptAggregate(agg)
    }    /* ==================== 最近文件与自动会话（F9-5） ==================== */

    private fun addRecent(ref: FileRef, lineCount: Int, size: Long) {
        recentFiles.removeAll { it.fileUri == ref.uri }
        recentFiles.add(
            0,
            RecentEntry(ref.name, ref.uri, lineCount, size, com.qcmian.logviewer.platform.nowMillis()),
        )
        while (recentFiles.size > MAX_RECENT_FILES) {
            recentFiles.removeAt(recentFiles.lastIndex)
        }
        persistRecent()
    }

    private fun persistRecent() {
        runCatching {
            platform.putPref("recent", json.encodeToString(ListSerializer(RecentEntry.serializer()), recentFiles))
        }
    }

    fun removeRecent(uri: String) {
        recentFiles.removeAll { it.fileUri == uri }
        persistRecent()
    }

    fun clearRecent() {
        recentFiles.clear()
        persistRecent()
    }

    /** 会话自动保存（脏标记 + 1s 防抖） */
    fun scheduleSaveSession() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1000)
            saveSessions()
        }
    }

    fun saveSessions() {
        runCatching {
            val list = tabs.mapNotNull { t ->
                val v = t.viewer
                val uri = v.fileRef?.uri ?: return@mapNotNull null
                v.sessionSnapshot(v.firstVisible)
            }
            platform.putPref("sessions", json.encodeToString(ListSerializer(SessionData.serializer()), list))
            // 过滤器按文件持久化（新 tab 打开同一文件时自动恢复）
            tabs.forEach { t ->
                val v = t.viewer
                val uri = v.fileRef?.uri ?: return@forEach
                runCatching {
                    platform.putPref(
                        "filters:$uri",
                        json.encodeToString(FilterSet.serializer(), v.filterSetSnapshot()),
                    )
                }
            }
            // 更新最近文件行数与大小
            recentFiles.forEach { entry ->
                val v = tabs.firstOrNull { it.viewer.fileRef?.uri == entry.fileUri }?.viewer
                if (v != null && v.lineCount > 0) {
                    entry.let {
                        recentFiles.removeAll { r -> r.fileUri == entry.fileUri }
                        recentFiles.add(
                            0,
                            RecentEntry(it.fileName, it.fileUri, v.lineCount, v.fileSizeBytes, it.lastOpenedAt),
                        )
                    }
                    persistRecent()
                }
            }
        }
    }

    private companion object {
        const val MAX_RECENT_FILES = 20
    }
}

/** 聚合文档：多文件合并查看（列模式为文件名列） */
class AggregateDocument(private val docs: List<DocumentSource>) : DocumentSource {
    private val offsets = IntArray(docs.size + 1)
    private val total: Int

    init {
        var acc = 0
        docs.forEachIndexed { i, d ->
            offsets[i] = acc
            acc += d.lineCount
        }
        offsets[docs.size] = acc
        total = acc
    }

    override val name = "聚合视图"
    override val fileUri = "aggregate://multi"
    override val lineCount get() = total
    override val sizeBytes get() = docs.sumOf { it.sizeBytes }
    override val encodingName = docs.firstOrNull()?.encodingName ?: "UTF-8"

    fun sourceIndexAt(globalIdx: Int): Int {
        var lo = 0; var hi = docs.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (offsets[mid] <= globalIdx) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun sourceAt(globalIdx: Int): DocumentSource = docs[sourceIndexAt(globalIdx)]

    override fun line(index: Int): String {
        val si = sourceIndexAt(index)
        return docs[si].line(index - offsets[si])
    }
}
