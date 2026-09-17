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
import androidx.compose.ui.input.key.KeyEvent
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.action.ClipAction
import com.qcmian.clipper.core.domain.action.modifierFlagsOf
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.HighlightMatch
import com.qcmian.clipper.core.settings.PinPosition
import com.qcmian.clipper.core.settings.PopupPosition
import com.qcmian.clipper.core.settings.SearchMode
import com.qcmian.clipper.core.settings.ShortcutSpec
import com.qcmian.clipper.core.settings.SortBy
import com.qcmian.clipper.core.settings.ThemeMode
import com.qcmian.clipper.core.ui.ModifierFlags
import com.qcmian.clipper.core.ui.components.HoverTooltip
import com.qcmian.clipper.core.ui.components.rememberApplicationIcon
import com.qcmian.clipper.core.ui.components.rememberApplicationName
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.shortcutCharacterOf
import com.qcmian.clipper.core.ui.theme.hintColor
import kotlin.math.roundToInt

/**
 * 渲染偏好设置对话框所需的只读数据。
 * 收拢为一个对象，避免十几份参数在 [PreferencesDialog] 与内容层之间重复转发。
 */
data class PreferencesUiData(
    val settings: AppSettings,
    val pinnedItems: List<ClipItem>,
    val storageSize: String?,
    /** 未置顶条目当前的近似占用，用于和「历史上限」对照显示。 */
    val historyBytes: Long,
    val screenCount: Int,
    val supportsLaunchAtLogin: Boolean,
    val supportsApplicationInfo: Boolean,
    val supportsTextRecognition: Boolean,
)

/** 偏好设置对话框的全部上行动作。 */
data class PreferencesActions(
    val onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    val availablePins: (ClipItem) -> List<String>,
    val onPinChange: (ClipItem, String?) -> Unit,
    val onTitleChange: (ClipItem, String) -> Unit,
    val onContentChange: (ClipItem, String) -> Unit,
    val onDeletePinned: (ClipItem) -> Unit,
    val onClearUnpinned: () -> Unit,
    val onClearAll: () -> Unit,
    val onDismiss: () -> Unit,
    val applicationName: (String) -> String?,
    val applicationIcon: (String?) -> String?,
    val onPickApplication: (() -> Unit)?,
)

/**
 * 正在录制快捷键的槽位。用枚举而非字符串，`when` 全覆盖、无兜底分支，
 * 不会因为拼错槽位名把快捷键静默写进别的设置。
 */
internal enum class ShortcutSlot { POPUP, PIN, DELETE, TOGGLE_PREVIEW }

/** 设置卡片的设计宽度。 */
private val CardDesignWidth = 560.dp

/** 设置卡片的高度上限。 */
private val CardMaxHeight = 680.dp

/**
 * 卡片与窗口边缘之间的留白：**必须大于 0**，且卡片不能碰到窗口边缘。
 *
 * 桌面端的 `Dialog` 是**同一窗口内的 layer**，`layer.boundsInWindow` 恰好等于内容尺寸，
 * 因此这圈留白有三层作用：
 * - 卡片之外的区域属于「layer 之外」，点击它会触发 `dismissOnClickOutside`（点卡片外面关闭）
 *   ——卡片一旦铺满窗口，这条关闭路径就消失了；
 * - 内容超出窗口的部分**既不绘制也收不到点击**（卡片是垂直居中的，比窗口高时右上角会跑到窗口外），
 *   留白保证卡片始终完整落在窗口内；
 * - 右上角的关闭按钮不会贴着窗口边缘。
 */
private val CardMargin = 12.dp

