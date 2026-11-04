package com.qcmian.logviewer.platform

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * 是否为自绘标题栏预留 macOS 交通灯的位置。
 *
 * 恒为 false：这个工作台已经嵌在宿主的开发者工具面板里，红绿灯归宿主的系统标题栏。
 */
actual val windowTrafficLightsInset: Boolean = false

/**
 * Chrome 式窗口拖拽：在区域内按住主键拖动 = 移动窗口。
 * 实现方式与 Compose 官方 WindowDraggableArea 相同（AWT MouseInfo 轮询 + window.location 更新），
 * 但不依赖 WindowScope，便于在共享 UI 中使用。
 */
@Composable
actual fun WindowDragRegion(modifier: Modifier, content: @Composable () -> Unit) {
    val window = LocalAppWindow.current as? java.awt.Window
    if (window == null) {
        Box(modifier.fillMaxWidth()) { content() }
        return
    }
    var winOrigin by remember { mutableStateOf(java.awt.Point()) }
    var mouseOrigin by remember { mutableStateOf(java.awt.Point()) }
    Box(
        modifier
            .fillMaxWidth()
            .pointerInput(window) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    winOrigin = window.location
                    mouseOrigin = java.awt.MouseInfo.getPointerInfo().location
                    var dragging = true
                    while (dragging) {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.changedToUp() } || !event.buttons.isPrimaryPressed) {
                            dragging = false
                        } else {
                            val now = java.awt.MouseInfo.getPointerInfo().location
                            window.setLocation(
                                winOrigin.x + now.x - mouseOrigin.x,
                                winOrigin.y + now.y - mouseOrigin.y,
                            )
                        }
                    }
                }
            },
    ) {
        content()
    }
}
