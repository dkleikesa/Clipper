package com.qcmian.clipper.feature.preferences.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.action.ClipAction
import com.qcmian.clipper.core.domain.action.modifierFlagsOf
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.HighlightMatch
import com.qcmian.clipper.core.settings.MenuIcon
import com.qcmian.clipper.core.settings.PinPosition
import com.qcmian.clipper.core.settings.PopupPosition
import com.qcmian.clipper.core.settings.SearchMode
import com.qcmian.clipper.core.settings.SearchVisibility
import com.qcmian.clipper.core.settings.ShortcutSpec
import com.qcmian.clipper.core.settings.SortBy
import com.qcmian.clipper.core.ui.ModifierFlags
import com.qcmian.clipper.core.ui.components.rememberApplicationIcon
import com.qcmian.clipper.core.ui.components.rememberApplicationName
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.shortcutCharacterOf
import kotlin.math.roundToInt

/**
 * 偏好设置窗口（通用 / 存储 / 外观 / 置顶 / 忽略 / 高级 六个分页），
 * 这里压缩成一个可滚动的对话框。
 *
 * 视觉：对话框底色用 `background`，每个分区是一张 `surface` 卡片（[SectionCard]），
 * 类似 macOS 系统设置的分组样式；主色只用于分区标题、选中态与录制态。
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
            shape = RoundedCornerShape(10.dp),
            color = colors.background,
            border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.4f)),
            modifier = Modifier.width(560.dp).heightIn(max = 680.dp),
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

/** 对话框主体，独立出来以便单独渲染与预览。 */
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

    // `KeyboardShortcuts.Recorder`：某个槽位正在录制时，所有按键都在这里被截获，
    // 而不会落到下面的文本输入框。
    var recording by remember { mutableStateOf<String?>(null) }
    // `PinsSettingsPane` 的表格选中：被选中的置顶行就是 Delete 键要删除的那一行。
    // 置顶项可能通过其它途径消失（列表里按 ⌥P、从列表删除……），
    // 因此只有当该条目仍处于置顶状态时才认可这次选中。
    var selectedPin by remember { mutableStateOf<String?>(null) }
    val effectiveSelectedPin = selectedPin?.takeIf { id -> pinnedItems.any { it.id == id } }
    val recorderFocus = remember { FocusRequester() }
    LaunchedEffect(recording) {
        if (recording != null) runCatching { recorderFocus.requestFocus() }
    }

    // 被选中的置顶行由 Delete 键删除，处理放在下面根 Column 的冒泡阶段。
    // 该处理器只在对话框持有焦点时才会收到按键，因此对话框一打开就让根 Column 取得焦点。
    LaunchedEffect(Unit) {
        runCatching { recorderFocus.requestFocus() }
    }

    val modifierKeys = remember { ModifierFlags() }
    val captureKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = { event ->
        val slot = recording
        if (slot == null) {
            false
        } else if (event.type != KeyEventType.KeyDown || modifierKeys.isModifierKey(event)) {
            // 吞掉只按修饰键的事件，让录制器继续等待。
            true
        } else if (event.key == Key.Escape) {
            // Escape 取消录制，而不是把它当作快捷键捕获。
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

    // 对应 `PinsSettingsPane.onDeleteCommand`。与上面的录制器不同，它运行在冒泡阶段，
    // 因此处于焦点的别名 / 内容输入框会先消费 Backspace/Delete，编辑文本时绝不会误删该行。
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
        // ---------------------------------------------------------------- 头部
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 14.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onBackground,
            )
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceVariant.copy(alpha = 0.5f))
                    .clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                ClipperIcon(ClipperIconKind.CLEAR, size = 13.dp, tint = colors.onSurfaceVariant)
            }
        }

        HorizontalDivider(
            thickness = 1.dp,
            color = colors.outline.copy(alpha = 0.2f),
        )

        // ---------------------------------------------------------------- 分区
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ---------------------------------------------------------------- 存储
            SectionCard("存储") {
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
                    // 允许 1…999。
                    range = 1f..999f,
                    onValueChange = { value ->
                        val size = value.roundToInt().coerceAtLeast(1)
                        onSettingsChange { it.copy(historySize = size) }
                    },
                )
                SegmentedBlock(
                    title = "排序方式",
                    values = SortBy.entries,
                    selected = settings.sortBy,
                    label = { it.label },
                    onSelect = { value -> onSettingsChange { it.copy(sortBy = value) } },
                )
            }

            // ---------------------------------------------------------------- 行为
            SectionCard("行为") {
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
                // 对应 `GeneralSettingsPane` 的「修饰键」说明，它展示当前偏好下
                // 每种动作对应的按键组合。
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
            }

            // ---------------------------------------------------------------- 快捷键
            SectionCard("快捷键") {
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
            }

            // ---------------------------------------------------------------- 搜索
            SectionCard("搜索") {
                SwitchRow(
                    title = "显示搜索框",
                    checked = settings.showSearch,
                ) { value -> onSettingsChange { it.copy(showSearch = value) } }
                SegmentedBlock(
                    title = "搜索框显示时机",
                    values = SearchVisibility.entries,
                    selected = settings.searchVisibility,
                    label = { it.label },
                    enabled = settings.showSearch,
                    onSelect = { value -> onSettingsChange { it.copy(searchVisibility = value) } },
                )
                SegmentedBlock(
                    title = "搜索模式",
                    values = SearchMode.entries,
                    selected = settings.searchMode,
                    label = { it.label },
                    onSelect = { value -> onSettingsChange { it.copy(searchMode = value) } },
                )
                SegmentedBlock(
                    title = "匹配高亮",
                    values = HighlightMatch.entries,
                    selected = settings.highlightMatch,
                    label = { it.label },
                    onSelect = { value -> onSettingsChange { it.copy(highlightMatch = value) } },
                )
            }

            // ---------------------------------------------------------------- 外观
            SectionCard("外观") {
                SegmentedBlock(
                    title = "弹窗位置",
                    values = PopupPosition.entries,
                    selected = settings.popupPosition,
                    label = { it.label },
                    onSelect = { value -> onSettingsChange { it.copy(popupPosition = value) } },
                )
                AnimatedVisibility(
                    visible = settings.popupPosition == PopupPosition.LAST_POSITION,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    TextButton(
                        onClick = onResetPosition,
                        modifier = Modifier.padding(top = 2.dp),
                    ) {
                        Text("重置弹窗位置")
                    }
                }
                // `AppearanceSettingsPane.screenPicker(for:)`：只为那两个锚定到整块屏幕的
                // 位置提供屏幕选择器，并且仅在有多块屏幕可选时才显示。
                AnimatedVisibility(
                    visible = screenCount > 1 &&
                        (settings.popupPosition == PopupPosition.SCREEN_CENTER ||
                            settings.popupPosition == PopupPosition.LAST_POSITION),
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    SegmentedBlock(
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
                SegmentedBlock(
                    title = "菜单栏图标",
                    values = MenuIcon.entries,
                    selected = settings.menuIcon,
                    label = { it.label },
                    // `AppearanceSettingsPane` 会在隐藏图标的同时禁用该选择器。
                    enabled = settings.showInStatusBar,
                    onSelect = { value -> onSettingsChange { it.copy(menuIcon = value) } },
                )
                SwitchRow(
                    title = "在菜单栏显示最近复制",
                    description = "把最新一条复制内容放到托盘提示中。",
                    checked = settings.showRecentCopyInMenuBar,
                ) { value -> onSettingsChange { it.copy(showRecentCopyInMenuBar = value) } }
                SegmentedBlock(
                    title = "置顶位置",
                    values = PinPosition.entries,
                    selected = settings.pinTo,
                    label = { it.label },
                    onSelect = { value -> onSettingsChange { it.copy(pinTo = value) } },
                )
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
                    // 允许 1…200。
                    range = 1f..200f,
                    onValueChange = { value ->
                        onSettingsChange { it.copy(imageMaxHeight = value.roundToInt()) }
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
            }

            // ---------------------------------------------------------------- 置顶项
            SectionCard("置顶项") {
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
            }

            // ---------------------------------------------------------------- 识别
            SectionCard("识别") {
                SwitchRow(
                    title = "识别图片中的文字",
                    description = "使用 Vision / ML Kit 识别图片文字并作为标题。",
                    checked = settings.recognizeText,
                ) { value -> onSettingsChange { it.copy(recognizeText = value) } }
            }

            // ---------------------------------------------------------------- 忽略
            SectionCard("忽略") {
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

                // 对应 `IgnoreApplicationsSettingsView`：列出复制内容会被跳过的应用，
                // 以及用于添加新条目的应用选择器。
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
                                name = rememberApplicationName(applicationName, bundleId) ?: bundleId,
                                iconBase64 = rememberApplicationIcon(applicationIcon, bundleId),
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
                // 对应 `IgnorePasteboardTypesSettingsView` 的
                // `Defaults.reset(.ignoredPasteboardTypes)` 按钮。
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
            }

            // ---------------------------------------------------------------- 高级
            SectionCard("高级") {
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
            }

            // ---------------------------------------------------------------- 数据
            SectionCard("数据") {
                SwitchRow(
                    title = "清除时不再确认",
                    description = "清除历史时不再弹出确认框。",
                    checked = settings.suppressClearAlert,
                ) { value -> onSettingsChange { it.copy(suppressClearAlert = value) } }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    // 清除是破坏性操作，按钮文字用 error 色与普通操作区分。
                    TextButton(onClick = onClearUnpinned) {
                        Text("清除未置顶", color = colors.error)
                    }
                    TextButton(onClick = onClearAll) {
                        Text("全部清除", color = colors.error)
                    }
                }
            }
        }
    }
}
