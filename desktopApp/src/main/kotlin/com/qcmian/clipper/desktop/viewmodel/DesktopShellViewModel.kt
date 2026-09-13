package com.qcmian.clipper.desktop.viewmodel

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.ui.Popup
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
import com.qcmian.clipper.core.settings.PopupPosition
import com.qcmian.clipper.desktop.domain.autoWindowSize
import com.qcmian.clipper.desktop.domain.constrained
import com.qcmian.clipper.desktop.domain.contentWidthOf
import com.qcmian.clipper.desktop.domain.cursorPosition
import com.qcmian.clipper.desktop.domain.nearlyEquals
import com.qcmian.clipper.desktop.domain.resolvePosition
import com.qcmian.clipper.desktop.domain.screenBounds
import com.qcmian.clipper.desktop.domain.slideoutWidthOf
import com.qcmian.clipper.di.AppContainer
import com.qcmian.clipper.core.platform.macos.GlobalShortcut
import com.qcmian.clipper.core.platform.macos.MacAppearance
import com.qcmian.clipper.core.platform.macos.MacGlobalHotKey
import com.qcmian.clipper.core.platform.macos.MacModifierMonitor
import com.qcmian.clipper.core.platform.macos.MacOutsideClickMonitor
import com.qcmian.clipper.core.platform.macos.MacStatusItem
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.roundToInt

/**
 * 桌面外壳的 UI 状态（下行）：视图（[com.qcmian.clipper.desktop.ui.ClipperWindow]）只读取它。
 *
 * 注意：窗口的尺寸与位置不在这里——它们由 Compose Desktop 的 [WindowState] 持有，
 * ViewModel 直接读写该对象，属于与平台 API 的桥接，而非 UI 状态。
 */