/**
 * 偏好设置窗口（存储 / 行为 / 快捷键 / 搜索 / 外观 / 置顶 / 识别 / 忽略 / 高级 / 数据 / 重置），
 * 这里压缩成一个可滚动的对话框。
 *
 * 视觉：对话框底色用 `background`，每个分区是一张 `surface` 卡片（[SectionCard]），
 * 类似 macOS 系统设置的分组样式；主色只用于分区标题、选中态与录制态。
 *
 * 宽度与高度都夹在窗口尺寸之内（见 [CardMargin]）。`usePlatformDefaultWidth = false` 是必需的：
 * 默认值（`true`）会按「窗口宽高中较小者」把内容最大宽度档位化（≥600dp→580dp、≥480dp→440dp、
 * 否则 320dp），而面板是自动高度的，改任何影响行高的设置都会让窗口高度跨档、对话框宽度跟着跳。
 */
@Composable
fun PreferencesDialog(
    data: PreferencesUiData,
    actions: PreferencesActions,
) {
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val maxCardWidth = (with(density) { windowSize.width.toDp() } - CardMargin * 2)
        .coerceAtLeast(1.dp)
        .coerceAtMost(CardDesignWidth)
    val maxCardHeight = (with(density) { windowSize.height.toDp() } - CardMargin * 2)
        .coerceAtLeast(1.dp)
        .coerceAtMost(CardMaxHeight)
    Dialog(
        onDismissRequest = actions.onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.background,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)),
            modifier = Modifier.width(maxCardWidth).heightIn(max = maxCardHeight),
        ) {
            PreferencesContent(data, actions)
        }
    }
}

/** 对话框主体：头部 + 可滚动的分区列表。 */
@Composable
private fun PreferencesContent(
    data: PreferencesUiData,
    actions: PreferencesActions,
) {
    val colors = MaterialTheme.colorScheme

    // `KeyboardShortcuts.Recorder`：某个槽位正在录制时，所有按键都在这里被截获，
    // 而不会落到下面的文本输入框。
    var recording by remember { mutableStateOf<ShortcutSlot?>(null) }
    // `PinsSettingsPane` 的表格选中：被选中的置顶行就是 Delete 键要删除的那一行。
    // 置顶项可能通过其它途径消失（列表里按 ⌥P、从列表删除……），
    // 因此只有当该条目仍处于置顶状态时才认可这次选中。
    var selectedPin by remember { mutableStateOf<String?>(null) }
    val effectiveSelectedPin = selectedPin?.takeIf { id -> data.pinnedItems.any { it.id == id } }
    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(recording) {
        if (recording != null) runCatching { rootFocus.requestFocus() }
    }

    // 对话框一打开就让根 Column 取得焦点：按键处理器只在对话框持有焦点时才会收到按键。
    LaunchedEffect(Unit) {
        runCatching { rootFocus.requestFocus() }
    }

    val modifierKeys = remember { ModifierFlags() }
    val captureKey: (KeyEvent) -> Boolean = { event ->
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
                actions.onSettingsChange { current ->
                    when (slot) {
                        ShortcutSlot.POPUP -> current.copy(popupShortcut = spec)
                        ShortcutSlot.PIN -> current.copy(pinShortcut = spec)
                        ShortcutSlot.DELETE -> current.copy(deleteShortcut = spec)
                        ShortcutSlot.TOGGLE_PREVIEW -> current.copy(togglePreviewShortcut = spec)
                    }
                }
            }
            recording = null
            true
        }
    }

    // 对应 `PinsSettingsPane.onDeleteCommand`。与上面的录制器不同，它运行在冒泡阶段，
    // 因此处于焦点的别名 / 内容输入框会先消费 Backspace/Delete，编辑文本时绝不会误删该行。
    val deleteSelectedPin: (KeyEvent) -> Boolean = { event ->
        val id = effectiveSelectedPin
        if (id != null &&
            event.type == KeyEventType.KeyDown &&
            (event.key == Key.Delete || event.key == Key.Backspace)
        ) {
            data.pinnedItems.firstOrNull { it.id == id }?.let(actions.onDeletePinned)
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
            .focusRequester(rootFocus)
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
            HoverTooltip("关闭设置") {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceVariant.copy(alpha = 0.5f))
                        .clickable(onClick = actions.onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    ClipperIcon(ClipperIconKind.CLEAR, size = 13.dp, tint = colors.onSurfaceVariant)
                }
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
            StorageSection(data, actions)
            BehaviorSection(data, actions)
            ShortcutsSection(
                data = data,
                actions = actions,
                recording = recording,
                onRecord = { slot -> recording = slot },
            )
            SearchSection(data, actions)
            AppearanceSection(data, actions)
            PinnedItemsSection(
                data = data,
                actions = actions,
                selectedPinId = effectiveSelectedPin,
                onSelectPin = { id -> selectedPin = id },
            )
            RecognitionSection(data, actions)
            IgnoreSection(data, actions)
            DataSection(data, actions)
            ResetSection(data, actions)
        }
    }
}

