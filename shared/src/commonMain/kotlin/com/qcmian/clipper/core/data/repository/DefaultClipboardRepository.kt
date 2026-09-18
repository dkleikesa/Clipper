package com.qcmian.clipper.core.data.repository

import com.qcmian.clipper.core.data.source.ClipStorageDataSource
import com.qcmian.clipper.core.data.source.ClipboardDataSource
import com.qcmian.clipper.core.data.source.NativeDataSource
import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.model.SourceApplication
import com.qcmian.clipper.core.domain.model.removingUnsafeTitleScalars
import com.qcmian.clipper.core.domain.repository.ClipboardPlatform
import com.qcmian.clipper.core.domain.repository.ClipboardRepository
import com.qcmian.clipper.core.domain.sort.ClipSorter
import com.qcmian.clipper.core.settings.AppSettings
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [ClipboardRepository] 的默认实现：以 [StateFlow] 在内存中持有历史，带防抖地镜像到平台存储，
 * 并把每一次剪贴板变化转发到 [snapshots]。
 *
 * 所有业务规则（记录什么、如何合并重复项、激活时做什么）都在领域层；本类只负责搬运数据，
 * 并维护它自己负责的两条不变量：历史始终有序，且永不超过配置的上限。
 */
class DefaultClipboardRepository(
    private val clipboard: ClipboardDataSource,
    private val storage: ClipStorageDataSource,
    private val native: NativeDataSource,
    private val scope: CoroutineScope,
) : ClipboardRepository, ClipboardPlatform {

    private val _items = MutableStateFlow<List<ClipItem>>(emptyList())
    override val items: StateFlow<List<ClipItem>> = _items.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _settingsLoaded = MutableStateFlow(false)

    /**
     * 偏好已从存储读出后为 `true`：此前的 [settings] 是默认值，还不是用户真正的偏好。
     *
     * 刻意不等历史加载完——历史里带图片（BLOB 反序列化），首次打开数据库还要几百毫秒，
     * 而依赖它的只有「按真实偏好注册全局热键」这一类急事。
     */
    override val settingsLoaded: StateFlow<Boolean> = _settingsLoaded.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    override val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _storageRevision = MutableStateFlow(0)
    override val storageRevision: StateFlow<Int> = _storageRevision.asStateFlow()

    private val _snapshots = MutableSharedFlow<ClipboardSnapshot>(extraBufferCapacity = 32)
    override val snapshots: Flow<ClipboardSnapshot> = _snapshots.asSharedFlow()

    private var started = false

    /** [load] 完成后为 `true`；防止 [flush] 覆盖掉已存储的状态。 */
    @Volatile
    private var loaded = false

    private var startJob: Job? = null
    private var itemsPersistJob: Job? = null
    private var settingsPersistJob: Job? = null

    /** 正在跑的存储维护（回收 / 压紧）；同一时刻只允许一个。 */
    private var maintenanceJob: Job? = null

    /** 累计「丢掉的内容」的近似字节，只用来决定何时去查一次空闲页（见 [reclaimStorageIfNeeded]）。 */
    private var droppedBytes = 0L

    override val storageBytes: Long? get() = storage.storageBytes()
    override val screenCount: Int get() = native.screenCount
    override val supportsLaunchAtLogin: Boolean get() = native.supportsLaunchAtLogin
    override val supportsApplicationInfo: Boolean get() = native.supportsApplicationInfo
    override val supportsTextRecognition: Boolean get() = native.supportsTextRecognition

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
        itemsPersistJob?.cancel()
        settingsPersistJob?.cancel()
        // 还没有加载任何数据：此时持久化会用空的内存状态覆盖已存历史。
        if (!loaded) return
        // 存储是挂起的（Room），因此最后一次写入改为在 [scope] 上派发，而不阻塞调用方。
        // 不能容忍丢失的宿主请改用 [flushNow]。
        val items = _items.value
        val settings = _settings.value
        scope.launch {
            storage.saveItems(items)
            storage.saveSettings(settings)
        }
    }

    override suspend fun flushNow() {
        itemsPersistJob?.cancel()
        settingsPersistJob?.cancel()
        if (!loaded) return
        storage.saveItems(_items.value)
        storage.saveSettings(_settings.value)
    }

    override suspend fun close() {
        flushNow()
        // 退出前压紧一次。放到后台跑并只等 [QUIT_COMPACT_TIMEOUT_MILLIS]：库大时用户按了退出
        // 不该等一次整库重写；等不到就放弃，下次启动的补检（见 [load]）会兜住那次空洞。
        val compact = scope.launch {
            try {
                storage.compact()
            } catch (cancellation: CancellationException) {
                // 被上面的超时取消：以取消状态结束，退出流程不再等它。
                throw cancellation
            } catch (_: Exception) {
                // 压紧失败（磁盘满、库已被关闭）不该拖住退出。
            }
        }
        if (withTimeoutOrNull(QUIT_COMPACT_TIMEOUT_MILLIS) { compact.join() } == null) {
            compact.cancel()
        }
        storage.close()
    }

    /** 加载已持久化的历史与偏好。运行在 [scope] 上，绝不在调用方线程上执行。 */
    private suspend fun load() {
        val settings = storage.loadSettings()
        _settings.value = settings
        // 偏好先行：热键注册、界面主题这些只依赖偏好的事情不该等历史加载完——
        // Room 首次打开数据库（含 WAL 恢复）实测要几百毫秒。
        // [loaded] 仍然等历史就绪才置位，保证 [flush] 不会用空历史覆盖已存数据。
        _settingsLoaded.value = true
        // 对应 `Storage.sanitizeTitles()`，外加对旧版图片标题的还原，见 [sanitisedTitle]。
        val restored = storage.loadItems().map { it.withSanitisedTitle() }
        _items.value = normalise(restored, settings)
        loaded = true
        // 冷启动补一次回收：覆盖「上次会话删了但没回收」「退出时压紧超时被放弃」留下的空洞。
        reclaimStorageIfNeeded()
    }

    /**
     * 修正单条已持久化历史的标题。
     *
     * 做两件事：
     * - 过滤会让 CoreText 在 macOS 26 上卡死的不安全标量（对应 `Storage.sanitizeTitles()`）；
     * - 还原旧版本图片标题里的 `⏎` / `⇥`。早期实现把识别结果的换行、制表符替换成这两个符号
     *   之后才存进 `title`，而 `title` 正是「复制图片文字」复制出去的内容，于是复制出来的
     *   就成了符号。纯图片条目的标题只可能来自识别，因此这两个符号必然是当时格式化留下的。
     */
    private fun ClipItem.withSanitisedTitle(): ClipItem {
        val cleaned = title.removingUnsafeTitleScalars()
        val plainImage = image != null && text.isNullOrBlank() && files.isEmpty()
        val restored = if (plainImage) {
            cleaned.replace('\u23ce', '\n').replace('\u21e5', '\t')
        } else {
            cleaned
        }
        return if (restored == title) this else copy(title = restored)
    }

    override fun setItems(items: List<ClipItem>) {
        val normalised = normalise(items, _settings.value)
        if (normalised == _items.value) return

        // 记账只决定「什么时候去查一次空闲页」，不参与回收多少的判断：单条删除与裁剪丢弃都会
        // 走到这里，热路径上只多一次整数加法（真正的判据是文件里的空闲页数）。
        if (normalised.size < _items.value.size) {
            val keptIds = normalised.mapTo(HashSet(normalised.size * 2)) { it.id }
            droppedBytes += _items.value
                .filterNot { it.id in keptIds }
                .sumOf { it.approximateSizeBytes }
            if (droppedBytes >= MIN_RECLAIM_BYTES) {
                droppedBytes = 0
                reclaimStorageIfNeeded()
            }
        }

        _items.value = normalised
        persistItems()
    }

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
    }

    override fun setStatusMessage(message: String?) {
        _statusMessage.value = message
    }

    // ---------------------------------------------------------------------------------
    // 存储维护（空闲页回收 / 压紧）
    // ---------------------------------------------------------------------------------

    override fun compactStorage() {
        runMaintenance { storage.compact() }
    }

    override fun reclaimStorageIfNeeded() {
        runMaintenance { storage.reclaimFreePages(MIN_RECLAIM_BYTES) }
    }

    /**
     * 存储维护的唯一入口：先落盘再动手，且同一时刻只跑一个。
     *
     * 先 [flushNow] 是必须的：刚删掉的内容可能还在防抖队列里，此时压紧会被随后的写入又撑大，
     * 白压一次。维护期间到来的重复请求直接丢弃——压缩结果与调用次数无关。
     *
     * 完成后递增 [storageRevision]：「存储文件大小」每次现读，界面靠这个信号重读一次。
     */
    private fun runMaintenance(block: suspend () -> Unit) {
        if (maintenanceJob?.isActive == true) return
        maintenanceJob = scope.launch {
            try {
                flushNow()
                block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // 维护失败（磁盘满、库已被关闭……）不该影响任何业务状态，等下一个时机再试。
            }
            _storageRevision.update { it + 1 }
        }
    }

    override fun writeClipboard(snapshot: ClipboardSnapshot): Boolean = clipboard.write(snapshot)

    override fun paste(): Boolean = clipboard.paste()

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

    /** 上一次复制来源的应用；平台无法判断时为 `null`。 */
    override fun currentSourceApplication(): SourceApplication? {
        if (!native.supportsApplicationInfo) return null
        return runCatching { native.frontmostApplication() }.getOrNull()
    }

    // ---------------------------------------------------------------------------------
    // 不变量
    // ---------------------------------------------------------------------------------

    /**
     * 按当前偏好排序历史，并把未置顶条目裁剪到配置的条数上限。
     *
     * 从排序结果的开头数条数（即保留最靠前的条目），超出的全部丢弃；置顶项不占额度也不会被
     * 丢弃。[AppSettings.historyMaxCount] 至少为 1，因此刚复制的内容不会被立刻清掉。
     */
    private fun normalise(items: List<ClipItem>, settings: AppSettings): List<ClipItem> {
        val sorted = ClipSorter.sort(items, settings.sortBy, settings.pinTo)
        val maxCount = settings.historyMaxCount
        // 非正数视为「不裁剪」：设置页只允许正整数，这里防的是外部写入的异常值。
        if (maxCount <= 0) return sorted

        var kept = 0
        val overflow = mutableSetOf<String>()
        for (item in sorted) {
            if (item.isPinned) continue
            kept++
            if (kept > maxCount) overflow += item.id
        }
        if (overflow.isEmpty()) return sorted
        return sorted.filterNot { it.id in overflow }
    }

    /** 下发位于平台侧的设置（轮询间隔、开机自启项）。 */
    private fun applyPlatformSettings(value: AppSettings) {
        clipboard.pollIntervalMillis = value.clipboardCheckIntervalMillis.toLong().coerceAtLeast(50L)
        if (native.supportsLaunchAtLogin) {
            runCatching { native.setLaunchAtLogin(value.launchAtLogin) }
        }
    }

    private fun persistItems() {
        itemsPersistJob?.cancel()
        itemsPersistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MILLIS)
            storage.saveItems(_items.value)
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
         * 累计丢掉这么多内容，才值得去查一次文件里的空闲页并回收。
         *
         * 这个阈值同时用于两处：这里是触发查询的记账阈值，数据层那边是「空闲页少于它就
         * 不值得为它搬一次页」的回收阈值——两者一致，语义就是「删够 1 MB 才回收 1 MB」。
         */
        const val MIN_RECLAIM_BYTES = 1L * 1024L * 1024L

        /** 退出时最多等压紧多久；等不到就放弃，交给下次启动的补检。 */
        const val QUIT_COMPACT_TIMEOUT_MILLIS = 1_500L
    }
}
