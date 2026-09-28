package com.qcmian.clipper.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qcmian.clipper.core.platform.macos.MacWorkspace
import com.qcmian.clipper.core.ui.theme.rememberClipperDarkTheme
import com.qcmian.clipper.desktop.domain.SETTINGS_WINDOW_TITLE
import com.qcmian.clipper.desktop.domain.isOnScreen
import com.qcmian.clipper.desktop.domain.screenCenterLocation
import com.qcmian.clipper.feature.history.state.ClipboardUiAction
import com.qcmian.clipper.feature.history.viewmodel.ClipboardViewModel
import com.qcmian.clipper.feature.preferences.ui.SettingsScreen
import com.qcmian.clipper.host.WindowController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/**
 * 设置窗口的初始尺寸。
 *
 * 760dp 是「左栏 170 + 右栏约 590」的量：右栏要放得下一行「说明 + 键位胶囊」而不用折行，
 * 这是原先那条约 376dp 的窄卡片做不到的——那正是设置页又长又难找的根源。
 */
private val SettingsWindowSize = DpSize(760.dp, 560.dp)

/** 设置窗口的尺寸下限：再小两栏就挤不成样子了（用户拖边缩放时由框架据此拦下）。 */
private val SettingsWindowMinimumSize = Dimension(640, 420)

/**
 * 偏好设置窗口（View 层）：一个**独立的常规窗口**，带标题栏、可缩放、可移动。
 *
 * 之所以不再叠在面板上：设置项多，需要自己的尺寸与分区导航（左栏选分区 + 右栏看内容），
 * 而这些在一个被面板窗口夹住的层里做不到。原先的形态是「400dp 宽的面板里塞七个分区，
 * 纵向滚到底」，找任何一项都得先滚一遍。
 *
 * 显隐完全由状态决定（见 `ClipboardUiState.settingsOpen`）：标题栏的关闭按钮只是把意图发回
 * 状态持有者，状态一变窗口才消失——与面板的显隐是同一种单向数据流。
 *
 * 窗口只隐藏、不销毁：下次打开时尺寸、位置、选中的分区都还在原处——唯一的例外是它跑到别的
 * 屏幕上去了（多屏下鼠标换了一块屏），那时才会被挪回鼠标所在屏幕的中央（见下面那个
 * `LaunchedEffect`）。
 */
