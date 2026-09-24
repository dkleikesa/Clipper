package com.qcmian.clipper.feature.history.ui

import androidx.compose.runtime.Composable
import com.qcmian.clipper.core.ui.components.ConfirmDialog
import com.qcmian.clipper.feature.history.state.ClipboardUiAction
import com.qcmian.clipper.feature.history.state.ClipboardUiState

/**
 * 面板之上的一层模态：「清除历史」的二次确认。
 *
 * 偏好设置**不在这里**——桌面端把它渲染成一个独立窗口（见 `ClipperSettingsWindow`），
 * 因此这里只剩确认框。它只在设置窗口关着时渲染：设置窗口开着时，同一个确认框由那个窗口画
 * （见 `SettingsWindowContent`），两边都画会同时冒出两个一模一样的框。
 */
@Composable
internal fun HistoryDialogs(
    state: ClipboardUiState,
    onAction: (ClipboardUiAction) -> Unit,
) {
    if (state.settingsOpen) return
    state.confirmation?.let { request ->
        ConfirmDialog(
            message = request.message,
            comment = request.comment,
            onConfirm = { onAction(ClipboardUiAction.ConfirmClear) },
            onDismiss = { onAction(ClipboardUiAction.DismissClear) },
        )
    }
}
