package com.qcmian.clipper.core

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qcmian.clipper.model.ClipItem
import com.qcmian.clipper.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the clipboard history. This is the Compose Multiplatform counterpart of Maccy's
 * `History` class: it captures new copies, de-duplicates them, keeps the pinned items
 * and persists everything through [ClipStorage].
 */
class ClipboardRepository(
    private val platform: ClipboardPlatform,
    private val storage: ClipStorage,
    private val scope: CoroutineScope,
) {
    private val _items = mutableStateListOf<ClipItem>()

    /** All history items, already sorted (pinned first/last, then by the sort preference). */
    val items: List<ClipItem> get() = _items

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

    private var started = false
    private var itemsPersistJob: Job? = null
    private var settingsPersistJob: Job? = null

    init {
        val restored = ClipSorter.sort(storage.loadItems(), settings.sortBy, settings.pinTo)
        _items.addAll(restored)
        limitHistorySize(settings.historySize)
    }

    /** The current query applied to the history. */
    val searchResults: List<SearchResult>
        get() = ClipSearch.search(searchQuery, _items, settings.searchMode)

    val visibleItems: List<ClipItem> get() = searchResults.map { it.item }

    val unpinnedCount: Int get() = _items.count { it.isUnpinned }

    fun start() {
        if (started) return
        started = true
        platform.start { snapshot -> capture(snapshot) }
    }

    fun stop() {
        if (!started) return
        started = false
        platform.stop()
    }

    fun updateSearchQuery(value: String) {
        searchQuery = value
    }

    fun clearSearch() {
        searchQuery = ""
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

        val text = snapshot.text
        if (!text.isNullOrBlank() && matchesIgnoredPattern(text)) return

        // Wall clock resolution is a millisecond, which is not enough to keep the order of
        // copies made in quick succession (Maccy relies on sub-millisecond `Date`). Bumping
        // past the newest item guarantees a strict ordering.
        val now = maxOf(currentTimeMillis(), (_items.maxOfOrNull { it.lastCopiedAt } ?: 0L) + 1L)
        var item = ClipItem(
            id = randomId(),
            text = text,
            imageBase64 = snapshot.imageBase64,
            files = snapshot.files,
            firstCopiedAt = now,
            lastCopiedAt = now,
            numberOfCopies = 1,
        )
        item = item.copy(title = item.generateTitle())
        if (item.text.isNullOrBlank() && item.imageBase64 == null && item.files.isEmpty()) return

        val existing = _items.firstOrNull { it.id != item.id && it.supersedes(item) }
        if (existing != null) {
            // Keep the identity of the original entry and only bump the counters.
            item = item.copy(
                firstCopiedAt = existing.firstCopiedAt,
                numberOfCopies = existing.numberOfCopies + 1,
                pin = existing.pin,
                title = existing.title.ifBlank { item.title },
            )
            _items.remove(existing)
        }

        _items.add(item)
        resort()
        limitHistorySize(settings.historySize)
        persist()
    }

    private fun matchesIgnoredPattern(text: String): Boolean =
        settings.ignoredRegexp.any { pattern ->
            runCatching { Regex(pattern).containsMatchIn(text) }.getOrDefault(false)
        }

    // ---------------------------------------------------------------------------------
    // Item actions
    // ---------------------------------------------------------------------------------

    /** Writes the item back to the system clipboard and optionally triggers a paste. */
    fun select(item: ClipItem, action: ClipAction = ClipAction.COPY) {
        val removeFormatting = action.removesFormatting(settings)
        if (!platform.write(snapshotFor(item, removeFormatting))) {
            statusMessage = "This platform can't copy that kind of content"
            return
        }
        clearSearch()

        if (action == ClipAction.PASTE || action == ClipAction.PASTE_WITHOUT_FORMATTING) {
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
        val text = if (removeFormatting) item.previewableText else item.text
        val image = if (removeFormatting) null else item.imageBase64
        val files = if (removeFormatting) emptyList() else item.files
        return ClipboardSnapshot(
            text = text ?: if (image == null && files.isEmpty()) item.previewableText else null,
            imageBase64 = image,
            files = files,
        )
    }

    fun togglePin(item: ClipItem) {
        val index = _items.indexOfFirst { it.id == item.id }
        if (index < 0) return

        val updated = item.copy(pin = if (item.isPinned) null else randomAvailablePin())
        _items[index] = updated
        resort()
        persist()
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

        // Only these preferences change the layout of the list; the rest (for example the
        // history size slider) should not trigger a re-sort on every drag frame.
        val layoutChanged = updated.historySize != settings.historySize ||
            updated.sortBy != settings.sortBy ||
            updated.pinTo != settings.pinTo

        settings = updated
        persistSettings()

        if (layoutChanged) {
            limitHistorySize(updated.historySize)
            resort()
            persist()
        }
    }

    fun dismissStatus() {
        statusMessage = null
    }

    // ---------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------

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

    private fun randomAvailablePin(): String {
        val assigned = _items.mapNotNull { it.pin }.toSet()
        val available = PIN_CHARACTERS.filter { it.toString() !in assigned }
        return available.randomOrNull()?.toString().orEmpty()
    }

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
