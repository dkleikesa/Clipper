package com.qcmian.clipper

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qcmian.clipper.di.AppContainer
import com.qcmian.clipper.ui.ClipboardViewModel
import com.qcmian.clipper.ui.ClipperController
import com.qcmian.clipper.ui.HostUiState
import com.qcmian.clipper.ui.HistoryScreen
import com.qcmian.clipper.ui.state.ClipboardUiAction
import com.qcmian.clipper.ui.theme.ClipperTheme

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
 * @param onPreviewOpenChange 上报预览滑出面板的状态，使桌面宿主能像 Maccy 那样加宽窗口。
 * @param previewOnLeft 对应 `SlideoutController.computePlacement`：弹窗右侧放不下时，
 *   预览改从左侧滑出。
 * @param controller 让宿主（桌面托盘）观察并驱动面板。
 * @param onPreferredHeightChange 上报弹窗希望得到的高度，使桌面宿主能像 Maccy 的浮动面板那样
 *   贴合内容。
 */
@Composable
fun App(
    /** 应用级依赖图，由宿主创建（绝不由 composable 创建）。 */
    container: AppContainer,
    onRequestHideWindow: () -> Unit = {},
    onQuit: (() -> Unit)? = null,
    onPreviewOpenChange: (Boolean) -> Unit = {},
    previewOnLeft: Boolean = false,
    controller: ClipperController? = null,
    onPreferredHeightChange: (Dp) -> Unit = {},
) {
    ClipperTheme {
        val viewModel = viewModel {
            ClipboardViewModel(
                repository = container.repository,
                platform = container.platform,
                useCases = container.useCases,
                showQuit = onQuit != null,
            )
        }

        val state by viewModel.uiState.collectAsStateWithLifecycle()

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
        val openRequests = controller?.openRequests ?: 0
        val cycleRequests = controller?.cycleRequests ?: 0
        val acceptRequests = controller?.acceptRequests ?: 0
        val hideRequests = controller?.hideRequests ?: 0

        LaunchedEffect(openRequests) {
            if (openRequests > 0) viewModel.onAction(ClipboardUiAction.Opened)
        }
        LaunchedEffect(cycleRequests) {
            if (cycleRequests > 0) viewModel.onAction(ClipboardUiAction.Cycle)
        }
        LaunchedEffect(acceptRequests) {
            if (acceptRequests > 0) viewModel.onAction(ClipboardUiAction.Accept)
        }
        LaunchedEffect(hideRequests) {
            if (hideRequests > 0) viewModel.onAction(ClipboardUiAction.Hidden)
        }

        // 托盘菜单会调用这些槽位。
        LaunchedEffect(controller, viewModel) {
            controller?.let { host ->
                host.togglePauseAction = { onlyNext ->
                    viewModel.onAction(ClipboardUiAction.TogglePause(onlyNext))
                }
                host.togglePreviewAction = { viewModel.onAction(ClipboardUiAction.TogglePreview) }
                host.clearSearchAction = { viewModel.onAction(ClipboardUiAction.ClearSearch) }
                host.quitAction = { viewModel.onQuit() }
            }
        }

        // `AppState.menuIconText`：最近一条未置顶的复制，按 Maccy 的方式缩短。
        val recentCopyText = remember(state.results, state.settings.showRecentCopyInMenuBar) {
            if (!state.settings.showRecentCopyInMenuBar) {
                ""
            } else {
                state.results.firstOrNull { it.item.isUnpinned }?.item?.previewableText
                    ?.take(100)
                    ?.trim()
                    ?.replace("\n", "")
                    ?.replace("\r", "")
                    ?.take(20)
                    .orEmpty()
            }
        }

        // 用一个投影取代十几个镜像字段：面向宿主的那部分状态以单个不可变值交给控制器，
        // 因此它只有一个数据源。
        SideEffect {
            controller?.hostUiState = HostUiState(
                settings = state.settings,
                isPaused = state.settings.ignoreEvents,
                isStatusItemDisabled = state.isStatusItemDisabled,
                isPreviewOpen = state.previewOpen,
                isModalOpen = state.isModalOpen,
                menuIcon = state.settings.menuIcon,
                recentCopyText = recentCopyText,
            )
        }

        HistoryScreen(
            state = state,
            onAction = viewModel::onAction,
            onPreviewOpenChange = onPreviewOpenChange,
            onPreferredHeightChange = onPreferredHeightChange,
            applicationIcon = viewModel::applicationIcon,
            applicationName = viewModel::applicationName,
            availablePins = viewModel::availablePins,
            previewOnLeft = previewOnLeft,
            onResetPosition = { controller?.resetPosition() },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
