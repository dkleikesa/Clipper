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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.HighlightMatch
import com.qcmian.clipper.core.settings.PinPosition
import com.qcmian.clipper.core.settings.PopupPosition
import com.qcmian.clipper.core.settings.ShortcutSlot
import com.qcmian.clipper.core.settings.ThemeMode
import com.qcmian.clipper.core.settings.shortcut
import com.qcmian.clipper.core.settings.withShortcut
import com.qcmian.clipper.core.ui.FixedShortcutGroup
import com.qcmian.clipper.core.ui.components.HoverTooltip
import com.qcmian.clipper.core.ui.fixedShortcuts
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.feature.history.ui.components.HistoryFilterBar
import com.qcmian.clipper.feature.history.ui.components.SearchField
import com.qcmian.clipper.feature.preferences.state.ShortcutRecording
import kotlin.math.roundToInt

/**
 * 渲染偏好设置所需的只读数据。
 * 收拢为一个对象，避免十几份参数在 [PreferencesScreen] 与内容层之间重复转发。
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
     * 状态机不在界面里（见 `ShortcutRecorder`）：界面只渲染它、把按键转发回去，
     * 窗口关掉时也不该把它忘掉——宿主正靠它让出系统级热键。
     */
    val shortcutRecording: ShortcutRecording = ShortcutRecording(),
)

