package com.qcmian.clipper.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qcmian.clipper.App
import com.qcmian.clipper.desktop.viewmodel.DesktopShellViewModel
import com.qcmian.clipper.di.AppContainer
import com.qcmian.clipper.feature.history.ui.PreviewHostPolicy
import com.qcmian.clipper.core.platform.macos.MacWorkspace
import com.qcmian.clipper.host.HotkeyController
import com.qcmian.clipper.host.WindowController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/**
 * 关闭请求的处理者：由窗口内容注册，`Window` 的 `onCloseRequest` 在调用时才解引用。
 * 本应用全局只有一个主窗口，全局一份即可。
 */
var onCloseRequest: () -> Unit = {}

/** 面板显示后为「键盘目标组件」争取 AWT 焦点的最大重试帧数。 */
private const val FOCUS_TARGET_ATTEMPTS = 12

/**
 * 把 AWT 焦点交给窗口里真正接收键盘的那个组件。
 *
 * Compose 的按键监听挂在窗口**内容组件**上（`ComposeSceneMediator.keyListener` 是个挂在
 * `SkiaLayerComponent` 上的 `KeyListener`），而 AWT 只把按键派发给焦点所有者。直接
 * `window.requestFocus()` 会把焦点给窗口框架本身，于是按键全落在框架上、Compose 侧一条都
 * 收不到——表现为「面板呼出后打不了字，Esc / 方向键也全没反应」，点一下窗口内部才恢复。
 *
 * 因此这里沿组件树下探到最深的可聚焦组件（也就是 Compose 的内容组件）再请求焦点；
 * 找不到时退回窗口本身，行为与改动前一致。
 */
private fun focusKeyboardTarget(window: java.awt.Window): Boolean {
    val target = deepestFocusableChild(window)
    if (target == null) {
        // 找不到内容组件（或它不可聚焦）：退回窗口本身，行为与改动前一致。
        return window.requestFocusInWindow() || window.isFocusOwner
    }
    if (target.isFocusOwner) return true
    // 窗口还没成为 focused window 时 `requestFocusInWindow()` 会返回 `false`，
    // 调用方据此跨帧重试。
    return target.requestFocusInWindow() || target.isFocusOwner
}

