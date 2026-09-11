package com.qcmian.clipper.settings

import kotlinx.serialization.Serializable

/** How the search query is matched against the history. Mirrors Maccy's `Search.Mode`. */
enum class SearchMode(val label: String) {
    EXACT("精确"),
    FUZZY("模糊"),
    REGEXP("正则"),
    MIXED("混合"),
}

/** Mirrors Maccy's `Sorter.By`. */
enum class SortBy(val label: String) {
    LAST_COPIED_AT("最后复制时间"),
    FIRST_COPIED_AT("首次复制时间"),
    NUMBER_OF_COPIES("复制次数"),
}

/** Mirrors Maccy's `PinsPosition`. */
enum class PinPosition(val label: String) {
    TOP("顶部"),
    BOTTOM("底部"),
}

/** Mirrors Maccy's `HighlightMatch`. */
enum class HighlightMatch(val label: String) {
    BOLD("加粗"),
    ITALIC("斜体"),
    UNDERLINE("下划线"),
    BACKGROUND("背景"),
}

/** Mirrors Maccy's `SearchVisibility`. */
enum class SearchVisibility(val label: String) {
    ALWAYS("总是显示"),
    DURING_SEARCH("搜索时显示"),
}

/** Mirrors Maccy's `PopupPosition`. */
enum class PopupPosition(val label: String) {
    CURSOR("光标位置"),
    MENU_BAR("菜单栏图标"),
    WINDOW_CENTER("应用窗口中心"),
    SCREEN_CENTER("屏幕中心"),
    LAST_POSITION("上次位置"),
}

/** Mirrors Maccy's `MenuIcon`. */
enum class MenuIcon(val label: String) {
    MACCY("Clipper"),
    CLIPBOARD("剪贴板"),
    SCISSORS("剪刀"),
    PAPERCLIP("回形针"),
}

/**
 * A user-recordable shortcut, the counterpart of Maccy's `KeyboardShortcuts.Name` +
 * `KeyboardShortcuts.Shortcut`. [character] is the rendered key (`"C"`, `"⌫"`, `" "`).
 */
@Serializable
data class ShortcutSpec(
    val character: String,
    val control: Boolean = false,
    val option: Boolean = false,
    val shift: Boolean = false,
    val command: Boolean = false,
) {
    /** `⌥⌘⌫`, the label the preferences window shows. */
    val label: String
        get() = buildString {
            if (control) append('\u2303')
            if (option) append('\u2325')
            if (shift) append('\u21e7')
            if (command) append('\u2318')
            append(if (character == " ") "Space" else character)
        }
}

/** The counterpart of Maccy's `Defaults.Keys` subset that makes sense on every platform. */
@Serializable
data class AppSettings(
    // Storage
    val historySize: Int = 200,
    val saveText: Boolean = true,
    val saveImages: Boolean = true,
    val saveFiles: Boolean = true,
    val sortBy: SortBy = SortBy.LAST_COPIED_AT,

    // Behavior
    val pasteByDefault: Boolean = false,
    val removeFormattingByDefault: Boolean = false,
    val clearOnQuit: Boolean = false,
    val clearSystemClipboard: Boolean = false,
    val searchThrottleMillis: Int = 200,
    /** Port of `Defaults[.clipboardCheckInterval]`, in milliseconds. */
    val clipboardCheckIntervalMillis: Int = 500,
    /** Port of `LaunchAtLogin`; registers the app as a login item. */
    val launchAtLogin: Boolean = false,
    /** Port of `Defaults[.suppressClearAlert]`: skip the "clear history" confirmation. */
    val suppressClearAlert: Boolean = false,

    // Shortcuts
    /** Port of `KeyboardShortcuts.Name.popup`, `⇧⌘C`. */
    val popupShortcut: ShortcutSpec = ShortcutSpec("C", command = true, shift = true),
    /** Port of `KeyboardShortcuts.Name.pin`, `⌥P`. */
    val pinShortcut: ShortcutSpec = ShortcutSpec("P", option = true),
    /** Port of `KeyboardShortcuts.Name.delete`, `⌥⌫`. */
    val deleteShortcut: ShortcutSpec = ShortcutSpec("\u232b", option = true),
    /** Port of `KeyboardShortcuts.Name.togglePreview`, `⌃Space`. */
    val togglePreviewShortcut: ShortcutSpec = ShortcutSpec(" ", control = true),

    // Search
    val searchMode: SearchMode = SearchMode.EXACT,
    val highlightMatch: HighlightMatch = HighlightMatch.BOLD,
    val showSearch: Boolean = true,
    val searchVisibility: SearchVisibility = SearchVisibility.ALWAYS,

    // Appearance
    val pinTo: PinPosition = PinPosition.TOP,
    val showTitle: Boolean = true,
    val showFooter: Boolean = true,
    val showHexColorSwatch: Boolean = true,
    val showSpecialSymbols: Boolean = true,
    val showApplicationIcons: Boolean = false,
    val imageMaxHeight: Int = 40,
    val openPreviewAutomatically: Boolean = true,
    val previewDelay: Int = 1_500,
    val popupPosition: PopupPosition = PopupPosition.CURSOR,
    val menuIcon: MenuIcon = MenuIcon.MACCY,
    val showRecentCopyInMenuBar: Boolean = false,
    /** Port of `Defaults[.showInStatusBar]`: show or hide the menu bar / tray icon. */
    val showInStatusBar: Boolean = true,
    /** Port of `Defaults[.popupScreen]`: 0 means the active screen, 1+ a specific one. */
    val popupScreen: Int = 0,
    /** Port of `Defaults[.windowSize].width`. */
    val windowWidth: Int = 450,
    /** Port of `Defaults[.windowSize].height`. */
    val windowHeight: Int = 800,
    /** Port of `Defaults[.previewWidth]`. */
    val previewWidth: Int = 400,

    // Ignore
    /** Pause capturing new copies. */
    val ignoreEvents: Boolean = false,
    /** When paused, only skip the next copy. */
    val ignoreOnlyNextEvent: Boolean = false,
    val ignoredRegexp: List<String> = emptyList(),
    /** Bundle identifiers of the applications whose copies are skipped. */
    val ignoredApps: List<String> = emptyList(),
    /** When set, only the applications listed in [ignoredApps] are recorded. */
    val ignoreAllAppsExceptListed: Boolean = false,
    /** Pasteboard type identifiers that must never be recorded, e.g. `com.agilebits.onepassword`. */
    val ignoredPasteboardTypes: List<String> = DEFAULT_IGNORED_PASTEBOARD_TYPES,

    // Recognition
    /** Run text recognition on copied images and use the result as the title. */
    val recognizeText: Boolean = true,
) {
    companion object {
        /** Maccy's default `ignoredPasteboardTypes`. */
        val DEFAULT_IGNORED_PASTEBOARD_TYPES = listOf(
            "Pasteboard generator type",
            "com.agilebits.onepassword",
            "com.typeit4me.clipping",
            "de.petermaurer.TransientPasteboardType",
            "net.antelle.keeweb",
        )

        /** Types Maccy always ignores because they are confidential or temporary. */
        val TRANSIENT_PASTEBOARD_TYPES = listOf(
            "org.nspasteboard.TransientType",
            "org.nspasteboard.ConcealedType",
            "org.nspasteboard.AutoGeneratedType",
        )
    }
}
