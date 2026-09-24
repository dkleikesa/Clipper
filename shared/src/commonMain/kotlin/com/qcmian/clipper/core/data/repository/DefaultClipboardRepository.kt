package com.qcmian.clipper.core.data.repository

import com.qcmian.clipper.core.data.local.NOT_PINNED_AT
import com.qcmian.clipper.core.data.local.toItem
import com.qcmian.clipper.core.data.source.ClipStorageDataSource
import com.qcmian.clipper.core.data.source.ClipboardDataSource
import com.qcmian.clipper.core.data.source.NativeDataSource
import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.model.ClipPayload
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.model.ClipText
import com.qcmian.clipper.core.domain.model.SourceApplication
import com.qcmian.clipper.core.domain.model.deriveTitle
import com.qcmian.clipper.core.domain.model.extractReadableText
import com.qcmian.clipper.core.domain.model.toStoredTitle
import com.qcmian.clipper.core.domain.repository.ClipboardPlatform
import com.qcmian.clipper.core.domain.repository.ClipboardRepository
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.util.currentTimeMillis
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [ClipboardRepository] 的默认实现。
 *
 * 与单表时代的最大区别：**内存里只有元数据**。排序、计数、去重判据全部下推给 SQL；
 * 载荷只在被点开时按 id 取一次。写路径也从「改内存 → 全量 diff → 落盘」变成
 * 「按 id 精确写一列」，因此不再需要防抖队列来掩盖全量重写的成本。
 *
 * 所有业务规则（记录什么、如何合并重复项、激活时做什么）仍在领域层；本类只负责搬运数据，
 * 并维护它自己负责的两条不变量：元数据始终有序，且历史永不超过配置的上限。
 */