/** 组件树里最深的「可聚焦且可见」的组件；只有窗口自身可聚焦时返回 `null`。 */
private fun deepestFocusableChild(container: java.awt.Container): java.awt.Component? {
    var found: java.awt.Component? = null
    fun visit(component: java.awt.Component) {
        if (component.isFocusable && component.isVisible) found = component
        (component as? java.awt.Container)?.components?.forEach(::visit)
    }
    visit(container)
    return found?.takeIf { it !== container }
}

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
    // 面板可见性：内容侧根据 ViewModel 状态回写，`Window` 参数读取。启动时隐藏。
    var windowVisible by remember { mutableStateOf(false) }

    Window(
        onCloseRequest = { onCloseRequest() },
        visible = windowVisible,
        title = "Clipper",
        // 任务栏图标：与打包的 ico、Android、iOS 同一份「炭火夜市」设计稿，直接矢量栅格化。
        // 只在 Windows / Linux 设置——macOS 上 AWT 的 setIconImage 会反过来覆盖 Dock 图标，
        // 把 bundle 里的 .icns 换成一张被系统缩进玻璃 squircle 的小图，四周多出一圈留白。
        icon = if (System.getProperty("os.name").orEmpty().startsWith("Mac")) {
            null
        } else {
            rememberVectorPainter(ClipperAppIcon)
        },
        // 隐藏系统标题栏（最大化 / 最小化 / 关闭按钮），让面板只显示内容本身。
        undecorated = true,
        transparent = true,
        // 浮层层级：快捷键呼出时始终盖在当前焦点应用之上。
        alwaysOnTop = true,
        state = windowState,
    ) {

        // 内容组合由窗口宿主提供 ViewModelStoreOwner：窗口隐藏时组合保留、store 不销毁。
        val viewModel = viewModel {
            DesktopShellViewModel(
                container = container,
                panel = windowController,
                hotkey = hotkeyController,
                windowState = windowState,
                // 位置与尺寸一次应用。Compose 自己的实现是 `setSize` + `setLocation` 两次
                // 调用，左侧停靠时窗口要同时「左移」和「变宽」，两次之间的中间帧会被系统
                // 画出来——整个窗口左右闪一下；`setBounds` 是原子的。
                applyBounds = { x, y, width, height -> window.setBounds(x, y, width, height) },
                // 内容区的下限：无标题栏拖拽由框架读 `minimumSize` 拦下，用户拖不过去
                // （见 `DesktopShellViewModel.observeMinimumWindowSize`）。
                applyMinimumSize = { width, height -> window.minimumSize = Dimension(width, height) },
            )
        }
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()
        val systemDark by viewModel.systemDark.collectAsStateWithLifecycle()

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
                override fun windowGainedFocus(event: WindowEvent?) {
                    // 系统激活可能晚于窗口显示到达（后台线程在等 macOS 完成激活）：
                    // 成为 key window 的这一刻把键盘焦点补到内容组件上。
                    focusKeyboardTarget(window)
                    viewModel.onWindowGainedFocus()
                }

                override fun windowLostFocus(event: WindowEvent?) =
                    viewModel.onWindowLostFocus()
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }

        // 「按住热键循环」的状态机完全由 ViewModel 自己驱动（见 `observeHotKeyHold`）：
        // 它按绝对时刻计时，不依赖这里的状态变化，因此视图不需要为它做任何事。

        // 每一次「呼出面板」（热键或托盘）都会自增；用来判断这次显示是不是新的一次。
        val openRequests by hotkeyController.openRequests.collectAsStateWithLifecycle()

        // 对应 `FloatingPanel.makeKeyAndOrderFront`：显示时把本应用带到前台并取得键盘焦点，
        // 面板才能成为 key window——键盘输入可到达面板，点击窗口外部也会触发失焦收起。
        //
        // 必须跟随 [windowVisible]（真正传给 `Window(visible=...)` 的那个状态），而不是
        // ViewModel 的原始状态：后者先于窗口参数变化一拍，`toFront()` / 焦点请求
        // 可能落在窗口显示之前而被 AWT 静默拒绝。
        //
        // 同时依赖 [openRequests]：点击托盘会让面板先失焦，那次隐藏可能和随后的显示
        // 挤在同一帧里，`windowVisible` 观察不到 false→true 的变化，于是这里不会重跑，
        // 应用也没被重新激活，看起来就是「点了托盘但窗口没出来」。
        LaunchedEffect(windowVisible, openRequests) {
            if (windowVisible) {
                // macOS 对**刚启动**的应用会延迟处理「激活自己」：`activateWithOptions:` 是
                // 同步等系统完成的调用，实测应用启动后的头几秒里它会阻塞 1s 上下。它绝不能
                // 跑在 EDT 上——面板虽然已经显示，但重组、绘制与输入都停在这一条调用里，
                // 看起来就是「呼出后要过一秒才能操作」。放到后台线程：`NSRunningApplication`
                // 线程安全，激活晚一点到达也没关系，窗口成为 key window 时下面的
                // `windowGainedFocus` 会把键盘焦点补上。
                launch(Dispatchers.IO) { runCatching { MacWorkspace.activateSelf() } }
                window.toFront()
                // 焦点必须落到窗口内的内容组件上，不能停在窗口框架上（见 [focusKeyboardTarget]）。
                // 窗口刚显示时它还没成为 focused window，请求会被拒，因此跨几帧重试到成功为止。
                repeat(FOCUS_TARGET_ATTEMPTS) {
                    if (focusKeyboardTarget(window)) return@LaunchedEffect
                    withFrameNanos { }
                }
            }
        }

        // 隐藏窗口（而不是销毁它）能让剪贴板监听保持存活，
        // 从而让「自动粘贴」动作到达此前聚焦的应用。
        App(
            container = container,
            onRequestHideWindow = { viewModel.hidePanel() },
            onQuit = { viewModel.quit() },
            previewHost = PreviewHostPolicy(
                onLeft = uiState.previewOnLeft,
                // 桌面端会为预览加宽窗口（`autoWindowSize`），因此预览永远与主列表并排：
                // 界面等窗口加宽到位再让它进场，不会先盖在列表上闪一下。
                // 也**不**传 `overlays`：并排布局里卡片与列表各占一边，任何一帧都不会互相遮盖，
                // 而覆盖层只要在过渡里出现一帧，看起来就是「预览整块盖住了列表」。
                expandsWindow = true,
                // 「加宽到位」由本侧直接给出，界面不用量窗口宽度（实测值慢一帧，收起时会闪）。
                windowReady = uiState.previewWindowReady,
                // 分隔条拖动上限之一：本侧算好的「这一侧屏幕还能给预览多少」。界面会再与
                // 「窗口内剩余空间」取较小值（见 `HistoryScreen.maxDragWidth`）。
                maxPreviewWidth = uiState.maxPreviewWidth,
            ),
            windowController = windowController,
            hotkeyController = hotkeyController,
            onPreferredHeightChange = viewModel::onPreferredHeightChanged,
            onMinimumHeightChange = viewModel::onMinimumHeightChanged,
            // panelVisible：面板可见性（原始值）；statusItemActive：托盘按下态只认
            // 「托盘触发的可见」——热键呼出时面板虽然可见，托盘保持常态。
            panelVisible = uiState.windowVisible,
            statusItemActive = uiState.windowVisible && uiState.panelOpenedByTray,
            // 桌面端的 isSystemInDarkTheme() 不实时跟随系统外观，改由宿主提供（系统通知事件驱动）。
            systemDarkTheme = systemDark,
        )
    }
}
