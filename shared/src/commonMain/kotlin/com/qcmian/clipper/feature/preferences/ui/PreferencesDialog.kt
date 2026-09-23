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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qcmian.clipper.core.domain.action.ClipAction
import com.qcmian.clipper.core.domain.action.modifierFlagsOf
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.HighlightMatch
import com.qcmian.clipper.core.settings.PinPosition
import com.qcmian.clipper.core.settings.PopupPosition
import com.qcmian.clipper.core.settings.ShortcutSlot
import com.qcmian.clipper.core.settings.ThemeMode
import com.qcmian.clipper.core.settings.shortcut
import com.qcmian.clipper.core.settings.withShortcut
import com.qcmian.clipper.core.ui.components.HoverTooltip
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.feature.history.ui.components.HistoryFilterBar
import com.qcmian.clipper.feature.preferences.state.ShortcutRecording
import kotlin.math.roundToInt

/**
 * 渲染偏好设置对话框所需的只读数据。
 * 收拢为一个对象，避免十几份参数在 [PreferencesDialog] 与内容层之间重复转发。
 */
data class PreferencesUiData(
    val settings: AppSettings,
    /** 数据库文件占用的字节数；平台测不到时为 `null`（此时只显示条数）。 */
    val storageBytes: Long?,
    /** 未置顶条目的条数，用于和「历史上限」对照显示。 */
    val historyCount: Int,
    val screenCount: Int,
    val supportsLaunchAtLogin: Boolean,
    val supportsTextRecognition: Boolean,
    /**
     * 正在录制的快捷键。
     *
     * 状态机不在界面里（见 `ShortcutRecorder`）：对话框只渲染它、把按键转发回去，
     * 关闭对话框时也不该把它忘掉——宿主正靠它让出系统级热键。
     */
    val shortcutRecording: ShortcutRecording = ShortcutRecording(),
)

/** 偏好设置对话框的全部上行动作。 */
data class PreferencesActions(
    val onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    val onClearUnpinned: () -> Unit,
    val onClearAll: () -> Unit,
    val onDismiss: () -> Unit,
    /** 开始录制某个槽位的快捷键。 */
    val onStartShortcutRecording: (ShortcutSlot) -> Unit,
    /**
     * 结束录制（对话框离开屏幕时调用）。
     *
     * 录制期间宿主的系统级热键是停着的，不收回它就会一直哑着。用户按 Esc 取消走的不是这里
     * ——那次按键先被录制器接住。
     */
    val onCancelShortcutRecording: () -> Unit,
    /**
     * 录制期间按下的按键；返回 `true` 表示已被录制器消费。
     *
     * 它直接就是 `ClipboardViewModel.captureShortcutKey`，而不是走一次单向动作：录制器要读
     * **最新**状态、返回值也要同帧拿到，否则按键会晚一帧才被拦住。
     */
    val onShortcutKeyEvent: (KeyEvent) -> Boolean = { false },
)

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
 * 偏好设置窗口（存储与数据 / 行为 / 快捷键 / 搜索 / 外观 / AI服务 / 重置），
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

    // 录制状态机在 `ShortcutRecorder` 里（viewmodel 层），这里只读它：哪个槽位在录、
    // 上一次为什么被拒。
    val recording = data.shortcutRecording

    val rootFocus = remember { FocusRequester() }

    // 对话框一打开就让根 Column 取得焦点：按键处理器只在对话框持有焦点时才会收到按键。
    // 开始录制时再要一次：用户点的那一行会先把焦点带走，不抢回来录制就收不到按键。
    LaunchedEffect(recording.slot) {
        runCatching { rootFocus.requestFocus() }
    }

    // 对话框离开屏幕时收回录制态：录制期间宿主的系统级热键是停着的，不收回它会一直哑着。
    // 用 `rememberUpdatedState` 取最新回调：`DisposableEffect(Unit)` 只在退出时执行一次，
    // 直接捕获 `actions` 会留下最初那一份引用。
    val cancelRecording by rememberUpdatedState(actions.onCancelShortcutRecording)
    DisposableEffect(Unit) { onDispose { cancelRecording() } }

    // `KeyboardShortcuts.Recorder`：某个槽位正在录制时，所有按键都在这一步被截获，而不会落到
    // 下面的文本输入框。转发给录制器由它判断——没在录制时它返回 `false`，按键照常往下走。
    val captureKey: (KeyEvent) -> Boolean = actions.onShortcutKeyEvent

    Column(
        Modifier
            .fillMaxWidth()
            .onPreviewKeyEvent(captureKey)
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
            ShortcutsSection(data, actions)
            SearchSection(data, actions)
            AppearanceSection(data, actions)
            RecognitionSection(data, actions)
            ResetSection(data, actions)
        }
    }
}