@Composable
fun ApplicationScope.ClipperSettingsWindow(
    viewModel: ClipboardViewModel,
    windowController: WindowController,
) {
    // 在应用作用域只订阅「窗口该不该在」这一位，而不是整份界面状态：后者每复制一条内容、
    // 每敲一个搜索键都会变，在这里订阅它会把两个窗口的宿主一起拖着反复重组。
    val visible by remember(viewModel) {
        viewModel.uiState.map { it.settingsOpen }.distinctUntilChanged()
    }.collectAsState(initial = viewModel.uiState.value.settingsOpen)

    // 位置不在这里给：多屏时必须落在**鼠标所在的那块屏幕**上，而 `WindowPosition(Alignment.Center)`
    // 是 Compose 按主屏算的——在副屏上唤出设置，窗口会跑到主屏去。定位交给下面那个
    // `LaunchedEffect`（见 `screenCenterLocation`）。
    val windowState = rememberWindowState(size = SettingsWindowSize)

    // 是否已经摆过一次位置：首次显示一律居中，之后只在窗口跑到别的屏幕上时才挪（见下面）。
    var placed by remember { mutableStateOf(false) }

    Window(
        // 关闭请求不自己去藏窗口：把意图发回状态持有者，由状态决定窗口的存亡。
        onCloseRequest = { viewModel.onAction(ClipboardUiAction.DismissPreferences) },
        visible = visible,
        title = SETTINGS_WINDOW_TITLE,
        icon = rememberVectorPainter(ClipperAppIcon),
        state = windowState,
        // 自绘标题栏：系统标题栏的底色由 AppKit 决定，既不跟主题走、也和本应用的配色对不上
        // （深色模式的窗口顶着一条浅色标题栏尤其突兀）。无边框之后，标题、关闭按钮与可拖拽区
        // 全在设置页自己手里（见 `PreferencesTitleBar`），窗口的圆角与描边也一并自绘。
        //
        // 透明是必需的：圆角之外必须能透出桌面，否则那四个角会被窗口底色填成方块。
        // 无边框窗口的**边缘拖拽缩放**由框架负责（`UndecoratedWindowResizer`），它按
        // `window.minimumSize` 拦下过小的尺寸。
        undecorated = true,
        transparent = true,
    ) {
        // 窗口内容里才收整份状态：这里组合只随窗口内容重组，不影响上面的应用作用域。
        val state by viewModel.uiState.collectAsStateWithLifecycle()
        // 系统外观从宿主投影里取：面板与设置窗口用同一个原始值解析主题，不会一个深一个浅。
        val host by windowController.hostUiState.collectAsStateWithLifecycle()

        // 每次窗口显示都自增一次，用来让设置页把 **Compose** 焦点抢到自己的根节点上。
        //
        // 只把 AWT 焦点交给内容组件（[focusKeyboardTarget]）还不够：`PreferencesScreen` 的按键
        // 入口是根节点上的 `onPreviewKeyEvent`，而 Compose 没有焦点节点时这条链根本不会被调用
        // ——表现为「刚打开设置、什么都还没点，Esc 没反应」，点一下内部之后才恢复。
        var keyboardFocusToken by remember { mutableStateOf(0) }

        // 尺寸下限交给框架：无边框窗口的拖边缩放由 `UndecoratedWindowResizer` 实现，
        // 它读的正是 `minimumSize`。
        LaunchedEffect(Unit) {
            window.minimumSize = SettingsWindowMinimumSize
        }

        // 拖动自绘标题栏移动窗口（手势本身与解锁窗共用，见 `rememberTitleBarDragModifier`）。
        val dragTitleBar = rememberTitleBarDragModifier(window)

        // 每次成为 key window 都把键盘焦点补到内容组件上：点过标题栏、从别的应用切回来之后，
        // AWT 的焦点所有者会退回窗口框架，不补一次按键就又收不到了（见 [focusKeyboardTarget]）。
        DisposableEffect(window) {
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(event: WindowEvent?) {
                    focusKeyboardTarget(window)
                }

                override fun windowLostFocus(event: WindowEvent?) = Unit
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }

        // 显示时把本应用带到前台：本应用是菜单栏应用（`LSUIElement`），不激活则窗口成为不了
        // key window，录制快捷键、输入上限条数、点选分区都会落空。
        //
        // 与面板同样的理由放到后台线程：macOS 对刚启动的应用会延迟处理「激活自己」，
        // 那条同步调用会让界面卡在重组里（见 `ClipperWindow`）。
        // 窗口隐藏时也仍在组合里，所以这个副作用在**挂载那一刻**就先跑了一次（那时 `visible`
        // 还是 false、窗口还没露过面）——定位实际发生在那一刻，而不是「首次显示」时。
        LaunchedEffect(visible) {
            // 定位（多屏）：窗口必须落在**鼠标所在的那块屏幕**上（索引 0 = 活动屏幕，见
            // `screenBounds`），否则在副屏上呼出设置，它会跑到主屏去。
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
                val location = screenCenterLocation(SettingsWindowSize, screenIndex = 0)
                window.setLocation(location.x, location.y)
            }
            if (!visible) return@LaunchedEffect
            launch(Dispatchers.IO) { runCatching { MacWorkspace.activateSelf() } }
            window.toFront()
            // 先请求一次：窗口可见后尽快让 Compose 有个焦点节点（此刻 AWT 焦点可能还没到位，
            // 这一次大概率落空，靠下面的重试兜住）。
            keyboardFocusToken++
            // 焦点必须落到窗口内的内容组件上，不能停在窗口框架上——否则录制快捷键、
            // 输入历史上限都会「按下去没反应」（见 [focusKeyboardTarget]）。
            // 窗口刚显示时它还没成为 focused window，请求会被拒，因此跨帧重试到成功为止。
            repeat(FOCUS_TARGET_ATTEMPTS) {
                if (focusKeyboardTarget(window)) {
                    // AWT 焦点真正到位之后再让 Compose 请求一次：Compose 的 `requestFocus`
                    // 要等窗口/key window 就绪才落得下来。
                    keyboardFocusToken++
                    return@LaunchedEffect
                }
                withFrameNanos { }
            }
        }

        SettingsScreen(
            state = state,
            onAction = viewModel::onAction,
            captureShortcutKey = viewModel::captureShortcutKey,
            darkTheme = rememberClipperDarkTheme(state.settings.themeMode, host.systemDark),
            titleBarDragModifier = dragTitleBar,
            focusRequestToken = keyboardFocusToken,
        )
    }
}
