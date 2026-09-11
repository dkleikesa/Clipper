package com.qcmian.clipper.desktop.viewmodel

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qcmian.clipper.data.source.ScreenRect
import com.qcmian.clipper.desktop.domain.CYCLE_INTERVAL_MILLIS
import com.qcmian.clipper.desktop.domain.CYCLE_START_DELAY_MILLIS
import com.qcmian.clipper.desktop.domain.FOCUS_GRACE_MILLIS
import com.qcmian.clipper.desktop.domain.InitialPanelHeight
import com.qcmian.clipper.desktop.domain.MODIFIER_POLL_MILLIS
import com.qcmian.clipper.desktop.domain.NS_MODIFIER_MASK
import com.qcmian.clipper.desktop.domain.PopupMode
import com.qcmian.clipper.desktop.domain.RESIZE_SETTLE_MILLIS
import com.qcmian.clipper.desktop.domain.autoWindowSize
import com.qcmian.clipper.desktop.domain.cursorPosition
import com.qcmian.clipper.desktop.domain.nearlyEquals
import com.qcmian.clipper.desktop.domain.resolvePosition
import com.qcmian.clipper.desktop.domain.screenBounds
import com.qcmian.clipper.di.AppContainer
import com.qcmian.clipper.macos.GlobalShortcut
import com.qcmian.clipper.macos.MacGlobalHotKey
import com.qcmian.clipper.macos.MacWorkspace
import com.qcmian.clipper.ui.ClipperController
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 桌面外壳的 UI 状态（下行）：视图（[com.qcmian.clipper.desktop.ui.ClipperWindow]）只读取它。
 *
 * 注意：窗口的尺寸与位置不在这里——它们由 Compose Desktop 的 [WindowState] 持有，
 * ViewModel 直接读写该对象，属于与平台 API 的桥接，而非 UI 状态。
 */
data class DesktopShellUiState(
    /** 面板是否显示。 */
    val windowVisible: Boolean = true,

    /** 预览滑出面板是否打开；由 `App` 上报，仅用于计算窗口宽度。 */
    val previewOpen: Boolean = false,

    /** 内容希望得到的高度（由 `HistoryScreen` 上报）。 */
    val preferredHeight: Dp = InitialPanelHeight,

    /** 预览是否改从左侧滑出（右侧放不下时）。 */
    val previewOnLeft: Boolean = false,

    /** 当前弹窗交互阶段（对应 Maccy 的 `PopupState`）。 */
    val popupMode: PopupMode = PopupMode.TOGGLE,
)

/**
 * 桌面外壳的 ViewModel：窗口的可见性、位置、尺寸，全局热键状态机与焦点恢复都集中在这里，
 * 对应 Maccy 的 `FloatingPanel` 加上 `AppDelegate` 里与窗口相关的部分。
 *
 * 状态管理遵循单向数据流：对外以 [StateFlow] 暴露单一 [DesktopShellUiState]（状态下行），
 * 视图通过本类的意图函数上报事件（事件上行）。仅两处 Compose snapshot 状态是平台桥接：
 * [windowState]（Compose Desktop 窗口 API 本身）与 `controller.hostUiState`（由 `App` 写入
 * 的投影），对它们的观察统一经 `snapshotFlow` 桥接为 Flow 后再与 [uiState] 组合。
 *
 * 定位与尺寸这类纯计算放在同包的 Model（`WindowPlacement` / `WindowSizing`）里。
 *
 * @param windowState Compose Desktop 的窗口状态持有者；由宿主创建后交给本类读写，
 *   因为窗口的尺寸与位置正是「视图模型」要驱动的东西。
 */
