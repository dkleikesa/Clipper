package com.qcmian.clipper.data.repository

import com.qcmian.clipper.data.source.ClipStorageDataSource
import com.qcmian.clipper.data.source.ClipboardDataSource
import com.qcmian.clipper.data.source.NativeDataSource
import com.qcmian.clipper.domain.model.AppSettings
import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.domain.model.ClipboardSnapshot
import com.qcmian.clipper.domain.model.SourceApplication
import com.qcmian.clipper.domain.model.removingUnsafeTitleScalars
import com.qcmian.clipper.domain.repository.ClipboardPlatform
import com.qcmian.clipper.domain.repository.ClipboardRepository
import com.qcmian.clipper.domain.sort.ClipSorter
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
 * Default [ClipboardRepository]: keeps the history in memory as a [StateFlow], mirrors it to
 * the platform storage with a debounce and forwards every clipboard change to [snapshots].
 *
 * All business rules (what to record, how to merge duplicates, what an activation does) live
 * in the domain layer; this class only moves data around and enforces the two invariants it
 * owns: the history is always sorted and never exceeds the configured size.
 */
class DefaultClipboardRepository(
    private val clipboard: ClipboardDataSource,
    private val storage: ClipStorageDataSource,
    private val native: NativeDataSource,
    private val scope: CoroutineScope,
) : ClipboardRepository, ClipboardPlatform {

    private val _items = MutableStateFlow<List<ClipItem>>(emptyList())
    override val items: StateFlow<List<ClipItem>> = _items.asStateFlow()

    private val _settings = MutableStateFlow(storage.loadSettings())
    override val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    override val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    private val _snapshots = MutableSharedFlow<ClipboardSnapshot>(extraBufferCapacity = 32)
    override val snapshots: Flow<ClipboardSnapshot> = _snapshots.asSharedFlow()

    private var started = false
    private var itemsPersistJob: Job? = null
    private var settingsPersistJob: Job? = null

    override val storageSize: String? get() = storage.storageSize()
    override val screenCount: Int get() = native.screenCount
    override val supportsLaunchAtLogin: Boolean get() = native.supportsLaunchAtLogin
    override val supportsApplicationInfo: Boolean get() = native.supportsApplicationInfo
    override val supportsTextRecognition: Boolean get() = native.supportsTextRecognition

    init {
        // Port of `Storage.sanitizeTitles()`: heal titles persisted before the unsafe
        // scalars were filtered out, otherwise the layout can hang on macOS 26.
        val restored = storage.loadItems()
            .map { it.copy(title = it.title.removingUnsafeTitleScalars()) }
        _items.value = normalise(restored, _settings.value)
    }

    override fun start() {
        if (started) return
        started = true
        applyPlatformSettings(_settings.value)
        clipboard.start { snapshot -> _snapshots.tryEmit(snapshot) }
    }

    override fun stop() {
        if (!started) return
        started = false
        clipboard.stop()
    }

    override fun flush() {
        itemsPersistJob?.cancel()
        settingsPersistJob?.cancel()
        storage.saveItems(_items.value)
        storage.saveSettings(_settings.value)
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

    override fun clearSystemClipboard() {
        clipboard.clear()
    }

    override fun paste(): Boolean = clipboard.paste()

    override fun applicationIcon(bundleId: String?): String? =
        runCatching { native.applicationIcon(bundleId) }.getOrNull()

    override fun applicationName(bundleId: String): String? =
        runCatching { native.applicationName(bundleId) }.getOrNull()

    override fun pickApplication(): SourceApplication? =
        runCatching { native.pickApplication() }.getOrNull()

    override fun openUrl(url: String): Boolean =
        runCatching { native.openUrl(url) }.getOrDefault(false)

    override suspend fun recognizeText(imageBase64: String): String? =
        runCatching { native.recognizeText(imageBase64) }.getOrNull()

    /** The application the last copy came from, `null` when the platform cannot tell. */
    override fun currentSourceApplication(): SourceApplication? {
        if (!native.supportsApplicationInfo) return null
        return runCatching { native.frontmostApplication() }.getOrNull()
    }

    // ---------------------------------------------------------------------------------
    // Invariants
    // ---------------------------------------------------------------------------------

    /** Sorts the history by the current preferences and trims it to the configured size. */
    private fun normalise(items: List<ClipItem>, settings: AppSettings): List<ClipItem> {
        val sorted = ClipSorter.sort(items, settings.sortBy, settings.pinTo)
        val maxSize = settings.historySize
        if (maxSize <= 0) return sorted

        val unpinned = sorted.filter { it.isUnpinned }
        if (unpinned.size <= maxSize) return sorted
        val overflow = unpinned.drop(maxSize).map { it.id }.toSet()
        return sorted.filterNot { it.id in overflow }
    }

    /** Pushes the settings that live on the platform side (poll interval, login item). */
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
