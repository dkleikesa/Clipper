package com.qcmian.clipper.feature.history.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.KeyEvent
import com.qcmian.clipper.core.ui.components.ConfirmDialog
import com.qcmian.clipper.feature.history.state.ClipboardDialog
import com.qcmian.clipper.feature.history.state.ClipboardUiAction
import com.qcmian.clipper.feature.history.state.ClipboardUiState
import com.qcmian.clipper.feature.preferences.ui.PreferencesActions
import com.qcmian.clipper.feature.preferences.ui.PreferencesDialog
import com.qcmian.clipper.feature.preferences.ui.PreferencesUiData

/**
 * 面板之上的两层模态：偏好设置与「清除历史」二次确认。
 *
 * 两者都不参与面板布局，只是叠在窗口上层，因此与列表渲染分开。偏好设置与确认框互斥
 * （`confirmation != null` 时不显示设置），避免两层模态同时抓焦点。
 *
 * @param captureShortcutKey 设置页录制快捷键期间的一次按键；由宿主直接提供
 *   （`ClipboardViewModel.captureShortcutKey`），不走 [onAction]——录制器要读最新状态、
 *   返回值也要同帧拿到（见 [HistoryScreen] 的同名参数）。
 */
@Composable
internal fun HistoryDialogs(
    state: ClipboardUiState,
    onAction: (ClipboardUiAction) -> Unit,
    captureShortcutKey: (KeyEvent) -> Boolean,
) {
    val settings = state.settings
    if (state.dialog == ClipboardDialog.PREFERENCES && state.confirmation == null) {
        PreferencesDialog(
            data = PreferencesUiData(
                settings = settings,
                storageSize = state.storageSize,
                historyBytes = state.historyBytes,
                screenCount = state.screenCount,
                supportsLaunchAtLogin = state.supportsLaunchAtLogin,
                supportsTextRecognition = state.supportsTextRecognition,
                shortcutRecording = state.shortcutRecording,
            ),
            actions = PreferencesActions(
                onSettingsChange = { transform -> onAction(ClipboardUiAction.UpdateSettings(transform)) },
                onClearUnpinned = { onAction(ClipboardUiAction.RequestClear(all = false, hidePanel = false)) },
                onClearAll = { onAction(ClipboardUiAction.RequestClear(all = true, hidePanel = false)) },
                onDismiss = { onAction(ClipboardUiAction.DismissPreferences) },
                // 录制期间宿主要停掉系统级热键，否则同一个组合会一边被录、一边触发原动作
                // （见 `ClipboardUiState.shortcutRecording`）。
                onStartShortcutRecording = { slot ->
                    onAction(ClipboardUiAction.StartShortcutRecording(slot))
                },
                // 对话框离开屏幕（关闭、重置、被确认框顶掉）时收回录制态：不收回的话宿主的
                // 系统级热键会一直哑着。
                onCancelShortcutRecording = { onAction(ClipboardUiAction.CancelShortcutRecording) },
                onShortcutKeyEvent = captureShortcutKey,
            ),
        )
    }

    state.confirmation?.let { request ->
        ConfirmDialog(
            message = request.message,
            comment = request.comment,
            onConfirm = { onAction(ClipboardUiAction.ConfirmClear) },
            onDismiss = { onAction(ClipboardUiAction.DismissClear) },
        )
    }
}