// ---------------------------------------------------------------------------------
// 分区
// ---------------------------------------------------------------------------------

@Composable
private fun StorageSection(data: PreferencesUiData, actions: PreferencesActions) {
    val colors = MaterialTheme.colorScheme
    val settings = data.settings
    SectionCard("存储") {
        SwitchRow(
            title = "保存文本",
            checked = settings.saveText,
        ) { value -> actions.onSettingsChange { it.copy(saveText = value) } }
        SwitchRow(
            title = "保存图片",
            checked = settings.saveImages,
        ) { value -> actions.onSettingsChange { it.copy(saveImages = value) } }
        SwitchRow(
            title = "保存文件",
            checked = settings.saveFiles,
        ) { value -> actions.onSettingsChange { it.copy(saveFiles = value) } }
        HistoryLimitField(
            maxSizeBytes = settings.historyMaxSizeBytes,
            usageBytes = data.historyBytes,
            storageSize = data.storageSize,
            onSizeChange = { bytes ->
                actions.onSettingsChange { it.copy(historyMaxSizeBytes = bytes) }
            },
        )
        SegmentedBlock(
            title = "排序方式",
            values = SortBy.entries,
            selected = settings.sortBy,
            label = { it.label },
            onSelect = { value -> actions.onSettingsChange { it.copy(sortBy = value) } },
        )
    }
}

/**
 * 历史上限输入框允许的最大 MB 数（1 TB）。再大既没有意义，也会让 [AppSettings.BYTES_PER_MEGABYTE]
 * 的相乘溢出成负数——而负数上限在仓库里等于「不裁剪」，反而把限制取消掉。
 */
private const val MAX_HISTORY_MEGABYTES = 1_048_576L

/**
 * 历史上限输入框：用户按 MB 填写目标尺寸，超过后由仓库按当前排序丢弃最旧的未置顶记录。
 *
 * 草稿以文本形式保存，这样用户清空重输时的中间状态（空串、半截数字）不会被弹回；
 * 只有解析出 1…[MAX_HISTORY_MEGABYTES] 的整数时才写回偏好。设置被外部修改时
 * （如重置默认值）草稿会跟随刷新。
 */