/** 偏好设置的全部上行动作。 */
data class PreferencesActions(
    val onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    val onClearUnpinned: () -> Unit,
    val onClearAll: () -> Unit,
    /** 关闭设置窗口（标题栏的关闭按钮、页内「恢复默认设置」都走它）。 */
    val onDismiss: () -> Unit,
    /** 开始录制某个槽位的快捷键。 */
    val onStartShortcutRecording: (ShortcutSlot) -> Unit,
    /**
     * 结束录制。
     *
     * 录制期间宿主的系统级热键是停着的，不收回它就会一直哑着。窗口关闭时由状态持有者收回
     * （见 `ClipboardUiAction.DismissPreferences`），这里兜住「内容被销毁」那一路。
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

/** 侧边栏宽度：放得下最长的一页名（「存储与数据」），再宽就是白占地方。 */
private val SidebarWidth = 170.dp

/** 设置窗口左栏的一页；声明顺序就是侧边栏的显示顺序。 */
internal enum class PreferencesSection(val title: String) {
    STORAGE("存储与数据"),
    BEHAVIOR("行为"),
    SHORTCUTS("快捷键"),
    SEARCH("搜索"),
    APPEARANCE("外观"),
    RECOGNITION("AI 服务"),
    RESET("重置"),
}

/**
 * 偏好设置窗口的完整内容：自绘标题栏 + 左栏分区导航 + 右栏当前分区。
 *
 * 标题栏是自绘的（宿主把窗口设成无边框，见 `ClipperSettingsWindow`）：系统标题栏的底色
 * 由 AppKit 决定，既不跟主题走，也和本应用的配色对不上——一个深色模式的窗口顶着一条浅色
 * 标题栏尤其突兀。自绘之后，标题、关闭按钮、可拖拽区都在我们手里。
 *
 * 换成两栏形态的原因是**分区之间不该互相挤**：原先七个分区竖着排成一条长列表，卡片又只有
 * 约 376dp 宽（被面板窗口夹着），越往下的分区越难找到——「设置很长」因此并不只是长的问题。
 * 分页之后，换分区是一次点击，与别的分区有多长完全无关。
 *
 * 它只渲染 [PreferencesUiData]、把交互发回 [PreferencesActions]，自己不持有任何偏好；
 * 连「关窗」都是通过 [PreferencesActions.onDismiss] 转达的——窗口的开关由宿主与状态持有者
 * 决定（见 `ClipboardUiState.settingsOpen`）。
 *
 * @param titleBarDragModifier 标题栏上「按住拖动窗口」的手势。共享代码里没有窗口概念
 *   （`java.awt` 只存在于 jvmMain），因此这里只留一个挂点，由宿主注入。
 */
@Composable
fun PreferencesScreen(
    data: PreferencesUiData,
    actions: PreferencesActions,
    titleBarDragModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // 选中的分区是纯粹的浏览状态（关窗即忘、不落盘），因此留在界面里。
    var section by remember { mutableStateOf(PreferencesSection.STORAGE) }

    val recording = data.shortcutRecording
    val rootFocus = remember { FocusRequester() }
    // 开始录制时把焦点抢回根节点：用户点的那一行会先把焦点带走，不抢回来录制就收不到按键。
    LaunchedEffect(recording.slot) {
        runCatching { rootFocus.requestFocus() }
    }
    // 内容被销毁时收回录制态（窗口只隐藏不销毁，那一路由状态持有者负责）。
    // 用 `rememberUpdatedState` 取最新回调：`DisposableEffect(Unit)` 只在退出时执行一次，
    // 直接捕获 `actions` 会留下最初那一份引用。
    val cancelRecording by rememberUpdatedState(actions.onCancelShortcutRecording)
    DisposableEffect(Unit) { onDispose { cancelRecording() } }

    val scrollState = rememberScrollState()
    // 换页回到顶部：上一页滚得深时，新页会停在同样的偏移量上（内容不够高就顶到底部），
    // 看起来像「这一页是空的」。
    LaunchedEffect(section) { scrollState.scrollTo(0) }

    // 录制中的按键必须在**预览阶段**就地截下，否则会打进筛选框、或落到面板自己的快捷键上；
    // 没在录制时它返回 `false`，按键照常往下走。
    val keyHandler: (KeyEvent) -> Boolean = { event ->
        when {
            actions.onShortcutKeyEvent(event) -> true
            // 自绘标题栏之后就没有系统菜单了，⌘W 得自己接上，否则这个窗口只能用鼠标关。
            // 排在录制之后：录制期间用户想录 ⌘W 就该录进去。
            event.type == KeyEventType.KeyDown && event.isMetaPressed && event.key == Key.W -> {
                actions.onDismiss()
                true
            }

            else -> false
        }
    }

    // 圆角 + 描边是窗口自己的外形：宿主把窗口设成透明无边框，这里画的才是用户看到的那一块。
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = colors.background,
        border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.4f)),
        modifier = modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent(keyHandler)
                .focusRequester(rootFocus)
                .focusable(),
        ) {
            PreferencesTitleBar(
                dragModifier = titleBarDragModifier,
                onClose = actions.onDismiss,
            )
            HorizontalDivider(
                thickness = 1.dp,
                color = colors.outline.copy(alpha = 0.3f),
            )

            Row(Modifier.weight(1f)) {
                PreferencesSidebar(selected = section, onSelect = { section = it })
                // 用一条竖线分开两栏，而不是靠底色差值——深色模式下那两个颜色本来就挨得很近。
                VerticalDivider(thickness = 1.dp, color = colors.outline.copy(alpha = 0.3f))

                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 22.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onBackground,
                    )
                    when (section) {
                        PreferencesSection.STORAGE -> StorageSection(data, actions)
                        PreferencesSection.BEHAVIOR -> BehaviorSection(data, actions)
                        PreferencesSection.SHORTCUTS -> ShortcutsSection(data, actions)
                        PreferencesSection.SEARCH -> SearchSection(data, actions)
                        PreferencesSection.APPEARANCE -> AppearanceSection(data, actions)
                        PreferencesSection.RECOGNITION -> RecognitionSection(data, actions)
                        PreferencesSection.RESET -> ResetSection(data, actions)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------
// 标题栏与侧边栏
// ---------------------------------------------------------------------------------

/**
 * 自绘标题栏：标题 + 关闭按钮，两者之间的那一段可以按住拖动整个窗口。
 *
 * 拖拽区刻意只覆盖标题那一段（[titleBarDragModifier] 挂在一个 `weight(1f)` 的容器上），
 * 而不是整条标题栏：关闭按钮若落在拖拽区里，小幅移动就会被判成拖动、点不中。
 */
@Composable
private fun PreferencesTitleBar(dragModifier: Modifier, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            .background(colors.surface)
            .padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(dragModifier),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
        }
        HoverTooltip("关闭设置") {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceVariant.copy(alpha = 0.5f))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                ClipperIcon(ClipperIconKind.CLEAR, size = 13.dp, tint = colors.onSurfaceVariant)
            }
        }
    }
}

