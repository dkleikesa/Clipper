package com.qcmian.clipper

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.qcmian.clipper.desktop.domain.InitialPanelHeight
import com.qcmian.clipper.desktop.ui.ClipperTray
import com.qcmian.clipper.desktop.ui.ClipperWindow
import com.qcmian.clipper.di.AppContainer
import com.qcmian.clipper.ui.ClipperController
import kotlinx.coroutines.flow.first

/**
 * 桌面端组合根：只负责装配进程级依赖，并把窗口与托盘挂到 Compose Desktop 的应用作用域上。
 *
 * 分层约定：
 * - Model（`desktop/domain`）：[com.qcmian.clipper.desktop.domain.WindowPlacement] /
 *   [com.qcmian.clipper.desktop.domain.WindowSizing] 提供窗口定位与尺寸的纯函数。
 * - ViewModel（`desktop/viewmodel`）：[com.qcmian.clipper.desktop.viewmodel.DesktopShellViewModel]
 *   持有窗口状态与行为，在 `Window` 内容里由窗口宿主的 `ViewModelStoreOwner` 持有；
 *   托盘只有一次修饰键判断加一个请求，直接内联在 `ClipperTray` 里。经 [ClipperController] 通信。
 * - View（`desktop/ui`）：[ClipperWindow] / [ClipperTray] 只渲染并转发事件。
 */
fun main() = application {
    // 应用级作用域：整个进程只有一张依赖图，绝不会因重组而重建。
    val container = remember { AppContainer() }
    val controller = remember { ClipperController() }

    // 窗口状态属于 UI 层，由宿主创建后交给窗口的 ViewModel 读写。
    val windowState = rememberWindowState(
        width = controller.hostUiState.settings.windowWidth.dp,
        height = InitialPanelHeight,
        position = WindowPosition(Alignment.Center),
    )

    // 退出：窗口 ViewModel 落盘完成后置位，由应用根结束进程；
    // `exitApplication()` 只能在应用作用域里调用。
    LaunchedEffect(controller) {
        controller.exitRequested.first { it }
        exitApplication()
    }

    // 窗口持有自己的 ViewModel（宿主 owner），托盘只负责点击弹出面板，经 [ClipperController] 通信。
    ClipperWindow(windowState, container, controller)
    ClipperTray(controller)
}
