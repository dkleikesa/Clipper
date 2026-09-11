package com.qcmian.clipper.ui.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.qcmian.clipper.data.model.ClipItem
import com.qcmian.clipper.domain.action.ClipAction
import com.qcmian.clipper.domain.action.modifierFlagsOf
import com.qcmian.clipper.settings.AppSettings
import com.qcmian.clipper.settings.HighlightMatch
import com.qcmian.clipper.settings.MenuIcon
import com.qcmian.clipper.settings.PinPosition
import com.qcmian.clipper.settings.PopupPosition
import com.qcmian.clipper.settings.SearchMode
import com.qcmian.clipper.settings.SearchVisibility
import com.qcmian.clipper.settings.ShortcutSpec
import com.qcmian.clipper.settings.SortBy
import com.qcmian.clipper.ui.ModifierFlags
import com.qcmian.clipper.ui.components.rememberImageBitmap
import com.qcmian.clipper.ui.icons.ClipperIcon
import com.qcmian.clipper.ui.icons.ClipperIconKind
import com.qcmian.clipper.ui.shortcutCharacterOf
import kotlin.math.roundToInt

/**
 * The counterpart of Maccy's preferences window (General / Storage / Appearance / Pins /
 * Ignore / Advanced panes), condensed into a single scrollable dialog.
 */
@Composable
fun PreferencesDialog(
    settings: AppSettings,
    pinnedItems: List<ClipItem>,
    storageSize: String?,
    availablePins: (ClipItem) -> List<String>,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onPinChange: (ClipItem, String?) -> Unit,
    onTitleChange: (ClipItem, String) -> Unit,
    onContentChange: (ClipItem, String) -> Unit,
    onDeletePinned: (ClipItem) -> Unit,
    onClearUnpinned: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
    screenCount: Int = 1,
    supportsLaunchAtLogin: Boolean = false,
    onResetPosition: () -> Unit = {},
    supportsApplicationInfo: Boolean = false,
    applicationName: (String) -> String? = { null },
    applicationIcon: (String?) -> String? = { null },
    onPickApplication: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.width(520.dp).heightIn(max = 640.dp),
        ) {
            PreferencesContent(
                settings = settings,
                pinnedItems = pinnedItems,
                storageSize = storageSize,
                availablePins = availablePins,
                onSettingsChange = onSettingsChange,
                onPinChange = onPinChange,
                onTitleChange = onTitleChange,
                onContentChange = onContentChange,
                onDeletePinned = onDeletePinned,
                onClearUnpinned = onClearUnpinned,
                onClearAll = onClearAll,
                onDismiss = onDismiss,
                screenCount = screenCount,
                supportsLaunchAtLogin = supportsLaunchAtLogin,
                onResetPosition = onResetPosition,
                supportsApplicationInfo = supportsApplicationInfo,
                applicationName = applicationName,
                applicationIcon = applicationIcon,
                onPickApplication = onPickApplication,
            )
        }
    }
}