/** 标题栏高度：与 macOS 常规工具栏一致的量级，够放下 28dp 的关闭按钮又不占地方。 */
private val TitleBarHeight = 40.dp

// ---------------------------------------------------------------------------------
// 侧边栏
// ---------------------------------------------------------------------------------

@Composable
private fun PreferencesSidebar(
    selected: PreferencesSection,
    onSelect: (PreferencesSection) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .width(SidebarWidth)
            .fillMaxHeight()
            .background(colors.surfaceVariant.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PreferencesSection.entries.forEach { entry ->
            SidebarItem(
                title = entry.title,
                selected = entry == selected,
                onClick = { onSelect(entry) },
            )
        }
    }
}

/** 侧边栏的一行；选中态沿用 macOS 的惯例——主色淡底 + 主色文字。 */
@Composable
private fun SidebarItem(title: String, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
        )
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
    SettingsGroup {
        GroupLabel("容量")
        HistoryLimitField(
            maxCount = settings.historyMaxCount,
            usageCount = data.historyCount,
            databaseBytes = data.storageBytes,
            onCountChange = { count ->
                actions.onSettingsChange { it.copy(historyMaxCount = count) }
            },
        )
    }
    // 原「数据」分区的开关与清除入口：改的都是存储里的内容，因此与上面同属一页。
    SettingsGroup {
        GroupLabel("清除")
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
    SettingsGroup {
        SwitchSettings(settings, switches, actions.onSettingsChange)
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

/**
 * 快捷键分区：筛选框 + 两段式清单。
 *
 * 这是全页最长的一处（可录制 5 行 + 固定十几行），因此做两件事：
 * - **筛选框**：按命令名与按键文本一起匹配（用户常记得「⌥P」却想不起它叫什么），
 *   十几行当场缩到两三行；筛选词只是浏览状态，不落盘；
 * - **两段式**：能改的（[ShortcutSlot]）与不能改的（[fixedShortcuts]）分成两段，
 *   后者顺带把「方向键、`⏎`、角标」这些一直没有任何说明的按键讲清楚。
 */
@Composable
private fun ShortcutsSection(data: PreferencesUiData, actions: PreferencesActions) {
    val colors = MaterialTheme.colorScheme
    val settings = data.settings
    val recording = data.shortcutRecording
    // 上一次录制被拒绝的原因：录制没有因此退出，就地说明原因、继续等下一个组合。
    val problem = recording.problem

    // 筛选词是纯粹的浏览状态（不落盘、不影响任何功能），因此留在界面里。
    var filterText by remember { mutableStateOf("") }
    val keyword = filterText.trim()

    /** 固定项的键位也参与匹配：只比标题会漏掉「我知道按 ⌥P，但不知道它叫什么」这一路。 */
    fun matches(title: String, keys: String?): Boolean =
        keyword.isEmpty() ||
            title.contains(keyword, ignoreCase = true) ||
            keys?.contains(keyword, ignoreCase = true) == true

    // 固定项随偏好现算：其中三条（`⏎` + 修饰键）与呼出键那一条的键位都由设置决定。
    val fixed = remember(settings) { fixedShortcuts(settings) }
    // 正在录制的槽位始终保留：它可能正好落在筛选结果之外，而用户此刻正需要看到它的状态。
    val visibleSlots = ShortcutSlot.entries.filter { slot ->
        slot == recording.slot || matches(slot.title, settings.shortcut(slot)?.label)
    }
    val visibleFixed = fixed.filter { matches(it.title, it.keys) }

    SettingsGroup {
        SearchField(
            query = filterText,
            onQueryChange = { filterText = it },
            // 不主动抢焦点：筛选框只是可选的入口，抢焦点会让按键先落进输入框。
            focusRequester = remember { FocusRequester() },
        )
        Spacer(Modifier.height(6.dp))

        if (visibleSlots.isEmpty() && visibleFixed.isEmpty()) {
            Text(
                text = "没有匹配「$keyword」的快捷键。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.hintColor,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }

        if (visibleSlots.isNotEmpty()) {
            ShortcutPartLabel("可自定义")
            // 槽位自己的元信息（标题 / 是否系统级）在 `ShortcutSlot` 里，界面只负责渲染，
            // 因此新增一个可录制快捷键不需要在这里、以及在别处再各抄一份。
            visibleSlots.forEach { slot ->
                ShortcutRow(
                    title = slot.title,
                    spec = settings.shortcut(slot),
                    recording = recording.slot == slot,
                    onRecord = { actions.onStartShortcutRecording(slot) },
                    onClear = { actions.onSettingsChange { it.withShortcut(slot, null) } },
                )
            }
        }

        if (visibleFixed.isNotEmpty()) {
            ShortcutPartLabel("固定（不可修改）", divider = visibleSlots.isNotEmpty())
            Text(
                text = "以下是面板内置的按键：它们是交互方式本身，不是可替换的命令，因此不提供修改。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.hintColor,
                modifier = Modifier.padding(top = 4.dp),
            )
            FixedShortcutGroup.entries.forEach { group ->
                val rows = visibleFixed.filter { it.group == group }
                if (rows.isEmpty()) return@forEach
                ShortcutGroupLabel(group.title)
                rows.forEach { FixedShortcutRow(title = it.title, keys = it.keys) }
            }
        }

        Text(
            text = when {
                // 被拒绝时录制**没有**退出，就地告诉他原因、并继续等下一个组合。
                problem != null -> "${problem.message}请换一个组合，或按 Esc 取消。"
                recording.isActive -> "请按下新的快捷键…（至少要按一个修饰键）"
                // 呼出键被清除之后没有全局热键了，得说清楚还能从哪打开面板。
                settings.popupShortcut == null -> "呼出面板的快捷键已清除，可以从菜单栏图标打开面板。"
                else -> "点击快捷键即可重新录制，✕ 清除绑定；" + globalScopeHint()
            },
            style = MaterialTheme.typography.labelSmall,
            color = when {
                problem != null -> colors.error
                recording.isActive -> colors.primary
                else -> MaterialTheme.hintColor
            },
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** 快捷键区的一级小标题：分开「可自定义」与「固定」两段。[divider] 用于在两段之间落一条分隔线。 */
@Composable
private fun ShortcutPartLabel(text: String, divider: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    if (divider) {
        HorizontalDivider(
            thickness = 1.dp,
            color = colors.outline.copy(alpha = 0.2f),
            modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
        )
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = colors.onSurfaceVariant,
    )
}

/** 二级小标题：固定项里的分组名（列表导航 / 激活与选择 / 呼出与窗口）。 */
@Composable
private fun ShortcutGroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.hintColor,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

/** 搜索分区里的开关表。 */
private val SearchSwitches = listOf(
    BooleanSetting("显示搜索框", { it.showSearch }, { value -> copy(showSearch = value) }),
)

@Composable
private fun SearchSection(data: PreferencesUiData, actions: PreferencesActions) {
    val settings = data.settings
    SettingsGroup {
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
    SettingsGroup {
        GroupLabel("窗口")
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
    }
    SettingsGroup {
        GroupLabel("列表")
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

/** AI 服务分区里的开关表；平台不支持时置灰并改说原因（而不是把这一项藏掉）。 */
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
    SettingsGroup {
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
    SettingsGroup {
        TextButton(
            onClick = {
                actions.onSettingsChange { AppSettings() }
                // 全部还原会把窗口尺寸、外观、快捷键一起改回去。关掉设置窗口，用户直接看到
                // 还原后的面板，而不是一张正在被改动的窗口。
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
