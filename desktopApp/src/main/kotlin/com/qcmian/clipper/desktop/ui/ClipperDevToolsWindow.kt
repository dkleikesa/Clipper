package com.qcmian.clipper.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qcmian.clipper.core.platform.macos.MacWorkspace
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.ui.theme.ClipperTheme
import com.qcmian.clipper.core.ui.theme.rememberClipperDarkTheme
import com.qcmian.clipper.desktop.domain.DEVTOOLS_WINDOW_TITLE
import com.qcmian.clipper.desktop.domain.RESIZE_SETTLE_MILLIS
import com.qcmian.clipper.desktop.domain.devToolsWindowSizeOf
import com.qcmian.clipper.desktop.domain.isOnScreen
import com.qcmian.clipper.desktop.domain.minimumDevToolsWindowSize
import com.qcmian.clipper.desktop.domain.screenBounds
import com.qcmian.clipper.desktop.domain.screenCenterLocation
import com.qcmian.clipper.devtools.registry.DevToolsRegistry
import com.qcmian.clipper.devtools.ui.DevToolsPanel
import com.qcmian.clipper.feature.history.state.ClipboardUiAction
import com.qcmian.clipper.feature.history.viewmodel.ClipboardViewModel
import com.qcmian.clipper.host.WindowController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import kotlin.math.roundToInt

/**
 * 开发者工具窗口（View 层）：一个独立窗口，内容由 [DevToolsPanel] 渲染。
 *
 * 与设置窗口同构——一个**独立窗口**、无边框 + 透明、自绘标题栏，显隐完全由状态
 * （[com.qcmian.clipper.feature.history.state.ClipboardUiState.devToolsOpen]）决定：标题栏的关闭
 * 按钮只把意图发回状态持有者。之所以不做成面板里的一层：工具需要「左栏清单 + 右栏一大片编辑区」
 * 这种横向空间，塞进 400dp 宽的面板里只能用一次滚一次的窄条。
 *
 * 窗口只隐藏、不销毁：切去别的工具再切回来，尺寸与位置都还在原处——唯一的例外是它跑到别的
 * 屏幕上去了（多屏下鼠标换了一块屏），那时才会被挪回鼠标所在屏幕的中央（见下面那个
 * `LaunchedEffect`）。
 *
 * 尺寸是**持久化**的（[AppSettings.devToolsWindowWidth] / [AppSettings.devToolsWindowHeight]）：
 * 只隐藏不销毁只能保住同一次运行，重启之后还是要回到一个默认值，而默认值不可能对每个人都合适。
 */
