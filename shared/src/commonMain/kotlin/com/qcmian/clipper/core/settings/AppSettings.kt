package com.qcmian.clipper.core.settings

import kotlinx.serialization.Serializable

/**。 */
enum class SortBy(val label: String) {
    LAST_COPIED_AT("最后复制"),
    FIRST_COPIED_AT("首次复制"),
    NUMBER_OF_COPIES("复制次数"),
    FILE_SIZE("内容大小"),
}

/** 排序方向。 */
enum class SortOrder(val label: String) {
    DESCENDING("降序"),
    ASCENDING("升序"),
}

/** 剪贴板条目的类型，用于主面板的筛选栏。 */
enum class ClipFilterType(val label: String) {
    TEXT("文本"),
    IMAGE("图片"),
    FILE("文件"),
    RICH_TEXT("富文本"),
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
     * 未置顶历史内容的条数上限。超过后按「最后一次复制」从新到旧保留这么多条，多出来的丢弃
     * （与排序方式无关：用户设上限的意图是「只留最近的」）；置顶项不受此限制，也从不因超限
     * 被丢弃。
     *
     * 条数**不设上限**：设置页只校验「正整数」，不设最大值——用户想留多少条就填多少条。
     * 默认给到 1 万条，是因为拆表之后这个量级不再有代价：列表只加载窗口、排序与分页下推给
     * SQL、图片与正文按需读取，常驻内存与历史总量无关。
     */
    val historyMaxCount: Int = DEFAULT_HISTORY_MAX_COUNT,
    val sortBy: SortBy = SortBy.LAST_COPIED_AT,
    val sortOrder: SortOrder = SortOrder.DESCENDING,
    /** 主面板筛选栏选中的类型集合；空集表示不显示任何未置顶内容。默认全选。 */
    val filterTypes: Set<ClipFilterType> = ClipFilterType.entries.toSet(),

    // 行为
    val pasteByDefault: Boolean = false,
    val removeFormattingByDefault: Boolean = false,
    /**
     * 连续粘贴时每条之后补一个裸回车：多数目标端要一次「提交 / 换行」才会腾出下一处落点
     * （终端执行、聊天发送、Excel 下移一格）。
     *
     * 只在**多条**时生效；单条粘贴一个多余按键都不发，免得在 Finder 这类「回车 = 重命名」
     * 的应用里造成破坏。
     */
    val pressReturnAfterPaste: Boolean = true,
    val clearOnQuit: Boolean = false,
    val searchThrottleMillis: Int = 200,
    /** 单位毫秒。 */
    val clipboardCheckIntervalMillis: Int = 500,
    /** 把应用注册为开机自启项。 */
    val launchAtLogin: Boolean = false,

    // 快捷键
    /**
     * 每个槽位的取值见 [ShortcutSlot]；`null` 表示用户把它**清除**了（未绑定），
     * 与「还没改过、用出厂默认值」不是一回事。
     *
     * 面板内置按键（导航、激活、`⎋`、`⌘,`、`⌘1…⌘9`）与条目操作共用同一套槽位，
     * 因此设置页里每一行都可录制。
     */
    val popupShortcut: ShortcutSpec? = ShortcutSlot.POPUP.default,
    val closeShortcut: ShortcutSpec? = ShortcutSlot.CLOSE.default,
    val openSettingsShortcut: ShortcutSpec? = ShortcutSlot.OPEN_SETTINGS.default,
    val movePreviousShortcut: ShortcutSpec? = ShortcutSlot.MOVE_PREVIOUS.default,
    val moveNextShortcut: ShortcutSpec? = ShortcutSlot.MOVE_NEXT.default,
    val moveToFirstShortcut: ShortcutSpec? = ShortcutSlot.MOVE_TO_FIRST.default,
    val moveToLastShortcut: ShortcutSpec? = ShortcutSlot.MOVE_TO_LAST.default,
    val activateShortcut: ShortcutSpec? = ShortcutSlot.ACTIVATE.default,
    val quickSelectShortcut: ShortcutSpec? = ShortcutSlot.QUICK_SELECT.default,
    val selectAllShortcut: ShortcutSpec? = ShortcutSlot.SELECT_ALL.default,
    val pauseShortcut: ShortcutSpec? = ShortcutSlot.PAUSE.default,
    val pinShortcut: ShortcutSpec? = ShortcutSlot.PIN.default,
    val deleteShortcut: ShortcutSpec? = ShortcutSlot.DELETE.default,
    val togglePreviewShortcut: ShortcutSpec? = ShortcutSlot.TOGGLE_PREVIEW.default,

    // 搜索
    val highlightMatch: HighlightMatch = HighlightMatch.BACKGROUND,
    val showSearch: Boolean = true,

    // 外观
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** 用户拖拽出的自定义面板大小；两者都为 `null` 时表示自动尺寸（贴合内容）。 */
    val customWindowWidth: Int? = null,
    val customWindowHeight: Int? = null,
    val pinTo: PinPosition = PinPosition.TOP,
    val showHexColorSwatch: Boolean = true,
    val showSpecialSymbols: Boolean = true,
    /** 在工具栏（主面板顶部）显示筛选栏；关闭时改到设置页「外观」里配置。 */
    val showFilterBar: Boolean = false,
    val showApplicationIcons: Boolean = false,
    val imageMaxHeight: Int = 40,
    val popupPosition: PopupPosition = PopupPosition.SCREEN_CENTER,
    /** 显示或隐藏菜单栏 / 托盘图标。 */
    val showInStatusBar: Boolean = true,
    /** 0 表示当前活动屏幕，1 及以上指向特定屏幕。 */
    val popupScreen: Int = 0,
    /** 预览面板宽度，只由预览分隔条的拖拽写入。 */
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

    // 识别
    /** 对复制进来的图片执行文字识别，并把结果用作标题。 */
    val recognizeText: Boolean = true,
) {
    companion object {
        /** [historyMaxCount] 的默认值。 */
        const val DEFAULT_HISTORY_MAX_COUNT = 10_000

        /**
         * [previewWidth] 的默认值。
         *
         * 单独拿出来是因为设置页的「恢复默认尺寸」也要用它：预览宽度是窗口里分出去的一段，
         * 只把主列表宽度恢复成默认、留着拖出来的预览宽度，窗口会比默认状态宽（或窄）一截。
         */
        const val DEFAULT_PREVIEW_WIDTH = 300

 /**
  * 始终忽略的粘贴板类型。
  *
  * 这是安全底线，**不是**设置项：命中它们的内容（临时内容、机密内容、自动生成的内容）
  * 任何情况下都不会进历史——密码管理器与剪贴板工具都靠这三个类型声明「不要记录我」。
  * 用户可以关掉的只有「暂停记录新的复制」。
  */
 val ALWAYS_IGNORED_PASTEBOARD_TYPES = listOf(
     "org.nspasteboard.TransientType",
     "org.nspasteboard.ConcealedType",
     "org.nspasteboard.AutoGeneratedType",
 )
    }
}