@Composable
private fun HistoryLimitField(
    maxSizeBytes: Long,
    usageBytes: Long,
    storageSize: String?,
    onSizeChange: (Long) -> Unit,
) {
    var draft by remember { mutableStateOf(megabytesTextOf(maxSizeBytes)) }
    var committed by remember { mutableStateOf(maxSizeBytes) }

    LaunchedEffect(maxSizeBytes) {
        if (maxSizeBytes != committed) {
            draft = megabytesTextOf(maxSizeBytes)
            committed = maxSizeBytes
        }
    }

    val databaseText = storageSize?.takeIf { it.isNotEmpty() }?.let { " · 存储文件 $it" }.orEmpty()
    OutlinedTextField(
        value = draft,
        onValueChange = { text ->
            if (text.any { !it.isDigit() }) return@OutlinedTextField
            draft = text
            val megabytes = text.toLongOrNull() ?: return@OutlinedTextField
            // 超出上限的输入直接丢弃（连同草稿），避免相乘溢出把限制变成负数。
            if (megabytes !in 1..MAX_HISTORY_MEGABYTES) return@OutlinedTextField
            val bytes = megabytes * AppSettings.BYTES_PER_MEGABYTE
            if (bytes == committed) return@OutlinedTextField
            committed = bytes
            onSizeChange(bytes)
        },
        label = { Text("历史上限（MB）") },
        supportingText = {
            Text(
                "当前占用 ${formatMegabytes(usageBytes)}$databaseText；" +
                    "超出后按排序丢弃最旧的未置顶记录，置顶项不计入。",
                color = MaterialTheme.hintColor,
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

/** 以 MB 为单位的整数文本；不足 1 MB 时按 1 MB 显示，避免输入框出现空串。 */
private fun megabytesTextOf(bytes: Long): String =
    (bytes / AppSettings.BYTES_PER_MEGABYTE).coerceAtLeast(1L).toString()

/** 把字节数格式化为 `3.2 MB` 这样的文本。 */
private fun formatMegabytes(bytes: Long): String {
    val tenths = bytes * 10 / AppSettings.BYTES_PER_MEGABYTE
    return if (tenths % 10L == 0L) "${tenths / 10} MB" else "${tenths / 10}.${tenths % 10} MB"
}

@Composable
private fun BehaviorSection(data: PreferencesUiData, actions: PreferencesActions) {
    val settings = data.settings
    SectionCard("行为") {
        SwitchRow(
            title = "自动粘贴",
            description = "选中项目后直接粘贴到上一个应用。",
            checked = settings.pasteByDefault,
        ) { value -> actions.onSettingsChange { it.copy(pasteByDefault = value) } }
        SwitchRow(
            title = "粘贴时去除格式",
            description = "只保留纯文本。",
            checked = settings.removeFormattingByDefault,
        ) { value -> actions.onSettingsChange { it.copy(removeFormattingByDefault = value) } }
        // 对应 `GeneralSettingsPane` 的「修饰键」说明，它展示当前偏好下
        // 每种动作对应的按键组合。
        Text(
            text = "按 ${modifierFlagsOf(ClipAction.COPY, settings)} 复制，" +
                "${modifierFlagsOf(ClipAction.PASTE, settings)} 粘贴，" +
                "${modifierFlagsOf(ClipAction.PASTE_WITHOUT_FORMATTING, settings)} 粘贴并去除格式。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.hintColor,
            modifier = Modifier.padding(top = 2.dp),
        )
        SliderRow(
            title = "剪贴板检查间隔",
            valueLabel = "${settings.clipboardCheckIntervalMillis} 毫秒",
            value = settings.clipboardCheckIntervalMillis.toFloat(),
            range = 100f..2_000f,
            onValueChange = { value ->
                actions.onSettingsChange {
                    it.copy(clipboardCheckIntervalMillis = value.roundToInt())
                }
            },
        )
        if (data.supportsLaunchAtLogin) {
            SwitchRow(
                title = "开机时启动",
                description = "登录后自动运行 Clipper。",
                checked = settings.launchAtLogin,
            ) { value -> actions.onSettingsChange { it.copy(launchAtLogin = value) } }
        }
    }
}

@Composable
private fun ShortcutsSection(
    data: PreferencesUiData,
    actions: PreferencesActions,
    recording: ShortcutSlot?,
    onRecord: (ShortcutSlot) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val settings = data.settings
    SectionCard("快捷键") {
        ShortcutRow(
            title = "呼出面板",
            spec = settings.popupShortcut,
            recording = recording == ShortcutSlot.POPUP,
            onRecord = { onRecord(ShortcutSlot.POPUP) },
            onReset = {
                actions.onSettingsChange { it.copy(popupShortcut = AppSettings().popupShortcut) }
            },
        )
        ShortcutRow(
            title = "置顶 / 取消置顶",
            spec = settings.pinShortcut,
            recording = recording == ShortcutSlot.PIN,
            onRecord = { onRecord(ShortcutSlot.PIN) },
            onReset = {
                actions.onSettingsChange { it.copy(pinShortcut = AppSettings().pinShortcut) }
            },
        )
        ShortcutRow(
            title = "删除选中项",
            spec = settings.deleteShortcut,
            recording = recording == ShortcutSlot.DELETE,
            onRecord = { onRecord(ShortcutSlot.DELETE) },
            onReset = {
                actions.onSettingsChange { it.copy(deleteShortcut = AppSettings().deleteShortcut) }
            },
        )
        ShortcutRow(
            title = "显示 / 隐藏预览",
            spec = settings.togglePreviewShortcut,
            recording = recording == ShortcutSlot.TOGGLE_PREVIEW,
            onRecord = { onRecord(ShortcutSlot.TOGGLE_PREVIEW) },
            onReset = {
                actions.onSettingsChange {
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
            color = if (recording != null) colors.primary else MaterialTheme.hintColor,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun SearchSection(data: PreferencesUiData, actions: PreferencesActions) {
    val settings = data.settings
    SectionCard("搜索") {
        SwitchRow(
            title = "显示搜索框",
            checked = settings.showSearch,
        ) { value -> actions.onSettingsChange { it.copy(showSearch = value) } }
        SegmentedBlock(
            title = "搜索模式",
            values = SearchMode.entries,
            selected = settings.searchMode,
            label = { it.label },
            onSelect = { value -> actions.onSettingsChange { it.copy(searchMode = value) } },
        )
        SegmentedBlock(
            title = "匹配高亮",
            values = HighlightMatch.entries,
            selected = settings.highlightMatch,
            label = { it.label },
            onSelect = { value -> actions.onSettingsChange { it.copy(highlightMatch = value) } },
        )
    }
}

@Composable
private fun AppearanceSection(data: PreferencesUiData, actions: PreferencesActions) {
    val colors = MaterialTheme.colorScheme
    val settings = data.settings
    SectionCard("外观") {
        // 拖动过窗口边缘、或拖过预览分隔条之后出现：点一下放弃自定义尺寸与预览宽度，恢复
        // 「自动贴合内容」。两者必须一起还原——预览宽度是窗口里分出去的一段，只把主列表宽度
        // 恢复成默认、留着拖出来的预览宽度，窗口会比默认状态宽（或窄）出预览让位的那一截。
        AnimatedVisibility(
            visible = settings.customWindowWidth != null ||
                settings.previewWidth != AppSettings.DEFAULT_PREVIEW_WIDTH,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            TextButton(
                onClick = {
                    actions.onSettingsChange {
                        it.copy(
                            customWindowWidth = null,
                            customWindowHeight = null,
                            previewWidth = AppSettings.DEFAULT_PREVIEW_WIDTH,
                        )
                    }
                },
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text("恢复默认尺寸")
            }
        }
        SegmentedBlock(
            title = "主题",
            values = ThemeMode.entries,
            selected = settings.themeMode,
            label = { it.label },
            onSelect = { value -> actions.onSettingsChange { it.copy(themeMode = value) } },
        )
        SegmentedBlock(
            title = "弹窗位置",
            values = PopupPosition.entries,
            selected = settings.popupPosition,
            label = { it.label },
            onSelect = { value -> actions.onSettingsChange { it.copy(popupPosition = value) } },
        )
        // `AppearanceSettingsPane.screenPicker(for:)`：只为锚定到整块屏幕的位置提供屏幕选择器，
        // 并且仅在有多块屏幕可选时才显示。
        AnimatedVisibility(
            visible = data.screenCount > 1 &&
                settings.popupPosition == PopupPosition.SCREEN_CENTER,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            SegmentedBlock(
                title = "弹窗屏幕",
                values = (0 until data.screenCount).toList(),
                selected = settings.popupScreen.coerceIn(0, data.screenCount - 1),
                label = { index -> if (index == 0) "活动屏幕" else "屏幕 ${index + 1}" },
                onSelect = { value -> actions.onSettingsChange { it.copy(popupScreen = value) } },
            )
        }
        SwitchRow(
            title = "显示菜单栏图标",
            description = "关闭后 Clipper 只在快捷键下工作。",
            checked = settings.showInStatusBar,
        ) { value -> actions.onSettingsChange { it.copy(showInStatusBar = value) } }
        SegmentedBlock(
            title = "置顶位置",
            values = PinPosition.entries,
            selected = settings.pinTo,
            label = { it.label },
            onSelect = { value -> actions.onSettingsChange { it.copy(pinTo = value) } },
        )
        SwitchRow(
            title = "显示十六进制色块",
            description = "为 #0A84FF 这类颜色显示色块。",
            checked = settings.showHexColorSwatch,
        ) { value -> actions.onSettingsChange { it.copy(showHexColorSwatch = value) } }
        SwitchRow(
            title = "显示来源应用图标",
            description = "在列表行与预览里显示复制来源应用的图标。",
            checked = settings.showApplicationIcons,
        ) { value -> actions.onSettingsChange { it.copy(showApplicationIcons = value) } }
        SwitchRow(
            title = "显示特殊符号",
            description = "把换行显示为 ⏎、制表符显示为 ⇥，首尾空格显示为 ·。",
            checked = settings.showSpecialSymbols,
        ) { value -> actions.onSettingsChange { it.copy(showSpecialSymbols = value) } }
        SliderRow(
            title = "图片最大高度",
            valueLabel = "${settings.imageMaxHeight} pt",
            value = settings.imageMaxHeight.toFloat(),
            // 允许 1…200。
            range = 1f..200f,
            onValueChange = { value ->
                actions.onSettingsChange { it.copy(imageMaxHeight = value.roundToInt()) }
            },
        )
    }
}

@Composable
private fun PinnedItemsSection(
    data: PreferencesUiData,
    actions: PreferencesActions,
    selectedPinId: String?,
    onSelectPin: (String) -> Unit,
) {
    SectionCard("置顶项") {
        if (data.pinnedItems.isEmpty()) {
            Text(
                text = "还没有置顶项目。在列表里按 ⌥P 置顶。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.hintColor,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        } else {
            data.pinnedItems.forEach { item ->
                PinRow(
                    item = item,
                    availablePins = actions.availablePins(item),
                    isSelected = selectedPinId == item.id,
                    onSelect = { onSelectPin(item.id) },
                    onPinChange = { pin -> actions.onPinChange(item, pin) },
                    onTitleChange = { title -> actions.onTitleChange(item, title) },
                    onContentChange = { text -> actions.onContentChange(item, text) },
                    onDelete = { actions.onDeletePinned(item) },
                )
            }
            Text(
                text = "键位可自定义，别名会替换列表里显示的标题；选中一行后按 Delete 可删除。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.hintColor,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun RecognitionSection(data: PreferencesUiData, actions: PreferencesActions) {
    val settings = data.settings
    val supported = data.supportsTextRecognition
    SectionCard("识别") {
        SwitchRow(
            title = "识别图片中的文字",
            description = if (supported) {
                "使用 Vision / ML Kit 识别图片文字，并作为纯图片条目的标题。"
            } else {
                "当前平台不支持图片文字识别。"
            },
            checked = settings.recognizeText,
            enabled = supported,
        ) { value -> actions.onSettingsChange { it.copy(recognizeText = value) } }
    }
}

@Composable
private fun IgnoreSection(data: PreferencesUiData, actions: PreferencesActions) {
    val colors = MaterialTheme.colorScheme
    val settings = data.settings
    SectionCard("忽略") {
        SwitchRow(
            title = "暂停记录新的复制",
            checked = settings.ignoreEvents,
        ) { value -> actions.onSettingsChange { it.copy(ignoreEvents = value) } }

        DelimitedListField(
            values = settings.ignoredRegexp,
            onValuesChange = { patterns ->
                actions.onSettingsChange { it.copy(ignoredRegexp = patterns) }
            },
            label = "忽略正则",
            supportingText = "以逗号分隔，命中的复制内容不会被记录。",
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )

        // 对应 `IgnoreApplicationsSettingsView`：列出复制内容会被跳过的应用，
        // 以及用于添加新条目的应用选择器。
        if (data.supportsApplicationInfo) {
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
                    color = MaterialTheme.hintColor,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } else {
                settings.ignoredApps.forEach { bundleId ->
                    IgnoredApplicationRow(
                        name = rememberApplicationName(actions.applicationName, bundleId) ?: bundleId,
                        iconBase64 = rememberApplicationIcon(actions.applicationIcon, bundleId),
                        onRemove = {
                            actions.onSettingsChange {
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
                if (actions.onPickApplication != null) {
                    TextButton(onClick = actions.onPickApplication) { Text("添加应用…") }
                }
                Text(
                    text = "来自这些应用的复制不会被记录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.hintColor,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        } else {
            Text(
                text = "当前平台无法识别复制来源的应用，因此没有忽略应用列表。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.hintColor,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        SwitchRow(
            title = "仅记录上面列出的应用",
            enabled = data.supportsApplicationInfo,
            checked = settings.ignoreAllAppsExceptListed,
        ) { value -> actions.onSettingsChange { it.copy(ignoreAllAppsExceptListed = value) } }

        DelimitedListField(
            values = settings.ignoredPasteboardTypes,
            onValuesChange = { types ->
                actions.onSettingsChange { it.copy(ignoredPasteboardTypes = types) }
            },
            label = "忽略剪贴板类型",
            supportingText = "以逗号分隔，如 org.nspasteboard.ConcealedType；机密内容不会被记录。",
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        // 对应 `IgnorePasteboardTypesSettingsView` 的
        // `Defaults.reset(.ignoredPasteboardTypes)` 按钮；文本框会跟随这次外部修改自动刷新。
        TextButton(
            onClick = {
                actions.onSettingsChange {
                    it.copy(ignoredPasteboardTypes = AppSettings.DEFAULT_IGNORED_PASTEBOARD_TYPES)
                }
            },
        ) { Text("恢复默认类型") }
    }
}

@Composable
private fun DataSection(data: PreferencesUiData, actions: PreferencesActions) {
    val colors = MaterialTheme.colorScheme
    val settings = data.settings
    SectionCard("数据") {
        SwitchRow(
            title = "退出时清空历史",
            description = "只清除未置顶的项目。",
            checked = settings.clearOnQuit,
        ) { value -> actions.onSettingsChange { it.copy(clearOnQuit = value) } }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            // 清除是破坏性操作，按钮文字用 error 色与普通操作区分。
            TextButton(onClick = actions.onClearUnpinned) {
                Text("清除未置顶", color = colors.error)
            }
            TextButton(onClick = actions.onClearAll) {
                Text("全部清除", color = colors.error)
            }
        }
    }
}

/**
 * 一键把全部偏好恢复为出厂默认值。
 *
 * 单项还原各自已有入口（快捷键、窗口尺寸、忽略类型）；这里是唯一的「全部还原」入口。
 * 它刻意不新增任何旁路：[onSettingsChange][PreferencesActions.onSettingsChange] 就是其它所有
 * 修改走的同一条路径，因此标题重算、丢弃不再收集的内容类型、窗口几何 / 托盘 / 热键重注册
 * 这些副作用，全部交给既有的观察者处理。
 *
 * 重置只覆盖设置：历史、置顶项与用户改过的别名都不属于设置，不会被它影响。
 */
@Composable
private fun ResetSection(data: PreferencesUiData, actions: PreferencesActions) {
    val isDefault = data.settings == AppSettings()
    SectionCard("重置") {
        TextButton(
            onClick = { actions.onSettingsChange { AppSettings() } },
            enabled = !isDefault,
        ) {
            Text("恢复默认设置")
        }
        Text(
            text = if (isDefault) {
                "当前所有设置都已是默认值。"
            } else {
                "把所有设置恢复为默认值；历史与置顶项目不受影响。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.hintColor,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
