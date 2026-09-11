package com.qcmian.clipper.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qcmian.clipper.settings.AppSettings
import com.qcmian.clipper.settings.MenuIcon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 界面中面向宿主的投影：桌面托盘或窗口绘制自身所需的一切。
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

    /** 预览滑出面板当前是否打开。 */
    val isPreviewOpen: Boolean = false,

    /**
     * 有对话框（偏好设置、关于、清除确认）显示时为 `true`。
     * 对应 `FloatingPanel.resignKey`，它在弹出警告框时不会关闭面板。
     */
    val isModalOpen: Boolean = false,

    /** `Defaults[.menuIcon]`。 */
    val menuIcon: MenuIcon = MenuIcon.MACCY,

    /** `AppState.menuIconText`，在 `showRecentCopyInMenuBar` 开启时显示。 */
    val recentCopyText: String = "",
)

/**
 * 让宿主（例如桌面托盘）像 Maccy 的菜单栏图标那样观察并驱动面板。
 *
 * 它携带的状态是由 `App` 写入的 [HostUiState] 投影；下面的请求则反向而行，
 * 从宿主进入状态持有者，以普通动作的形式传递。
 */
class ClipperController {
    /** 由 `App` 从界面状态镜像过来；宿主只读取它。 */
    var hostUiState by mutableStateOf(HostUiState())
        internal set

    /**
     * 面板已经打开时按下全局热键则自增。面板会据此高亮下一条，
     * 即 Maccy 的 `PopupState.cycle`。
     */
    var cycleRequests by mutableStateOf(0)
        internal set

    /** 通过全局热键打开面板时自增。 */
    var openRequests by mutableStateOf(0)
        internal set

    /** 托盘请求显示面板（菜单「显示 Clipper」/ 普通点击图标）时自增。 */
    var showRequests by mutableStateOf(0)
        internal set

    /** 宿主（窗口或托盘的 ViewModel）落盘完成后置位，由应用根结束进程。 */
    private val _exitRequested = MutableStateFlow(false)
    val exitRequested: StateFlow<Boolean> = _exitRequested.asStateFlow()

    /**
     * 循环模式下松开全局热键时自增。对应 `Popup.handleFlagsChanged`，
     * 它在松开时接受当前高亮的条目。
     */
    var acceptRequests by mutableStateOf(0)
        internal set

    /** 宿主隐藏面板时自增，使预览与之一同关闭。 */
    var hideRequests by mutableStateOf(0)
        internal set

    internal var togglePauseAction: (Boolean) -> Unit = {}
    internal var togglePreviewAction: () -> Unit = {}
    internal var clearSearchAction: () -> Unit = {}
    internal var quitAction: () -> Unit = {}

    /** 由宿主设置，使「重置弹窗位置」按钮能清掉记住的位置。 */
    var resetPositionAction: () -> Unit = {}

    /**
     * 对应按住 ⌥ 点击状态项；[onlyNext] 对应 ⇧⌥ 组合，即只为下一次复制暂停记录。
     */
    fun togglePause(onlyNext: Boolean = false) = togglePauseAction(onlyNext)

    fun togglePreview() = togglePreviewAction()

    /** 对应 `.cycle` 状态下 `Popup.handleKeyDown`：移到下一条历史。 */
    fun requestCycle() {
        cycleRequests++
    }

    /** 对应面板关闭时的 `Popup.handleFirstKeyDown`。 */
    fun requestOpen() {
        openRequests++
    }

    /** 对应托盘菜单的「显示 Clipper」：窗口侧 ViewModel 观察该请求后显示面板。 */
    fun requestShow() {
        showRequests++
    }

    /** 宿主 ViewModel 落盘完成后请求结束进程。 */
    fun requestExit() {
        _exitRequested.value = true
    }

    /** 对应 `Popup.handleFlagsChanged`：松开修饰键时接受高亮的条目。 */
    fun requestAccept() {
        acceptRequests++
    }

    /** 对应 `FloatingPanel.close()`：关闭弹窗的同时也关闭预览滑出面板。 */
    fun requestHide() {
        hideRequests++
    }

    /** 对应 `ListHeaderView` 的「弹窗失去焦点时清空搜索」。 */
    fun clearSearch() = clearSearchAction()

    /**
     * 对应 `AppDelegate.applicationWillTerminate`：应用「退出时清空历史」偏好。
     * 宿主要在关闭前调用它，然后等待自己的存储落盘。
     */
    fun quit() = quitAction()

    /** 对应 `PopupPosition.lastPosition` 旁的「重置」按钮。 */
    fun resetPosition() = resetPositionAction()
}
