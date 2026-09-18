package com.qcmian.clipper

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qcmian.clipper.di.AppContainer
import com.qcmian.clipper.feature.history.viewmodel.ClipboardViewModel
import com.qcmian.clipper.feature.history.ui.HistoryScreen
import com.qcmian.clipper.feature.history.ui.PreviewHostPolicy
import com.qcmian.clipper.host.HostUiState
import com.qcmian.clipper.host.HotkeyController
import com.qcmian.clipper.host.WindowController
import com.qcmian.clipper.feature.history.state.ClipboardUiAction
import androidx.compose.foundation.isSystemInDarkTheme
import com.qcmian.clipper.core.settings.ThemeMode
import com.qcmian.clipper.core.ui.theme.ClipperTheme
import kotlinx.coroutines.launch

/**
 * 所有目标共用的入口。
 *
 * 它用宿主提供的 [container] 创建状态持有者，并转发宿主的回调；真正的界面在 [HistoryScreen]，
 * 是状态的纯函数。
 *
 * @param container 由宿主（Android 上是 `Application`，其它目标是进程入口）持有的依赖图，
 *   因此它的存活时间超过任何一次组合。
 * @param onRequestHideWindow 让宿主在「自动粘贴」动作送达之前隐藏窗口，
 *   使按键能到达此前聚焦的应用。
 * @param onQuit 「退出」页脚行。无法退出的平台上传入 `null` 会隐藏该行。
 * @param previewHost 宿主为预览面板提供的空间约束：停靠侧、是否只能覆盖、是否加宽窗口。
 *   见 [PreviewHostPolicy]。
 * @param windowController 让宿主观察面板投影并驱动窗口事件（显示 / 隐藏 / 退出）。
 * @param hotkeyController 让宿主的全局热键状态机把按键意图发给面板。
 * @param onPreferredHeightChange 上报弹窗希望得到的高度，使桌面宿主能
 *   贴合内容。
 * @param onMinimumHeightChange 上报窗口的下限高度（滑动区下限 + 置顶区 + 头部 / 页脚），
 *   使桌面宿主在用户手动拖拽时不会把内容区压到下限以下。
 */
@Composable
fun App(
    /** 应用级依赖图，由宿主创建（绝不由 composable 创建）。 */
    container: AppContainer,
    onRequestHideWindow: () -> Unit = {},
    onQuit: (() -> Unit)? = null,
    /** 宿主为预览面板提供的空间约束；见 [HistoryScreen] 的同名参数。 */
    previewHost: PreviewHostPolicy = PreviewHostPolicy(),
    windowController: WindowController? = null,
    hotkeyController: HotkeyController? = null,
    onPreferredHeightChange: (Dp) -> Unit = {},
    /** 窗口的下限高度；见 [HistoryScreen] 的同名参数。 */
    onMinimumHeightChange: (Dp) -> Unit = {},
    /** 面板当前是否可见。桌面宿主据此给托盘图标画按下态；其它平台无此概念。 */
    panelVisible: Boolean = true,
    /**
     * 系统外观是否为深色，由桌面宿主提供（系统通知事件驱动；Compose 的 `isSystemInDarkTheme()`
     * 在桌面端不实时跟随系统）；`null` 表示平台未提供，退回 [isSystemInDarkTheme]。
     */
    systemDarkTheme: Boolean? = null,
    /**
     * 托盘是否应处于按下态。桌面宿主传「可见 且 由托盘触发」——热键呼出时面板虽然可见，
     * 托盘保持常态。不传时与 [panelVisible] 一致（旧宿主 / 其它平台）。
     */
    statusItemActive: Boolean = panelVisible,
) {
    // 状态持有者先于主题创建：主题模式本身取自用户偏好。
    val viewModel = viewModel {
        ClipboardViewModel(
            repository = container.repository,
            platform = container.platform,
            useCases = container.useCases,
            showQuit = onQuit != null,
            // 设置页录制系统级快捷键时用它判断组合有没有被别的应用占用（见 `MacGlobalHotKey`）。
            canUseGlobalShortcut = container.native::isGlobalShortcutAvailable,
        )
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // 主题模式：跟随系统 / 强制浅色 / 强制深色。
    val darkTheme = when (state.settings.themeMode) {
        ThemeMode.SYSTEM -> systemDarkTheme ?: isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    ClipperTheme(darkTheme = darkTheme) {
        DisposableEffect(viewModel, onQuit) {
            viewModel.onRequestHideWindow = onRequestHideWindow
            viewModel.onQuitRequest = onQuit ?: {}
            onDispose {
                // 对应 `AppDelegate.applicationWillTerminate`：把防抖的写入落盘，
                // 并应用「退出时清空历史」偏好。只有真正能退出的宿主才会走到这里。
                if (onQuit != null) viewModel.onQuit() else container.repository.flush()
            }
        }

        // 宿主通过这些计数器驱动面板（`PopupState.toggle/cycle`）；把它们当作普通动作转发，
        // 使 ViewModel 保持为唯一的状态所有者。
        LaunchedEffect(hotkeyController, viewModel) {
            val hotkey = hotkeyController ?: return@LaunchedEffect
            launch {
                hotkey.openRequests.collect { if (it > 0) viewModel.onAction(ClipboardUiAction.Opened) }
            }
            launch {
                hotkey.cycleRequests.collect { if (it > 0) viewModel.onAction(ClipboardUiAction.Cycle) }
            }
            launch {
                hotkey.acceptRequests.collect { if (it > 0) viewModel.onAction(ClipboardUiAction.Accept) }
            }
        }
        LaunchedEffect(windowController, viewModel) {
            val host = windowController ?: return@LaunchedEffect
            launch {
                host.hideRequests.collect { if (it > 0) viewModel.onAction(ClipboardUiAction.Hidden) }
            }
            host.clearSearchAction = { viewModel.onAction(ClipboardUiAction.ClearSearch) }
            host.quitAction = { viewModel.onQuit() }
        }

        // 用一个投影取代十几个镜像字段：面向宿主的那部分状态以单个不可变值交给控制器，
        // 因此它只有一个数据源。
        SideEffect {
            windowController?.setHostUiState(HostUiState(
                settings = state.settings,
                isStatusItemDisabled = state.isStatusItemDisabled,
                isStatusItemActive = statusItemActive,
                isWindowVisible = panelVisible,
                isModalOpen = state.isModalOpen,
                // 录制快捷键期间宿主必须把手从系统级热键上拿开（见 `HostUiState`）。
                isRecordingShortcut = state.isRecordingShortcut,
            ))
        }

        HistoryScreen(
            state = state,
            onAction = viewModel::onAction,
            onPreferredHeightChange = onPreferredHeightChange,
            onMinimumHeightChange = onMinimumHeightChange,
            applicationIcon = viewModel::applicationIcon,
            // 设置页录制的按键由 ViewModel 的状态机处理（见 `ShortcutRecorder`）。
            captureShortcutKey = viewModel::captureShortcutKey,
            previewHost = previewHost,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
