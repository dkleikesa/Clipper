package com.qcmian.clipper.desktop.ui

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qcmian.clipper.App
import com.qcmian.clipper.desktop.viewmodel.DesktopShellViewModel
import com.qcmian.clipper.di.AppContainer
import com.qcmian.clipper.core.platform.macos.MacWorkspace
import com.qcmian.clipper.host.HotkeyController
import com.qcmian.clipper.host.WindowController
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/**
 * 关闭请求的处理者：由窗口内容注册，`Window` 的 `onCloseRequest` 在调用时才解引用。
 * 本应用全局只有一个主窗口，全局一份即可。
 */
var onCloseRequest: () -> Unit = {}

/**
 * 主窗口（View 层）：把 [DesktopShellViewModel] 渲染成一个无标题栏的浮层，并把窗口级事件
 * （焦点、显示时机）转发给它。
 *
 * ViewModel 在 `Window` 内容里创建：内容组合由窗口宿主注入 `LocalViewModelStoreOwner`，
 * 无需自建。`Window` 的 `visible` 参数在进入内容之前求值，由内容根据 ViewModel 状态回写。
 */
@Composable
fun ApplicationScope.ClipperWindow(
    windowState: WindowState,
    container: AppContainer,
    windowController: WindowController,
    hotkeyController: HotkeyController,
) {
    // 面板可见性：内容侧根据 ViewModel 状态回写，`Window` 参数读取。
    var windowVisible by remember { mutableStateOf(true) }

    Window(
        onCloseRequest = { onCloseRequest() },
        visible = windowVisible,
        title = "Clipper",
        // 隐藏系统标题栏（最大化 / 最小化 / 关闭按钮），让面板只显示内容本身。
        undecorated = true,
        // 浮层层级：快捷键呼出时始终盖在当前焦点应用之上。
        alwaysOnTop = true,
        state = windowState,
    ) {
        // 内容组合由窗口宿主提供 ViewModelStoreOwner：窗口隐藏时组合保留、store 不销毁。
        val viewModel = viewModel {
            DesktopShellViewModel(container, windowController, hotkeyController, windowState)
        }
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        // 把 ViewModel 的能力「返回」给外层的 Window 参数：关闭请求转发给 hidePanel。
        DisposableEffect(viewModel) {
            onCloseRequest = { viewModel.hidePanel() }
            onDispose { onCloseRequest = {} }
        }
        LaunchedEffect(viewModel) {
            viewModel.uiState.collect {
                windowVisible = it.windowVisible
            }
        }

        // 对应 `FloatingPanel.resignKey()`：面板失去焦点即隐藏，但它的对话框弹出时不隐藏。
        DisposableEffect(window) {
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(event: WindowEvent?) =
                    viewModel.onWindowGainedFocus()

                override fun windowLostFocus(event: WindowEvent?) =
                    viewModel.onWindowLostFocus()
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }

        // 「按住热键循环」的修饰键状态机由 ViewModel 持有，这里只驱动它的生命周期。
        LaunchedEffect(uiState.windowVisible, uiState.popupMode) { viewModel.watchModifiers() }

        // 对应 `FloatingPanel.makeKeyAndOrderFront`：显示时把本应用带到前台并取得键盘焦点，
        // 面板才能成为 key window——键盘输入可到达面板，点击窗口外部也会触发失焦收起。
        LaunchedEffect(uiState.windowVisible) {
            if (uiState.windowVisible) {
                runCatching { MacWorkspace.activateSelf() }
                window.toFront()
                window.requestFocus()
            }
        }

        // 隐藏窗口（而不是销毁它）能让剪贴板监听保持存活，
        // 从而让「自动粘贴」动作到达此前聚焦的应用。
        App(
            container = container,
            onRequestHideWindow = { viewModel.hidePanel() },
            onQuit = { viewModel.quit() },
            onPreviewOpenChange = viewModel::onPreviewOpenChanged,
            previewOnLeft = uiState.previewOnLeft,
            windowController = windowController,
            hotkeyController = hotkeyController,
            onPreferredHeightChange = viewModel::onPreferredHeightChanged,
        )
    }
}
