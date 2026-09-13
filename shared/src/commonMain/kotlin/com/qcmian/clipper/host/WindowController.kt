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

    /** `Defaults[.ignoreEvents]`。 */
    val isPaused: Boolean = false,

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

    /** 预览滑出面板当前是否打开。 */
    val isPreviewOpen: Boolean = false,

    /**
     * 有对话框（偏好设置、关于、清除确认）显示时为 `true`。
     * 对应 `FloatingPanel.resignKey`，它在弹出警告框时不会关闭面板。
     */
    val isModalOpen: Boolean = false,
)

/**
 * 面板窗口的宿主通道：宿主（托盘、应用根）观察并驱动窗口的生命周期，
 * 窗口的 ViewModel 借它把面板状态投影给 [App]，并接收面板能力的回调。
 *
 * 与 [HotkeyController] 的分工：这里传递的是「窗口事件」（切换显示、隐藏、退出、
 * 窗口内容的搜索/退出能力），与按键无关；按键意图见 [HotkeyController]。
 */
class WindowController {
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

    private val _hideRequests = MutableStateFlow(0)

    /** 宿主隐藏面板时自增，使预览与之一同关闭。 */
    val hideRequests: StateFlow<Int> = _hideRequests.asStateFlow()

    private val _exitRequested = MutableStateFlow(false)

    /** 宿主 ViewModel 落盘完成后置位，由应用根结束进程。 */
    val exitRequested: StateFlow<Boolean> = _exitRequested.asStateFlow()

    /** 由 `App` 设置的能力：面板失去焦点时清空搜索。 */
    internal var clearSearchAction: () -> Unit = {}

    /** 由 `App` 设置的能力：页脚中的「退出」一行。 */
    internal var quitAction: () -> Unit = {}

    /**
     * 由视图设置：判断屏幕坐标（AWT 全局坐标）是否落在面板窗口内。
     * 供「点击面板之外就收起」的判定使用；未设置时视为面板内（不收起）。
     */
    var panelContainsPoint: ((Double, Double) -> Boolean)? = null

    /** 由 `App` 写入最新的界面投影。 */
    internal fun setHostUiState(value: HostUiState) {
        _hostUiState.value = value
    }

    /** 对应托盘的点击：窗口侧 ViewModel 观察该请求后按当前可见性呼出或收起面板。 */
    fun requestToggle() {
        lastTrayClickAtMillis = currentTimeMillis()
        _toggleRequests.value++
    }

    /** 对应 `FloatingPanel.close()`：关闭弹窗的同时也关闭预览滑出面板。 */
    fun requestHide() {
        _hideRequests.value++
    }

    /** 宿主 ViewModel 落盘完成后请求结束进程。 */
    fun requestExit() {
        _exitRequested.value = true
    }

    /** 对应 `ListHeaderView` 的「弹窗失去焦点时清空搜索」。 */
    fun clearSearch() = clearSearchAction()

    /** 对应 `AppDelegate.applicationWillTerminate`：应用「退出时清空历史」偏好。 */
    fun quit() = quitAction()
}
