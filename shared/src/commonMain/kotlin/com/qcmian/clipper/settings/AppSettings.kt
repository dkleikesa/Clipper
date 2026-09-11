package com.qcmian.clipper.settings

import kotlinx.serialization.Serializable

/** How the search query is matched against the history. Mirrors Maccy's `Search.Mode`. */
enum class SearchMode(val label: String) {
    EXACT("Exact"),
    FUZZY("Fuzzy"),
    REGEXP("Regex"),
    MIXED("Mixed"),
}

/** Mirrors Maccy's `Sorter.By`. */
enum class SortBy(val label: String) {
    LAST_COPIED_AT("Last copied"),
    FIRST_COPIED_AT("First copied"),
    NUMBER_OF_COPIES("Number of copies"),
}

/** Mirrors Maccy's `PinsPosition`. */
enum class PinPosition(val label: String) {
    TOP("Top"),
    BOTTOM("Bottom"),
}

/** Mirrors Maccy's `HighlightMatch`. */
enum class HighlightMatch(val label: String) {
    BOLD("Bold"),
    ITALIC("Italic"),
    UNDERLINE("Underline"),
    BACKGROUND("Background"),
}

/** The counterpart of Maccy's `Defaults.Keys` subset that makes sense on every platform. */
@Serializable
data class AppSettings(
    val historySize: Int = 200,
    val pasteByDefault: Boolean = false,
    val removeFormattingByDefault: Boolean = false,
    val searchMode: SearchMode = SearchMode.EXACT,
    val sortBy: SortBy = SortBy.LAST_COPIED_AT,
    val pinTo: PinPosition = PinPosition.TOP,
    val showTitle: Boolean = true,
    val showFooter: Boolean = true,
    val showHexColorSwatch: Boolean = true,
    val highlightMatch: HighlightMatch = HighlightMatch.BOLD,
    /** Pause capturing new copies. */
    val ignoreEvents: Boolean = false,
    /** When paused, only skip the next copy. */
    val ignoreOnlyNextEvent: Boolean = false,
    val ignoredRegexp: List<String> = emptyList(),
    /** Clear the system clipboard when the history is cleared. */
    val clearSystemClipboard: Boolean = false,
)
