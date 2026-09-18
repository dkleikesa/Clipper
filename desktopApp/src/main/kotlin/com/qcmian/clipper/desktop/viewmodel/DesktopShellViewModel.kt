package com.qcmian.clipper.desktop.viewmodel

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.window.WindowState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qcmian.clipper.core.platform.macos.MacAppearance
import com.qcmian.clipper.core.platform.macos.MacWorkspace
import com.qcmian.clipper.di.AppContainer
import com.qcmian.clipper.host.HotkeyController
import com.qcmian.clipper.host.WindowController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 桌面外壳的 ViewModel：只做**协调**与窗口可见性 / 平台桥接，本身不再承载具体机制。
 *
 * 状态管理遵循单向数据流：对外以 [StateFlow] 暴露单一 [DesktopShellUiState]（状态下行），
 * 视图通过本类的意图函数上报事件（事件上行）。三块机制各自收在一个控制器里，它们共享同一份
 * [DesktopShellUiState]，因此彼此不必互相持有引用：
 *
 * - [PanelPresentationController]：面板显隐、焦点恢复、托盘与面板外点击；
 * - [WindowGeometryController]：位置 / 尺寸、预览停靠、用户拖拽、最小尺寸；
 * - [GlobalHotKeyController]：全局热键注册与「按住循环」状态机。
 *
 * 定位与尺寸这类纯计算仍放在同包的 Model（`WindowPlacement` / `WindowSizing`）里。
 *
 * @param windowState Compose Desktop 的窗口状态持有者；由宿主创建后交给 [WindowGeometryController]
 *   读写，因为窗口的尺寸与位置正是「视图模型」要驱动的东西。
 * @param applyBounds 把位置与尺寸一次性应用到窗口上（见 [WindowGeometryController]）。
 * @param applyMinimumSize 设置窗口允许的最小尺寸（AWT `window.minimumSize`）。
 */
class DesktopShellViewModel(
    private val container: AppContainer,
    /** 窗口事件通道：显示 / 隐藏 / 退出请求与面板投影。 */
    private val panel: WindowController,
    hotkey: HotkeyController,
    windowState: WindowState,
    applyBounds: (x: Int, y: Int, width: Int, height: Int) -> Unit,
    applyMinimumSize: (width: Int, height: Int) -> Unit,
) : ViewModel() {
    private val _uiState = MutableStateFlow(DesktopShellUiState())
    val uiState: StateFlow<DesktopShellUiState> = _uiState.asStateFlow()

    /**
     * 系统外观是否为深色；`null` 表示未知（原生层不可用）。Compose 的
     * `isSystemInDarkTheme()` 在桌面端不会实时跟随系统外观变化，
     * 「跟随系统」主题模式改由这里的事件驱动观察（见 [observeSystemAppearance]）。
     */
    private val _systemDark = MutableStateFlow<Boolean?>(null)
    val systemDark: StateFlow<Boolean?> = _systemDark.asStateFlow()

    /** 面板显隐、焦点、托盘与面板外点击。 */
    private val presentation = PanelPresentationController(
        state = _uiState,
        panel = panel,
        hotkey = hotkey,
    )

    /** 窗口几何：定位、尺寸、预览停靠、用户拖拽。 */
    private val geometry = WindowGeometryController(
        state = _uiState,
        repository = container.repository,
        windowState = windowState,
        applyBounds = applyBounds,
        applyMinimumSize = applyMinimumSize,
        scope = viewModelScope,
        openedByTray = { presentation.openedByTray },
    )

    /** 全局热键注册与「按住循环」状态机。 */
    private val hotKeys = GlobalHotKeyController(
        state = _uiState,
        repository = container.repository,
        native = container.native,
        panel = panel,
        hotkey = hotkey,
        onHotKeyOpened = presentation::onHotKeyOpened,
        onMoveToCursor = geometry::moveToCursor,
    )

    init {
        presentation.captureFrontmostWindow()
        // 同步预读一次系统外观：首帧不必退回 `isSystemInDarkTheme()`；观察者装不上时，
        // 它也是本次会话唯一的外观来源（见 [observeSystemAppearance]）。
        _systemDark.value = runCatching { MacWorkspace.isSystemAppearanceDark() }.getOrNull()
        viewModelScope.launch { hotKeys.observeShortcut() }
        viewModelScope.launch { hotKeys.observeHotKeyHold() }
        viewModelScope.launch { geometry.observeWindowGeometry() }
        viewModelScope.launch { geometry.observeMinimumWindowSize() }
        viewModelScope.launch { geometry.observeUserResize() }
        viewModelScope.launch { presentation.observeToggleRequests() }
        viewModelScope.launch { presentation.observeOutsideClicks() }
        viewModelScope.launch { observeSystemAppearance() }
    }

    // ---------------------------------------------------------------------------------
    // 意图函数：视图与托盘转发进来的命令（事件上行）
    // ---------------------------------------------------------------------------------

    /** 全局热键：未显示则打开；已显示则逐条循环，托盘呼出的面板再按则把窗口移到鼠标位置。 */
    fun onHotKeyPressed() = hotKeys.onHotKeyPressed()

    /** 全局热键松开：主键抬起了（见 `GlobalHotKeyController.onHotKeyReleased`）。 */
    fun onHotKeyReleased() = hotKeys.onHotKeyReleased()

    /** 显示面板（不进入循环模式）。对应托盘菜单的「显示 Clipper」。 */
    fun showPanel() = presentation.showPanel()

    /** 点击菜单栏图标：面板已显示则收起，否则呼出。 */
    fun togglePanel() = presentation.togglePanel()

    /** 隐藏面板。[restoreFocus] 为 `false`（因点击别处而失焦）时不抢回焦点。 */
    fun hidePanel(restoreFocus: Boolean = true) = presentation.hidePanel(restoreFocus)

    fun onWindowGainedFocus() = presentation.onWindowGainedFocus()

    fun onWindowLostFocus() = presentation.onWindowLostFocus()

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
     * 「跟随系统」主题模式：只用系统通知（`AppleInterfaceThemeChangedNotification`）事件驱动，
     * 零轮询。
     *
     * 刻意不保留「观察者装不上就 1 秒轮询 `AppleInterfaceStyle`」的兜底：那是常驻的定时唤醒，
     * 换来的只是「通知装不上时主题不实时跟随」。装不上时不再覆盖 [systemDark]，界面停在
     * [init] 里同步读到的那个值——那也是 `MacAppearance.install` 失败时唯一的外观来源，
     * 不能让它被 `null` 冲掉。
     */
    private suspend fun observeSystemAppearance() {
        if (!MacAppearance.install()) return
        MacAppearance.systemDark.collect { _systemDark.value = it }
    }
}
