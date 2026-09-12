package com.qcmian.clipper.core.settings

import kotlinx.serialization.Serializable

/** 查询词与历史的匹配方式。。 */
enum class SearchMode(val label: String) {
    EXACT("精确"),
    FUZZY("模糊"),
    REGEXP("正则"),
    MIXED("混合"),
}

/**。 */
enum class SortBy(val label: String) {
    LAST_COPIED_AT("最后复制时间"),
    FIRST_COPIED_AT("首次复制时间"),
    NUMBER_OF_COPIES("复制次数"),
}

/**。 */
enum class PinPosition(val label: String) {
    TOP("顶部"),
    BOTTOM("底部"),
}

/**。 */
enum class HighlightMatch(val label: String) {
    BOLD("加粗"),
    ITALIC("斜体"),
    UNDERLINE("下划线"),
    BACKGROUND("背景"),
}

/**。 */
enum class SearchVisibility(val label: String) {
    ALWAYS("总是显示"),
    DURING_SEARCH("搜索时显示"),
}

/**。 */
enum class PopupPosition(val label: String) {
    CURSOR("光标位置"),
    MENU_BAR("菜单栏图标"),
    WINDOW_CENTER("应用窗口中心"),
    SCREEN_CENTER("屏幕中心"),
    LAST_POSITION("上次位置"),
}

/**。 */
enum class MenuIcon(val label: String) {
    MACCY("Clipper"),
    CLIPBOARD("剪贴板"),
    SCISSORS("剪刀"),
    PAPERCLIP("回形针"),
}

/**
 * 用户可录制的快捷键， +
 * `KeyboardShortcuts.Shortcut`。[character] 是渲染出来的按键（`"C"`、`"⌫"`、`" "`）。
 */
@Serializable
data class ShortcutSpec(
    val character: String,
    val control: Boolean = false,
    val option: Boolean = false,
    val shift: Boolean = false,
    val command: Boolean = false,
) {
    /** `⌥⌘⌫`，偏好设置窗口显示的标签。 */
    val label: String
        get() = buildString {
            if (control) append('\u2303')
            if (option) append('\u2325')
            if (shift) append('\u21e7')
            if (command) append('\u2318')
            append(if (character == " ") "Space" else character)
        }
}

/**
 * 用户偏好设置。刻意*不*放在领域模型里：它混合了存储、行为、快捷键与外观选项，
 * 因此独立放在 `settings` 包中，而不是与纯领域实体并列。
 *
 * 中在各平台都成立的那个子集。
 */
@Serializable
data class AppSettings(
    // 存储
    val historySize: Int = 200,
    val saveText: Boolean = true,
    val saveImages: Boolean = true,
    val saveFiles: Boolean = true,
    val sortBy: SortBy = SortBy.LAST_COPIED_AT,

    // 行为
    val pasteByDefault: Boolean = false,
    val removeFormattingByDefault: Boolean = false,
    val clearOnQuit: Boolean = false,
    val clearSystemClipboard: Boolean = false,
    val searchThrottleMillis: Int = 200,
    /** 对应 `Defaults[.clipboardCheckInterval]`，单位毫秒。 */
    val clipboardCheckIntervalMillis: Int = 500,
    /** 对应 `LaunchAtLogin`；把应用注册为开机自启项。 */
    val launchAtLogin: Boolean = false,
    /** 对应 `Defaults[.suppressClearAlert]`：跳过「清除历史」的二次确认。 */
    val suppressClearAlert: Boolean = false,

    // 快捷键
    /** 对应 `KeyboardShortcuts.Name.popup`，`⇧⌘C`。 */
    val popupShortcut: ShortcutSpec = ShortcutSpec("C", command = true, shift = true),
    /** 对应 `KeyboardShortcuts.Name.pin`，`⌥P`。 */
    val pinShortcut: ShortcutSpec = ShortcutSpec("P", option = true),
    /** 对应 `KeyboardShortcuts.Name.delete`，`⌥⌫`。 */
    val deleteShortcut: ShortcutSpec = ShortcutSpec("\u232b", option = true),
    /** 对应 `KeyboardShortcuts.Name.togglePreview`，`⌃Space`。 */
    val togglePreviewShortcut: ShortcutSpec = ShortcutSpec(" ", control = true),

    // 搜索
    val searchMode: SearchMode = SearchMode.EXACT,
    val highlightMatch: HighlightMatch = HighlightMatch.BOLD,
    val showSearch: Boolean = true,
    val searchVisibility: SearchVisibility = SearchVisibility.ALWAYS,

    // 外观
    val pinTo: PinPosition = PinPosition.TOP,
    val showHexColorSwatch: Boolean = true,
    val showSpecialSymbols: Boolean = true,
    val showApplicationIcons: Boolean = false,
    val imageMaxHeight: Int = 40,
    val popupPosition: PopupPosition = PopupPosition.CURSOR,
    val menuIcon: MenuIcon = MenuIcon.MACCY,
    val showRecentCopyInMenuBar: Boolean = false,
    /** 对应 `Defaults[.showInStatusBar]`：显示或隐藏菜单栏 / 托盘图标。 */
    val showInStatusBar: Boolean = true,
    /** 对应 `Defaults[.popupScreen]`：0 表示当前活动屏幕，1 及以上指向特定屏幕。 */
    val popupScreen: Int = 0,
    /** 对应 `Defaults[.windowSize].width`。 */
    val windowWidth: Int = 450,
    /** 对应 `Defaults[.windowSize].height`。 */
    val windowHeight: Int = 800,
    /** 对应 `Defaults[.previewWidth]`。 */
    val previewWidth: Int = 400,

    // 忽略
    /** 暂停记录新的复制。 */
    val ignoreEvents: Boolean = false,
    /** 暂停时，只跳过下一条复制。 */
    val ignoreOnlyNextEvent: Boolean = false,
    val ignoredRegexp: List<String> = emptyList(),
    /** 复制内容会被跳过的应用 Bundle 标识符。 */
    val ignoredApps: List<String> = emptyList(),
    /** 开启后，只记录 [ignoredApps] 中列出的应用。 */
    val ignoreAllAppsExceptListed: Boolean = false,
    /** 绝不能记录的粘贴板类型标识，例如 `com.agilebits.onepassword`。 */
    val ignoredPasteboardTypes: List<String> = DEFAULT_IGNORED_PASTEBOARD_TYPES,

    // 识别
    /** 对复制进来的图片执行文字识别，并把结果用作标题。 */
    val recognizeText: Boolean = true,
) {
    companion object {
 /** `ignoredPasteboardTypes` 的默认值。 */
        val DEFAULT_IGNORED_PASTEBOARD_TYPES = listOf(
            "Pasteboard generator type",
            "com.agilebits.onepassword",
            "com.typeit4me.clipping",
            "de.petermaurer.TransientPasteboardType",
            "net.antelle.keeweb",
        )

 /** 始终忽略的类型：涉及机密或只是临时内容。 */
        val TRANSIENT_PASTEBOARD_TYPES = listOf(
            "org.nspasteboard.TransientType",
            "org.nspasteboard.ConcealedType",
            "org.nspasteboard.AutoGeneratedType",
        )
    }
}