/** The dialog body, split out so it can be rendered and previewed on its own. */
@Composable
fun PreferencesContent(
    settings: AppSettings,
    pinnedItems: List<ClipItem>,
    storageSize: String?,
    availablePins: (ClipItem) -> List<String>,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onPinChange: (ClipItem, String?) -> Unit,
    onTitleChange: (ClipItem, String) -> Unit,
    onContentChange: (ClipItem, String) -> Unit,
    onDeletePinned: (ClipItem) -> Unit,
    onClearUnpinned: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
    screenCount: Int = 1,
    supportsLaunchAtLogin: Boolean = false,
    onResetPosition: () -> Unit = {},
    supportsApplicationInfo: Boolean = false,
    applicationName: (String) -> String? = { null },
    applicationIcon: (String?) -> String? = { null },
    onPickApplication: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme

    // `KeyboardShortcuts.Recorder`: while a slot is being recorded every key press is
    // captured here instead of reaching the text fields below.
    var recording by remember { mutableStateOf<String?>(null) }
    // `PinsSettingsPane`'s table selection: the selected pin row is the one the Delete key
    // removes. A pin can disappear through another path (⌥P in the list, a delete from the
    // list, …), so the selection is only honoured while the item is still pinned.
    var selectedPin by remember { mutableStateOf<String?>(null) }
    val effectiveSelectedPin = selectedPin?.takeIf { id -> pinnedItems.any { it.id == id } }
    val recorderFocus = remember { FocusRequester() }
    LaunchedEffect(recording) {
        if (recording != null) runCatching { recorderFocus.requestFocus() }
    }

    // The selected pin row is removed with the Delete key, handled in the bubble phase on the
    // root column below. That handler only ever sees the key while the dialog owns the focus,
    // so the column takes it as soon as the dialog opens.
    LaunchedEffect(Unit) {
        runCatching { recorderFocus.requestFocus() }
    }

    val modifierKeys = remember { ModifierFlags() }
    val captureKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        val slot = recording
        if (slot == null) {
            false
        } else if (event.type != KeyEventType.KeyDown || modifierKeys.isModifierKey(event)) {
            // Swallow modifier-only presses so the recorder keeps waiting.
            true
        } else if (event.key == Key.Escape) {
            // Escape cancels the recording instead of being captured as a shortcut.
            recording = null
            true
        } else {
            val character = shortcutCharacterOf(event)
            if (character != null) {
                val spec = ShortcutSpec(
                    character = character,
                    control = event.isCtrlPressed,
                    option = event.isAltPressed,
                    shift = event.isShiftPressed,
                    command = event.isMetaPressed,
                )
                onSettingsChange { current ->
                    when (slot) {
                        "popup" -> current.copy(popupShortcut = spec)
                        "pin" -> current.copy(pinShortcut = spec)
                        "delete" -> current.copy(deleteShortcut = spec)
                        else -> current.copy(togglePreviewShortcut = spec)
                    }
                }
            }
            recording = null
            true
        }
    }

    // Port of `PinsSettingsPane.onDeleteCommand`. Unlike the recorder above this runs in the
    // bubble phase, so a focused alias/content field consumes Backspace/Delete first and
    // editing text can never delete the row.
    val deleteSelectedPin: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        val id = effectiveSelectedPin
        if (id != null &&
            event.type == KeyEventType.KeyDown &&
            (event.key == Key.Delete || event.key == Key.Backspace)
        ) {
            pinnedItems.firstOrNull { it.id == id }?.let(onDeletePinned)
            selectedPin = null
            true
        } else {
            false
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent(captureKey)
            .onKeyEvent(deleteSelectedPin)
            .focusRequester(recorderFocus)
            .focusable(),
    ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("偏好设置", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceVariant.copy(alpha = 0.6f))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        ClipperIcon(ClipperIconKind.CLEAR, size = 12.dp)
                    }
                }

                HorizontalDivider(color = colors.outline.copy(alpha = 0.4f))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    // ---------------------------------------------------------------- 存储
                    SectionTitle("存储")
                    SwitchRow(
                        title = "保存文本",
                        checked = settings.saveText,
                    ) { value -> onSettingsChange { it.copy(saveText = value) } }
                    SwitchRow(
                        title = "保存图片",
                        checked = settings.saveImages,
                    ) { value -> onSettingsChange { it.copy(saveImages = value) } }
                    SwitchRow(
                        title = "保存文件",
                        checked = settings.saveFiles,
                    ) { value -> onSettingsChange { it.copy(saveFiles = value) } }
                    SliderRow(
                        title = "历史条数",
                        valueLabel = "${settings.historySize} 条未置顶记录" +
                            (storageSize?.takeIf { it.isNotEmpty() }?.let { " · 占用 $it" } ?: ""),
                        value = settings.historySize.toFloat(),
                        // Maccy's `StorageSettingsPane` allows 1…999.
                        range = 1f..999f,
                        onValueChange = { value ->
                            val size = value.roundToInt().coerceAtLeast(1)
                            onSettingsChange { it.copy(historySize = size) }
                        },
                    )
                    ChipBlock(
                        title = "排序方式",
                        values = SortBy.entries,
                        selected = settings.sortBy,
                        label = { it.label },
                        onSelect = { value -> onSettingsChange { it.copy(sortBy = value) } },
                    )

                    // ---------------------------------------------------------------- 行为
                    SectionTitle("行为")
                    SwitchRow(
                        title = "自动粘贴",
                        description = "选中项目后直接粘贴到上一个应用。",
                        checked = settings.pasteByDefault,
                    ) { value -> onSettingsChange { it.copy(pasteByDefault = value) } }
                    SwitchRow(
                        title = "粘贴时去除格式",
                        description = "只保留纯文本。",
                        checked = settings.removeFormattingByDefault,
                    ) { value -> onSettingsChange { it.copy(removeFormattingByDefault = value) } }
                    // Port of `GeneralSettingsPane`'s "Modifiers" explanation, which shows
                    // which combination performs each action under the current preferences.
                    Text(
                        text = "按 ${modifierFlagsOf(ClipAction.COPY, settings)} 复制，" +
                            "${modifierFlagsOf(ClipAction.PASTE, settings)} 粘贴，" +
                            "${modifierFlagsOf(ClipAction.PASTE_WITHOUT_FORMATTING, settings)} 粘贴并去除格式。",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    SliderRow(
                        title = "剪贴板检查间隔",
                        valueLabel = "${settings.clipboardCheckIntervalMillis} 毫秒",
                        value = settings.clipboardCheckIntervalMillis.toFloat(),
                        range = 100f..2_000f,
                        onValueChange = { value ->
                            onSettingsChange {
                                it.copy(clipboardCheckIntervalMillis = value.roundToInt())
                            }
                        },
                    )
                    if (supportsLaunchAtLogin) {
                        SwitchRow(
                            title = "开机时启动",
                            description = "登录后自动运行 Clipper。",
                            checked = settings.launchAtLogin,
                        ) { value -> onSettingsChange { it.copy(launchAtLogin = value) } }
                    }

                    // ---------------------------------------------------------------- 快捷键
                    SectionTitle("快捷键")
                    ShortcutRow(
                        title = "呼出面板",
                        spec = settings.popupShortcut,
                        recording = recording == "popup",
                        onRecord = { recording = "popup" },
                        onReset = {
                            onSettingsChange { it.copy(popupShortcut = AppSettings().popupShortcut) }
                        },
                    )
                    ShortcutRow(
                        title = "置顶 / 取消置顶",
                        spec = settings.pinShortcut,
                        recording = recording == "pin",
                        onRecord = { recording = "pin" },
                        onReset = {
                            onSettingsChange { it.copy(pinShortcut = AppSettings().pinShortcut) }
                        },
                    )
                    ShortcutRow(
                        title = "删除选中项",
                        spec = settings.deleteShortcut,
                        recording = recording == "delete",
                        onRecord = { recording = "delete" },
                        onReset = {
                            onSettingsChange { it.copy(deleteShortcut = AppSettings().deleteShortcut) }
                        },
                    )
                    ShortcutRow(
                        title = "显示 / 隐藏预览",
                        spec = settings.togglePreviewShortcut,
                        recording = recording == "preview",
                        onRecord = { recording = "preview" },
                        onReset = {
                            onSettingsChange {
                                it.copy(togglePreviewShortcut = AppSettings().togglePreviewShortcut)
                            }
                        },
                    )
                    Text(
                        text = if (recording != null) {
                            "请按下新的快捷键…"
                        } else {
                            "点击快捷键即可重新录制；呼出面板的快捷键在系统范围内生效。"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (recording != null) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    // ---------------------------------------------------------------- 搜索
                    SectionTitle("搜索")
                    SwitchRow(
                        title = "显示搜索框",
                        checked = settings.showSearch,
                    ) { value -> onSettingsChange { it.copy(showSearch = value) } }
                    ChipBlock(
                        title = "搜索框显示时机",
                        values = SearchVisibility.entries,
                        selected = settings.searchVisibility,
                        label = { it.label },
                        enabled = settings.showSearch,
                        onSelect = { value -> onSettingsChange { it.copy(searchVisibility = value) } },
                    )
                    ChipBlock(
                        title = "搜索模式",
                        values = SearchMode.entries,
                        selected = settings.searchMode,
                        label = { it.label },
                        onSelect = { value -> onSettingsChange { it.copy(searchMode = value) } },
                    )
                    ChipBlock(
                        title = "匹配高亮",
                        values = HighlightMatch.entries,
                        selected = settings.highlightMatch,
                        label = { it.label },
                        onSelect = { value -> onSettingsChange { it.copy(highlightMatch = value) } },
                    )

                    // ---------------------------------------------------------------- 外观
                    SectionTitle("外观")
                    ChipBlock(
                        title = "弹窗位置",
                        values = PopupPosition.entries,
                        selected = settings.popupPosition,
                        label = { it.label },
                        onSelect = { value -> onSettingsChange { it.copy(popupPosition = value) } },
                    )
                    if (settings.popupPosition == PopupPosition.LAST_POSITION) {
                        TextButton(
                            onClick = onResetPosition,
                            modifier = Modifier.padding(top = 2.dp),
                        ) {
                            Text("重置弹窗位置")
                        }
                    }
                    // `AppearanceSettingsPane.screenPicker(for:)`: the screen picker is only
                    // offered for the two positions that are anchored to a whole screen, and
                    // only when there is more than one screen to choose from.
                    if (screenCount > 1 &&
                        (settings.popupPosition == PopupPosition.SCREEN_CENTER ||
                            settings.popupPosition == PopupPosition.LAST_POSITION)
                    ) {
                        ChipBlock(
                            title = "弹窗屏幕",
                            values = (0 until screenCount).toList(),
                            selected = settings.popupScreen.coerceIn(0, screenCount - 1),
                            label = { index -> if (index == 0) "活动屏幕" else "屏幕 ${index + 1}" },
                            onSelect = { value -> onSettingsChange { it.copy(popupScreen = value) } },
                        )
                    }
                    SwitchRow(
                        title = "显示菜单栏图标",
                        description = "关闭后 Clipper 只在快捷键下工作。",
                        checked = settings.showInStatusBar,
                    ) { value -> onSettingsChange { it.copy(showInStatusBar = value) } }
                    ChipBlock(
                        title = "菜单栏图标",
                        values = MenuIcon.entries,
                        selected = settings.menuIcon,
                        label = { it.label },
                        // `AppearanceSettingsPane` disables the picker together with the icon.
                        enabled = settings.showInStatusBar,
                        onSelect = { value -> onSettingsChange { it.copy(menuIcon = value) } },
                    )
                    SwitchRow(
                        title = "在菜单栏显示最近复制",
                        description = "把最新一条复制内容放到托盘提示中。",
                        checked = settings.showRecentCopyInMenuBar,
                    ) { value -> onSettingsChange { it.copy(showRecentCopyInMenuBar = value) } }
                    ChipBlock(
                        title = "置顶位置",
                        values = PinPosition.entries,
                        selected = settings.pinTo,
                        label = { it.label },
                        onSelect = { value -> onSettingsChange { it.copy(pinTo = value) } },
                    )
                    SwitchRow(
                        title = "显示标题",
                        checked = settings.showTitle,
                    ) { value -> onSettingsChange { it.copy(showTitle = value) } }
                    SwitchRow(
                        title = "显示页脚",
                        checked = settings.showFooter,
                    ) { value -> onSettingsChange { it.copy(showFooter = value) } }
                    // Port of `AppearanceSettingsPane`'s `OpenPreferencesWarning`, which tells
                    // the user how to get the footer back once it is hidden.
                    if (!settings.showFooter) {
                        Text(
                            text = "页脚已隐藏。按 ⌘, 打开偏好设置即可重新启用。",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    SwitchRow(
                        title = "显示十六进制色块",
                        description = "为 #0A84FF 这类颜色显示色块。",
                        checked = settings.showHexColorSwatch,
                    ) { value -> onSettingsChange { it.copy(showHexColorSwatch = value) } }
                    SwitchRow(
                        title = "显示来源应用图标",
                        description = "在列表行与预览里显示复制来源应用的图标。",
                        checked = settings.showApplicationIcons,
                    ) { value -> onSettingsChange { it.copy(showApplicationIcons = value) } }
                    SwitchRow(
                        title = "显示特殊符号",
                        description = "把换行显示为 ⏎、制表符显示为 ⇥，首尾空格显示为 ·。",
                        checked = settings.showSpecialSymbols,
                    ) { value -> onSettingsChange { it.copy(showSpecialSymbols = value) } }
                    SliderRow(
                        title = "图片最大高度",
                        valueLabel = "${settings.imageMaxHeight} pt",
                        value = settings.imageMaxHeight.toFloat(),
                        // Maccy's `AppearanceSettingsPane` allows 1…200.
                        range = 1f..200f,
                        onValueChange = { value ->
                            onSettingsChange { it.copy(imageMaxHeight = value.roundToInt()) }
                        },
                    )
                    SwitchRow(
                        title = "自动展开预览",
                        description = "选中项稳定后自动滑出预览面板。",
                        checked = settings.openPreviewAutomatically,
                    ) { value -> onSettingsChange { it.copy(openPreviewAutomatically = value) } }
                    SliderRow(
                        title = "预览延迟",
                        valueLabel = "${settings.previewDelay} 毫秒",
                        value = settings.previewDelay.toFloat(),
                        range = 200f..5_000f,
                        enabled = settings.openPreviewAutomatically,
                        onValueChange = { value ->
                            onSettingsChange { it.copy(previewDelay = value.roundToInt()) }
                        },
                    )
                    SliderRow(
                        title = "窗口宽度",
                        valueLabel = "${settings.windowWidth} pt",
                        value = settings.windowWidth.toFloat(),
                        range = 300f..900f,
                        onValueChange = { value ->
                            onSettingsChange { it.copy(windowWidth = value.roundToInt()) }
                        },
                    )
                    SliderRow(
                        title = "窗口最大高度",
                        valueLabel = "${settings.windowHeight} pt",
                        value = settings.windowHeight.toFloat(),
                        range = 300f..1_200f,
                        onValueChange = { value ->
                            onSettingsChange { it.copy(windowHeight = value.roundToInt()) }
                        },
                    )
                    SliderRow(
                        title = "预览宽度",
                        valueLabel = "${settings.previewWidth} pt（也可拖拽分隔条调整）",
                        value = settings.previewWidth.toFloat(),
                        range = 200f..900f,
                        onValueChange = { value ->
                            onSettingsChange { it.copy(previewWidth = value.roundToInt()) }
                        },
                    )

                    // ---------------------------------------------------------------- 置顶项
                    SectionTitle("置顶项")
                    if (pinnedItems.isEmpty()) {
                        Text(
                            text = "还没有置顶项目。在列表里按 ⌥P 置顶。",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    } else {
                        pinnedItems.forEach { item ->
                            PinRow(
                                item = item,
                                availablePins = availablePins(item),
                                isSelected = effectiveSelectedPin == item.id,
                                onSelect = { selectedPin = item.id },
                                onPinChange = { pin -> onPinChange(item, pin) },
                                onTitleChange = { title -> onTitleChange(item, title) },
                                onContentChange = { text -> onContentChange(item, text) },
                                onDelete = { onDeletePinned(item) },
                            )
                        }
                        Text(
                            text = "键位可自定义，别名会替换列表里显示的标题；选中一行后按 Delete 可删除。",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }

                    // ---------------------------------------------------------------- 识别
                    SectionTitle("识别")
                    SwitchRow(
                        title = "识别图片中的文字",
                        description = "使用 Vision / ML Kit 识别图片文字并作为标题（Maccy 的 OCR）。",
                        checked = settings.recognizeText,
                    ) { value -> onSettingsChange { it.copy(recognizeText = value) } }

                    // ---------------------------------------------------------------- 忽略
                    SectionTitle("忽略")
                    SwitchRow(
                        title = "暂停记录新的复制",
                        checked = settings.ignoreEvents,
                    ) { value ->
                        onSettingsChange { it.copy(ignoreEvents = value, ignoreOnlyNextEvent = false) }
                    }
                    SwitchRow(
                        title = "仅忽略下一次复制",
                        checked = settings.ignoreOnlyNextEvent,
                        enabled = settings.ignoreEvents,
                    ) { value -> onSettingsChange { it.copy(ignoreOnlyNextEvent = value) } }

                    var regexpText by remember {
                        mutableStateOf(settings.ignoredRegexp.joinToString(", "))
                    }
                    OutlinedTextField(
                        value = regexpText,
                        onValueChange = { text ->
                            regexpText = text
                            val patterns = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            onSettingsChange { it.copy(ignoredRegexp = patterns) }
                        },
                        label = { Text("忽略正则") },
                        supportingText = { Text("以逗号分隔，命中的复制内容不会被记录。") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )

                    // Port of `IgnoreApplicationsSettingsView`: the list of applications whose
                    // copies are skipped, plus the application picker that adds new ones.
                    if (supportsApplicationInfo) {
                        Text(
                            text = "忽略应用",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurface,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                        if (settings.ignoredApps.isEmpty()) {
                            Text(
                                text = "尚未添加任何应用。",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        } else {
                            settings.ignoredApps.forEach { bundleId ->
                                IgnoredApplicationRow(
                                    name = applicationName(bundleId) ?: bundleId,
                                    iconBase64 = applicationIcon(bundleId),
                                    onRemove = {
                                        onSettingsChange {
                                            it.copy(ignoredApps = it.ignoredApps - bundleId)
                                        }
                                    },
                                )
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 4.dp),
                        ) {
                            if (onPickApplication != null) {
                                TextButton(onClick = onPickApplication) { Text("添加应用…") }
                            }
                            Text(
                                text = "来自这些应用的复制不会被记录。",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    } else {
                        Text(
                            text = "当前平台无法识别复制来源的应用，因此没有忽略应用列表。",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    SwitchRow(
                        title = "仅记录上面列出的应用",
                        enabled = supportsApplicationInfo,
                        checked = settings.ignoreAllAppsExceptListed,
                    ) { value -> onSettingsChange { it.copy(ignoreAllAppsExceptListed = value) } }

                    var ignoredTypesText by remember {
                        mutableStateOf(settings.ignoredPasteboardTypes.joinToString(", "))
                    }
                    OutlinedTextField(
                        value = ignoredTypesText,
                        onValueChange = { text ->
                            ignoredTypesText = text
                            val types = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            onSettingsChange { it.copy(ignoredPasteboardTypes = types) }
                        },
                        label = { Text("忽略剪贴板类型") },
                        supportingText = {
                            Text("以逗号分隔，如 org.nspasteboard.ConcealedType；机密内容不会被记录。")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    // Port of `IgnorePasteboardTypesSettingsView`'s
                    // `Defaults.reset(.ignoredPasteboardTypes)` button.
                    TextButton(
                        onClick = {
                            ignoredTypesText =
                                AppSettings.DEFAULT_IGNORED_PASTEBOARD_TYPES.joinToString(", ")
                            onSettingsChange {
                                it.copy(
                                    ignoredPasteboardTypes = AppSettings.DEFAULT_IGNORED_PASTEBOARD_TYPES,
                                )
                            }
                        },
                    ) { Text("恢复默认类型") }

                    // ---------------------------------------------------------------- 高级
                    SectionTitle("高级")
                    SwitchRow(
                        title = "退出时清空历史",
                        description = "只清除未置顶的项目。",
                        checked = settings.clearOnQuit,
                    ) { value -> onSettingsChange { it.copy(clearOnQuit = value) } }
                    SwitchRow(
                        title = "同时清空系统剪贴板",
                        description = "清除历史时一并清空系统剪贴板。",
                        checked = settings.clearSystemClipboard,
                    ) { value -> onSettingsChange { it.copy(clearSystemClipboard = value) } }

                    // ---------------------------------------------------------------- 数据
                    SectionTitle("数据")
                    SwitchRow(
                        title = "清除时不再确认",
                        description = "对应 Maccy 的“不再提示”。",
                        checked = settings.suppressClearAlert,
                    ) { value -> onSettingsChange { it.copy(suppressClearAlert = value) } }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onClearUnpinned) { Text("清除未置顶") }
                        TextButton(onClick = onClearAll) { Text("全部清除") }
                    }
                    Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * One row of Maccy's `IgnoreApplicationsSettingsView`: the application icon, the resolved
 * application name and the button that removes it from the ignore list.
 */
@Composable
private fun IgnoredApplicationRow(
    name: String,
    iconBase64: String?,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val icon = rememberImageBitmap(iconBase64)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        } else {
            ClipperIcon(ClipperIconKind.APP, size = 18.dp, tint = colors.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            ClipperIcon(ClipperIconKind.CLEAR, size = 12.dp, tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun PinRow(
    item: ClipItem,
    availablePins: List<String>,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onPinChange: (String?) -> Unit,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    var title by remember(item.id, item.title) { mutableStateOf(item.title) }
    // `PinValueView`: only plain text entries expose an editable content field.
    val editable = item.text != null && item.imageBase64 == null && item.files.isEmpty()
    var content by remember(item.id, item.text) { mutableStateOf(item.text.orEmpty()) }

    Column(
        Modifier
            .fillMaxWidth()
            // `PinsSettingsPane`'s table selection: clicking the row makes it the target of
            // the Delete key.
            .clickable(onClick = onSelect)
            .background(
                if (isSelected) colors.primary.copy(alpha = 0.10f) else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .padding(vertical = 3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Key picker
            Box {
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, colors.outline, RoundedCornerShape(6.dp))
                        .clickable { expanded = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = (item.pin ?: "—").uppercase(),
                        fontSize = 12.sp,
                        color = colors.onSurface,
                    )
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    (listOfNotNull(item.pin) + availablePins).forEach { pin ->
                        DropdownMenuItem(
                            text = { Text(pin.uppercase(), fontSize = 12.sp) },
                            onClick = {
                                expanded = false
                                onPinChange(pin)
                            },
                        )
                    }
                }
            }

            // Alias
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, colors.outline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        onTitleChange(it)
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 12.sp,
                        color = colors.onSurface,
                    ),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                ClipperIcon(ClipperIconKind.TRASH, size = 13.dp, tint = colors.onSurfaceVariant)
            }
        }

        if (editable) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, colors.outline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = content,
                    onValueChange = {
                        content = it
                        onContentChange(it)
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 12.sp,
                        color = colors.onSurface,
                    ),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Text(
                text = "该置顶项不是纯文本，无法编辑内容。",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** Port of Maccy's `KeyboardShortcuts.Recorder` row. */
@Composable
private fun ShortcutRow(
    title: String,
    spec: ShortcutSpec,
    recording: Boolean,
    onRecord: () -> Unit,
    onReset: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(
                    width = 1.dp,
                    color = if (recording) colors.primary else colors.outline,
                    shape = RoundedCornerShape(6.dp),
                )
                .clickable(onClick = onRecord)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (recording) "按下新快捷键…" else spec.label,
                fontSize = 12.sp,
                color = if (recording) colors.primary else colors.onSurface,
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onReset),
            contentAlignment = Alignment.Center,
        ) {
            ClipperIcon(ClipperIconKind.CLEAR, size = 12.dp, tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(
        text = text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.primary,
    )
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    description: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.width(200.dp),
        )
    }
}

@Composable
private fun <T> ChipBlock(
    title: String,
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    enabled = enabled,
                    label = {
                        Text(label(value), style = MaterialTheme.typography.labelMedium)
                    },
                )
            }
        }
    }
}
