package com.qcmian.clipper.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.runtime.collectAsState
import com.qcmian.clipper.host.WindowController

/**
 * 菜单栏 / 托盘图标（View 层）：从 [WindowController] 读取要显示的投影状态。
 * 点击图标即弹出主窗口（菜单为空时点击触发 onAction），不挂下拉菜单、无其他功能。
 */
@Composable
fun ApplicationScope.ClipperTray(
    windowController: WindowController,
) {
    val host by windowController.hostUiState.collectAsState()
    if (!host.settings.showInStatusBar) return

    // macOS 的模板图片会随菜单栏外观自动反相；Compose Desktop 不提供该能力，
    // 因此改为按当前主题给菜单栏图标着色。`isStatusItemDisabled` 时图标变暗。
    val tint = if (isSystemInDarkTheme()) Color.White else Color.Black
    val color = if (host.isStatusItemDisabled) tint.copy(alpha = 0.4f) else tint
    val icon = rememberVectorPainter(menuIconVector(host.menuIcon, color))
    val recentCopy = host.recentCopyText

    Tray(
        icon = icon,
        state = rememberTrayState(),
        tooltip = if (recentCopy.isNotEmpty()) "Clipper — $recentCopy" else "Clipper — 剪贴板历史记录",
        onAction = { windowController.requestShow() },
        // 退出走窗口页脚或 ⌘Q。
    )
}
