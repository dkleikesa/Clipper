package com.qcmian.clipper.desktop.viewmodel

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Rectangle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.ShortcutSpec
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.desktop.domain.CYCLE_INTERVAL_MILLIS
import com.qcmian.clipper.desktop.domain.CYCLE_START_DELAY_MILLIS
import com.qcmian.clipper.desktop.domain.FOCUS_GRACE_MILLIS
import com.qcmian.clipper.desktop.domain.InitialPanelHeight
import com.qcmian.clipper.desktop.domain.MODIFIER_POLL_MILLIS
import com.qcmian.clipper.desktop.domain.PANEL_WINDOW_TITLE
import com.qcmian.clipper.desktop.domain.PopupMode
import com.qcmian.clipper.desktop.domain.nsModifierMask
import com.qcmian.clipper.desktop.domain.RESIZE_SETTLE_MILLIS
import com.qcmian.clipper.desktop.domain.TRAY_CLICK_GRACE_MILLIS
import com.qcmian.clipper.core.settings.PopupPosition
import com.qcmian.clipper.desktop.domain.APPLIED_SIZE_HISTORY
import com.qcmian.clipper.desktop.domain.RESIZE_TOLERANCE_DP
import com.qcmian.clipper.desktop.domain.WINDOW_SLIDE_MILLIS
import com.qcmian.clipper.desktop.domain.WINDOW_SLIDE_STEP_MILLIS
import com.qcmian.clipper.desktop.domain.autoWindowSize
import com.qcmian.clipper.desktop.domain.constrained
import com.qcmian.clipper.desktop.domain.contentWidthOf
import com.qcmian.clipper.desktop.domain.cursorAnchor
import com.qcmian.clipper.desktop.domain.minimumWindowSizeOf
import com.qcmian.clipper.desktop.domain.nearlyEquals
import com.qcmian.clipper.desktop.domain.resolvePosition
import com.qcmian.clipper.desktop.domain.screenBounds
import com.qcmian.clipper.desktop.domain.slideoutWidthOf
import com.qcmian.clipper.di.AppContainer
import com.qcmian.clipper.core.platform.macos.GlobalShortcut
import com.qcmian.clipper.core.platform.macos.MacAppearance
import com.qcmian.clipper.core.platform.macos.MacGlobalHotKey
import com.qcmian.clipper.core.platform.macos.MacKeyboard
import com.qcmian.clipper.core.platform.macos.MacModifierMonitor
import com.qcmian.clipper.core.platform.macos.MacOutsideClickMonitor
import com.qcmian.clipper.core.platform.macos.MacStatusItem
import com.qcmian.clipper.core.platform.macos.MacWorkspace
import com.qcmian.clipper.host.HotkeyController
import com.qcmian.clipper.host.WindowController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

    /** 内容希望得到的高度（由 `HistoryScreen` 上报）。 */
    val preferredHeight: Dp = InitialPanelHeight,

    /**
     * 窗口的下限高度（由 `HistoryScreen` 上报）：滑动区下限 + 置顶区 + 头部 / 页脚。
     *
     * 手动拖拽时不会低于它。首次上报之前用滑动区的下限兜底，界面第一帧就会纠正。
     */
    val minimumHeight: Dp = Popup.minimumContentHeight,

    /** 预览是否改从左侧滑出（右侧放不下、左侧放得下时）。 */
    val previewOnLeft: Boolean = false,

    /**
     * 当前停靠侧「锚点到屏幕边缘」还能给预览多少宽度（已扣掉分隔条与主列表）。
     *
     * 只作为分隔条的拖动上限报给界面（见 [PreviewHostPolicy.maxPreviewWidth]）：贴边的窗口若
     * 允许拖出屏幕放不下的宽度，落盘时会被夹回来、窗口跟着缩一截。
     */
    val maxPreviewWidth: Dp = Popup.maximumPreviewWidth,

    /**
     * 窗口已经为预览让出位置（加宽 / 收回的那次几何**已经应用**），界面据此让预览卡片进场。
     *
     * 必须是宿主算出来的信号，不能让界面拿量到的窗口宽度去猜——实测值慢窗口一帧，收起预览时
     * 会让卡片在已经收窄的窗口里多画一帧（见 `PreviewHostPolicy.windowReady`）。
     */
    val previewWindowReady: Boolean = false,

    /** 当前弹窗交互阶段：按住会话的投影，由本类写；界面目前不读它。 */
    val popupMode: PopupMode = PopupMode.TOGGLE,
)

/**
 * 一次按住会话里，组合键当前按到什么程度。
 *
 * 关键在于区分「松掉一部分」和「全松开」：前者只挂起循环（用户可能只是松了一个修饰键，
 * 马上又按回去），后者才是一次真正的松手，要选中高亮项并关窗。
 */
private enum class ComboState { NONE, PARTIAL, COMPLETE }

/**
 * 注册到 Carbon 的热键标识（`EventHotKeyID.id`）：事件里带的就是它，系统据此把按键派发给
 * 对应的注册。本应用只注册一条真实热键（呼出面板），其余可选键都在面板内匹配；
 * 占用探测自己用保留 id（见 `MacGlobalHotKey.isAvailable`），所以这里从 1 开始。
 */