class DefaultClipboardRepository(
    private val clipboard: ClipboardDataSource,
    private val storage: ClipStorageDataSource,
    private val native: NativeDataSource,
    private val scope: CoroutineScope,
) : ClipboardRepository, ClipboardPlatform {

    private val _pinned = MutableStateFlow<List<ClipMeta>>(emptyList())
    override val pinned: StateFlow<List<ClipMeta>> = _pinned.asStateFlow()

    private val _unpinned = MutableStateFlow<List<ClipMeta>>(emptyList())
    override val unpinned: StateFlow<List<ClipMeta>> = _unpinned.asStateFlow()

    private val _totalUnpinned = MutableStateFlow(0)
    override val totalUnpinned: StateFlow<Int> = _totalUnpinned.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _settingsLoaded = MutableStateFlow(false)

    /**
     * 偏好已从存储读出后为 `true`：此前的 [settings] 是默认值，还不是用户真正的偏好。
     *
     * 刻意不等窗口加载完——打开数据库（含 WAL 恢复）实测要几百毫秒，而依赖它的只有
     * 「按真实偏好注册全局热键」这一类急事。
     */
    override val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    override val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _storageRevision = MutableStateFlow(0)
    override val storageRevision: StateFlow<Int> = _storageRevision.asStateFlow()

    private val _snapshots = MutableSharedFlow<ClipboardSnapshot>(extraBufferCapacity = 32)
    override val snapshots: Flow<ClipboardSnapshot> = _snapshots.asSharedFlow()

    private val _isWritingClipboard = MutableStateFlow(false)
    override val isWritingClipboard: StateFlow<Boolean> = _isWritingClipboard.asStateFlow()

    /**
     * 元数据的读改写全部串行化。
     *
     * 一次「刷新」与一次「按 id 写入」若交错执行，刷新可能读到半新半旧的一份元数据；
     * 而它是唯一被界面直接渲染的东西，错一格就是可见的错行。
     */
    private val metadataLock = Mutex()

    private var started = false

    /** [load] 完成后为 `true`；防止 [flush] 覆盖掉已存储的状态。 */
    @Volatile
    private var loaded = false

    private var startJob: Job? = null
    private var settingsPersistJob: Job? = null

    /** 正在跑的存储维护（回收 / 压紧）；同一时刻只允许一个。 */
    private var maintenanceJob: Job? = null

    override val storageBytes: Long? get() = storage.storageBytes()
    override val screenCount: Int get() = native.screenCount
    override val supportsLaunchAtLogin: Boolean get() = native.supportsLaunchAtLogin
    override val supportsApplicationInfo: Boolean get() = native.supportsApplicationInfo
    override val supportsTextRecognition: Boolean get() = native.supportsTextRecognition
    override val clipboardPollIntervalMillis: Int get() = clipboard.pollIntervalMillis.toInt()

    override fun start() {
        if (started) return
        started = true
        // 读取历史与偏好是阻塞式 IO。它运行在 [scope]——容器传入的、固定在 IO 上的作用域——
        // 而不是在构建依赖图的那个线程上（对 Android 而言是主线程）。剪贴板监听只会在存储
        // 状态就绪之后才启动，因此早期的复制不会拿过期的偏好来判断。
        startJob = scope.launch {
            if (!loaded) load()
            if (!started) return@launch
            applyPlatformSettings(_settings.value)
            clipboard.start { snapshot -> _snapshots.tryEmit(snapshot) }
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        startJob?.cancel()
        clipboard.stop()
    }

    override fun flush() {
        settingsPersistJob?.cancel()
        // 还没有加载任何数据：此时持久化会用内存默认值覆盖已存偏好。
        if (!loaded) return
        val settings = _settings.value
        scope.launch { storage.saveSettings(settings) }
    }

    override suspend fun flushNow() {
        settingsPersistJob?.cancel()
        if (!loaded) return
        storage.saveSettings(_settings.value)
    }

    override suspend fun close() {
        flushNow()
        // 退出时不再压紧（`VACUUM`）：拆表之后载荷表只靠 `incremental_vacuum` 回收，元数据表
        // 小到不必重写；而一个几 GB 的库做一次 `VACUUM` 要几十秒到几分钟，退出路径等不起。
        // 这里只做一次便宜的空闲页回收。
        runCatching { storage.reclaimFreePages(MIN_RECLAIM_BYTES) }
        storage.close()
    }

    // ---------------------------------------------------------------------------------
    // 元数据
    // ---------------------------------------------------------------------------------

    /**
     * 从存储重建内存中的元数据。
     *
     * **上限内的元数据是一次性全部装进来的**，不分页。两个原因：
     *
     * - **滚动条按「每条一行」的前缀和定位**（见 `HistoryScrollbar` 的 `ListHeightModel`）。
     *   只装前缀的话，内容总高按已加载条数算，滑块长度与拖动落点全都不对；而且「加载更多」
     *   会让总高突增，滑块当场跳一下——正是之前看到的现象。
     * - **类型筛选发生在内存里**（置顶项还豁免筛选）。要拿出一份「过滤后还剩多少条」的列表，
     *   就必须知道每一行的类型，那已经是元数据本身了。
     *
     * 这一层很轻：一行只有 id、标题、类型、几个时间戳与计数，没有图片字节也没有正文
     * （见 [ClipMeta]）。一万条约 6 MB；真正的量级来自图片与长正文，它们仍然只在被看到
     * 或被复制时才读出来。
     */
    private suspend fun reloadMetadata() {
        val current = _settings.value
        val total = storage.countUnpinned()
        _pinned.value = storage.loadPinned()
        _unpinned.value = storage.loadUnpinned(current.sortBy, current.sortOrder, total, 0)
        _totalUnpinned.value = total
    }

    // ---------------------------------------------------------------------------------
    // 载荷
    // ---------------------------------------------------------------------------------

    override suspend fun payload(id: String): ClipPayload? = storage.loadPayload(id)

    override suspend fun texts(ids: List<String>): List<ClipText> = storage.loadTexts(ids)

    override suspend fun item(id: String): ClipItem? {
        val meta = storage.loadMeta(id) ?: return null
        return meta.toItem(storage.loadPayload(id))
    }

    // ---------------------------------------------------------------------------------
    // 写
    // ---------------------------------------------------------------------------------

    override suspend fun insert(meta: ClipMeta, payload: ClipPayload?) {
        metadataLock.withLock {
            storage.insert(meta, payload)
            trimOverflow()
            reloadMetadata()
        }
    }

    override suspend fun updateStats(id: String, numberOfCopies: Int, lastCopiedAt: Long) {
        metadataLock.withLock {
            storage.updateStats(id, numberOfCopies, lastCopiedAt)
            // 统计变化会影响排序（「最后复制」「复制次数」两种排序下这一条都会挪位置）。
            reloadMetadata()
        }
    }

    override suspend fun <T> withoutCapturing(block: suspend () -> T): T {
        _isWritingClipboard.value = true
        return try {
            block()
        } finally {
            // 取消、异常也必须复位：否则监听会一直哑着，之后用户手动复制什么都不再记录。
            _isWritingClipboard.value = false
        }
    }

    override suspend fun recordBatchCopy(ids: List<String>) {
        if (ids.isEmpty()) return
        metadataLock.withLock {
            // 时间戳按顺序递增（先粘的排在后面）；墙钟精度不够时越过历史里最大的那一个。
            var now = maxOf(currentTimeMillis(), storage.maxLastCopiedAt() + 1L)
            for (id in ids) {
                val meta = storage.loadMeta(id) ?: continue
                storage.updateStats(id, meta.numberOfCopies + 1, now)
                now += 1L
            }
            reloadMetadata()
        }
    }

    override suspend fun updateRecognizedText(id: String, fullText: String, title: String) {
        metadataLock.withLock {
            storage.updateRecognizedText(id, fullText, title)
            reloadMetadata()
        }
    }

    override suspend fun meta(id: String): ClipMeta? = storage.loadMeta(id)

    override suspend fun findByContentKey(contentKey: String): String? =
        storage.findIdByContentKey(contentKey)

    override suspend fun latestLastCopiedAt(): Long = storage.maxLastCopiedAt()

    /**
     * 给历史遗留的空标题补标题。
     *
     * 只处理「标题为空、且载荷里确实能提取出文字」的条目，因此对正常条目零副作用；写完一次
     * 之后这条查询长期返回空列表。
     *
     * 标题为空的条目必然没有文件路径（路径会参与派生标题），所以这里不必再读元数据的
     * `files`，只按 id 取载荷就够。
     */
    override suspend fun backfillEmptyTitles() {
        val ids = storage.emptyTitleIds()
        if (ids.isEmpty()) return

        var repaired = 0
        for (id in ids) {
            val payload = storage.loadPayload(id) ?: continue
            if (payload.contents.isEmpty()) continue

            // 与写入路径同口径：标题既进内存又进搜索，必须有长度上限。附加表示可能是几十 KB
            // （一整页网页），不截断会把单条记录的常驻内存放大两个数量级。
            val title = deriveTitle(
                text = payload.text,
                files = emptyList(),
                title = "",
                richText = extractReadableText(payload.contents),
            ).toStoredTitle()
            if (title.isBlank()) continue

            storage.updateTitle(id, title, fromRecognition = false)
            repaired++
        }

        if (repaired > 0) metadataLock.withLock { reloadMetadata() }
    }

    override suspend fun setPinned(ids: List<String>, pinned: Boolean) {
        if (ids.isEmpty()) return
        metadataLock.withLock {
            if (pinned) {
                // 置顶时间戳从「现在」与「已有最大值之上」的较大者往下发：同一次批量置顶的几条
                // 因此也有确定的先后（在列表里靠上的仍排在置顶区靠上），且不会与已有的值撞上。
                var stamp = maxOf(currentTimeMillis(), storage.maxPinnedAt() + 1L) + ids.size - 1L
                for (id in ids) {
                    storage.updatePinned(id, pinned = true, pinnedAt = stamp)
                    stamp -= 1L
                }
            } else {
                ids.forEach { storage.updatePinned(it, pinned = false, pinnedAt = NOT_PINNED_AT) }
            }
            // 一次锁、一次重排：置顶会改变排序（置顶项单独成区），但不必每条都重读全量元数据。
            reloadMetadata()
        }
    }

    override suspend fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        metadataLock.withLock {
            storage.delete(ids)
            reloadMetadata()
        }
        reclaimStorageIfNeeded()
    }

    override suspend fun clear(all: Boolean) {
        metadataLock.withLock {
            if (all) storage.deleteAll() else storage.deleteAllUnpinned()
            reloadMetadata()
        }
        reclaimStorageIfNeeded()
    }

    /**
     * 把超出上限的未置顶条目丢掉。
     *
     * 口径是「按最后一次复制保留最近 N 条」，与排序方式无关——用户设上限的意图是「只留最近的」，
     * 而不是「只留当前排序下的前 N」。
     */
    private suspend fun trimOverflow() {
        val maxCount = _settings.value.historyMaxCount
        // 非正数视为「不裁剪」：设置页只允许正整数，这里防的是外部写入的异常值。
        if (maxCount <= 0) return
        val overflow = storage.overflowIds(maxCount)
        if (overflow.isEmpty()) return
        storage.delete(overflow)
    }

    // ---------------------------------------------------------------------------------
    // 偏好
    // ---------------------------------------------------------------------------------

    override fun setSettings(settings: AppSettings) {
        if (settings == _settings.value) return
        val previous = _settings.value
        _settings.value = settings

        if (previous.clipboardCheckIntervalMillis != settings.clipboardCheckIntervalMillis ||
            previous.launchAtLogin != settings.launchAtLogin
        ) {
            applyPlatformSettings(settings)
        }
        persistSettings()

        // 这四项决定窗口的内容与顺序，必须重读；其余偏好（主题、快捷键、预览宽度……）
        // 与历史无关，重启一次数据库查询是浪费。
        if (previous.sortBy != settings.sortBy ||
            previous.sortOrder != settings.sortOrder ||
            previous.historyMaxCount != settings.historyMaxCount
        ) {
            scope.launch {
                metadataLock.withLock {
                    if (settings.historyMaxCount > 0) trimOverflow()
                    reloadMetadata()
                }
            }
        }
    }

    override fun setStatusMessage(message: String?) {
        _statusMessage.value = message
    }

    // ---------------------------------------------------------------------------------
    // 存储维护
    // ---------------------------------------------------------------------------------

    override fun reclaimStorageIfNeeded() {
        runMaintenance { storage.reclaimFreePages(MIN_RECLAIM_BYTES) }
    }

    /**
     * 存储维护的唯一入口：同一时刻只跑一个，完成后递增 [storageRevision]。
     *
     * 「存储文件大小」每次现读，界面靠这个信号重读一次，否则回收之后设置页里的数字
     * 会停在旧值上。维护只在数据集上跑，因此不需要先 flush——条目是即时落盘的。
     */
    private fun runMaintenance(block: suspend () -> Unit) {
        if (maintenanceJob?.isActive == true) return
        maintenanceJob = scope.launch {
            try {
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // 维护失败（磁盘满、库已被关闭……）不该影响任何业务状态，等下一个时机再试。
            }
            _storageRevision.value += 1
        }
    }

    // ---------------------------------------------------------------------------------
    // 平台
    // ---------------------------------------------------------------------------------

    override fun writeClipboard(snapshot: ClipboardSnapshot): Boolean = clipboard.write(snapshot)

    override fun paste(): Boolean = clipboard.paste()

    override fun pressReturn(): Boolean = clipboard.pressReturn()

    override fun applicationIcon(bundleId: String?): String? =
        runCatching { native.applicationIcon(bundleId) }.getOrNull()

    override suspend fun recognizeText(image: ClipImage): String? =
        try {
            native.recognizeText(image)
        } catch (cancellation: CancellationException) {
            // 绝不吞掉取消异常，否则会破坏外层的结构化并发。
            throw cancellation
        } catch (_: Exception) {
            null
        }

    override fun currentSourceApplication(): SourceApplication? {
        if (!native.supportsApplicationInfo) return null
        return runCatching { native.frontmostApplication() }.getOrNull()
    }

    // ---------------------------------------------------------------------------------
    // 内部
    // ---------------------------------------------------------------------------------

    /** 加载已持久化的偏好与窗口。运行在 [scope] 上，绝不在调用方线程上执行。 */
    private suspend fun load() {
        val current = storage.loadSettings()
        _settings.value = current
        // 偏好先行：热键注册、界面主题这些只依赖偏好的事情不该等历史加载完。
        // [loaded] 仍然等窗口就绪才置位，保证 [flush] 不会用默认偏好覆盖已存数据。
        _settingsLoaded.value = true
        // PRAGMA 与孤儿清理只做一次。
        storage.initialise()
        metadataLock.withLock { reloadMetadata() }
        loaded = true
        // 冷启动补一次回收：覆盖「上次会话删了但没回收」留下的空洞。
        reclaimStorageIfNeeded()
        // 补历史遗留的空标题。它要按 id 读载荷，因此另起一次任务，不拖慢首屏。
        scope.launch {
            try {
                backfillEmptyTitles()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // 回填失败（库已关闭、磁盘问题……）不影响任何业务状态，下次启动再试。
            }
        }
    }

    /** 下发位于平台侧的设置（轮询间隔、开机自启项）。 */
    private fun applyPlatformSettings(value: AppSettings) {
        clipboard.pollIntervalMillis = value.clipboardCheckIntervalMillis.toLong().coerceAtLeast(50L)
        if (native.supportsLaunchAtLogin) {
            runCatching { native.setLaunchAtLogin(value.launchAtLogin) }
        }
    }

    private fun persistSettings() {
        settingsPersistJob?.cancel()
        settingsPersistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MILLIS)
            storage.saveSettings(_settings.value)
        }
    }

    private companion object {
        const val PERSIST_DEBOUNCE_MILLIS = 300L

        /**
         * 空闲页至少要这么多才值得去回收（`incremental_vacuum` 的代价与回收页数成正比）。
         *
         * 语义是「删够 1 MB 才回收 1 MB」。
         */
        const val MIN_RECLAIM_BYTES = 1L * 1024L * 1024L
    }
}
