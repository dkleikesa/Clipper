package com.qcmian.clipper.host

import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.util.currentTimeMillis
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 界面中面向宿主的投影：桌面窗口绘制自身所需的一切。
 *
 * `App` 会一次性从 `ClipboardUiState` 写入它，因此这里是单个不可变值，而不是十几个可变字段——
 * 控制器只是镜像状态持有者，永远不会成为第二个数据源。
 */
data class HostUiState(
    /** 偏好设置，便于宿主遵循窗口尺寸、屏幕与快捷键。 */
    val settings: AppSettings = AppSettings(),

    /**
     * `AppDelegate.isStatusItemDisabled`：记录被暂停时托盘图标变灰，
     * 存储偏好中把所有内容类型都关掉时同样如此。
     */
    val isStatusItemDisabled: Boolean = false,

    /**
     * 面板当前是否可见。可见时托盘图标画出按下态背景，对应菜单栏项的 `highlighted`。
     * 只有桌面宿主有这个概念，其它平台保持默认值。
     */
    val isStatusItemActive: Boolean = false,

    /** 面板是否可见（不区分触发来源）。托盘点击时用它预推翻转后的按下态。 */
    val isWindowVisible: Boolean = false,

    /**
     * 面板之上有它自己的模态框（清除确认）时为 `true`，此时面板不得自动隐藏。
     */
    val isModalOpen: Boolean = false,

    /**
     * 偏好设置里正在录制快捷键。系统级热键不看焦点，录制期间必须由宿主自己停手
     * （见 `ClipboardUiState.isRecordingShortcut`）。
     */
    val isRecordingShortcut: Boolean = false,

    /**
     * 偏好设置窗口（独立窗口，见 `ClipperSettingsWindow`）是否打开。
     *
     * 面板据此收起：两个窗口争夺前台时，留着面板只会挡住设置。
     */
    val isSettingsWindowOpen: Boolean = false,

    /**
     * 开发者工具窗口（独立窗口，见 `ClipperDevToolsWindow`）是否打开。
     *
     * 与 [isSettingsWindowOpen] 同理：面板据此让位，而不是与新窗口叠在一起。
     */
    val isDevToolsWindowOpen: Boolean = false,

    /**
     * 宿主提供的原始系统外观；`null` 表示未提供。
     *
     * 刻意是**原始值**而不是解析后的深浅色：设置窗口用它和主题偏好一起自行解析，
     * 与面板走同一个算式（`rememberClipperDarkTheme`），因此两个窗口不会差一帧。
     */
    val systemDark: Boolean? = null,
)

/**
 * 面板窗口的宿主通道：宿主（托盘、应用根）观察并驱动窗口的生命周期，
 * 窗口的 ViewModel 借它把面板状态投影给 [App]，并接收面板能力的回调。
 *
 * 与 [HotkeyController] 的分工：这里传递的是「窗口事件」（切换显示、隐藏、退出、
 * 窗口内容的搜索/退出能力），与按键无关；按键意图见 [HotkeyController]。
 */