@Composable
fun ApplicationScope.ClipperDevToolsWindow(
    viewModel: ClipboardViewModel,
    /** 插件表：由组合根（`main.kt`）建好传进来——它属于 `:devTools` 模块，不在 `:shared` 的依赖图里。 */
    registry: DevToolsRegistry,
    windowController: WindowController,
) {
    // 在应用作用域只订阅「窗口该不该在」这一位：整份界面状态每复制一条都会变，在这里订阅它
    // 会把窗口宿主一起拖着反复重组（与设置窗口同理）。
    val visible by remember(viewModel) {
        viewModel.uiState.map { it.devToolsOpen }.distinctUntilChanged()
    }.collectAsState(initial = viewModel.uiState.value.devToolsOpen)

    // 位置不在这里给：多屏时必须落在**鼠标所在的那块屏幕**上，而 `WindowPosition(Alignment.Center)`
    // 是 Compose 按主屏算的——`⇧⌘D` 在别的屏幕上按下时窗口会跑到另一块屏去。定位交给下面那个
    // `LaunchedEffect`（见 `screenCenterLocation`）。
    //
    // 尺寸同理：先读一次设置（没有就按屏幕算），`remember` **不带键**——只取这一次的值当窗口的
    // 初始尺寸。带上 `settings` 做键的话，下面「拖完边落盘」写回设置会立刻反过来改窗口尺寸，
    // 拖拽过程中每写一次就被回拉一次。
    val windowState = rememberWindowState(
        size = remember { devToolsWindowSizeOf(viewModel.uiState.value.settings, screenBounds(index = 0)) }
    )

    // 是否已经摆过一次位置：首次显示一律居中，之后只在窗口跑到别的屏幕上时才挪（见下面）。
    var placed by remember { mutableStateOf(false) }

    Window(
        // 关闭请求不自己去藏窗口：把意图发回状态持有者，由状态决定窗口的存亡。
        onCloseRequest = { viewModel.onAction(ClipboardUiAction.CloseDevTools) },
        visible = visible,
        title = DEVTOOLS_WINDOW_TITLE,
        icon = rememberVectorPainter(ClipperAppIcon),
        state = windowState,
        // 自绘标题栏：系统标题栏的底色由 AppKit 决定，既不跟主题走也和本应用配色对不上。
        // 透明是必需的：圆角之外要能透出桌面，否则四个角会被窗口底色填成方块。
        undecorated = true,
        transparent = true,
        // 窗口级快捷键：自绘标题栏之后就没有系统菜单了，`⌘W` 得自己接上；`Esc` 与设置窗口
        // 一致，表示「关掉这一层」。放在窗口层而不是内容根节点上——内容里有没有 Compose 焦点
        // 节点取决于用户点没点过编辑区，靠焦点链会让这两个键时灵时不灵。
        onPreviewKeyEvent = { event ->
            if (event.type != KeyEventType.KeyDown) {
                false
            } else if (event.key == Key.Escape || (event.isMetaPressed && event.key == Key.W)) {
                viewModel.onAction(ClipboardUiAction.CloseDevTools)
                true
            } else {
                false
            }
        },
    ) {
        // 窗口内容里才收整份状态：这里的重组只发生在窗口内部。
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        // 系统外观从宿主投影里取：与面板、设置窗口用同一个原始值解析主题，三个窗口不会深浅不一。
        val host by windowController.hostUiState.collectAsStateWithLifecycle()

        // 尺寸下限交给框架：无边框窗口的拖边缩放由 `UndecoratedWindowResizer` 实现，读的正是它。
        LaunchedEffect(Unit) {
            val minimum = minimumDevToolsWindowSize(screenBounds(index = 0))
            window.minimumSize = Dimension(minimum.width.value.roundToInt(), minimum.height.value.roundToInt())
        }

        // 用户拖完边之后把尺寸落盘。`collectLatest` 让新值取消上一次的等待，等于一个防抖：
        // 拖动过程中不会每帧写一次设置。
        //
        // `drop(1)` 丢掉 `snapshotFlow` 的首次发射——那是窗口刚建出来时的尺寸，还没被用户碰过，
        // 写回去只会把「用户从没调整过」这个状态抹掉（`null` 与具体值是两回事：前者跟着屏幕走，
        // 后者不跟）。
        //
        // 不担心反馈回路：`rememberWindowState` 的尺寸只在挂载时读一次设置，之后写设置不会回头
        // 改窗口（见上面那段说明）。
        LaunchedEffect(window) {
            snapshotFlow { windowState.size }
                .distinctUntilChanged()
                .drop(1)
                .collectLatest { size ->
                    delay(RESIZE_SETTLE_MILLIS)
                    viewModel.onAction(
                        ClipboardUiAction.UpdateSettings { current ->
                            current.copy(
                                devToolsWindowWidth = size.width.value.roundToInt(),
                                devToolsWindowHeight = size.height.value.roundToInt(),
                            )
                        }
                    )
                }
        }

        // 拖动自绘标题栏移动窗口（与设置窗口共用同一个手势）。
        val dragTitleBar = rememberTitleBarDragModifier(window)

        // 每次成为 key window 都把 AWT 焦点补到内容组件上：否则从别的应用切回来之后，
        // 工具里的编辑区收不到键盘输入（见 [focusKeyboardTarget]）。
        //
        // 顺带维护「本窗口是不是在最前」这一位：面板要靠它决定收起后的 ⌘V 投给谁——用户在开发
        // 窗口里编辑时，粘贴应当落在这个窗口的光标处（见 `WindowController.isDevToolsWindowFocused`）。
        DisposableEffect(window) {
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(event: WindowEvent?) {
                    windowController.isDevToolsWindowFocused = true
                    focusKeyboardTarget(window)
                }

                override fun windowLostFocus(event: WindowEvent?) {
                    windowController.isDevToolsWindowFocused = false
                }
            }
            window.addWindowFocusListener(listener)
            onDispose {
                window.removeWindowFocusListener(listener)
                windowController.isDevToolsWindowFocused = false
            }
        }

        // 显示时把本应用带到前台：本应用是菜单栏应用（`LSUIElement`），不激活则窗口成为不了
        // key window，编辑区输不进字、也收不到快捷键。
        // 窗口隐藏时也仍在组合里，所以这个副作用在**挂载那一刻**就先跑了一次（那时 `visible`
        // 还是 false、窗口还没露过面）——定位实际发生在那一刻，而不是「首次显示」时。
        LaunchedEffect(visible) {
            // 定位（多屏）：窗口必须落在**鼠标所在的那块屏幕**上（索引 0 = 活动屏幕，见
            // `screenBounds`），否则在副屏上按 `⇧⌘D`，它会跑到主屏去。
            //
            // 但只在两种情况才挪：
            // - **首次**（窗口刚挂载、位置还是平台给的默认值，见上）一律居中；
            // - 此后只有窗口**不在鼠标那块屏上**时才挪。用户把它拖到顺手的位置之后，下次打开
            //   应当还在原处——「窗口只隐藏、不销毁」这条承诺不能因为多屏修复而被破坏。
            //   副屏被拔掉时窗口坐标会落到屏幕外，同样由这里把它拉回来。
            //
            // 用 `setLocation` 一步到位，而不是写 `windowState.position`：后者要等下一帧才落到
            // 窗口上，多屏下能看到窗口先出现在主屏、再跳过来（面板的 `applyBounds` 出于同样的
            // 理由用 `setBounds`）。这一步排在 `if (!visible)` 之前，因此窗口显示出来时已经摆好。
            if (!placed || !isOnScreen(window.bounds, screenIndex = 0)) {
                placed = true
                // 居中的尺寸用**窗口当前的尺寸**而不是常量：用户拖过之后窗口可能比默认值大，
                // 拿默认值去算中心会让窗口偏在一角。
                val location = screenCenterLocation(
                    DpSize(window.width.dp, window.height.dp),
                    screenIndex = 0,
                )
                window.setLocation(location.x, location.y)
            }
            if (!visible) {
                // 窗口藏起来了就不再是「用户正在这里编辑」：不清的话，下次从别的应用呼出面板
                // 会把粘贴误投给一个看不见的窗口。
                windowController.isDevToolsWindowFocused = false
                return@LaunchedEffect
            }
            // 与面板、设置窗口同样的理由放到后台线程：macOS 对刚启动的应用会延迟处理「激活自己」。
            launch(Dispatchers.IO) { runCatching { MacWorkspace.activateSelf() } }
            window.toFront()
            // 窗口刚显示时还没成为 focused window，焦点请求会被拒，因此跨几帧重试到成功为止。
            repeat(FOCUS_TARGET_ATTEMPTS) {
                if (focusKeyboardTarget(window)) return@LaunchedEffect
                withFrameNanos { }
            }
        }

        ClipperTheme(
            darkTheme = rememberClipperDarkTheme(state.settings.themeMode, host.systemDark),
        ) {
            DevToolsPanel(
                registry = registry,
                item = state.devToolsItem,
                onClose = { viewModel.onAction(ClipboardUiAction.CloseDevTools) },
                onCopyToClipboard = viewModel::copyToClipboardFromDevTools,
                // 文件那一组：只给路径，读写留在工具侧（kotlinx-io）。`window` 是 Compose 的
                // `ComposeWindow`，本身就是 AWT 的 `Frame`，可直接当原生对话框的父窗口。
                onPickFileToOpen = { pickFileToOpen(window) },
                onPickFileToSave = { suggestedName -> pickFileToSave(window, suggestedName) },
                onDroppedFilePaths = ::droppedFilePaths,
                titleBarDragModifier = dragTitleBar,
            )
        }
    }
}
