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
    LAST_COPIED_AT("最后复制"),
    FIRST_COPIED_AT("首次复制"),
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

/** 应用主题的三种模式。 */
enum class ThemeMode(val label: String) {
    SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
}

/**。 */
enum class PopupPosition(val label: String) {
    CURSOR("光标位置"),
    MENU_BAR("托盘图标"),
    SCREEN_CENTER("屏幕中心"),
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
    /**
     * 未置顶历史内容的总大小上限（字节）。超过后按当前排序丢弃最旧的记录；
     * 置顶项不受此限制，也从不因超限被丢弃。
     */
    val historyMaxSizeBytes: Long = DEFAULT_HISTORY_MAX_SIZE_BYTES,
    val saveText: Boolean = true,
    val saveImages: Boolean = true,
    val saveFiles: Boolean = true,
    val sortBy: SortBy = SortBy.LAST_COPIED_AT,

    // 行为
    val pasteByDefault: Boolean = false,
    val removeFormattingByDefault: Boolean = false,
    val clearOnQuit: Boolean = false,
    val searchThrottleMillis: Int = 200,
    /** 对应 `Defaults[.clipboardCheckInterval]`，单位毫秒。 */
    val clipboardCheckIntervalMillis: Int = 500,
    /** 对应 `LaunchAtLogin`；把应用注册为开机自启项。 */
    val launchAtLogin: Boolean = false,

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

    // 外观
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 用户拖拽出的自定义面板大小；两者都为 `null` 时表示自动尺寸（贴合内容）。 */
    val customWindowWidth: Int? = null,
    val customWindowHeight: Int? = null,
    val pinTo: PinPosition = PinPosition.TOP,
    val showHexColorSwatch: Boolean = true,
    val showSpecialSymbols: Boolean = true,
    val showApplicationIcons: Boolean = false,
    val imageMaxHeight: Int = 40,
    val popupPosition: PopupPosition = PopupPosition.CURSOR,
    /** 对应 `Defaults[.showInStatusBar]`：显示或隐藏菜单栏 / 托盘图标。 */
    val showInStatusBar: Boolean = true,
    /** 对应 `Defaults[.popupScreen]`：0 表示当前活动屏幕，1 及以上指向特定屏幕。 */
    val popupScreen: Int = 0,
    /** 对应 `Defaults[.previewWidth]`：预览面板宽度，只由预览分隔条的拖拽写入。 */
    val previewWidth: Int = DEFAULT_PREVIEW_WIDTH,
    /**
     * 预览面板是否打开。
     *
     * 与 [previewWidth] 一样属于「用户的选择」，因此随设置持久化：打开后一直开着，关闭后一直
     * 关着，重启、面板隐藏都不会改变它——只有用户再次切换（按钮 / 快捷键 / 页脚）才会翻转。
     */
    val previewOpen: Boolean = false,

    // 忽略
    /** 暂停记录新的复制。 */
    val ignoreEvents: Boolean = false,
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
        /** 字节与 MB 的换算，供设置界面与体积计算共用。 */
        const val BYTES_PER_MEGABYTE = 1024L * 1024L

        /** [historyMaxSizeBytes] 的默认值：50 MB。 */
        const val DEFAULT_HISTORY_MAX_SIZE_BYTES = 50L * BYTES_PER_MEGABYTE

        /**
         * [previewWidth] 的默认值。
         *
         * 单独拿出来是因为设置页的「恢复默认尺寸」也要用它：预览宽度是窗口里分出去的一段，
         * 只把主列表宽度恢复成默认、留着拖出来的预览宽度，窗口会比默认状态宽（或窄）一截。
         */
        const val DEFAULT_PREVIEW_WIDTH = 300

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
