package com.qcmian.clipper.desktop.viewmodel

import com.qcmian.clipper.core.platform.macos.MacKeyboard
import com.qcmian.clipper.core.platform.macos.MacOutsideClickMonitor
import com.qcmian.clipper.core.platform.macos.MacWorkspace
import com.qcmian.clipper.desktop.domain.FOCUS_GRACE_MILLIS
import com.qcmian.clipper.desktop.domain.PANEL_WINDOW_TITLE
import com.qcmian.clipper.desktop.domain.PopupMode
import com.qcmian.clipper.desktop.domain.TRAY_CLICK_GRACE_MILLIS
import com.qcmian.clipper.host.HotkeyController
import com.qcmian.clipper.host.WindowController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 面板的「表现层」：显隐意图、焦点恢复、托盘与面板外点击。
 *
 * 它只决定「面板该不该显示、谁是它的前台」，完全不碰窗口几何——几何由
 * [WindowGeometryController] 观察 [state] 自行收敛。三条收起路径（失焦、面板外点击、托盘
 * 切换）都在这里汇到 [hidePanel]。
 *
 * 「是否托盘呼出」这一位从这里对外暴露给几何层（[openedByTray]）：托盘呼出时锚点固定在菜单栏
 * 图标上，位置偏好因此要临时改写（见 `WindowGeometryController.placementPreference`）。
 */
internal class PanelPresentationController(
    private val state: MutableStateFlow<DesktopShellUiState>,
    private val panel: WindowController,
    private val hotkey: HotkeyController,
) {
    /**
     * 本次显示是否由点击托盘图标触发。是的话位置直接锚定菜单栏图标：点托盘那一刻光标就在
     * 图标上，「光标位置」与「菜单栏图标」本来就是同一个落点，统一走图标锚点更一致。
     *
     * 热键回调运行在 AppKit 主线程，而定位在 EDT 上，故标记为 `@Volatile`。
     */
    @Volatile
    private var _openedByTray = false

    /** 几何层读取「本次是否托盘呼出」，据此决定锚点。 */
    val openedByTray: Boolean get() = _openedByTray

    /** 面板显示前最前的那个外部应用 pid，隐藏时用于把焦点还回去。 */
    private var previousAppPid = -1L

    private var lastFocusGainedAt = System.currentTimeMillis()

    /** 最近一次「点击面板之外」导致的收起时刻，用于识别同一次点击触发的托盘切换。 */
    private var lastOutsideHideAtMillis = 0L

    /**
     * 全局热键在面板隐藏时按下：只做表现层该记的账（抓前台应用 + 标记非托盘呼出），
     * 可见性与热键「打开」由 `GlobalHotKeyController` 自己写——避免两个方向互相调用。
     */
    fun onHotKeyOpened() {
        captureFrontmostWindow()
        _openedByTray = false
    }

    /** 显示面板（不进入循环模式）。对应托盘菜单的「显示 Clipper」，属托盘触发。 */
    fun showPanel() {
        captureFrontmostWindow()
        _openedByTray = true
        state.update {
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
        if (state.value.windowVisible) hidePanel() else showPanel()
    }

    /** 隐藏面板。[restoreFocus] 为 `false`（因点击别处而失焦）时不抢回焦点。 */
    fun hidePanel(restoreFocus: Boolean = true) {
        val pid = previousAppPid
        state.update {
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
        // 面板真正成为 key window 时，搜索框重新获得焦点并选中第一条。
        // 聚焦跟着「窗口取得键盘焦点」走，而不是跟着
        // 「打开意图」走——后者可能落在窗口显示之前，`requestFocus()` 会静默失效，
        // 表现为「打开后偶尔打不了字」。
        if (state.value.windowVisible) hotkey.requestOpen()
    }

    /** 失去焦点即隐藏（面板自己弹着确认框时不隐藏）。 */
    fun onWindowLostFocus() {
        val current = state.value
        val host = panel.hostUiState.value
        if (!current.windowVisible) return
        // 设置窗口是**独立窗口**：它抢走焦点就说明用户要改设置，面板必须让位。
        //
        // 这一条要排在 [HostUiState.isModalOpen] 与下面两条宽限期判断**之前**：一来从面板里
        // 按 ⌘, 打开设置，多半正好落在「面板刚显示」的宽限期内；二来面板自己那层模态
        // （清除确认）此刻是画在设置窗口里的（见 `HistoryDialogs`），拿它拦住隐藏只会把面板
        // 留在屏幕上、和设置窗口叠在一起。
        if (host.isSettingsWindowOpen) {
            // 不能把焦点还给上一个应用——用户要的是设置窗口，抢回去等于把它挤到后面。
            hidePanel(restoreFocus = false)
            return
        }
        if (host.isModalOpen) return
        // 忽略面板刚显示之后那一次短暂的失焦。
        if (System.currentTimeMillis() - lastFocusGainedAt < FOCUS_GRACE_MILLIS) return
        // 刚点过菜单栏图标：这次失焦是点击本身造成的，收起与否交给 [togglePanel] 决定。
        // 否则会先在这里被隐藏、再被 toggle 重新打开，看起来就是「点托盘关不掉」。
        if (System.currentTimeMillis() - panel.lastTrayClickAtMillis < TRAY_CLICK_GRACE_MILLIS) return
        // 用户已经点了别处，不能再把焦点抢回来。
        hidePanel(restoreFocus = false)
    }

    /**
     * 托盘请求切换面板：托盘与窗口逻辑隔离，点击经
     * [WindowController.requestToggle] 通道转达，由这里按当前可见性决定呼出还是收起。
     */
    suspend fun observeToggleRequests() {
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
    suspend fun observeOutsideClicks() {
        MacOutsideClickMonitor.install(PANEL_WINDOW_TITLE)
        MacOutsideClickMonitor.outsideClicks.collect {
            if (state.value.windowVisible && !panel.hostUiState.value.isModalOpen) {
                lastOutsideHideAtMillis = System.currentTimeMillis()
                hidePanel(restoreFocus = false)
            }
        }
    }

    /**
     * 记住面板显示前处于最前的那个外部应用，隐藏时把焦点还回去，
     * 这样「自动粘贴」的 ⌘V 才会落到它上面。
     */
    fun captureFrontmostWindow() {
        val pid = runCatching { MacWorkspace.frontmostExternalPid() }.getOrDefault(-1L)
        // 抓不到外部应用时（例如本应用已在最前）保留上一次记录。
        if (pid > 0) previousAppPid = pid
    }
}
