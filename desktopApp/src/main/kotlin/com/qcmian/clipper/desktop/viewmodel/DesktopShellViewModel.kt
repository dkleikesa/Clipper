package com.qcmian.clipper.desktop.viewmodel

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qcmian.clipper.core.data.source.ScreenRect
import com.qcmian.clipper.desktop.domain.CYCLE_INTERVAL_MILLIS
import com.qcmian.clipper.desktop.domain.CYCLE_START_DELAY_MILLIS
import com.qcmian.clipper.desktop.domain.FOCUS_GRACE_MILLIS
import com.qcmian.clipper.desktop.domain.InitialPanelHeight
import com.qcmian.clipper.desktop.domain.MODIFIER_POLL_MILLIS
import com.qcmian.clipper.desktop.domain.MOUSE_BUTTONS_MASK
import com.qcmian.clipper.desktop.domain.OUTSIDE_CLICK_POLL_MILLIS
import com.qcmian.clipper.desktop.domain.PANEL_WINDOW_TITLE
import com.qcmian.clipper.desktop.domain.PopupMode
import com.qcmian.clipper.desktop.domain.SYSTEM_APPEARANCE_POLL_MILLIS
import com.qcmian.clipper.desktop.domain.nsModifierMask
import com.qcmian.clipper.desktop.domain.RESIZE_SETTLE_MILLIS
import com.qcmian.clipper.desktop.domain.TRAY_CLICK_GRACE_MILLIS
import com.qcmian.clipper.desktop.domain.autoWindowSize
import com.qcmian.clipper.desktop.domain.cursorPosition
import com.qcmian.clipper.desktop.domain.nearlyEquals
import com.qcmian.clipper.desktop.domain.resolvePosition
import com.qcmian.clipper.desktop.domain.screenBounds
import com.qcmian.clipper.di.AppContainer
import com.qcmian.clipper.core.platform.macos.GlobalShortcut
import com.qcmian.clipper.core.platform.macos.MacAppearance
import com.qcmian.clipper.core.platform.macos.MacGlobalHotKey
import com.qcmian.clipper.core.platform.macos.MacOutsideClickMonitor
import com.qcmian.clipper.core.platform.macos.MacWorkspace
import com.qcmian.clipper.host.HotkeyController
import com.qcmian.clipper.host.WindowController
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

    /** 当前这次可见是否由托盘（点击 / 菜单）触发。热键呼出时托盘不画按下态。 */
    val panelOpenedByTray: Boolean = false,

    /** 预览滑出面板是否打开；由 `App` 上报，仅用于计算窗口宽度。 */
    val previewOpen: Boolean = false,

    /** 内容希望得到的高度（由 `HistoryScreen` 上报）。 */
    val preferredHeight: Dp = InitialPanelHeight,

    /** 预览是否改从左侧滑出（右侧放不下时）。 */
    val previewOnLeft: Boolean = false,

 /** 当前弹窗交互阶段。 */
    val popupMode: PopupMode = PopupMode.TOGGLE,
)

/**
 * 桌面外壳的 ViewModel：窗口的可见性、位置、尺寸，全局热键状态机与焦点恢复都集中在这里，
 * 加上 `AppDelegate` 里与窗口相关的部分。
 *
 * 状态管理遵循单向数据流：对外以 [StateFlow] 暴露单一 [DesktopShellUiState]（状态下行），
 * 视图通过本类的意图函数上报事件（事件上行）。仅一处 Compose snapshot 状态是平台桥接：
 * [windowState]（Compose Desktop 窗口 API 本身），经 `snapshotFlow` 桥接为 Flow 后
 * 与 [uiState]、控制器的 StateFlow 组合。
 *
 * 定位与尺寸这类纯计算放在同包的 Model（`WindowPlacement` / `WindowSizing`）里。
 *
 * @param windowState Compose Desktop 的窗口状态持有者；由宿主创建后交给本类读写，
 *   因为窗口的尺寸与位置正是「视图模型」要驱动的东西。
 */
