package com.qcmian.clipper.feature.preferences.ui

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.qcmian.clipper.core.settings.ShortcutSlot
import com.qcmian.clipper.core.ui.components.HoverTooltip
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.feature.preferences.state.ShortcutRecording

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
    /** 「清除历史」确认框此刻是否开着；`Esc` 时它比设置窗口本身优先。 */
    val hasConfirmation: Boolean = false,
)

/** 偏好设置的全部上行动作。 */
data class PreferencesActions(
    val onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    val onClearUnpinned: () -> Unit,
    val onClearAll: () -> Unit,
    /** 关闭设置窗口（标题栏的关闭按钮、页内「恢复默认设置」都走它）。 */
    val onDismiss: () -> Unit,
    /** 关掉「清除历史」确认框（`Esc` 在确认框开着时走它）。 */
    val onDismissConfirmation: () -> Unit = {},
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

/** 侧边栏一行的高度。原为 28dp，配合 17dp 的图标显得挤，加高 5dp。 */
private val SidebarItemHeight = 33.dp

/** 设置窗口左栏的一页；声明顺序就是侧边栏的显示顺序。 */
internal enum class PreferencesSection(val title: String, val icon: ClipperIconKind) {
    STORAGE("存储与数据", ClipperIconKind.DATABASE),
    BEHAVIOR("行为", ClipperIconKind.SLIDERS),
    SHORTCUTS("快捷键", ClipperIconKind.KEYBOARD),
    APPEARANCE("外观", ClipperIconKind.APPEARANCE),
    RECOGNITION("AI 服务", ClipperIconKind.TEXT_VIEWFINDER),
    RESET("重置", ClipperIconKind.RESET),
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
 * 各分区的内容不在这个文件里：普通分区见 [StorageSection] 所在的那个文件，快捷键分区见
 * [ShortcutsSection]（它单独一份，因为它是全页最长的一处）。
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
            // `Esc` 关闭设置窗口；确认框开着时只关确认框。排在录制之后：录制期间的 `Esc`
            // 是「取消录制」，那一支由录制器消费（见 `ShortcutRecorder.onKeyEvent`）。
            event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
                if (data.hasConfirmation) actions.onDismissConfirmation() else actions.onDismiss()
                true
            }

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
                    // 页面大标题比组标题再大一档（`titleLarge` 22sp）：组标题已经升到
                    // `titleMedium`（16sp）加粗，若大标题还是 16sp，两者就分不出层级了。
                    Text(
                        text = section.title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = colors.onBackground,
                    )
                    when (section) {
                        PreferencesSection.STORAGE -> StorageSection(data, actions)
                        PreferencesSection.BEHAVIOR -> BehaviorSection(data, actions)
                        PreferencesSection.SHORTCUTS -> ShortcutsSection(data, actions)
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
                icon = entry.icon,
                selected = entry == selected,
                onClick = { onSelect(entry) },
            )
        }
    }
}

/**
 * 侧边栏的一行：图标 + 标题。
 *
 * 选中态沿用 macOS 的惯例——主色淡底 + 主色文字，图标与文字同色：一行里出现两种颜色
 * （比如图标恒为灰、文字变蓝）会让选中态看起来像没对齐。
 */
@Composable
private fun SidebarItem(
    title: String,
    icon: ClipperIconKind,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val content = if (selected) colors.primary else colors.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(SidebarItemHeight)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) colors.primary.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标尺寸取 14dp 放大 20%（14 × 1.2 = 16.8，圆整到 17dp）：原来偏小，
        // 与同行标题（bodyMedium）的字重不成比例。
        ClipperIcon(kind = icon, size = 17.dp, tint = content)
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = content,
        )
    }
}