data class DesktopShellUiState(
    /** 面板是否显示。启动时隐藏，静默驻留等待热键 / 托盘唤起。 */
    val windowVisible: Boolean = false,

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

    /** 应用自己最后设定过的尺寸，任何其它尺寸都说明是用户拖动。 */
    private val lastAppliedSize = MutableStateFlow<DpSize?>(null)

    /**
     * 用户正在拖动窗口边缘时的「静默期」截止时刻。拖动是一个连续过程，期间不接受任何
     * 程序化的尺寸改动，否则会和用户的手抢同一个窗口。
     */
    private var userResizeUntil = 0L

    /**
     * 主列表（内容区）左上角的锚点。预览打开时窗口以此为准向一侧展开，因此主列表本身不会移动；
     * 面板隐藏后清空，下次显示时重新取。
     */
    private var contentAnchor: WindowPosition.Absolute? = null

    /** 上一次取锚点所用的位置偏好；变化时要重新取。 */
    private var lastPlacementSignature: Pair<PopupPosition, Int>? = null

    /**
     * 本次显示是否由点击托盘图标触发。是的话位置直接锚定菜单栏图标：点托盘那一刻光标就在
     * 图标上，「光标位置」与「菜单栏图标」本来就是同一个落点，统一走图标锚点更一致。
     *
     * 热键回调运行在 AppKit 主线程，而定位在 EDT 上，故标记为 `@Volatile`。
     */
    @Volatile
    private var openedByTray = false

    /** 面板显示前最前的那个外部应用 pid，隐藏时用于把焦点还回去。 */
    private var previousAppPid = -1L

    private var lastFocusGainedAt = System.currentTimeMillis()

    /** 最近一次「点击面板之外」导致的收起时刻，用于识别同一次点击触发的托盘切换。 */
    private var lastOutsideHideAtMillis = 0L

    /**
     * 呼出热键的主键（`⇧⌘C` 里的 `C`）是否仍按着。
     *
     * 它只在能收到 `kEventHotKeyReleased` 时有用（macOS 实际不发这个事件，见
     * [MacGlobalHotKey.register]）：能收到就表示主键松手、停在当前这一条；收不到时恒为
     * true，一切以修饰键为准——「松手」= 修饰键不再完整。
     */
    private var mainKeyDown = false

    init {
        captureFrontmostWindow()
        // 同步预读一次系统外观，避免首帧用回退路径导致主题闪一下。
        _systemDark.value = runCatching { MacWorkspace.isSystemAppearanceDark() }.getOrNull()
        viewModelScope.launch { observeShortcut() }
        viewModelScope.launch { observeWindowSize() }
        viewModelScope.launch { observeUserResize() }
        viewModelScope.launch { observePlacement() }
        viewModelScope.launch { observeToggleRequests() }
        viewModelScope.launch { observeOutsideClicks() }
        viewModelScope.launch { observeSystemAppearance() }
    }

    // ---------------------------------------------------------------------------------
    // 意图函数：视图与托盘转发进来的命令（事件上行）
    // ---------------------------------------------------------------------------------

    /** 全局热键：未显示则打开；已显示则逐条循环，托盘呼出的面板再按则把窗口移到鼠标位置。 */
    fun onHotKeyPressed() {
        val state = _uiState.value
        mainKeyDown = true
        if (!state.windowVisible) {
            captureFrontmostWindow()
            openedByTray = false
            _uiState.update {
                it.copy(windowVisible = true, popupMode = PopupMode.OPENING, panelOpenedByTray = false)
            }
            hotkey.requestOpen()
        } else {
            when (state.popupMode) {
                // 呼出之后还接着按：往下选一条，仍留在「打开中」等主键松手或按满时长。
                PopupMode.OPENING -> hotkey.requestCycle()

                PopupMode.CYCLE -> hotkey.requestCycle()

                // 热键呼出的面板：再按（含连按）都是「往下选一条」，绝不挪窗口——否则
                // 连按几下窗口就跟着光标跑了。托盘呼出的面板才是原来那条「移到鼠标位置」。
                PopupMode.TOGGLE -> if (state.panelOpenedByTray) {
                    moveToCursor()
                } else {
                    hotkey.requestCycle()
                }
            }
        }
    }

    /**
     * 全局热键松开：主键松手（收到就用，见 [MacGlobalHotKey.register] 说明它未必会到）。
     *
     * 「松手选中」不依赖它——那是修饰键的事，这里只是让循环能提前停在当前这一条。
     */
    fun onHotKeyReleased() {
        mainKeyDown = false
    }

    /** 显示面板（不进入循环模式）。对应托盘菜单的「显示 Clipper」，属托盘触发。 */
    fun showPanel() {
        captureFrontmostWindow()
        openedByTray = true
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
     * 对应 `Popup.handleFlagsChanged`：整组键按住期间逐条循环，全部松开时循环模式选中高亮项、
     * 打开中模式退回切换模式。由视图的 `LaunchedEffect(windowVisible, popupMode)` 驱动，
     * 因此 [DesktopShellUiState.popupMode] 变化时会重新进入。
     *
     * Carbon 的 `RegisterEventHotKey` 只在按下时报一次、不会自动重复，因此这里自己按节奏循环，
     * 效果与依赖按键自动重复的实现一致。
     */
    suspend fun watchModifiers() {
        val state = _uiState.value
        if (!state.windowVisible || state.popupMode == PopupMode.TOGGLE) return

        // 整组键（`⇧⌘C` 的修饰键 + 主键）都按着才算「按住」，都松开才算「松手」。
        val requiredMask = nsModifierMask(panel.hostUiState.value.settings.popupShortcut)

        // 修饰键读两条来源，任一读到「不完整」就停止：
        // - `flagsChanged` 事件（[MacModifierMonitor]）：热键按住期间轮询可能读不到松手，
        //   事件能读到，这是「一直循环停不下来」的修复；
        // - 轮询 `NSEvent.modifierFlags`：改造前的唯一来源，保留它意味着监视器收不到事件时
        //   也不会比改前更差。
        val monitored = MacModifierMonitor.install()

        fun modifiersHeld(): Boolean {
            if (requiredMask == 0) return false
            if (native.currentModifierFlags() and requiredMask != requiredMask) return false
            if (!monitored) return true
            return MacModifierMonitor.flags.value and requiredMask == requiredMask
        }

        /**
         * 整组键都按着：这才是「按住」，循环才会往前推进。
         *
         * 主键的松手只用来「能收到就停一停」——macOS 不会把 `kEventHotKeyReleased` 交给应用
         * （Carbon 只发按下），收不到时这个条件就等于只看修饰键。
         */
        fun comboHeld(): Boolean = modifiersHeld() && mainKeyDown

        when (state.popupMode) {
            PopupMode.OPENING -> {
                // 开始这次按住之前先对齐一次：修饰键是在别的应用里按下的，那几次 `flagsChanged`
                // 并没有派发到本应用，事件驱动的值这时还是「什么都没按」。
                if (monitored) MacModifierMonitor.resync()
                // 先观察一小段时间：轻按一下就松开不算按住，面板停在切换模式，
                // 用户接着按就是逐条往下选。
                var held = 0L
                while (held < CYCLE_START_DELAY_MILLIS) {
                    delay(MODIFIER_POLL_MILLIS)
                    if (!comboHeld()) {
                        _uiState.update { it.copy(popupMode = PopupMode.TOGGLE) }
                        return
                    }
                    held += MODIFIER_POLL_MILLIS
                }
                // 一直按着：进入循环并高亮下一条。
                _uiState.update { it.copy(popupMode = PopupMode.CYCLE) }
                hotkey.requestCycle()
            }

            PopupMode.CYCLE -> {
                while (true) {
                    delay(CYCLE_INTERVAL_MILLIS)
                    // 松手（修饰键不再完整）：选中高亮项并收起，效果与鼠标点击这一刻的那一
                    // 行相同。这里不能等主键的松手事件——macOS 根本不发那个事件。
                    if (!modifiersHeld()) {
                        hotkey.requestAccept()
                        _uiState.update { it.copy(popupMode = PopupMode.TOGGLE) }
                        return
                    }
                    if (mainKeyDown) hotkey.requestCycle()
                }
            }

            PopupMode.TOGGLE -> return
        }
    }

    // ---------------------------------------------------------------------------------
    // 观察：把状态变化翻译成窗口动作
    // ---------------------------------------------------------------------------------

    /** 面板已稳定显示时再按热键：把窗口移到鼠标处，并同步预览展开所用的锚点。 */
    private fun moveToCursor() {
        val settings = container.repository.settings.value
        val placed = cursorPosition(windowState.size, settings.popupScreen) as WindowPosition.Absolute
        windowState.position = placed
        // 同步「内容区锚点」：否则下次切换预览会按旧锚点摆放，窗口会跳回去。
        contentAnchor = if (_uiState.value.previewOnLeft) {
            WindowPosition.Absolute(placed.x + slideoutWidthOf(settings), placed.y)
        } else {
            placed
        }
        lastPlacementSignature = Pair(settings.popupPosition, settings.popupScreen)
    }

    /** 用户录制了不同的快捷键时重新注册全局热键。 */
    private suspend fun observeShortcut() {
        // 等持久化偏好加载完成，并以仓库中的真实设置为注册来源：
        // hostUiState 是 App 组合时才镜像的投影，启动瞬间仍是默认值（⇧⌘C），
        // 按它注册的热键不是用户真正录制的那个，启动头几秒会「按了没反应」。
        container.repository.settingsLoaded.first { it }
        container.repository.settings
            .map { it.popupShortcut }
            .distinctUntilChanged()
            .collectLatest { spec ->
                val handle = GlobalShortcut.fromSpec(spec)?.let { shortcut ->
                    MacGlobalHotKey.register(
                        shortcut = shortcut,
                        onTrigger = { onHotKeyPressed() },
                        onRelease = { onHotKeyReleased() },
                    )
                }
                try {
                    awaitCancellation()
                } finally {
                    handle?.unregister()
                }
            }
    }

    /** 宽度 / 高度跟随内容与设置；预览打开时窗口额外加宽以容纳滑出面板。 */
    private suspend fun observeWindowSize() {
        combine(
            uiState,
            panel.hostUiState.map { it.settings }.distinctUntilChanged(),
            snapshotFlow { windowState.position },
        ) { state, settings, position ->
            Triple(state, settings, position)
        }.collect { (state, settings, position) ->
            // 用户正在拖边缘：这一刻以他的手为准。程序化改尺寸会和拖动互相打架
            // （两边都在 setSize），拖左边框时尤其明显——位置同时也在变。
            // 手停下来后由 [observeUserResize] 补一次。
            if (userIsResizing()) return@collect
            applyAutoWindowSize(state, settings, position)
        }
    }

    /** 按内容与偏好设定期望的窗口尺寸；与当前尺寸一致时不触碰窗口。 */
    private fun applyAutoWindowSize(
        state: DesktopShellUiState,
        settings: AppSettings,
        position: WindowPosition,
    ) {
        val top = (position as? WindowPosition.Absolute)?.y?.value?.toInt()
            ?: screenBounds(settings.popupScreen).y
        val target = autoWindowSize(
            settings = settings,
            previewOpen = state.previewOpen,
            preferredHeight = state.preferredHeight,
            top = top,
        )
        lastAppliedSize.value = target
        if (windowState.size != target) {
            // 这次改动是程序触发的：紧接着的尺寸通知必然等于 [lastAppliedSize]，
            // [observeUserResize] 按值就能认出它不是用户拖动，不必再用时间窗兜。
            windowState.size = target
        }
    }

    /**
     * 用户拖动窗口边缘改变尺寸：手停下来之后，把最终尺寸记为「自定义尺寸」。
     *
     * 拖动过程中每来一个尺寸通知就写一次偏好，会让整棵界面（含历史搜索）每帧重算，
     * 这正是拖动卡顿的根源。这里按「尺寸持续 [RESIZE_SETTLE_MILLIS] 不变」来判定手已松开，
     * 于是整段拖动只落盘一次；[collectLatest] 负责在下一个尺寸到来时取消上一次等待。
     */
    private suspend fun observeUserResize() {
        snapshotFlow { windowState.size }
            .collectLatest { size ->
                // 与「应用最后设定的尺寸」一致 —— 这次通知是程序自己造成的，直接忽略。
                val applied = lastAppliedSize.value ?: return@collectLatest
                if (size.nearlyEquals(applied)) return@collectLatest

                userResizeUntil = System.currentTimeMillis() + RESIZE_SETTLE_MILLIS
                delay(RESIZE_SETTLE_MILLIS)

                val settings = container.repository.settings.value
                // 预览打开时拖的是整窗宽度，写回前要减掉滑出面板：自定义宽度始终表示主列表宽度。
                val contentWidth = if (_uiState.value.previewOpen) {
                    size.width - slideoutWidthOf(settings)
                } else {
                    size.width
                }
                lastAppliedSize.value = size
                // 用户可能把窗口拖到了别处（拖左边框加宽时窗口位置就会变）：锚点先跟着走。
                // 必须在写设置之前——设置一变，[observePlacement] 可能立刻按旧锚点把窗口拉回去，
                // 等于把刚才的拖动撤销掉。
                rememberContentAnchor()
                container.repository.setSettings(
                    settings.copy(
                        customWindowWidth = contentWidth.value.roundToInt()
                            .coerceAtLeast(Popup.minimumContentWidth.value.toInt()),
                        customWindowHeight = size.height.value.roundToInt(),
                    ),
                )

                userResizeUntil = 0L
                // 用户拖出的尺寸仍要受屏幕约束（例如不能盖住 Dock），补一次程序化尺寸。
                applyAutoWindowSize(
                    state = _uiState.value,
                    settings = container.repository.settings.value,
                    position = windowState.position,
                )
            }
    }

    /** 以当前窗口位置更新「内容区锚点」；面板尚未摆放时不动。 */
    private fun rememberContentAnchor() {
        if (contentAnchor == null) return
        val position = windowState.position as? WindowPosition.Absolute ?: return
        val settings = container.repository.settings.value
        contentAnchor = if (_uiState.value.previewOnLeft) {
            WindowPosition.Absolute(position.x + slideoutWidthOf(settings), position.y)
        } else {
            position
        }
        lastPlacementSignature = Pair(settings.popupPosition, settings.popupScreen)
    }

    /** 用户是否正在手动调整窗口尺寸（含刚停下的一小段静默期）。 */
    private fun userIsResizing(): Boolean = System.currentTimeMillis() < userResizeUntil

    /**
     * 摆放窗口：主列表先按位置偏好定位，预览面板以主列表的边缘为基准展开——
     * 默认在右侧（窗口向右加宽），主列表右边缘放不下时改到左侧（窗口向左加宽），
     * 因此预览永远不会盖住主列表。
     */
    private suspend fun observePlacement() {
        combine(
            uiState.map { Pair(it.windowVisible, it.previewOpen) }.distinctUntilChanged(),
            panel.hostUiState.map { it.settings }.distinctUntilChanged(),
        ) { (visible, previewOpen), settings -> Triple(visible, previewOpen, settings) }
            .collect { (visible, previewOpen, settings) ->
                if (!visible) {
                    // 隐藏后下次显示要重新取锚点，否则会把上一次的旧位置带过来。
                    contentAnchor = null
                    lastPlacementSignature = null
                    return@collect
                }

                val contentWidth = contentWidthOf(settings)
                val slideoutWidth = slideoutWidthOf(settings)
                val bounds = screenBounds(settings.popupScreen)

                // 点托盘图标时直接用图标锚点，和「菜单栏图标」这一项同一套逻辑。
                val position = if (openedByTray) PopupPosition.MENU_BAR else settings.popupPosition

                // 只在「重新显示」或「位置偏好变化」时取锚点；仅切换预览时沿用旧锚点，
                // 主列表不会跟着鼠标或上次的窗口尺寸跳动。
                val signature = Pair(position, settings.popupScreen)
                val anchor = contentAnchor?.takeIf { signature == lastPlacementSignature }
                    ?: (
                        resolvePosition(
                            position = position,
                            size = DpSize(contentWidth, windowState.size.height),
                            screenIndex = settings.popupScreen,
                            statusItem = MacStatusItem.currentAnchor(),
                        ) as WindowPosition.Absolute
                        ).also { lastPlacementSignature = signature }
                contentAnchor = anchor

                // 预览默认停靠右侧；主列表右边缘之外放不下滑出面板时改停靠左侧。
                val previewOnLeft = previewOpen &&
                    anchor.x.value + contentWidth.value + slideoutWidth.value > bounds.x + bounds.width
                _uiState.update {
                    if (it.previewOnLeft == previewOnLeft) it else it.copy(previewOnLeft = previewOnLeft)
                }

                val windowWidth = contentWidth + if (previewOpen) slideoutWidth else 0.dp
                val windowX = if (previewOnLeft) {
                    anchor.x.value - slideoutWidth.value
                } else {
                    anchor.x.value
                }
                windowState.position = constrained(
                    x = windowX.toInt(),
                    y = anchor.y.value.toInt(),
                    size = DpSize(windowWidth, windowState.size.height),
                    bounds = bounds,
                )
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
     * 记住面板显示前处于最前的那个外部应用，隐藏时把焦点还回去，
     * 这样「自动粘贴」的 ⌘V 才会落到它上面。
     */
    private fun captureFrontmostWindow() {
        val pid = runCatching { MacWorkspace.frontmostExternalPid() }.getOrDefault(-1L)
        // 抓不到外部应用时（例如本应用已在最前）保留上一次记录。
        if (pid > 0) previousAppPid = pid
    }
}
