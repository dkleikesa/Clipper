package com.qcmian.clipper.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qcmian.clipper.core.platform.macos.MacWorkspace
import com.qcmian.clipper.core.ui.theme.rememberClipperDarkTheme
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
 * 窗口只隐藏、不销毁：下次打开时尺寸、位置、选中的分区都还在原处。
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

    val windowState = rememberWindowState(
        size = SettingsWindowSize,
        position = WindowPosition(Alignment.Center),
    )

    Window(
        // 关闭请求不自己去藏窗口：把意图发回状态持有者，由状态决定窗口的存亡。
        onCloseRequest = { viewModel.onAction(ClipboardUiAction.DismissPreferences) },
        visible = visible,
        title = "Clipper 设置",
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

        // 尺寸下限交给框架：无边框窗口的拖边缩放由 `UndecoratedWindowResizer` 实现，
        // 它读的正是 `minimumSize`。
        LaunchedEffect(Unit) {
            window.minimumSize = SettingsWindowMinimumSize
        }

        // 拖动自绘标题栏移动窗口。
        //
        // 用「按下点 + 屏幕坐标」而不是累加窗口内的增量：笔触事件的坐标是**窗口内相对坐标**，
        // 而我们每挪一次窗口，同一个屏幕点对应的相对坐标就跟着变——增量要靠「窗口的移动晚于
        // 事件的投递」才凑得回来，一旦时序不同就会越拖越偏。屏幕坐标不受自己的移动影响，
        // 因此这里只记一次「按下时光标相对窗口左上角的偏移」，之后每次都按绝对位置摆放。
        val dragTitleBar = remember(window) {
            Modifier.pointerInput(window) {
                awaitEachGesture {
                    // 找到「按下」的那一次事件，并记下光标相对窗口左上角的抓取偏移。
                    //
                    // 循环而不是直接取第一次事件：`awaitEachGesture` 只保证「上一轮手势已经
                    // 结束」，块里的第一个事件可能只是一次悬停移动（没有任何按下的指针）。
                    // 也不能先 `awaitFirstDown` 再回头找事件——`awtEventOrNull` 挂在
                    // `PointerEvent` 上，而不是 `PointerInputChange` 上。
                    var pressedId: PointerId? = null
                    var grabX = 0
                    var grabY = 0
                    while (pressedId == null) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.firstOrNull { it.pressed } ?: continue
                        // 非鼠标来源（触控 / 笔）没有 AWT 事件，也就没有屏幕坐标可用。
                        val mouse = event.awtEventOrNull ?: return@awaitEachGesture
                        val origin = window.locationOnScreen
                        grabX = mouse.xOnScreen - origin.x
                        grabY = mouse.yOnScreen - origin.y
                        pressedId = pressed.id
                    }
                    // 循环退出时它必然非空；收进 `val` 是为了让后面的循环拿到一个非空类型。
                    val draggingId = pressedId

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == draggingId } ?: break
                        if (!change.pressed) break
                        val mouse = event.awtEventOrNull ?: continue
                        // 消费掉手势：否则它还会往下传给标题栏底下的东西。
                        change.consume()
                        window.setLocation(mouse.xOnScreen - grabX, mouse.yOnScreen - grabY)
                    }
                }
            }
        }

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
        LaunchedEffect(visible) {
            if (!visible) return@LaunchedEffect
            launch(Dispatchers.IO) { runCatching { MacWorkspace.activateSelf() } }
            window.toFront()
            // 焦点必须落到窗口内的内容组件上，不能停在窗口框架上——否则录制快捷键、
            // 输入历史上限都会「按下去没反应」（见 [focusKeyboardTarget]）。
            // 窗口刚显示时它还没成为 focused window，请求会被拒，因此跨帧重试到成功为止。
            repeat(FOCUS_TARGET_ATTEMPTS) {
                if (focusKeyboardTarget(window)) return@LaunchedEffect
                withFrameNanos { }
            }
        }

        SettingsScreen(
            state = state,
            onAction = viewModel::onAction,
            captureShortcutKey = viewModel::captureShortcutKey,
            darkTheme = rememberClipperDarkTheme(state.settings.themeMode, host.systemDark),
            titleBarDragModifier = dragTitleBar,
        )
    }
}