private const val HOT_KEY_POPUP = 1

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
    /**
     * 把位置与尺寸一次性应用到窗口上。
     *
     * 必须一次调用完成：Compose 自己的窗口实现把它拆成 `setSize` + `setLocation` 两次原生
     * 调用，而预览停靠左侧时窗口要同时「左移」和「变宽」（右边缘不动，主列表才停在原地），
     * 两次调用之间的中间帧会被系统画出来——整个窗口左右闪一下。宿主改为一次 `setBounds`。
     */
    private val applyBounds: (x: Int, y: Int, width: Int, height: Int) -> Unit,
    /**
     * 设置窗口允许的最小尺寸（AWT `window.minimumSize`）。
     *
     * 手动拖拽窗口边缘时由框架读它拦下收缩（`UndecoratedWindowResizer`），因此这里算的
     * 就是内容区的下限（预览并排时再加上滑出面板），见 [minimumWindowSizeOf]。
     */
    private val applyMinimumSize: (width: Int, height: Int) -> Unit,
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

    /**
     * 程序自己最近应用过的窗口尺寸（新的在前）。
     *
     * 不能只记「最后那一个」：尺寸通知会滞后于程序的最新一次设定。预览打开 / 收起、内容高度
     * 变化、偏好改动都可能在几帧内连着改几次几何，第 N 次的尺寸通知常常在第 N+1 次之后才到，
     * 此时最后值已经是第 N+1 次的了——这条「自己造成的」通知会被误判成用户在拖窗口边缘，几何
     * 更新随之冻结（见 [userIsResizing]），表现为窗口不再跟随设置。
     */
    private val recentAppliedSizes = ArrayDeque<DpSize>()

    /** 应用自己最后设定过的窗口几何（位置 + 尺寸），用来跳过重复的原生调用。 */
    private var lastAppliedBounds: Rectangle? = null

    /** 最后**落到原生窗口上**的几何。预览滑出 / 收回的过渡从这里开始插值，见 [applyWindowBounds]。 */
    private var lastNativeBounds: Rectangle? = null

    /** 正在跑的窗口几何过渡（位置 / 宽度逐帧插值）；新目标到来时取代上一次。 */
    private var geometryJob: Job? = null

    /** 应用自己最后设定过的窗口最小尺寸，用来跳过重复的原生调用。 */
    private var lastAppliedMinimumSize: DpSize? = null

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

    /**
     * [contentAnchor] 是在「预览宽度为这个值」时定下来的。
     *
     * 预览停靠左侧时，窗口左边缘由「锚点 − 滑出宽度」推出：预览宽度一变，窗口就会跟着平移。
     * 而拖动分隔条只该改变两个面板的划分（见 `HistoryScreen.draggedPreviewWidth`），窗口一帧
     * 都不该动——因此按这个差值把锚点挪过去（见 [applyWindowGeometry]），两者必须一起更新。
     * `null` 表示锚点已失效（面板隐藏或尚未摆放）。
     */
    private var anchorPreviewWidth: Int? = null

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
     * 每一次热键按下自增，驱动 [observeHotKeyHold] 里的按住会话。
     *
     * 热键的按下事件是「用户按了一次快捷键」唯一可靠的信号：Carbon 既不自动重复，
     * 也几乎不发松开（见 [MacGlobalHotKey.register]）。因此每一次按下都重新开一次会话，
     * 延迟从头计——松开一个键再按回来，同样算「又按了一次」。
     */
    private val holdPresses = MutableStateFlow(0)

    /** 本次按住会话按下的时刻；0 表示没有会话。用绝对时刻，采样协程重建也不会把进度弄丢。 */
    private var holdStartedAt = 0L

    /** 是否有进行中的按住会话；由 [observeHotKeyHold] 的采样循环结束。 */
    private var holdActive = false

    /** 本次会话是否已经推进过：推进过，整组键松开时才选中高亮项；轻按（没到延迟）什么都不做。 */
    private var holdCycled = false

    /**
     * 这一次「按住修饰键」期间按了几次主键。
     *
     * 对应两条交互约定：按一次是「呼出面板」或「往下选一条」，整组键松开后什么都不做；
     * 按两次及以上说明用户是在挑条目，整组键松开时直接选中并关窗。
     * 会话可以因为「只抬起主键」而挂起，但计数跟着这一次按住走，不跟着会话走。
     */
    private var holdPressCount = 0

    /** 本次会话上一次推进的时刻。 */
    private var lastCycleAt = 0L

    /**
     * 主键（`⇧⌘C` 里的 `C`）是否按着，由 Carbon 的按下 / 松开事件维护。
     *
     * 真机日志确认 macOS **会**把 `kEventHotKeyReleased` 送到应用（每次短按都能收到），
     * 所以这个状态值得采信：只抬起主键、修饰键还按着时，它是唯一能说明「组合键已经不完整」的信号。
     */
    private var mainKeyDown = false

    /**
     * 本进程是否收到过热键松开事件。
     *
     * 收不到时（是否派发取决于系统版本）就不能拿 [mainKeyDown] 判松手，否则会话永远收不了尾、
     * 面板再也关不掉；那种情况下退回「修饰键全松开即松手」。
     */
    private var mainKeyReleaseSupported = false

    init {
        captureFrontmostWindow()
        // 同步预读一次系统外观：首帧不必退回 `isSystemInDarkTheme()`；观察者装不上时，
        // 它也是本次会话唯一的外观来源（见 [observeSystemAppearance]）。
        _systemDark.value = runCatching { MacWorkspace.isSystemAppearanceDark() }.getOrNull()
        viewModelScope.launch { observeShortcut() }
        viewModelScope.launch { observeHotKeyHold() }
        viewModelScope.launch { observeWindowGeometry() }
        viewModelScope.launch { observeMinimumWindowSize() }
        viewModelScope.launch { observeUserResize() }
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
            beginHoldSession()
            return
        }
        // 托盘呼出的面板：再按（含连按）是把窗口移到鼠标位置，不参与「按住循环」；
        // 热键呼出的面板不挪窗口——否则连按几下窗口就跟着光标跑了。
        if (state.popupMode == PopupMode.TOGGLE && state.panelOpenedByTray) {
            moveToCursor()
            return
        }
        if (holdActive) {
            // 同一次「按住修饰键」里又按了一下主键：往下选一条（短按就是逐条往下选），
            // 并把延迟重新计满——松了其中一个键再按回来，也算新的一次按住。
            // 已经循环过的事实保留：这一次修饰键一直没抬起来，松手时仍然要选中。
            hotkey.requestCycle()
            holdPressCount++
            holdStartedAt = System.currentTimeMillis()
            lastCycleAt = 0L
            mainKeyDown = true
            MacModifierMonitor.assumeHeld(requiredModifierMask())
            setPopupMode(PopupMode.OPENING)
            return
        }
        // 修饰键也已经全松开，这是新的一次按键：先往下选一条（Maccy 的「连按往下选」），
        // 再开始计时。
        hotkey.requestCycle()
        beginHoldSession()
    }

    /**
     * 全局热键松开：主键抬起了。它不直接触发选中——选中要等「整组键都松开」，
     * 见 [runHoldSession]：修饰键还按着时只挂起，不选中也不关窗。
     */
    fun onHotKeyReleased() {
        mainKeyReleaseSupported = true
        mainKeyDown = false
    }

    /** 开启一次新的按住会话；上一次会话（如果还在跑）会被 [observeHotKeyHold] 随即取代。 */
    private fun beginHoldSession() {
        holdStartedAt = System.currentTimeMillis()
        holdActive = true
        holdCycled = false
        holdPressCount = 1
        lastCycleAt = 0L
        mainKeyDown = true
        // 按下事件本身就证明组合键此刻是按全的：把监视器就位成「按全」。它此刻的旧值往往
        // 停在别的应用里按下的那一刻，拿它去比会让松手变成「没有变化」，从而永远收不了尾。
        MacModifierMonitor.assumeHeld(requiredModifierMask())
        setPopupMode(PopupMode.OPENING)
        holdPresses.update { it + 1 }
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
        if (restoreFocus && pid > 0) {
            // `activate` 只是异步请求，⌘V 发出时目标应用未必已到前台；
            // 把 pid 交给 [MacKeyboard]，让它用 `CGEventPostToPid` 直接投递，绕开时序竞态。
            MacKeyboard.pasteTargetPid = pid
            runCatching { MacWorkspace.activate(pid) }
        }
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

    /** `App` 上报内容希望得到的高度。 */
    fun onPreferredHeightChanged(height: Dp) {
        _uiState.update { it.copy(preferredHeight = height) }
    }

    /** `App` 上报窗口的下限高度（滑动区下限 + 置顶区 + 头部 / 页脚）。 */
    fun onMinimumHeightChanged(height: Dp) {
        _uiState.update { it.copy(minimumHeight = height) }
    }

    /** 退出：应用「退出时清空历史」偏好，落盘并关闭数据库，然后请求宿主结束进程。 */
    fun quit() {
        panel.quit()
        // [close] 内含落盘：写完最后状态后关闭最后一个连接，SQLite 会把 WAL 合并回
        // 主库并删除 -wal / -shm，下次启动不再需要恢复，也不会留下膨胀的日志文件。
        runBlocking { container.repository.close() }
        panel.requestExit()
    }

    /**
     * 「按住热键循环」状态机：每一次按下（[holdPresses]）开启一次按住会话，会话里每
     * [MODIFIER_POLL_MILLIS] 采一次组合键按到什么程度（[ComboState]）。
     *
     * 三个刻意的设计，报出来的毛病都出在这里：
     *
     * 1. 采样间隔独立于循环间隔。原来循环期间每 [CYCLE_INTERVAL_MILLIS]（120ms）才看一眼
     *    组合键，松掉一个键再按回来（一两百毫秒）会被整段跳过——循环于是停不下来，再按时
     *    也直接续上。现在固定 30ms 采样，且只在会话进行中采样，空闲时一次原生调用都不发。
     * 2. 计时用绝对时刻、状态放在 ViewModel 里，而不是放在「由 Compose 状态驱动的协程」里。
     *    原来那个协程以 `(windowVisible, popupMode)` 为键，模式一变就被取消重建，
     *    「已经等了多久」跟着丢；而且只有从「打开中」进入才会计时，面板已显示时按住根本不循环。
     * 3. 「松掉一个键」和「整组键都松开」是两件事：前者只挂起，后者才选中并关窗。
     */
    private suspend fun observeHotKeyHold() {
        // 修饰键监视器只是「松手」的第二来源，装不上也不影响：那时只剩轮询。
        MacModifierMonitor.install()
        holdPresses.collectLatest { presses ->
            if (presses > 0) runHoldSession()
        }
    }

    /**
     * 一次「按住」的采样循环。按全组合键满 [CYCLE_START_DELAY_MILLIS] 之后开始逐条往下走，
     * 中途松掉哪个键都只是挂起（[ComboState.PARTIAL]），直到整组键都松开才收尾。
     *
     * 收尾时是否选中由「这一次按住怎么用的」决定：进过循环，或点按了两次及以上 → 选中并关窗；
     * 只点按一次 → 什么都不做（那只是呼出面板 / 往下看一眼）。
     */
    private suspend fun runHoldSession() {
        val mask = requiredModifierMask()
        // 按下这一刻组合键一定是按全的：Carbon 只在按全时才报按下。
        var previous = ComboState.COMPLETE
        while (true) {
            delay(MODIFIER_POLL_MILLIS)
            // 面板已经被收起（失焦、Esc、点了某一条…）：会话就此结束。
            if (!_uiState.value.windowVisible) {
                holdActive = false
                return
            }

            val held = heldModifiers(mask)
            // 收不到松开事件时只能当主键一直按着（退回「修饰键全松开即松手」）。
            val mainDown = mainKeyDown || !mainKeyReleaseSupported
            val state = when {
                // 整组键都松开了：这才是真正的松手。
                held == 0 && !mainDown -> ComboState.NONE
                // 组合键完整按着：可以循环。
                held == mask && mainDown -> ComboState.COMPLETE
                // 松了一部分（只抬主键、或只松某个修饰键）：挂起，不选中也不关窗。
                else -> ComboState.PARTIAL
            }
            if (state == ComboState.NONE) {
                holdActive = false
                // 整组键都松开了才收尾。选中条件：
                // - 这一次按住进过循环（长按）；或者
                // - 这一次按住里点按了两次及以上（用户在挑条目）。
                // 只点按一次是「呼出面板」或「往下选一条」，松手不做任何事，面板留在原地。
                val accept = holdCycled || holdPressCount >= 2
                if (accept) hotkey.requestAccept()
                setPopupMode(PopupMode.TOGGLE)
                return
            }

            if (state == ComboState.COMPLETE) {
                if (previous != ComboState.COMPLETE) {
                    // 松了一个键又按回来：这是一次新的「按住」，延迟从头计。
                    holdStartedAt = System.currentTimeMillis()
                    lastCycleAt = 0L
                }
                val now = System.currentTimeMillis()
                if (now - holdStartedAt < CYCLE_START_DELAY_MILLIS) {
                    setPopupMode(PopupMode.OPENING)
                } else {
                    setPopupMode(PopupMode.CYCLE)
                    // 延迟刚结束时先推进一条，之后每 [CYCLE_INTERVAL_MILLIS] 一条。
                    if (!holdCycled || now - lastCycleAt >= CYCLE_INTERVAL_MILLIS) {
                        holdCycled = true
                        lastCycleAt = now
                        hotkey.requestCycle()
                    }
                }
            } else {
                // 只松了一部分：停在这里等剩下的键，不选中也不关窗。
                setPopupMode(PopupMode.OPENING)
            }
            previous = state
        }
    }

    /** 呼出热键要求的修饰键掩码；呼出快捷键被清除时没有热键，也就没有掩码。 */
    private fun requiredModifierMask(): Int =
        // 读仓库里的偏好，而不是 `panel.hostUiState` 投影：投影要等 `App` 组合才更新，
        // 启动瞬间还是默认值，用它算出来的掩码会和真正注册的热键对不上。
        container.repository.settings.value.popupShortcut?.let { nsModifierMask(it) } ?: 0

    /**
     * 当前正按着的、落在 [mask] 里的修饰键：轮询与事件监视器**取交集**（任一来源看到某个键
     * 松了，就认为它松了）。
     *
     * - 轮询 `NSEvent.modifierFlags`（[com.qcmian.clipper.core.data.source.NativeDataSource.currentModifierFlags]）
     *   是主来源，每个采样点都读一次当前状态；
     * - [MacModifierMonitor] 的 `flagsChanged` 是事件来源：本应用是 key window 时它最灵敏。
     *   轮询在热键按住期间未必读得到松手（当初加这个监视器就是为了它），所以松手这件事必须
     *   由它兜住；反过来它的值可能是本次会话之前的旧值，因此会话开始时用
     *   [MacModifierMonitor.assumeHeld] 就位成「按全」。
     *
     * 取交集而不是只看其中一条：任何一条来源看到松手都足以让循环停下来，而两条都看不到的
     * 情况本来也无法判定。
     */
    private fun heldModifiers(mask: Int): Int =
        native.currentModifierFlags() and mask and (MacModifierMonitor.flags.value and mask)

    /** 只写「弹窗阶段」这一项；值没变时不产生新状态。 */
    private fun setPopupMode(mode: PopupMode) {
        _uiState.update { if (it.popupMode == mode) it else it.copy(popupMode = mode) }
    }

    // ---------------------------------------------------------------------------------
    // 观察：把状态变化翻译成窗口动作
    // ---------------------------------------------------------------------------------

    /**
     * 当前这次摆放实际使用的位置偏好：点托盘呼出时直接锚定菜单栏图标（点托盘那一刻光标就在
     * 图标上，「光标位置」与「菜单栏图标」是同一个落点）。
     *
     * 锚点签名必须用它而不是 `settings.popupPosition`：签名只用来判断「锚点是否还有效」，
     * 托盘呼出时两者并不相等，用错会让下一次几何重算重新取锚点，把窗口拉回菜单栏。
     */
    private fun placementPreference(settings: AppSettings): PopupPosition =
        if (openedByTray) PopupPosition.MENU_BAR else settings.popupPosition

    /**
     * 面板已稳定显示时再按热键：把窗口移到鼠标处。
     *
     * 只重设锚点，位置与尺寸一起交给 [applyWindowGeometry]（它对同一个锚点算尺寸、再把窗口夹进
     * 屏幕）。刻意**不**在这里用「当前窗口尺寸」摆放窗口：那样算出来的 y 会被当前高度顶高，而它
     * 紧接着又被记成锚点——高度于是等于自己的旧值，鼠标往下移也不会变小，只能往上长。
     */
    private fun moveToCursor() {
        val settings = container.repository.settings.value
        val cursor = cursorAnchor(settings.popupScreen) as WindowPosition.Absolute
        // 同步「内容区锚点」：否则下次切换预览会按旧锚点摆放，窗口会跳回去。
        contentAnchor = if (_uiState.value.previewOnLeft) {
            WindowPosition.Absolute(cursor.x + slideoutWidthOf(settings), cursor.y)
        } else {
            cursor
        }
        anchorPreviewWidth = settings.previewWidth
        lastPlacementSignature = Pair(placementPreference(settings), settings.popupScreen)
        applyWindowGeometry(_uiState.value, settings)
    }

    /** 用户录制了不同的快捷键时重新注册全局热键。 */
    private suspend fun observeShortcut() {
        // 等持久化偏好加载完成，并以仓库中的真实设置为注册来源：
        // hostUiState 是 App 组合时才镜像的投影，启动瞬间仍是默认值（⇧⌘C），
        // 按它注册的热键不是用户真正录制的那个，启动头几秒会「按了没反应」。
        container.repository.settingsLoaded.first { it }
        combine(
            container.repository.settings.map { it.popupShortcut }.distinctUntilChanged(),
            // 录制快捷键期间把热键**注销**掉，而不是只在回调里忽略它：Carbon 注册的组合会被系统
            // 从窗口事件里吞掉，留着它那一次按键就到不了录制器；注销之后它就只是一次普通按键——
            // 既不触发原动作，也能被设置页里的录制器收下。
            //
            // 条件是「面板正显示着录制器」：面板被托盘收起时录制界面已经不在眼前（组合会留在
            // 那份保留下来的组合里），继续压着全局热键只会让人以为热键坏了。
            panel.hostUiState
                .map { it.isRecordingShortcut && it.isWindowVisible }
                .distinctUntilChanged(),
        ) { spec, recording -> spec.takeUnless { recording } }
            .collectLatest { spec ->
                applyGlobalHotKey(spec)
                try {
                    awaitCancellation()
                } finally {
                    MacGlobalHotKey.unregister(HOT_KEY_POPUP)
                }
            }
    }

    /**
     * 注册呼出面板的系统级热键；`null`（设置页里清除了绑定）表示不注册。
     *
     * 只有它是系统级的，其余可录制快捷键都在面板内匹配（见 `ShortcutSlot.global`）——
     * 所以这里也不需要「暂停键不进按住循环」那类特例。
     */
    private fun applyGlobalHotKey(spec: ShortcutSpec?) {
        MacGlobalHotKey.unregister(HOT_KEY_POPUP)
        if (!native.supportsGlobalHotKeys) return
        val shortcut = spec?.let { GlobalShortcut.fromSpec(it) } ?: return
        MacGlobalHotKey.register(
            id = HOT_KEY_POPUP,
            shortcut = shortcut,
            onTrigger = { onHotKeyPressed() },
            onRelease = { onHotKeyReleased() },
        )
    }

    /**
     * 窗口允许的最小尺寸（内容区下限 + 预览开着时的滑出面板），交给 AWT 的 `window.minimumSize`。
     *
     * 用户拖拽窗口边缘时由框架读它拦下收缩（`UndecoratedWindowResizer` 对左 / 上两侧做了
     * `coerceAtLeast(window.minimumSize)`），所以下限要在拖拽开始之前就已经设好。宽度由
     * [minimumWindowSizeOf] 给出（预览开着时含滑出面板），高度用界面报上来的
     * [DesktopShellUiState.minimumHeight]。值没变时不产生原生调用。
     *
     * 下限**变小**时额外补一次几何：之前可能有一次尺寸请求被旧下限夹住（系统接受的是夹过之后
     * 的值，而 [lastAppliedBounds] 记的是请求值，于是再也去重不掉），清掉记录再请求一次，
     * 窗口才收得回来。
     */
    private suspend fun observeMinimumWindowSize() {
        combine(
            uiState,
            // 预览开关决定下限要不要含滑出面板（见 [minimumWindowSizeOf]）。
            container.repository.settings.map { it.previewOpen }.distinctUntilChanged(),
        ) { state, previewOpen -> minimumWindowSizeOf(state.minimumHeight, previewOpen) }
            .distinctUntilChanged()
            .collect { size ->
                val previous = lastAppliedMinimumSize
                if (size == previous) return@collect
                lastAppliedMinimumSize = size
                applyMinimumSize(size.width.value.roundToInt(), size.height.value.roundToInt())
                if (previous != null && size.height < previous.height) {
                    lastAppliedBounds = null
                    applyWindowGeometry(_uiState.value, container.repository.settings.value)
                }
            }
    }

    /**
     * 窗口几何：位置与尺寸一起算、一起写。
     *
     * 两者必须落在同一帧。预览停靠在左侧时窗口要同时「左移」和「变宽」（右边缘不动，主列表
     * 才停在原地）；若位置与尺寸由两次独立的写入驱动，界面就会先看到「位置已移、宽度未变」
     * 或「宽度已变、位置未移」的中间状态——主列表于是左右抖一下。
     *
     * 触发来源涵盖：面板显示 / 隐藏、预览开关、内容高度、偏好设置，以及窗口位置本身的变化。
     * 位置只当**触发键**用，不参与计算：尺寸由内容与偏好决定，位置由锚点决定，因此每次重算都
     * 收敛到同一个结果（`applyWindowBounds` 再去重），顺带保证窗口被外部挪走后仍会被拉回锚点。
     */
    private suspend fun observeWindowGeometry() {
        combine(
            uiState,
            // 直接用仓库里的偏好，而不是 `panel.hostUiState` 投影：投影由 `App` 在组合里用
            // `SideEffect` 写入，于是「改设置」到「窗口跟上」要多等一帧。拖动预览分隔条时每帧
            // 都在改设置，那一帧的差值会让预览抢在窗口前面变宽、主列表替它吸收差额——两边的
            // 尺寸一起抖（与 `observeShortcut` 是同一个坑）。
            container.repository.settings,
            snapshotFlow { windowState.position },
        ) { state, settings, position ->
            Triple(state, settings, position)
        }.collect { (state, settings, _) ->
            applyWindowGeometry(state, settings)
        }
    }

    /**
     * 按内容、偏好与屏幕空间算出窗口的位置与尺寸，并一次写入。
     *
     * 主列表（内容区）的锚点先定下来，预览再以它为基准向一侧展开——默认在右侧（窗口向右
     * 加宽），主列表右边缘放不下时改到左侧（窗口向左加宽），因此预览永远不会盖住主列表，
     * 主列表本身也不会移动。
     */
    private fun applyWindowGeometry(
        state: DesktopShellUiState,
        settings: AppSettings,
    ) {
        if (!state.windowVisible) {
            // 隐藏后下次显示要重新取锚点，否则会把上一次的旧位置带过来。
            contentAnchor = null
            anchorPreviewWidth = null
            lastPlacementSignature = null
            return
        }
        // 用户正在拖边缘：这一刻以他的手为准。程序化改几何会和拖动互相打架
        // （两边都在 setSize / setLocation），拖左边框时尤其明显——位置同时也在变。
        // 手停下来后由 [observeUserResize] 补一次。
        if (userIsResizing()) return

        val contentWidth = contentWidthOf(settings)
        val desiredSlideout = slideoutWidthOf(settings)
        val bounds = screenBounds(settings.popupScreen)

        val preferred = placementPreference(settings)

        // 只在「重新显示」或「位置偏好变化」时取锚点；仅切换预览时沿用旧锚点，
        // 主列表不会跟着鼠标或上次的窗口尺寸跳动。
        val signature = Pair(preferred, settings.popupScreen)
        var anchor = contentAnchor?.takeIf { signature == lastPlacementSignature }
            ?: (
                resolvePosition(
                    position = preferred,
                    size = DpSize(contentWidth, windowState.size.height),
                    screenIndex = settings.popupScreen,
                    statusItem = MacStatusItem.currentAnchor(),
                ) as WindowPosition.Absolute
                ).also {
                    lastPlacementSignature = signature
                    anchorPreviewWidth = settings.previewWidth
                }

        // 预览停靠左侧时，窗口左边缘是「锚点 − 滑出宽度」推出来的：预览宽度一变，窗口就会
        // 跟着平移。而拖动分隔条只该改变两个面板的划分（见 `HistoryScreen.draggedPreviewWidth`），
        // 窗口一帧都不该动。锚点本来就表示「主列表左上角在哪」，预览变宽时主列表的左边缘正是
        // 往右让出这么多，因此把它按同样的差值挪过去——算出来的窗口位置与改动前完全相同，
        // `applyWindowBounds` 直接去重，原生窗口一动不动。
        //
        // 用 [state] 里的停靠侧判断（那是上一次布局的结论）：本次停靠侧要等锚点定下来才能算，
        // 而锚点正是这里要先挪的东西。预览开关、换屏幕、换位置偏好都会重取锚点，那些情况下这次
        // 挪动作用在即将被丢弃的旧锚点上，没有影响。
        if (state.previewOnLeft) {
            val reference = anchorPreviewWidth
            if (reference != null && reference != settings.previewWidth) {
                anchor = WindowPosition.Absolute(
                    anchor.x + (settings.previewWidth - reference).dp,
                    anchor.y,
                )
                anchorPreviewWidth = settings.previewWidth
            }
        }
        contentAnchor = anchor

        // 预览停靠在哪一侧：优先右侧，右侧放不下时改左侧，两侧都放不下就退回覆盖层。
        //
        // 判断的是「窗口整个（主列表 + 滑出面板）放不放得进屏幕」，而不是「预览放不放得进
        // 列表旁边」：主列表是跟着窗口走的，只要窗口被 `constrained` 夹回屏幕内，主列表就会
        // 跟着平移——表现为「预览先盖在主列表原来的位置上，主列表被推到一边」，收起时再推回来。
        //
        // 余量按「能不能放下**最小**宽度的预览」算，实际给出去的宽度再夹进这个余量：面板宽度
        // 超过这一侧的余量时（用户把窗口拖到了屏幕边上），它只会在边缘停住，而不是翻到另一侧
        // ——翻侧会让整个窗口跳到锚点另一边，主列表跟着平移。
        val roomRight = (bounds.x + bounds.width - anchor.x.value - contentWidth.value).dp
            .coerceAtLeast(0.dp)
        val roomLeft = (anchor.x.value - bounds.x).dp.coerceAtLeast(0.dp)
        val fitsRight = roomRight >= Popup.minimumSlideoutWidth
        // 预览开没开直接读设置，**不**从界面上报：这里它决定窗口要不要为预览让位，这一份一旦
        // 停在「开着」，窗口就再也收不回来——预览早已收起，窗口右侧却留着预览那一块空白。
        val previewOpen = settings.previewOpen
        // 桌面端**永远**并排，不走覆盖层（`overlays` 恒为 false，见 [DesktopShellUiState]）：
        // 窗口宽度由内容 + 滑出面板决定，放不下时把滑出宽度按这一侧的余量夹小（下面 `slideout`），
        // 而不是让预览盖在主列表上——覆盖层只要在过渡里出现一帧，看起来就是「预览整块盖住了
        // 列表」，而并排布局里卡片与列表各占一边，任何一帧都不可能互相遮盖。
        val previewOnLeft = previewOpen && !fitsRight

        // 尺寸由内容与偏好决定（预览并排时额外容纳滑出面板）；位置以锚点为基准，预览停靠左侧时
        // 窗口向左展开——两者用的必须是**同一个**滑出宽度，否则窗口边缘和面板会差着一段。
        //
        // 高度截的是「锚点下方还剩多少」，不是窗口当前的 y：那样会让「窗口有多高」和「窗口在哪」
        // 互相追赶（见 `autoWindowSize`）。实在放不下时由下面的 `constrained` 把窗口上移。
        val slideout = if (previewOpen) {
            desiredSlideout.coerceAtMost(if (previewOnLeft) roomLeft else roomRight)
        } else {
            null
        }
        // 界面拿它当分隔条的拖动上限（与「窗口内剩余空间」取较小值）：必须与这里真正会接受的
        // 宽度同源，否则拖动会报出一个收不下的值，松手又被夹回来——看起来就是「拖了没用」。
        val maxPreviewWidth =
            ((if (previewOnLeft) roomLeft else roomRight) - Popup.previewDividerWidth)
                .coerceAtLeast(Popup.minimumPreviewWidth)
        // 屏幕放不下时，把设置里的预览宽度也收敛掉——只夹窗口是不够的：界面里的面板会照设置值
        // 继续变宽，多出来的部分只能挤主列表；而设置值一路涨到拖动上限之后，用户往回拖一大段都
        // 不见效（一段死区）。夹到同一个值，分隔条就会在屏幕边缘自然停住，与窗口被屏幕夹住是
        // 同一件事。
        if (slideout != null && slideout < desiredSlideout) {
            val capped = (slideout - Popup.previewDividerWidth).value.roundToInt()
                .coerceAtLeast(Popup.minimumPreviewWidth.value.toInt())
            if (capped < settings.previewWidth) {
                container.repository.setSettings(settings.copy(previewWidth = capped))
            }
        }
        val target = autoWindowSize(
            settings = settings,
            slideoutWidth = slideout,
            preferredHeight = state.preferredHeight,
            minimumHeight = state.minimumHeight,
            anchorY = anchor.y.value.toInt(),
            bounds = bounds,
        )
        val windowX = if (previewOnLeft) {
            anchor.x.value - (slideout ?: 0.dp).value
        } else {
            anchor.x.value
        }

        // 预览是不是（还）在窗口里：设置说开着；或者这次收起的过渡还没把窗口收回去（原生宽度仍
        // 多着一段）——那几帧里卡片要继续占着槽位，主列表才不会随窗口一起挪。用户自己拖窗口
        // 边缘时窗口也会比内容宽，那种情况不算（否则已经关闭的预览会凭空冒出来）。
        val windowReady = previewOpen || (
            !userIsResizing() &&
                (lastNativeBounds?.width ?: 0) >
                target.width.value.roundToInt() + RESIZE_TOLERANCE_DP.toInt()
            )
        _uiState.update {
            if (it.previewOnLeft == previewOnLeft &&
                it.previewWindowReady == windowReady &&
                it.maxPreviewWidth == maxPreviewWidth
            ) {
                it
            } else {
                it.copy(
                    previewOnLeft = previewOnLeft,
                    previewWindowReady = windowReady,
                    maxPreviewWidth = maxPreviewWidth,
                )
            }
        }

        rememberAppliedSize(target)
        // 位置与尺寸必须一次应用（见 [applyBounds]），`windowState` 也由 [applyWindowBounds] 一并
        // 对齐——不写它会留下一帧「尺寸已新、位置仍旧」的窗口，理由见那里。
        val placed = constrained(
            x = windowX.toInt(),
            y = anchor.y.value.toInt(),
            size = target,
            bounds = bounds,
        )
        applyWindowBounds(
            x = placed.x.value.roundToInt(),
            y = placed.y.value.roundToInt(),
            size = target,
        )
    }

    /** 应用一次窗口几何；与上次应用过的完全相同就跳过，避免每次状态变化都动一次原生窗口。 */
    private fun applyWindowBounds(x: Int, y: Int, size: DpSize) {
        val bounds = Rectangle(
            x,
            y,
            size.width.value.roundToInt(),
            size.height.value.roundToInt(),
        )
        if (lastAppliedBounds == bounds) return
        val from = lastNativeBounds
        lastAppliedBounds = bounds
        geometryJob?.cancel()

        // 首次摆放、或高度也变了：一次到位。高度由内容决定（条目增减），逐帧插值只会让窗口
        // 长得比内容慢；首次摆放时窗口本来就在进场，也不该滑一下。`WINDOW_SLIDE_MILLIS` 置 0
        // 即关掉过渡，退回一次到位。
        if (from == null || from.height != bounds.height || from.width == bounds.width ||
            WINDOW_SLIDE_MILLIS <= 0L
        ) {
            applyNativeBounds(bounds)
            return
        }

        // 只有「预览滑出 / 收回」这一类变化（位置与宽度一起变、高度不变）才逐帧插值，见
        // [WINDOW_SLIDE_MILLIS]：一次到位会留下系统按旧内容补位的那一帧，看起来就是预览整块
        // 盖在主列表上。
        geometryJob = viewModelScope.launch {
            val startedAt = System.nanoTime()
            try {
                while (true) {
                    val progress = (
                        (System.nanoTime() - startedAt) / 1_000_000f / WINDOW_SLIDE_MILLIS
                        ).coerceIn(0f, 1f)
                    applyNativeBounds(
                        Rectangle(
                            from.x + ((bounds.x - from.x) * progress).roundToInt(),
                            bounds.y,
                            from.width + ((bounds.width - from.width) * progress).roundToInt(),
                            bounds.height,
                        ),
                    )
                    if (progress >= 1f) return@launch
                    delay(WINDOW_SLIDE_STEP_MILLIS)
                }
            } catch (cancellation: CancellationException) {
                // 被新的目标取代：停在当前帧，由新的那次过渡接着走。
                throw cancellation
            } catch (error: Throwable) {
                // 过渡本身出岔子也不能让窗口停在半路：直接把目标摆上。
                applyNativeBounds(bounds)
            }
        }
    }

    /**
     * 真正落到原生窗口上的一次应用，并把 `windowState` 对齐到同一组值。
     *
     * 对齐 state 是因为 Compose 自己的窗口实现会把 state 里的尺寸与位置**分两次**应用到窗口
     * 上（先尺寸、后位置），而它读到的这两个值来自窗口的通知，到达有先后：缩放通知先到、移动
     * 通知后到。左侧停靠时窗口要同时「左移」和「变宽」，于是它可能在「尺寸已新、位置仍旧」的
     * 那一帧按旧位置再摆一次窗口。我们把 state 一并对齐后，它那两次应用都是空操作。
     *
     * 每一帧都要记进 [recentAppliedSizes]：插值的中间尺寸也是**程序自己**设的，漏记的话会被
     * 当成「用户在拖窗口边缘」（见 [observeUserResize]）。
     */
    private fun applyNativeBounds(bounds: Rectangle) {
        lastNativeBounds = bounds
        applyBounds(bounds.x, bounds.y, bounds.width, bounds.height)
        val size = DpSize(bounds.width.dp, bounds.height.dp)
        rememberAppliedSize(size)
        windowState.size = size
        windowState.position = WindowPosition.Absolute(bounds.x.dp, bounds.y.dp)
    }

    /** 记下这次由程序应用的尺寸；保留一小段历史的原因见 [recentAppliedSizes]。 */
    private fun rememberAppliedSize(size: DpSize) {
        recentAppliedSizes.addFirst(size)
        while (recentAppliedSizes.size > APPLIED_SIZE_HISTORY) recentAppliedSizes.removeLast()
    }

    /** 这个尺寸是不是程序自己刚设过的（[RESIZE_TOLERANCE_DP] 之内）。 */
    private fun wasAppliedByProgram(size: DpSize): Boolean =
        recentAppliedSizes.any { size.nearlyEquals(it) }

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
                // 命中程序最近设过的某个尺寸 —— 这次通知是自己造成的，直接忽略。
                // 判据必须是「最近若干次」而不是「最后一次」，理由见 [recentAppliedSizes]。
                if (wasAppliedByProgram(size)) return@collectLatest

                userResizeUntil = System.currentTimeMillis() + RESIZE_SETTLE_MILLIS
                delay(RESIZE_SETTLE_MILLIS)

                val settings = container.repository.settings.value
                // 预览打开时拖的是整窗宽度，写回前要减掉滑出面板：自定义宽度始终表示主列表宽度。
                val contentWidth = if (settings.previewOpen) {
                    size.width - slideoutWidthOf(settings)
                } else {
                    size.width
                }
                rememberAppliedSize(size)
                // 窗口已经不在「应用设定的几何」上了：清掉记录，让随后的补正一定重新应用一次
                // （否则算出来的几何恰好等于旧值就会被当成「已经应用过」而跳过）。
                lastAppliedBounds = null
                // 用户可能把窗口拖到了别处（拖左边框加宽时窗口位置就会变）：锚点先跟着走。
                // 必须在写设置之前——设置一变，[observeWindowGeometry] 可能立刻按旧锚点把窗口
                // 拉回去，等于把刚才的拖动撤销掉。
                rememberContentAnchor()
                container.repository.setSettings(
                    settings.copy(
                        // 内容区下限：右 / 下两侧的拖拽框架不读 `minimumSize`，落盘时收敛一次，
                        // 最终尺寸同样不会低于下限（见 [minimumWindowSizeOf]）。这里用的是
                        // 「划分里的下限」[Popup.minimumSplitContentWidth]（比窗口下限小）：
                        // 预览开着时拖窄窗口，同时被挤窄的是主列表，按窗口下限收敛会把窗口又顶宽。
                        // 高度用的是界面报上来的窗口下限，滑动区因此总是留着「剪贴板为空时」那一档。
                        customWindowWidth = contentWidth.value.roundToInt()
                            .coerceAtLeast(Popup.minimumSplitContentWidth.value.toInt()),
                        customWindowHeight = size.height.value.roundToInt()
                            .coerceAtLeast(_uiState.value.minimumHeight.value.roundToInt()),
                    ),
                )

                userResizeUntil = 0L
                // 用户拖出的尺寸仍要受屏幕约束（例如不能盖住 Dock），补一次程序化几何。
                applyWindowGeometry(
                    state = _uiState.value,
                    settings = container.repository.settings.value,
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
        anchorPreviewWidth = settings.previewWidth
        lastPlacementSignature = Pair(placementPreference(settings), settings.popupScreen)
    }

    /** 用户是否正在手动调整窗口尺寸（含刚停下的一小段静默期）。 */
    private fun userIsResizing(): Boolean = System.currentTimeMillis() < userResizeUntil

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
     * 观察面板外的点击：只用 NSEvent 全局 / 本地监视器，事件驱动、零轮询。
     * 收起前检查弹窗状态——偏好设置等弹窗的点击属于本应用，不应收起面板。
     *
     * 刻意不保留「监视器装不上就退回 `pressedMouseButtons` 轮询」的兜底：那是面板可见期间
     * 40ms 一次的常驻原生调用（25Hz），代价远高于它兜住的那点失败概率。装不上时只有
     * 「点击别处收起」这一条能力静默缺失，其余收起路径（失焦、Esc、托盘、热键）不受影响。
     */
    private suspend fun observeOutsideClicks() {
        MacOutsideClickMonitor.install(PANEL_WINDOW_TITLE)
        MacOutsideClickMonitor.outsideClicks.collect {
            if (uiState.value.windowVisible && !panel.hostUiState.value.isModalOpen) {
                lastOutsideHideAtMillis = System.currentTimeMillis()
                hidePanel(restoreFocus = false)
            }
        }
    }

    /**
     * 「跟随系统」主题模式：只用系统通知（`AppleInterfaceThemeChangedNotification`）事件驱动，
     * 零轮询。
     *
     * 刻意不保留「观察者装不上就 1 秒轮询 `AppleInterfaceStyle`」的兜底：那是常驻的定时唤醒，
     * 换来的只是「通知装不上时主题不实时跟随」。装不上时不再覆盖 [systemDark]，界面停在
     * [init] 里同步读到的那个值——那也是 [MacAppearance.install] 失败时唯一的外观来源，
     * 不能让它被 `null` 冲掉。
     */
    private suspend fun observeSystemAppearance() {
        if (!MacAppearance.install()) return
        MacAppearance.systemDark.collect { _systemDark.value = it }
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