class WindowController(
    /**
     * 当前时刻。默认读系统时钟；单测注入可变时钟，才能把「托盘点击宽限期」这类时序判定
     * 跑满边界（见 `PanelPresentationController`）。
     */
    private val now: () -> Long = ::currentTimeMillis,
) {
    private val _hostUiState = MutableStateFlow(HostUiState())

    /** 由 `App` 从界面状态镜像过来；宿主只读取它。 */
    val hostUiState: StateFlow<HostUiState> = _hostUiState.asStateFlow()

    private val _toggleRequests = MutableStateFlow(0)

    /** 托盘请求切换面板（点击图标）时自增。 */
    val toggleRequests: StateFlow<Int> = _toggleRequests.asStateFlow()

    /**
     * 最近一次托盘点击的时刻。窗口侧据此认出「这次失焦是点菜单栏图标造成的」，
     * 从而不在切换逻辑之外单独收起面板。
     */
    @Volatile
    var lastTrayClickAtMillis: Long = 0L
        private set

    /**
     * 开发者工具窗口此刻是不是本应用的 key window。
     *
     * 由那个窗口自己的焦点监听维护（见 `ClipperDevToolsWindow`）。**不复用
     * [HostUiState.isDevToolsWindowOpen]**：窗口开着不等于用户在看它——设置窗口在最前时开发窗口
     * 也是开着的，而那一次粘贴该落到别处。
     *
     * 这一位的用途只有一个：开发窗口是个正常的编辑面，它自己在最前时，面板收起后的 ⌘V 该投给
     * 本应用、落在它的光标处，而不是回头找上一次那个外部应用（见 `PanelPresentationController`）。
     *
     * `@Volatile`：写在 AWT 的焦点回调里，读在 AppKit 的热键回调里，是两个线程。
     */
    @Volatile
    var isDevToolsWindowFocused: Boolean = false

    private val _hideRequests = MutableStateFlow(0)

    /** 宿主隐藏面板时自增，使预览与之一同关闭。 */
    val hideRequests: StateFlow<Int> = _hideRequests.asStateFlow()

    private val _devToolsToggleRequests = MutableStateFlow(0)

    /** 系统级快捷键请求切换开发者工具窗口；见 [devToolsToggleRequests]。 */
    fun requestToggleDevTools() {
        _devToolsToggleRequests.value++
    }

    private val _devToolsShowRequests = MutableStateFlow(0)

    /**
     * 系统级快捷键请求把开发者工具窗口**推到最前**（窗口开着、但已经不在最前时）；见
     * [devToolsShowRequests]。
     */
    fun requestDevToolsToFront() {
        _devToolsShowRequests.value++
    }

    private val _exitRequested = MutableStateFlow(false)

    /** 宿主 ViewModel 落盘完成后置位，由应用根结束进程。 */
    val exitRequested: StateFlow<Boolean> = _exitRequested.asStateFlow()

    /** 由 `App` 设置的能力：面板失去焦点时清空搜索。 */
    internal var clearSearchAction: () -> Unit = {}

    /** 由 `App` 设置的能力：页脚中的「退出」一行。 */
    internal var quitAction: () -> Unit = {}

    /** 由 `App` 写入最新的界面投影。 */
    internal fun setHostUiState(value: HostUiState) {
        _hostUiState.value = value
    }

    /** 对应托盘的点击：窗口侧 ViewModel 观察该请求后按当前可见性呼出或收起面板。 */
    fun requestToggle() {
        lastTrayClickAtMillis = now()
        _toggleRequests.value++
    }

    /**
     * 系统级快捷键（`⇧⌘D`）请求打开 / 关闭开发者工具窗口时自增。
     *
     * 走通道而不是直接改状态：开关（`ClipboardUiState.devToolsOpen`）由面板的状态持有者所有，
     * 而热键注册在桌面外壳的 ViewModel 里，两者互不持有——与 [toggleRequests] 同一种接法。
     */
    val devToolsToggleRequests: StateFlow<Int> = _devToolsToggleRequests.asStateFlow()

    /**
     * 系统级快捷键（`⇧⌘D`）请求把**已经开着的**开发者工具窗口带到最前时自增。
     *
     * 与 [devToolsToggleRequests] 分开，是因为「开着的窗口再按一次」有两种截然不同的意图：
     * 用户看着它时按是「关掉」，它被别的应用压到后台时按是「叫回来」。判据只有窗口自己知道
     * （`WindowController.isDevToolsWindowFocused` 由它的焦点监听维护），因此这里不替它决定，
     * 只把两条意图各自送到——事件由热键回调里读到的焦点位分流（见 `GlobalHotKeyController`）。
     *
     * 用计数器而不是布尔量：[requestDevToolsToFront] 每次都是一次**新的**「叫回来」，
     * 连着按两下（中间又被别的应用抢走前台）也得各响应一次。
     */
    val devToolsShowRequests: StateFlow<Int> = _devToolsShowRequests.asStateFlow()

    /** 关闭弹窗的同时也关闭预览滑出面板。 */
    fun requestHide() {
        _hideRequests.value++
    }

    /** 宿主 ViewModel 落盘完成后请求结束进程。 */
    fun requestExit() {
        _exitRequested.value = true
    }

    /** 弹窗失去焦点时清空搜索。 */
    fun clearSearch() = clearSearchAction()

    /** 应用「退出时清空历史」偏好。 */
    fun quit() = quitAction()
}
