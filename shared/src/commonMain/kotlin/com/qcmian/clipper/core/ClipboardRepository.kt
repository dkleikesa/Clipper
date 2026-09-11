package com.qcmian.clipper.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qcmian.clipper.model.ClipItem
import com.qcmian.clipper.model.SourceApplication
import com.qcmian.clipper.model.removingUnsafeTitleScalars
import com.qcmian.clipper.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the clipboard history. This is the Compose Multiplatform counterpart of Maccy's
 * `History` class: it captures new copies, de-duplicates them, keeps the pinned items and
 * persists everything through [ClipStorage].
 */
class ClipboardRepository(
    private val platform: ClipboardPlatform,
    private val storage: ClipStorage,
    private val scope: CoroutineScope,
    private val native: NativeIntegration = UnsupportedNativeIntegration,
) {
    private val _items = mutableStateListOf<ClipItem>()

    /** All history items, already sorted (pinned first/last, then by the sort preference). */
    val items: List<ClipItem> get() = _items

    /** What the user has typed. Updated on every keystroke. */
    var queryInput by mutableStateOf("")
        private set

    /**
     * The query actually applied to the history. Maccy throttles the search by 0.2s through
     * `Throttler`, so this trails [queryInput] by at most `searchThrottleMillis`.
     */
    var searchQuery by mutableStateOf("")
        private set

    var settings by mutableStateOf(storage.loadSettings())
        private set

    /** Non-null while a transient message (e.g. "pasting is not supported") is shown. */
    var statusMessage by mutableStateOf<String?>(null)

    /**
     * Invoked when the panel has to step aside so the paste keystroke reaches the
     * application that was focused before.
     */
    var onPasteRequest: ((ClipAction) -> Unit)? = null

    /**
     * Invoked after an item has been written to the clipboard. Maccy's `History.select`
     * closes the popup for every action, not only when pasting.
     */
    var onSelectRequest: (() -> Unit)? = null

    private var started = false
    private var itemsPersistJob: Job? = null
    private var settingsPersistJob: Job? = null
    private var searchJob: Job? = null
    private var lastSearchAt = 0L

    init {
        val restored = ClipSorter.sort(storage.loadItems(), settings.sortBy, settings.pinTo)
            // Port of `Storage.sanitizeTitles()`: heal titles persisted before the unsafe
            // scalars were filtered out, otherwise the layout can hang on macOS 26.
            .map { it.copy(title = it.title.removingUnsafeTitleScalars()) }
        _items.addAll(restored)
        limitHistorySize(settings.historySize)
    }

    /** The current query applied to the history. */
    val searchResults: List<SearchResult>
        get() = ClipSearch.search(searchQuery, _items, settings.searchMode)

    val visibleItems: List<ClipItem> get() = searchResults.map { it.item }

    val unpinnedCount: Int get() = _items.count { it.isUnpinned }

    /** Approximate size of the persisted history, `null` when the platform cannot tell. */
    val storageSize: String? get() = storage.storageSize()

    /** How many screens the "popup screen" preference can point at. */
    val screenCount: Int get() = native.screenCount

    /** Whether the host can register the app as a login item. */
    val supportsLaunchAtLogin: Boolean get() = native.supportsLaunchAtLogin

    /**
     * Whether the host can tell which application a copy came from. Port of Maccy's reliance
     * on `NSWorkspace.frontmostApplication`: without it the ignore-application list is moot.
     */
    val supportsApplicationInfo: Boolean get() = native.supportsApplicationInfo

    fun start() {
        if (started) return
        started = true
        applyPlatformSettings(settings)
        platform.start { snapshot -> capture(snapshot) }
    }

    fun stop() {
        if (!started) return
        started = false
        platform.stop()
    }

    // ---------------------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------------------

    /** Port of `History.searchQuery.didSet` + `Throttler(minimumDelay: 0.2)`. */
    fun updateSearchQuery(value: String) {
        queryInput = value

        val now = currentTimeMillis()
        val elapsed = now - lastSearchAt
        searchJob?.cancel()

        if (value.isEmpty() || elapsed >= settings.searchThrottleMillis) {
            lastSearchAt = now
            searchQuery = value
        } else {
            searchJob = scope.launch {
                delay(settings.searchThrottleMillis - elapsed)
                lastSearchAt = currentTimeMillis()
                searchQuery = value
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        queryInput = ""
        searchQuery = ""
    }

    /**
     * Port of `Clipboard.copyInMaccy(history.searchQuery)`: when nothing is selected, pressing
     * Return copies the typed query itself onto the clipboard.
     */
    fun copySearchQuery(): Boolean {
        val query = queryInput
        if (query.isEmpty()) return false
        val written = platform.write(ClipboardSnapshot(text = query))
        if (written) clearSearch()
        return written
    }

    /**
     * Port of `ToolbarView`'s `text.viewfinder` action: puts the text recognised inside an
     * image (the item title) back onto the clipboard.
     */
    fun copyExtractedText(item: ClipItem): Boolean {
        val text = item.title.trim()
        if (item.imageBase64 == null || text.isEmpty()) return false
        val written = platform.write(ClipboardSnapshot(text = text))
        if (written) clearSearch()
        return written
    }

    /** Port of `KeyChord.deleteOneCharFromSearch` (⌃H). */
    fun deleteOneCharFromSearch() {
        if (queryInput.isNotEmpty()) updateSearchQuery(queryInput.dropLast(1))
    }

    /** Port of `KeyChord.deleteLastWordFromSearch` (⌃W). */
    fun deleteLastWordFromSearch() {
        val words = queryInput.split(" ").filter { it.isNotEmpty() }.dropLast(1)
        updateSearchQuery(if (words.isEmpty()) "" else words.joinToString(" ") + " ")
    }

    // ---------------------------------------------------------------------------------
    // Capturing
    // ---------------------------------------------------------------------------------

    private fun capture(snapshot: ClipboardSnapshot) {
        if (snapshot.isEmpty) return

        if (settings.ignoreEvents) {
            if (settings.ignoreOnlyNextEvent) {
                updateSettings { it.copy(ignoreEvents = false, ignoreOnlyNextEvent = false) }
            }
            return
        }

        // Port of `Clipboard.shouldIgnore(_ types:)`: transient and user listed pasteboard
        // types never reach the history.
        val ignoredTypes = settings.ignoredPasteboardTypes + AppSettings.TRANSIENT_PASTEBOARD_TYPES
        if (snapshot.types.any { it in ignoredTypes }) return

        // Maccy filters the pasteboard through `enabledPasteboardTypes`, so disabled content
        // kinds never reach the history at all.
        val text = snapshot.text.takeIf { settings.saveText }
        val image = snapshot.imageBase64.takeIf { settings.saveImages }
        val files = snapshot.files.takeIf { settings.saveFiles }.orEmpty()
        if (text.isNullOrBlank() && image == null && files.isEmpty()) return

        if (!text.isNullOrBlank() && matchesIgnoredPattern(text)) return

        // Port of `Clipboard.shouldIgnore(_ sourceAppBundle:)`.
        val sourceApplication = resolveSourceApplication()
        if (sourceApplication != null && isIgnoredApplication(sourceApplication)) return

        // Wall clock resolution is a millisecond, which is not enough to keep the order of
        // copies made in quick succession (Maccy relies on sub-millisecond `Date`). Bumping
        // past the newest item guarantees a strict ordering.
        val now = maxOf(currentTimeMillis(), (_items.maxOfOrNull { it.lastCopiedAt } ?: 0L) + 1L)
        var item = ClipItem(
            id = randomId(),
            text = text,
            imageBase64 = image,
            files = files,
            firstCopiedAt = now,
            lastCopiedAt = now,
            numberOfCopies = 1,
        )
        item = item.copy(
            title = item.generateTitle(settings.showSpecialSymbols),
            application = sourceApplication,
        )

        val existing = _items.firstOrNull { it.id != item.id && it.supersedes(item) }
        if (existing != null) {
            // Keep the identity of the original entry and only bump the counters.
            item = item.copy(
                firstCopiedAt = existing.firstCopiedAt,
                numberOfCopies = existing.numberOfCopies + 1,
                pin = existing.pin,
                title = existing.title.ifBlank { item.title },
                application = existing.application ?: item.application,
            )
            _items.remove(existing)
        }

        _items.add(item)
        resort()
        limitHistorySize(settings.historySize)
        persist()

        // Port of `HistoryItem.generateTitle()`: images get their title from text recognition.
        if (image != null && settings.recognizeText && native.supportsTextRecognition) {
            recognizeImageText(item.id, image)
        }
    }

    private fun matchesIgnoredPattern(text: String): Boolean =
        settings.ignoredRegexp.any { pattern ->
            runCatching { Regex(pattern).containsMatchIn(text) }.getOrDefault(false)
        }

    private fun resolveSourceApplication(): SourceApplication? {
        if (!native.supportsApplicationInfo) return null
        return runCatching { native.frontmostApplication() }.getOrNull()
    }

    private fun isIgnoredApplication(application: SourceApplication): Boolean {
        val keys = setOfNotNull(application.bundleId, application.name)
        val listed = settings.ignoredApps.any { it in keys }
        return if (settings.ignoreAllAppsExceptListed) !listed else listed
    }

    /** Runs Vision / ML Kit in the background and promotes the result to the item title. */
    private fun recognizeImageText(itemId: String, imageBase64: String) {
        scope.launch {
            val recognized = runCatching { native.recognizeText(imageBase64) }.getOrNull() ?: return@launch
            val index = _items.indexOfFirst { it.id == itemId }
            if (index < 0) return@launch

            val title = recognized
                .replace("\n", "\u23ce")
                .take(ClipItem.MAX_TITLE_LENGTH)
            if (title.isBlank()) return@launch

            _items[index] = _items[index].copy(title = title)
            persist()
        }
    }

    /** Base64 PNG of the icon of the application an item came from. */
    fun applicationIcon(bundleId: String?): String? =
        runCatching { native.applicationIcon(bundleId) }.getOrNull()

    /** `NSWorkspace.applicationName(at:)`: turns a bundle id into a display name. */
    fun applicationName(bundleId: String): String? =
        runCatching { native.applicationName(bundleId) }.getOrNull()

    /** Port of the `.fileImporter` next to Maccy's ignore list. `null` when unsupported. */
    fun pickApplication(): SourceApplication? =
        runCatching { native.pickApplication() }.getOrNull()

    /** Opens a link with the default handler, used by the About dialog. */
    fun openUrl(url: String): Boolean =
        runCatching { native.openUrl(url) }.getOrDefault(false)

    // ---------------------------------------------------------------------------------
    // Item actions
    // ---------------------------------------------------------------------------------

    /** Writes the item back to the system clipboard and optionally triggers a paste. */
    fun select(item: ClipItem, action: ClipAction = ClipAction.DEFAULT) {
        // Port of `History.select`: an unsupported modifier combination does nothing.
        if (action == ClipAction.UNKNOWN) return

        val removeFormatting = action.removesFormatting(settings)
        if (!platform.write(snapshotFor(item, removeFormatting))) {
            statusMessage = "This platform can't copy that kind of content"
            return
        }
        clearSearch()

        // Port of `History.select`: every branch closes the popup, copying included.
        onSelectRequest?.invoke()

        if (action.pastes(settings)) {
            requestPaste(action)
        }
    }

    private fun requestPaste(action: ClipAction) {
        val handler = onPasteRequest
        if (handler == null) {
            statusMessage = "Pasting is not supported on this platform"
            return
        }
        handler(action)
        scope.launch {
            delay(PASTE_DELAY_MILLIS)
            if (!platform.paste()) {
                statusMessage = "Pasting is not supported on this platform"
            }
        }
    }

    private fun snapshotFor(item: ClipItem, removeFormatting: Boolean): ClipboardSnapshot {
        if (!removeFormatting) {
            return ClipboardSnapshot(
                text = item.text ?: if (item.imageBase64 == null && item.files.isEmpty()) item.previewableText else null,
                imageBase64 = item.imageBase64,
                files = item.files,
            )
        }

        // Port of `Clipboard.clearFormatting(_:)`: keep the plain string *and* the file URLs
        // so "paste without formatting" still pastes files. When the item carries no string
        // representation, behave exactly like a normal copy.
        if (item.text == null) {
            return ClipboardSnapshot(
                text = if (item.imageBase64 == null && item.files.isEmpty()) item.previewableText else null,
                imageBase64 = item.imageBase64,
                files = item.files,
            )
        }
        return ClipboardSnapshot(text = item.text, files = item.files)
    }

    fun togglePin(item: ClipItem) {
        val index = _items.indexOfFirst { it.id == item.id }
        if (index < 0) return

        val updated = item.copy(pin = if (item.isPinned) null else randomAvailablePin())
        _items[index] = updated
        resort()
        // Port of `History.togglePin`: pinning always leaves the search behind.
        clearSearch()
        persist()
    }

    /** Port of `PinsSettingsPane`: lets the user re-assign the key of a pinned item. */
    fun updatePin(item: ClipItem, pin: String?) {
        val index = _items.indexOfFirst { it.id == item.id }
        if (index < 0) return
        _items[index] = item.copy(pin = pin)
        resort()
        persist()
    }

    /** Port of `PinsSettingsPane`'s alias column, which edits `HistoryItem.title`. */
    fun updateTitle(item: ClipItem, title: String) {
        val index = _items.indexOfFirst { it.id == item.id }
        if (index < 0) return
        _items[index] = item.copy(title = title)
        persist()
    }

    /** Port of `PinValueView.updateItemContent()`: replaces the plain text representation. */
    fun updateContent(item: ClipItem, text: String) {
        val index = _items.indexOfFirst { it.id == item.id }
        if (index < 0) return
        val hasPlainText = item.text != null && item.imageBase64 == null && item.files.isEmpty()
        if (!hasPlainText) return
        _items[index] = item.copy(text = text)
        persist()
    }

    fun availablePins(excluding: ClipItem? = null): List<String> {
        val assigned = _items.mapNotNull { if (it.id == excluding?.id) null else it.pin }.toSet()
        return supportedPins().filter { it !in assigned }
    }

    /**
     * Port of `HistoryItem.supportedPins`: the characters reserved by the recordable
     * `delete` / `pin` / `togglePreview` shortcuts are removed from the pool, so a custom
     * shortcut can never collide with the generated pin of a pinned item.
     */
    private fun supportedPins(): List<String> {
        val reserved = setOf(
            settings.deleteShortcut.character,
            settings.pinShortcut.character,
            settings.togglePreviewShortcut.character,
        ).mapTo(mutableSetOf()) { it.lowercase() }
        return PIN_CHARACTERS.map { it.toString() }.filterNot { it.lowercase() in reserved }
    }

    fun delete(item: ClipItem) {
        if (_items.removeAll { it.id == item.id }) {
            persist()
        }
    }

    /** Removes every unpinned item, exactly like Maccy's "Clear". */
    fun clear() {
        if (_items.removeAll { it.isUnpinned }) {
            persist()
        }
        if (settings.clearSystemClipboard) platform.clear()
    }

    /** Removes every item, including the pinned ones. */
    fun clearAll() {
        if (_items.isNotEmpty()) {
            _items.clear()
            persist()
        }
        if (settings.clearSystemClipboard) platform.clear()
    }

    // ---------------------------------------------------------------------------------
    // Settings
    // ---------------------------------------------------------------------------------

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val updated = transform(settings)
        if (updated == settings) return

        val previous = settings
        settings = updated

        if (previous.clipboardCheckIntervalMillis != updated.clipboardCheckIntervalMillis ||
            previous.launchAtLogin != updated.launchAtLogin
        ) {
            applyPlatformSettings(updated)
        }

        // Rebuild the titles when the special symbol preference flips, and drop content kinds
        // that are no longer collected.
        if (previous.showSpecialSymbols != updated.showSpecialSymbols) {
            for (index in _items.indices) {
                val item = _items[index]
                _items[index] = item.copy(title = item.generateTitle(updated.showSpecialSymbols))
            }
        }
        if (previous.saveText != updated.saveText ||
            previous.saveImages != updated.saveImages ||
            previous.saveFiles != updated.saveFiles
        ) {
            _items.removeAll { item ->
                (!updated.saveText && item.text != null && item.imageBase64 == null && item.files.isEmpty()) ||
                    (!updated.saveImages && item.imageBase64 != null && item.text.isNullOrBlank() && item.files.isEmpty()) ||
                    (!updated.saveFiles && item.files.isNotEmpty() && item.text.isNullOrBlank() && item.imageBase64 == null)
            }
        }

        val layoutChanged = updated.historySize != previous.historySize ||
            updated.sortBy != previous.sortBy ||
            updated.pinTo != previous.pinTo

        persistSettings()

        if (layoutChanged) {
            limitHistorySize(updated.historySize)
            resort()
            persist()
        } else {
            persist()
        }
    }

    fun dismissStatus() {
        statusMessage = null
    }

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    /** Writes everything to storage immediately instead of waiting for the debounce. */
    fun flush() {
        itemsPersistJob?.cancel()
        settingsPersistJob?.cancel()
        storage.saveItems(_items.toList())
        storage.saveSettings(settings)
    }

    /** Port of `AppDelegate.applicationWillTerminate` + a synchronous flush. */
    fun handleQuit() {
        if (settings.clearOnQuit) {
            _items.removeAll { it.isUnpinned }
            if (settings.clearSystemClipboard) platform.clear()
        }
        flush()
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

    /** Pushes the settings that live on the platform side (poll interval, login item). */
    private fun applyPlatformSettings(value: AppSettings) {
        platform.pollIntervalMillis = value.clipboardCheckIntervalMillis.toLong().coerceAtLeast(50L)
        if (native.supportsLaunchAtLogin) {
            runCatching { native.setLaunchAtLogin(value.launchAtLogin) }
        }
    }

    private fun resort() {
        val sorted = ClipSorter.sort(_items.toList(), settings.sortBy, settings.pinTo)
        _items.clear()
        _items.addAll(sorted)
    }

    /** Pinned items are never removed by the size limit. */
    private fun limitHistorySize(maxSize: Int) {
        if (maxSize <= 0) return
        val unpinned = _items.filter { it.isUnpinned }
        if (unpinned.size <= maxSize) return
        val overflow = unpinned.drop(maxSize).map { it.id }.toSet()
        _items.removeAll { it.id in overflow }
    }

    private fun randomAvailablePin(): String = availablePins().randomOrNull().orEmpty()

    private fun persist() {
        itemsPersistJob?.cancel()
        itemsPersistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MILLIS)
            storage.saveItems(_items.toList())
        }
    }

    private fun persistSettings() {
        settingsPersistJob?.cancel()
        settingsPersistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MILLIS)
            storage.saveSettings(settings)
        }
    }

    private companion object {
        /** `a`, `q`, `v`, `w`, `z` are reserved for shortcuts, see Maccy. */
        const val PIN_CHARACTERS = "bcdefghijklmnoprstuxy"
        const val PASTE_DELAY_MILLIS = 160L
        const val PERSIST_DEBOUNCE_MILLIS = 300L
    }
}
