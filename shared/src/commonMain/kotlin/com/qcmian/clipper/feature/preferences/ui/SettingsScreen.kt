package com.qcmian.clipper.feature.preferences.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import com.qcmian.clipper.core.ui.components.ConfirmDialog
import com.qcmian.clipper.core.ui.theme.ClipperTheme
import com.qcmian.clipper.feature.history.state.ClipboardUiAction
import com.qcmian.clipper.feature.history.state.ClipboardUiState

/**
 * 设置窗口的内容：主题 + 设置页 + 「清除历史」确认框。
 *
 * 与面板同构——[ClipboardUiState] 进、[ClipboardUiAction] 出，界面是状态的纯函数。宿主
 * （`ClipperSettingsWindow`）因此只需要决定「窗口显不显示」，不必知道设置页长什么样。
 *
 * @param captureShortcutKey 录制期间的一次按键；由宿主直接给出
 *   （`ClipboardViewModel.captureShortcutKey`），不走 [onAction]——录制器要读最新状态、
 *   返回值也要同帧拿到，否则按键会晚一帧才被拦住。
 */
@Composable
fun SettingsScreen(
    state: ClipboardUiState,
    onAction: (ClipboardUiAction) -> Unit,
    captureShortcutKey: (KeyEvent) -> Boolean,
    darkTheme: Boolean,
    /** 标题栏上「按住拖动窗口」的手势，由宿主注入（见 [PreferencesScreen] 的同名参数）。 */
    titleBarDragModifier: Modifier = Modifier,
    /** 每次窗口显示都由宿主自增（见 [PreferencesScreen] 的同名参数）。 */
    focusRequestToken: Int = 0,
    modifier: Modifier = Modifier,
) {
    // `PreferencesActions` 的字段全是内联 lambda，若每次重组都重建，[PreferencesScreen]
    // 会因为「actions 引用变了」而整页无法跳过——任何一次状态变化都会全量重组当前分区。
    // 它只捕获 `onAction`（稳定的方法引用）与 `captureShortcutKey`，按这两个键缓存即可稳定。
    val actions = remember(onAction, captureShortcutKey) {
        PreferencesActions(
            onSettingsChange = { transform -> onAction(ClipboardUiAction.UpdateSettings(transform)) },
            // `hidePanel = false`：面板此刻本来就已经收起了（设置窗口抢走焦点的那一刻收的），
            // 而确认清除时若顺手去「收起面板」，`hidePanel` 会把焦点还给上一个应用——
            // 设置窗口会当场失去焦点。
            onClearUnpinned = { onAction(ClipboardUiAction.RequestClear(all = false, hidePanel = false)) },
            onClearAll = { onAction(ClipboardUiAction.RequestClear(all = true, hidePanel = false)) },
            onDismiss = { onAction(ClipboardUiAction.DismissPreferences) },
            onDismissConfirmation = { onAction(ClipboardUiAction.DismissClear) },
            // 录制期间宿主要停掉系统级热键，否则同一个组合会一边被录、一边触发原动作
            // （见 `ClipboardUiState.shortcutRecording`）。
            onStartShortcutRecording = { slot ->
                onAction(ClipboardUiAction.StartShortcutRecording(slot))
            },
            onCancelShortcutRecording = { onAction(ClipboardUiAction.CancelShortcutRecording) },
            onShortcutKeyEvent = captureShortcutKey,
        )
    }

    ClipperTheme(darkTheme = darkTheme) {
        PreferencesScreen(
            data = PreferencesUiData(
                settings = state.settings,
                storageBytes = state.storageBytes,
                historyCount = state.historyCount,
                screenCount = state.screenCount,
                supportsLaunchAtLogin = state.supportsLaunchAtLogin,
                supportsTextRecognition = state.supportsTextRecognition,
                shortcutRecording = state.shortcutRecording,
                hasConfirmation = state.confirmation != null,
            ),
            actions = actions,
            titleBarDragModifier = titleBarDragModifier,
            focusRequestToken = focusRequestToken,
            modifier = modifier.fillMaxSize(),
        )

        // 确认框属于**当前在场的那个窗口**：设置窗口开着时它在这里画，面板那边同时不作声
        // （见 `HistoryDialogs`），因此不会冒出两个一模一样的框。
        state.confirmation?.let { request ->
            ConfirmDialog(
                message = request.message,
                comment = request.comment,
                onConfirm = { onAction(ClipboardUiAction.ConfirmClear) },
                onDismiss = { onAction(ClipboardUiAction.DismissClear) },
            )
        }
    }
}
