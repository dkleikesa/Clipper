package com.qcmian.clipper

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.desktop.domain.InitialPanelHeight
import com.qcmian.clipper.desktop.ui.ClipperTray
import com.qcmian.clipper.desktop.ui.ClipperWindow
import com.qcmian.clipper.di.AppContainer
import com.qcmian.clipper.host.HotkeyController
import com.qcmian.clipper.host.WindowController
import kotlinx.coroutines.flow.first

/**
 * 桌面端组合根：只负责装配进程级依赖，并把窗口与托盘挂到 Compose Desktop 的应用作用域上。
 *
 * 分层约定：
 * - Model（`desktop/domain`）：[com.qcmian.clipper.desktop.domain.WindowPlacement] /
 *   [com.qcmian.clipper.desktop.domain.WindowSizing] 提供窗口定位与尺寸的纯函数。
 * - ViewModel（`desktop/viewmodel`）：[com.qcmian.clipper.desktop.viewmodel.DesktopShellViewModel]
 *   持有窗口状态、热键状态机与窗口尺寸逻辑，在 `Window` 内容里由窗口宿主的
 *   `ViewModelStoreOwner` 持有；托盘只有一次点击请求，直接内联在 [ClipperTray] 里。
 * - 通道：[WindowController] 承载窗口事件（显示 / 隐藏 / 退出）与面板投影，
 *   [HotkeyController] 承载热键按键意图，二者都由 [App]（shared）消费。
 * - View（`desktop/ui`）：[ClipperWindow] / [ClipperTray] 只渲染并转发事件。
 */
fun main() {
    hideFromDock()

    // 依赖图在组合之外建好，并立刻开始加载数据：Room 首次打开数据库（含 WAL 恢复）实测要
    // 几百毫秒，让它与 AWT / Skiko 初始化、首次组合**并行**跑完。放在组合里的话，数据加载
    // 要等窗口内容组合完成才开始，全局热键（它要等真实偏好就绪才注册）就会晚半秒多——
    // 启动后那一秒里按快捷键等于按了个寂寞。
    val container = AppContainer()
    container.repository.start()

    application {
        val windowController = remember { WindowController() }
        val hotkeyController = remember { HotkeyController() }

        // 窗口状态属于 UI 层，由宿主创建后交给窗口的 ViewModel 读写。
        val windowState = rememberWindowState(
            width = Popup.contentWidth,
            height = InitialPanelHeight,
            position = WindowPosition(Alignment.Center),
        )

        // 退出：窗口 ViewModel 落盘完成后置位，由应用根结束进程；
        // `exitApplication()` 只能在应用作用域里调用。
        LaunchedEffect(windowController) {
            windowController.exitRequested.first { it }
            exitApplication()
        }

        // 窗口持有自己的 ViewModel（宿主 owner）；托盘只负责点击弹出面板。
        ClipperWindow(windowState, container, windowController, hotkeyController)
        ClipperTray(windowController)
    }
}

/**
 * macOS：把应用标记成「只在菜单栏里存在」，也就是 Maccy 的 `LSUIElement`——不出现在 Dock
 * 与 ⌘Tab 中，也不占用菜单栏左侧的应用菜单。
 *
 * 三条生效路径：
 * - `./gradlew :desktopApp:run` 靠 build.gradle.kts 里的 `-Dapple.awt.UIElement=true`；
 * - 打包后的 `.app` 靠 Info.plist 里的 `LSUIElement`；
 * - 从 IDE 直接跑 `main()` 时两者都不生效，这里兜底。
 *
 * **必须在 AWT 初始化之前设置**：这个属性只在 AWT 建 `NSApplication` 时被读取一次，之后
 * 再改系统属性表也不会重新配置。因此它是 `main` 的第一条语句——[application] 一旦被调用，
 * 窗口与托盘会依次初始化 AWT，就来不及了。
 */
private fun hideFromDock() {
    if (!System.getProperty("os.name").orEmpty().startsWith("Mac")) return
    System.setProperty("apple.awt.UIElement", "true")
}