class DesktopShellViewModel(
    private val container: AppContainer,
    private val controller: ClipperController,
    /** 由宿主创建、交给本类读写的窗口状态（位置 / 尺寸）。 */
    val windowState: WindowState,
) : ViewModel() {
    private val native = container.native

    private val _uiState = MutableStateFlow(DesktopShellUiState())
    val uiState: StateFlow<DesktopShellUiState> = _uiState.asStateFlow()

    /** 用户拖动过窗口之后固定下来的尺寸；为 `null` 时高度跟随内容。 */
    private val customSize = MutableStateFlow<DpSize?>(null)

    /** 应用自己最后设定过的尺寸，任何其它尺寸都说明是用户拖动。 */
    private val lastAppliedSize = MutableStateFlow<DpSize?>(null)

    /** 紧跟在程序化调整之后的尺寸通知不应计入。 */
    private var ignoreResizesUntil = 0L

    private val lastPosition = MutableStateFlow<WindowPosition.Absolute?>(null)
    private val frontmostWindowRect = MutableStateFlow<ScreenRect?>(null)

    /** 面板显示前最前的那个外部应用 pid，隐藏时用于把焦点还回去。 */
    private var previousAppPid = -1L

    private var lastFocusGainedAt = System.currentTimeMillis()

    private val settings get() = controller.hostUiState.settings

    init {
        controller.resetPositionAction = { lastPosition.value = null }
        captureFrontmostWindow()
        viewModelScope.launch { observeShortcut() }
        viewModelScope.launch { observeWindowSize() }
        viewModelScope.launch { observeUserResize() }
        viewModelScope.launch { observePlacement() }
        viewModelScope.launch { observePreviewSide() }
        viewModelScope.launch { observeShowRequests() }
    }

    // ---------------------------------------------------------------------------------
    // 意图函数：视图与托盘转发进来的命令（事件上行）
    // ---------------------------------------------------------------------------------

    /** 全局热键：未显示则打开；已显示则循环，稳定显示后再按则把窗口移到鼠标位置。 */
    fun onHotKeyPressed() {
        val state = _uiState.value
        if (!state.windowVisible) {
            captureFrontmostWindow()
            _uiState.update { it.copy(windowVisible = true, popupMode = PopupMode.OPENING) }
            controller.requestOpen()
        } else {
            when (state.popupMode) {
                // 第一次重复：切到循环模式并高亮下一条。
                PopupMode.OPENING -> {
                    _uiState.update { it.copy(popupMode = PopupMode.CYCLE) }
                    controller.requestCycle()
                }

                PopupMode.CYCLE -> controller.requestCycle()

                // 面板已稳定显示：再按快捷键把窗口移动到鼠标所在位置。
                PopupMode.TOGGLE ->
                    windowState.position = cursorPosition(windowState.size, settings.popupScreen)
            }
        }
    }

    /** 显示面板（不进入循环模式）。对应托盘菜单的「显示 Clipper」。 */
    fun showPanel() {
        captureFrontmostWindow()
        _uiState.update { it.copy(windowVisible = true, popupMode = PopupMode.TOGGLE) }
    }

    /** 隐藏面板。[restoreFocus] 为 `false`（因点击别处而失焦）时不抢回焦点。 */
    fun hidePanel(restoreFocus: Boolean = true) {
        val pid = previousAppPid
        _uiState.update { it.copy(windowVisible = false, popupMode = PopupMode.TOGGLE) }
        // `FloatingPanel.close()`：关闭弹窗时一并关闭预览。
        controller.requestHide()
        controller.clearSearch()
        // 把焦点还给此前聚焦的应用：合成粘贴（⌘V）才会落到它上面。
        if (restoreFocus && pid > 0) runCatching { MacWorkspace.activate(pid) }
    }

    /** 托盘菜单的「暂停记录 / 恢复记录」。 */
    fun togglePause() = controller.togglePause()

    fun onWindowGainedFocus() {
        lastFocusGainedAt = System.currentTimeMillis()
    }

    /** 对应 `FloatingPanel.resignKey()`：失去焦点即隐藏（有弹窗时不隐藏）。 */
    fun onWindowLostFocus() {
        val state = _uiState.value
        if (!state.windowVisible || controller.hostUiState.isModalOpen) return
        // 忽略面板刚显示之后那一次短暂的失焦。
        if (System.currentTimeMillis() - lastFocusGainedAt < FOCUS_GRACE_MILLIS) return
        // 用户已经点了别处，不能再把焦点抢回来。
        hidePanel(restoreFocus = false)
    }

    /** `App` 上报预览滑出面板的开关状态。 */
    fun onPreviewOpenChanged(open: Boolean) {
        _uiState.update { it.copy(previewOpen = open) }
    }

    /** `App` 上报内容希望得到的高度。 */
    fun onPreferredHeightChanged(height: Dp) {
        _uiState.update { it.copy(preferredHeight = height) }
    }

    /** 退出：应用「退出时清空历史」偏好并等待落盘，然后请求宿主结束进程。 */
    fun quit() {
        controller.quit()
        runBlocking { container.repository.flushNow() }
        controller.requestExit()
    }

    /**
     * 对应 `Popup.handleFlagsChanged`：按住热键的修饰键期间逐条循环，松开时循环模式接受高亮项、
     * 打开中模式退回切换模式。由视图的 `LaunchedEffect(windowVisible, popupMode)` 驱动，
     * 因此 [DesktopShellUiState.popupMode] 变化时会重新进入。
     *
     * Carbon 的 `RegisterEventHotKey` 只在按下时报一次、不会自动重复，因此这里自己按节奏循环，
     * 等价于 Maccy 依赖按键重复事件的行为。
     */
    suspend fun watchModifiers() {
        val state = _uiState.value
        if (!state.windowVisible || state.popupMode == PopupMode.TOGGLE) return

        fun modifiersHeld(): Boolean = native.currentModifierFlags() and NS_MODIFIER_MASK != 0

        when (state.popupMode) {
            PopupMode.OPENING -> {
                // 先观察一小段时间：用户只是轻按一下就松开则不进入循环。
                var held = 0L
                while (held < CYCLE_START_DELAY_MILLIS) {
                    delay(MODIFIER_POLL_MILLIS)
                    if (!modifiersHeld()) {
                        _uiState.update { it.copy(popupMode = PopupMode.TOGGLE) }
                        return
                    }
                    held += MODIFIER_POLL_MILLIS
                }
                // 修饰键一直按着：进入循环并高亮下一条。
                _uiState.update { it.copy(popupMode = PopupMode.CYCLE) }
                controller.requestCycle()
            }

            PopupMode.CYCLE -> {
                while (true) {
                    delay(CYCLE_INTERVAL_MILLIS)
                    if (!modifiersHeld()) {
                        // 松开修饰键：接受（粘贴）高亮项。
                        controller.requestAccept()
                        _uiState.update { it.copy(popupMode = PopupMode.TOGGLE) }
                        return
                    }
                    controller.requestCycle()
                }
            }

            PopupMode.TOGGLE -> return
        }
    }

    // ---------------------------------------------------------------------------------
    // 观察：把状态变化翻译成窗口动作
    // ---------------------------------------------------------------------------------

    /** 用户录制了不同的快捷键时重新注册全局热键。 */
    private suspend fun observeShortcut() {
        snapshotFlow { settings.popupShortcut }.collectLatest { spec ->
            val handle = GlobalShortcut.fromSpec(spec)?.let { shortcut ->
                MacGlobalHotKey.register(shortcut) { onHotKeyPressed() }
            }
            try {
                awaitCancellation()
            } finally {
                handle?.unregister()
            }
        }
    }

    /** 宽度 / 高度跟随内容与设置；用户拖动过窗口时保持用户选定的尺寸。 */
    private suspend fun observeWindowSize() {
        combine(
            customSize,
            uiState,
            snapshotFlow { controller.hostUiState.settings },
            snapshotFlow { windowState.position },
        ) { custom, state, settings, position ->
            val top = (position as? WindowPosition.Absolute)?.y?.value?.toInt()
                ?: screenBounds(settings.popupScreen).y
            custom ?: autoWindowSize(
                settings = settings,
                previewOpen = state.previewOpen,
                preferredHeight = state.preferredHeight,
                top = top,
            )
        }.collect { target ->
            lastAppliedSize.value = target
            if (windowState.size != target) {
                ignoreResizesUntil = System.currentTimeMillis() + RESIZE_SETTLE_MILLIS
                windowState.size = target
            }
        }
    }

    /** 与应用请求的尺寸不一致的尺寸说明是用户拖动，此后固定为用户选定的大小。 */
    private suspend fun observeUserResize() {
        snapshotFlow { windowState.size }.collect { size ->
            val applied = lastAppliedSize.value ?: return@collect
            if (System.currentTimeMillis() >= ignoreResizesUntil) return@collect
            if (!size.nearlyEquals(applied)) customSize.value = size
        }
    }

    /** 每次显示面板都按偏好重新定位；隐藏时记住它的位置。 */
    private suspend fun observePlacement() {
        combine(
            uiState.map { it.windowVisible }.distinctUntilChanged(),
            snapshotFlow { controller.hostUiState.settings }
                .map { Pair(it.popupPosition, it.popupScreen) }
                .distinctUntilChanged(),
        ) { visible, (position, screenIndex) ->
            Triple(visible, position, screenIndex)
        }.collect { (visible, position, screenIndex) ->
            if (visible) {
                windowState.position = resolvePosition(
                    position = position,
                    size = windowState.size,
                    lastPosition = lastPosition.value,
                    screenIndex = screenIndex,
                    windowRect = frontmostWindowRect.value,
                )
            } else {
                lastPosition.value = windowState.position as? WindowPosition.Absolute
            }
        }
    }

    /** `SlideoutController.computePlacement`：预览会溢出屏幕右边缘时改把它停靠在左侧。 */
    private suspend fun observePreviewSide() {
        combine(
            uiState.map { Pair(it.previewOpen, it.windowVisible) }.distinctUntilChanged(),
            snapshotFlow { windowState.position },
            snapshotFlow { controller.hostUiState.settings },
        ) { (previewOpen, visible), position, settings ->
            val absolute = position as? WindowPosition.Absolute
            when {
                !previewOpen || !visible || absolute == null -> false
                else -> {
                    val bounds = screenBounds(settings.popupScreen)
                    val right = absolute.x.value + settings.windowWidth + settings.previewWidth
                    right > bounds.x + bounds.width
                }
            }
        }.collect { onLeft ->
            _uiState.update { if (it.previewOnLeft != onLeft) it.copy(previewOnLeft = onLeft) else it }
        }
    }

    /**
     * 托盘请求显示面板：托盘与窗口持有各自独立的 ViewModel，菜单动作经
     * [ClipperController.requestShow] 通道转达，这里与热键打开共用 [showPanel] 路径。
     */
    private suspend fun observeShowRequests() {
        snapshotFlow { controller.showRequests }.collectLatest { count ->
            if (count > 0) showPanel()
        }
    }

    /**
     * `PopupPosition.window` 锚定到用户在使用弹窗之前所在的应用，因此必须趁那个应用仍处于最前
     * 时抓取它的窗口框；同时记住它，好在隐藏时把焦点还回去。
     */
    private fun captureFrontmostWindow() {
        frontmostWindowRect.value = runCatching { native.frontmostWindowRect() }.getOrNull()
        val pid = runCatching { MacWorkspace.frontmostExternalPid() }.getOrDefault(-1L)
        // 抓不到外部应用时（例如本应用已在最前）保留上一次记录。
        if (pid > 0) previousAppPid = pid
    }
}