class DesktopShellViewModel(
    private val container: AppContainer,
    /** 窗口事件通道：显示 / 隐藏 / 退出请求与面板投影。 */
    private val panel: WindowController,
    /** 按键意图通道：热键状态机向面板转发打开 / 循环 / 接受。 */
    private val hotkey: HotkeyController,
    /** 由宿主创建、交给本类读写的窗口状态（位置 / 尺寸）。 */
    val windowState: WindowState,
) : ViewModel() {
    private val native = container.native

    private val _uiState = MutableStateFlow(DesktopShellUiState())
    val uiState: StateFlow<DesktopShellUiState> = _uiState.asStateFlow()

    /**
     * 系统外观是否为深色；`null` 表示未知（原生层不可用）。Compose 的
     * `isSystemInDarkTheme()` 在桌面端不会实时跟随系统外观变化，
     * 「跟随系统」主题模式改由这里轮询 `AppleInterfaceStyle` 驱动。
     */
    private val _systemDark = MutableStateFlow<Boolean?>(null)
    val systemDark: StateFlow<Boolean?> = _systemDark.asStateFlow()

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

    /** 最近一次「点击面板之外」导致的收起时刻，用于识别同一次点击触发的托盘切换。 */
    private var lastOutsideHideAtMillis = 0L

    init {
        panel.resetPositionAction = { lastPosition.value = null }
        captureFrontmostWindow()
        // 同步预读一次系统外观，避免首帧用回退路径导致主题闪一下。
        _systemDark.value = runCatching { MacWorkspace.isSystemAppearanceDark() }.getOrNull()
        viewModelScope.launch { observeShortcut() }
        viewModelScope.launch { observeWindowSize() }
        viewModelScope.launch { observeUserResize() }
        viewModelScope.launch { observePlacement() }
        viewModelScope.launch { observePreviewSide() }
        viewModelScope.launch { observeToggleRequests() }
        viewModelScope.launch { observeOutsideClicks() }
        viewModelScope.launch { observeSystemAppearance() }
    }

    // ---------------------------------------------------------------------------------
    // 意图函数：视图与托盘转发进来的命令（事件上行）
    // ---------------------------------------------------------------------------------

    /** 全局热键：未显示则打开；已显示则循环，稳定显示后再按则把窗口移到鼠标位置。 */
    fun onHotKeyPressed() {
        val state = _uiState.value
        if (!state.windowVisible) {
            captureFrontmostWindow()
            _uiState.update {
                it.copy(windowVisible = true, popupMode = PopupMode.OPENING, panelOpenedByTray = false)
            }
            hotkey.requestOpen()
        } else {
            when (state.popupMode) {
                // 第一次重复：切到循环模式并高亮下一条。
                PopupMode.OPENING -> {
                    _uiState.update { it.copy(popupMode = PopupMode.CYCLE) }
                    hotkey.requestCycle()
                }

                PopupMode.CYCLE -> hotkey.requestCycle()

                // 面板已稳定显示：再按快捷键把窗口移动到鼠标所在位置。
                PopupMode.TOGGLE ->
                    windowState.position =
                        cursorPosition(windowState.size, panel.hostUiState.value.settings.popupScreen)
            }
        }
    }

    /** 显示面板（不进入循环模式）。对应托盘菜单的「显示 Clipper」，属托盘触发。 */
    fun showPanel() {
        captureFrontmostWindow()
        _uiState.update {
            it.copy(windowVisible = true, popupMode = PopupMode.TOGGLE, panelOpenedByTray = true)
        }
        // 与热键打开一样通知面板：搜索框要重新获得焦点，弹出后立刻就能输入。
        hotkey.requestOpen()
    }

    /** 点击菜单栏图标：面板已显示则收起，否则呼出。 */
    fun togglePanel() {
        // 面板外的点击收起与托盘切换可能来自同一次点击（点在本应用托盘图标上）：
        // 轮询先收起、托盘回调随后到达，这里跳过，避免刚收起又被重新打开。
        if (System.currentTimeMillis() - lastOutsideHideAtMillis < TRAY_CLICK_GRACE_MILLIS) return
        if (_uiState.value.windowVisible) hidePanel() else showPanel()
    }

    /** 隐藏面板。[restoreFocus] 为 `false`（因点击别处而失焦）时不抢回焦点。 */
    fun hidePanel(restoreFocus: Boolean = true) {
        val pid = previousAppPid
        _uiState.update {
            it.copy(windowVisible = false, popupMode = PopupMode.TOGGLE, panelOpenedByTray = false)
        }
        // `FloatingPanel.close()`：关闭弹窗时一并关闭预览。
        panel.requestHide()
        panel.clearSearch()
        // 把焦点还给此前聚焦的应用：合成粘贴（⌘V）才会落到它上面。
        if (restoreFocus && pid > 0) runCatching { MacWorkspace.activate(pid) }
    }

    fun onWindowGainedFocus() {
        lastFocusGainedAt = System.currentTimeMillis()
        // 对应 Maccy `HistoryListView.onChange(of: scenePhase)`：面板真正成为 key window 时，
        // 搜索框重新获得焦点并选中第一条。聚焦跟着「窗口取得键盘焦点」走，而不是跟着
        // 「打开意图」走——后者可能落在窗口显示之前，`requestFocus()` 会静默失效，
        // 表现为「打开后偶尔打不了字」。
        if (_uiState.value.windowVisible) hotkey.requestOpen()
    }

    /** 对应 `FloatingPanel.resignKey()`：失去焦点即隐藏（有弹窗时不隐藏）。 */
    fun onWindowLostFocus() {
        val state = _uiState.value
        if (!state.windowVisible || panel.hostUiState.value.isModalOpen) return
        // 忽略面板刚显示之后那一次短暂的失焦。
        if (System.currentTimeMillis() - lastFocusGainedAt < FOCUS_GRACE_MILLIS) return
        // 刚点过菜单栏图标：这次失焦是点击本身造成的，收起与否交给 [togglePanel] 决定。
        // 否则会先在这里被隐藏、再被 toggle 重新打开，看起来就是「点托盘关不掉」。
        if (System.currentTimeMillis() - panel.lastTrayClickAtMillis < TRAY_CLICK_GRACE_MILLIS) return
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
        panel.quit()
        runBlocking { container.repository.flushNow() }
        panel.requestExit()
    }

    /**
     * 对应 `Popup.handleFlagsChanged`：按住热键的修饰键期间逐条循环，松开时循环模式接受高亮项、
     * 打开中模式退回切换模式。由视图的 `LaunchedEffect(windowVisible, popupMode)` 驱动，
     * 因此 [DesktopShellUiState.popupMode] 变化时会重新进入。
     *
     * Carbon 的 `RegisterEventHotKey` 只在按下时报一次、不会自动重复，因此这里自己按节奏循环，
 * 效果与依赖按键自动重复的实现一致。
     */
    suspend fun watchModifiers() {
        val state = _uiState.value
        if (!state.windowVisible || state.popupMode == PopupMode.TOGGLE) return

        // 只有呼出快捷键要求的完整修饰键组合仍被按住，循环才继续：
        // 3 键热键松开其中任意一个，就该立刻接受并停止，而不是「任意修饰键还按着」。
        val requiredMask = nsModifierMask(panel.hostUiState.value.settings.popupShortcut)

        fun modifiersHeld(): Boolean =
            requiredMask != 0 && native.currentModifierFlags() and requiredMask == requiredMask

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
                hotkey.requestCycle()
            }

            PopupMode.CYCLE -> {
                while (true) {
                    delay(CYCLE_INTERVAL_MILLIS)
                    if (!modifiersHeld()) {
                        // 松开修饰键：接受（粘贴）高亮项。
                        hotkey.requestAccept()
                        _uiState.update { it.copy(popupMode = PopupMode.TOGGLE) }
                        return
                    }
                    hotkey.requestCycle()
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
        panel.hostUiState
            .map { it.settings.popupShortcut }
            .distinctUntilChanged()
            .collectLatest { spec ->
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
            panel.hostUiState.map { it.settings }.distinctUntilChanged(),
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
            panel.hostUiState
                .map { Pair(it.settings.popupPosition, it.settings.popupScreen) }
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
            panel.hostUiState.map { it.settings }.distinctUntilChanged(),
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
     * 托盘请求切换面板：托盘与窗口逻辑隔离，点击经
     * [WindowController.requestToggle] 通道转达，由这里按当前可见性决定呼出还是收起。
     */
    private suspend fun observeToggleRequests() {
        panel.toggleRequests.collect { count ->
            if (count > 0) togglePanel()
        }
    }

    /**
     * 观察面板外的点击：优先用 NSEvent 全局 / 本地监视器（事件驱动、零轮询），
     * 安装失败时退回 `pressedMouseButtons` 轮询。收起前检查弹窗状态——
     * 偏好设置等弹窗的点击属于本应用，不应收起面板。
     */
    private suspend fun observeOutsideClicks() {
        if (MacOutsideClickMonitor.install(PANEL_WINDOW_TITLE)) {
            MacOutsideClickMonitor.outsideClicks.collect {
                if (uiState.value.windowVisible && !panel.hostUiState.value.isModalOpen) {
                    lastOutsideHideAtMillis = System.currentTimeMillis()
                    hidePanel(restoreFocus = false)
                }
            }
            return
        }

        // 轮询兜底：面板可见期间观察鼠标按压，始于面板之外的按压在其外松开就收起。
        combine(
            uiState.map { it.windowVisible }.distinctUntilChanged(),
            panel.hostUiState.map { it.isModalOpen }.distinctUntilChanged(),
        ) { visible, modal -> visible && !modal }
            .distinctUntilChanged()
            .collectLatest { watching ->
                if (!watching) return@collectLatest
                // 起始就处于按压中（例如热键呼出时用户正在别处拖拽）：
                // 视为始于面板内部，松开不收起。
                var pressed = MacWorkspace.pressedMouseButtons() and MOUSE_BUTTONS_MASK != 0L
                var pressStartedInside = true
                while (true) {
                    delay(OUTSIDE_CLICK_POLL_MILLIS)
                    val nowPressed = MacWorkspace.pressedMouseButtons() and MOUSE_BUTTONS_MASK != 0L
                    if (nowPressed == pressed) continue
                    pressed = nowPressed
                    if (pressed) {
                        pressStartedInside = isPointerInsidePanel()
                    } else if (!pressStartedInside) {
                        lastOutsideHideAtMillis = System.currentTimeMillis()
                        hidePanel(restoreFocus = false)
                        return@collectLatest
                    }
                }
            }
    }

    /** 指针是否落在面板窗口内；位置或判定器不可用时视为在内（宁可漏收起，不可误收起）。 */
    private fun isPointerInsidePanel(): Boolean {
        val point = runCatching { java.awt.MouseInfo.getPointerInfo()?.location }.getOrNull()
            ?: return true
        return panel.panelContainsPoint?.invoke(point.x.toDouble(), point.y.toDouble()) ?: true
    }

    /**
     * 「跟随系统」主题模式：优先用系统通知（`AppleInterfaceThemeChangedNotification`）
     * 事件驱动；通知注册失败时退回 1 秒轮询。
     */
    private suspend fun observeSystemAppearance() {
        if (MacAppearance.install()) {
            MacAppearance.systemDark.collect { _systemDark.value = it }
            return
        }
        while (true) {
            _systemDark.value = runCatching { MacWorkspace.isSystemAppearanceDark() }.getOrNull()
            delay(SYSTEM_APPEARANCE_POLL_MILLIS)
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
