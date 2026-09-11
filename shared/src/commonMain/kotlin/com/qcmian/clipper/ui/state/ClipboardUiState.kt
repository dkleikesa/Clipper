package com.qcmian.clipper.ui.state

import com.qcmian.clipper.data.model.ClipItem
import com.qcmian.clipper.domain.model.SearchResult
import com.qcmian.clipper.settings.AppSettings
import com.qcmian.clipper.settings.SearchVisibility

/** Which modal is currently on screen, if any. */
enum class ClipboardDialog { PREFERENCES, ABOUT }

/** The "clear history" confirmation, including what it is going to clear. */
data class ClearConfirmation(
    val message: String,
    val comment: String,
    val all: Boolean,
    val hidePanel: Boolean,
)

/**
 * The complete, immutable description of the history screen.
 *
 * The UI renders this object and nothing else; every user interaction is sent back as a
 * [ClipboardUiAction]. This is the single source of truth for the screen, produced by
 * `ClipboardViewModel`.
 */
data class ClipboardUiState(
    val settings: AppSettings = AppSettings(),
    /** What the user has typed, updated on every keystroke. */
    val query: String = "",
    /** The query actually applied to the history, throttled like Maccy's `Throttler`. */
    val appliedQuery: String = "",
    /** The history filtered by [appliedQuery], in display order. */
    val results: List<SearchResult> = emptyList(),
    val historySelection: Int = 0,
    /** `-1` while the history list owns the highlight. */
    val footerSelection: Int = -1,
    val previewOpen: Boolean = false,
    /** `false` once the mouse moved, so hovering starts selecting again. */
    val keyboardNavigating: Boolean = true,
    val statusMessage: String? = null,
    val storageSize: String? = null,
    val screenCount: Int = 1,
    val supportsLaunchAtLogin: Boolean = false,
    val supportsApplicationInfo: Boolean = false,
    /** Whether the host can quit, which adds the "退出" footer row. */
    val showQuit: Boolean = false,
    val dialog: ClipboardDialog? = null,
    val confirmation: ClearConfirmation? = null,
    /** Bumped whenever the search field should take focus again. */
    val focusRequestToken: Int = 0,
) {
    /** The fixed pins block, kept outside the scroll view like Maccy's `HistoryListView`. */
    val pinnedEntries: List<IndexedValue<SearchResult>>
        get() = results.withIndex().filter { it.value.item.isPinned }

    /** The scrolling history below/above the pins block. */
    val unpinnedEntries: List<IndexedValue<SearchResult>>
        get() = results.withIndex().filterNot { it.value.item.isPinned }

    val pinnedItems: List<ClipItem> get() = results.map { it.item }.filter { it.isPinned }

    val selectedResult: SearchResult? get() = results.getOrNull(historySelection)

    val selectedItem: ClipItem? get() = selectedResult?.item

    val isHistoryHighlighted: Boolean get() = footerSelection < 0

    /** Port of `AppState.searchVisible`; it reads the throttled query, not the raw input. */
    val searchVisible: Boolean
        get() = settings.showSearch &&
            (settings.searchVisibility == SearchVisibility.ALWAYS || appliedQuery.isNotEmpty())

    /** `true` while a dialog is up, so the desktop panel must not auto-hide. */
    val isModalOpen: Boolean get() = dialog != null || confirmation != null

    /** `AppDelegate.isStatusItemDisabled`: paused or nothing is being recorded at all. */
    val isStatusItemDisabled: Boolean
        get() = settings.ignoreEvents ||
            (!settings.saveText && !settings.saveImages && !settings.saveFiles)
}