// ---------------------------------------------------------------------------------
// 分区
// ---------------------------------------------------------------------------------

/** 原「数据」分区的开关表；该分区已整体并入「存储与数据」。 */
private val DataSwitches = listOf(
    BooleanSetting(
        "退出时清空历史",
        { it.clearOnQuit },
        { value -> copy(clearOnQuit = value) },
        description = "只清除未置顶的项目。",
    ),
)

@Composable
private fun StorageSection(data: PreferencesUiData, actions: PreferencesActions) {
    val colors = MaterialTheme.colorScheme
    val settings = data.settings
    SectionCard("存储与数据") {
        HistoryLimitField(
            maxCount = settings.historyMaxCount,
            usageCount = data.historyCount,
            databaseBytes = data.storageBytes,
            onCountChange = { count ->
                actions.onSettingsChange { it.copy(historyMaxCount = count) }
            },
        )
        // 原「数据」分区的开关与清除入口：改的都是存储里的内容，与上面同属一类。
        SwitchSettings(settings, DataSwitches, actions.onSettingsChange)
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
 * 历史上限输入框：用户按条数填写目标，超过后由仓库按当前排序丢弃最旧的未置顶记录。
 *
 * 条数**不设上限**：只校验「正整数」，没有最大值。`toIntOrNull()` 顺带挡掉超出 `Int` 范围的
 * 输入（返回 `null` 即丢弃），因此不需要额外的溢出保护。草稿以文本形式保存，这样用户清空
 * 重输时的中间状态（空串、半截数字）不会被弹回；设置被外部修改时（如重置默认值）草稿跟随刷新。
 *
 * 输入框下方始终显示当前条数与数据库文件大小：前者是上限的直接对象，后者是用户真正关心的磁盘
 * 占用（含置顶项与索引，删除后由空闲页回收 / 压紧负责让它跟着降）。
 */
@Composable
private fun HistoryLimitField(
    maxCount: Int,
    usageCount: Int,
    databaseBytes: Long?,
    onCountChange: (Int) -> Unit,
) {
    var draft by remember { mutableStateOf(maxCount.toString()) }
    var committed by remember { mutableStateOf(maxCount) }

    LaunchedEffect(maxCount) {
        if (maxCount != committed) {
            draft = maxCount.toString()
            committed = maxCount
        }
    }

    val databaseText = databaseBytes?.let { " · 数据库 ${formatSize(it)}" }.orEmpty()
    OutlinedTextField(
        value = draft,
        onValueChange = { text ->
            if (text.any { !it.isDigit() }) return@OutlinedTextField
            draft = text
            val count = text.toIntOrNull() ?: return@OutlinedTextField
            if (count < 1 || count == committed) return@OutlinedTextField
            committed = count
            onCountChange(count)
        },
        label = { Text("历史上限（条）") },
        supportingText = {
            Text(
                "当前 $usageCount 条$databaseText；超出后按排序丢弃最旧的未置顶记录，置顶项不占额度。",
                color = MaterialTheme.hintColor,
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

/**
 * 把字节数格式化为带一位小数的可读文本：`512 B` / `912.3 KB` / `4.1 MB` / `1.2 GB`。
 *
 * 分级是必要的：库刚建好时只有几十 KB，只按 MB 显示会变成 `0 MB`。整数毫不出小数部分时省掉
 * `.0`（写 `1 MB` 而不是 `1.0 MB`）。
 */
private fun formatSize(bytes: Long): String = when {
    bytes < KILOBYTE -> "$bytes B"
    bytes < MEGABYTE -> scaledBy(bytes, KILOBYTE, "KB")
    bytes < GIGABYTE -> scaledBy(bytes, MEGABYTE, "MB")
    else -> scaledBy(bytes, GIGABYTE, "GB")
}

/** [bytes] 按 [unit] 换算成一位小数。 */
private fun scaledBy(bytes: Long, unit: Long, suffix: String): String {
    val tenths = bytes * 10 / unit
    return if (tenths % 10L == 0L) "${tenths / 10} $suffix" else "${tenths / 10}.${tenths % 10} $suffix"
}

private const val KILOBYTE = 1024L
private const val MEGABYTE = KILOBYTE * 1024L
private const val GIGABYTE = MEGABYTE * 1024L

/**
 * 行为分区里的开关表。
 *
 * 「开机时启动」由宿主能力决定去留，因此表要现算（[BooleanSetting.visible] 为假时整行不显示）。
 */
private fun behaviorSwitches(data: PreferencesUiData) = listOf(
    BooleanSetting(
        "自动粘贴",
        { it.pasteByDefault },
        { value -> copy(pasteByDefault = value) },
        description = "选中项目后直接粘贴到上一个应用。",
    ),
    BooleanSetting(
        "粘贴时去除格式",
        { it.removeFormattingByDefault },
        { value -> copy(removeFormattingByDefault = value) },
        description = "只保留纯文本。",
    ),
    BooleanSetting(
        "连续粘贴后按回车",
        { it.pressReturnAfterPaste },
        { value -> copy(pressReturnAfterPaste = value) },
        description = "多条逐条粘贴时，每条之后补一个回车，让终端 / 聊天框腾出下一处落点。",
    ),
    BooleanSetting(
        "开机时启动",
        { it.launchAtLogin },
        { value -> copy(launchAtLogin = value) },
        description = "登录后自动运行 Clipper。",
        visible = data.supportsLaunchAtLogin,
    ),
    // 原先独占「忽略」分区的开关：它改的是「接下来还记不记录」，属于记录行为。
    BooleanSetting(
        "暂停记录新的复制",
        { it.ignoreEvents },
        { value -> copy(ignoreEvents = value) },
    ),
)

@Composable
private fun BehaviorSection(data: PreferencesUiData, actions: PreferencesActions) {
    val settings = data.settings
    val switches = remember(data.supportsLaunchAtLogin) { behaviorSwitches(data) }
    SectionCard("行为") {
        SwitchSettings(settings, switches, actions.onSettingsChange)
        // 展示当前偏好下每种动作对应的按键组合。
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
    }
}

/**
 * 「哪些快捷键在系统范围内生效」那句提示。
 *
 * 从槽位表里推出来而不是手写：某个槽位换组时（比如暂停记录从全局改成面板内），
 * 这行说明不会跟着过期。
 */
private fun globalScopeHint(): String {
    val global = ShortcutSlot.entries.filter { it.global }.joinToString("、") { it.title }
    return if (global.isEmpty()) {
        "所有快捷键都只在面板里有焦点时生效。"
    } else {
        "${global}在系统范围内生效，其余快捷键只在面板里有焦点时生效。"
    }
}

@Composable
private fun ShortcutsSection(data: PreferencesUiData, actions: PreferencesActions) {
    val colors = MaterialTheme.colorScheme
    val settings = data.settings
    val recording = data.shortcutRecording
    // 上一次录制被拒绝的原因：录制没有因此退出，就地说明原因、继续等下一个组合。
    val problem = recording.problem
    SectionCard("快捷键") {
        // 槽位自己的元信息（标题 / 是否系统级）在 `ShortcutSlot` 里，设置页只负责渲染，
        // 因此新增一个可录制快捷键不需要在这里、以及在别处再各抄一份。
        ShortcutSlot.entries.forEach { slot ->
            ShortcutRow(
                title = slot.title,
                spec = settings.shortcut(slot),
                recording = recording.slot == slot,
                onRecord = { actions.onStartShortcutRecording(slot) },
                onClear = { actions.onSettingsChange { it.withShortcut(slot, null) } },
            )
        }
        Text(
            text = when {
                // 被拒绝时录制**没有**退出，就地告诉他原因、并继续等下一个组合。
                problem != null -> "${problem.message}请换一个组合，或按 Esc 取消。"
                recording.isActive -> "请按下新的快捷键…（至少要按一个修饰键）"
                // 呼出键被清除之后没有全局热键了，得说清楚还能从哪打开面板。
                settings.popupShortcut == null -> "呼出面板的快捷键已清除，可以从菜单栏图标打开面板。"
                else -> "点击快捷键即可重新录制，✕ 清除绑定；" +
                    globalScopeHint()
            },
            style = MaterialTheme.typography.labelSmall,
            color = when {
                problem != null -> colors.error
                recording.isActive -> colors.primary
                else -> MaterialTheme.hintColor
            },
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/** 搜索分区里的开关表。 */
private val SearchSwitches = listOf(
    BooleanSetting("显示搜索框", { it.showSearch }, { value -> copy(showSearch = value) }),
)

@Composable
private fun SearchSection(data: PreferencesUiData, actions: PreferencesActions) {
    val settings = data.settings
    SectionCard("搜索") {
        SwitchSettings(settings, SearchSwitches, actions.onSettingsChange)
        SegmentedBlock(
            title = "匹配高亮",
            values = HighlightMatch.entries,
            selected = settings.highlightMatch,
            label = { it.label },
            onSelect = { value -> actions.onSettingsChange { it.copy(highlightMatch = value) } },
        )
    }
}

/** 外观分区里的开关表。 */
private val AppearanceSwitches = listOf(
    BooleanSetting(
        "显示菜单栏图标",
        { it.showInStatusBar },
        { value -> copy(showInStatusBar = value) },
        description = "关闭后 Clipper 只在快捷键下工作。",
    ),
    BooleanSetting(
        "显示十六进制色块",
        { it.showHexColorSwatch },
        { value -> copy(showHexColorSwatch = value) },
        description = "为 #0A84FF 这类颜色显示色块。",
    ),
    BooleanSetting(
        "显示来源应用图标",
        { it.showApplicationIcons },
        { value -> copy(showApplicationIcons = value) },
        description = "在列表行与预览里显示复制来源应用的图标。",
    ),
    BooleanSetting(
        "显示特殊符号",
        { it.showSpecialSymbols },
        { value -> copy(showSpecialSymbols = value) },
        description = "把换行显示为 ⏎、制表符显示为 ⇥、首尾空格显示为 ·。",
    ),
)

@Composable
private fun AppearanceSection(data: PreferencesUiData, actions: PreferencesActions) {
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
        SegmentedBlock(
            title = "置顶位置",
            values = PinPosition.entries,
            selected = settings.pinTo,
            label = { it.label },
            onSelect = { value -> actions.onSettingsChange { it.copy(pinTo = value) } },
        )
        // 筛选栏开关：开 → 工具栏显示筛选栏（设置页里这几个按钮隐藏）；
        // 关 → 设置页「外观」里显示筛选栏的同一套按钮。
        SwitchRow(
            title = "显示筛选栏",
            checked = settings.showFilterBar,
            description = "在工具栏上显示类型、排序与升降序按钮。",
        ) { value ->
            actions.onSettingsChange { it.copy(showFilterBar = value) }
        }
        AnimatedVisibility(
            visible = !settings.showFilterBar,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            HistoryFilterBar(
                filterTypes = settings.filterTypes,
                sortBy = settings.sortBy,
                sortOrder = settings.sortOrder,
                onFilterTypesChange = { value ->
                    actions.onSettingsChange { it.copy(filterTypes = value) }
                },
                onSortByChange = { value ->
                    actions.onSettingsChange { it.copy(sortBy = value) }
                },
                onSortOrderChange = { value ->
                    actions.onSettingsChange { it.copy(sortOrder = value) }
                },
            )
        }
        SwitchSettings(settings, AppearanceSwitches, actions.onSettingsChange)
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

/** AI服务分区里的开关表；平台不支持时置灰并改说原因（而不是把这一项藏掉）。 */
private fun recognitionSwitches(data: PreferencesUiData) = listOf(
    BooleanSetting(
        "识别图片中的文字",
        { it.recognizeText },
        { value -> copy(recognizeText = value) },
        description = if (data.supportsTextRecognition) {
            "使用 Vision / ML Kit 识别图片文字，并作为纯图片条目的标题。"
        } else {
            "当前平台不支持图片文字识别。"
        },
        enabled = data.supportsTextRecognition,
    ),
)

@Composable
private fun RecognitionSection(data: PreferencesUiData, actions: PreferencesActions) {
    val settings = data.settings
    val switches = remember(data.supportsTextRecognition) { recognitionSwitches(data) }
    SectionCard("AI服务") {
        SwitchSettings(settings, switches, actions.onSettingsChange)
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
 * 重置只覆盖设置：历史与置顶项都不属于设置，不会被它影响。
 */
@Composable
private fun ResetSection(data: PreferencesUiData, actions: PreferencesActions) {
    val isDefault = data.settings == AppSettings()
    SectionCard("重置") {
        TextButton(
            onClick = {
                actions.onSettingsChange { AppSettings() }
                // 全部还原会连窗口尺寸、外观、快捷键一起改回去，而对话框自己也在被改动的窗口里
                // （桌面端的 `Dialog` 是窗口内的一层）：顺手关掉它，让用户直接看到还原后的面板，
                // 而不是一张被窗口重新起算的尺寸挤得跳来跳去的卡片。
                actions.onDismiss()
            },
            enabled = !isDefault,
        ) {
            Text("恢复默认设置")
        }
        Text(
            text = if (isDefault) {
                "当前所有设置都已是默认值。"
            } else {
                "把所有设置恢复为默认值；历史与置顶项目不受影响，设置窗口会关闭。"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.hintColor,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
