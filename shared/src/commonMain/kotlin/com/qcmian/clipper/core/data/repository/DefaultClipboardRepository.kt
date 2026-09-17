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
import kotlinx.coroutines.launch

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

    private val _snapshots = MutableSharedFlow<ClipboardSnapshot>(extraBufferCapacity = 32)
    override val snapshots: Flow<ClipboardSnapshot> = _snapshots.asSharedFlow()

    private var started = false

    /** [load] 完成后为 `true`；防止 [flush] 覆盖掉已存储的状态。 */
    @Volatile
    private var loaded = false

    private var startJob: Job? = null
    private var itemsPersistJob: Job? = null
    private var settingsPersistJob: Job? = null

    override val storageSize: String? get() = storage.storageSize()
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

    override fun writeClipboard(snapshot: ClipboardSnapshot): Boolean = clipboard.write(snapshot)

    override fun paste(): Boolean = clipboard.paste()

    override fun applicationIcon(bundleId: String?): String? =
        runCatching { native.applicationIcon(bundleId) }.getOrNull()

    override fun applicationName(bundleId: String): String? =
        runCatching { native.applicationName(bundleId) }.getOrNull()

    override fun pickApplication(): SourceApplication? =
        runCatching { native.pickApplication() }.getOrNull()

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
     * 按当前偏好排序历史，并把未置顶条目的总体积裁剪到配置的上限。
     *
     * 从排序结果的开头累计体积（即保留最靠前的条目），一旦超出上限，后续条目全部丢弃；
     * 置顶项不占额度也不会被丢弃。始终至少保留一条未置顶记录，避免刚复制的大内容被立刻清掉。
     */
    private fun normalise(items: List<ClipItem>, settings: AppSettings): List<ClipItem> {
        val sorted = ClipSorter.sort(items, settings.sortBy, settings.pinTo)
        val maxBytes = settings.historyMaxSizeBytes
        if (maxBytes <= 0L) return sorted

        var used = 0L
        var kept = 0
        val overflow = mutableSetOf<String>()
        for (item in sorted) {
            if (item.isPinned) continue
            val size = item.approximateSizeBytes
            if (kept > 0 && used + size > maxBytes) {
                overflow += item.id
            } else {
                used += size
                kept++
            }
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
    }
}
