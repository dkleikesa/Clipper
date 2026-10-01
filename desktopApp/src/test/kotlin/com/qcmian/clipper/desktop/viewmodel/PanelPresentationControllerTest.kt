package com.qcmian.clipper.desktop.viewmodel

import com.qcmian.clipper.desktop.domain.FOCUS_GRACE_MILLIS
import com.qcmian.clipper.desktop.domain.PanelEnvironment
import com.qcmian.clipper.desktop.domain.PopupMode
import com.qcmian.clipper.desktop.domain.TRAY_CLICK_GRACE_MILLIS
import com.qcmian.clipper.host.HotkeyController
import com.qcmian.clipper.host.WindowController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `PanelPresentationController` 的行为：面板该不该收起、收起时要不要抢回焦点、要不要通知宿主。
 *
 * 这里出问题的从来不是「算得对不对」，而是**时序判定**：
 *
 * - 面板刚显示后那一次短暂失焦（[FOCUS_GRACE_MILLIS]）不能当成用户点了别处；
 * - 点击菜单栏图标引起的失焦要交给托盘切换处理，否则会「点托盘关不掉」
 *   （[TRAY_CLICK_GRACE_MILLIS]）；
 * - 让位给设置窗口时**不能**抢回焦点、也**不能**连带请求隐藏（那会被当成用户关闭主窗口）。
 *
 * 读系统时钟与碰 AppKit 的部分收在 [PanelEnvironment] 与注入的 `now` 里，于是这些边界可以
 * 用一个可变时钟精确地跑在「宽限期内 / 外」两侧。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PanelPresentationControllerTest {

    // -------------------------------------------------------------------------------------
    // 失焦：宽限期
    // -------------------------------------------------------------------------------------

    @Test
    fun `面板刚显示后的失焦不收起`() = runTest {
        val f = fixture()

        f.controller.onWindowGainedFocus()
        f.clock += FOCUS_GRACE_MILLIS - 1
        f.controller.onWindowLostFocus()

        assertTrue(f.state.value.windowVisible, "宽限期内的失焦是显示本身造成的，不该收起")
    }

    @Test
    fun `过了宽限期的失焦收起面板`() = runTest {
        val f = fixture()

        f.controller.onWindowGainedFocus()
        f.clock += FOCUS_GRACE_MILLIS
        f.controller.onWindowLostFocus()

        assertFalse(f.state.value.windowVisible)
        assertEquals(1, f.panel.hideRequests.value, "收起要通知宿主，预览才跟着关闭")
    }

    @Test
    fun `刚点过托盘图标的失焦交给托盘切换处理`() = runTest {
        val f = fixture()

        f.controller.onWindowGainedFocus()
        // 让「刚显示」那一次宽限先过去，好把焦点落在托盘那一条宽限上。
        f.clock += FOCUS_GRACE_MILLIS + 50
        f.panel.requestToggle()
        f.clock += 50
        f.controller.onWindowLostFocus()

        assertTrue(f.state.value.windowVisible, "这次失焦是点图标本身造成的，不能单独收起")

        f.clock += TRAY_CLICK_GRACE_MILLIS
        f.controller.onWindowLostFocus()

        assertFalse(f.state.value.windowVisible, "过了宽限期的失焦照常收起")
    }

    // -------------------------------------------------------------------------------------
    // 面板外点击
    // -------------------------------------------------------------------------------------

    @Test
    fun `面板外点击收起面板`() = runTest {
        val f = fixture()
        f.startOutsideClicks(backgroundScope)
        runCurrent()

        f.environment.clicks.emit(Unit)
        runCurrent()

        assertFalse(f.state.value.windowVisible)
    }

    @Test
    fun `同一次点击引起的托盘切换不会把刚收起的面板重新打开`() = runTest {
        val f = fixture()
        f.startOutsideClicks(backgroundScope)
        runCurrent()

        // 点托盘图标：面板外的点击先到，托盘回调紧随其后——两者其实是同一次点击。
        f.environment.clicks.emit(Unit)
        runCurrent()
        assertFalse(f.state.value.windowVisible)

        f.panel.requestToggle()
        f.controller.togglePanel()
        assertFalse(f.state.value.windowVisible, "同一次点击不该刚收起又打开")
        assertEquals(0, f.hotkey.openRequests.value)

        // 换一次真正的点击（过了宽限位）：正常呼出。
        f.clock += TRAY_CLICK_GRACE_MILLIS
        f.controller.togglePanel()

        assertTrue(f.state.value.windowVisible)
        assertTrue(f.state.value.panelOpenedByTray, "由托盘呼出，托盘图标要画按下态")
    }

    @Test
    fun `托盘请求经通道驱动面板开合`() = runTest {
        // 面板此刻是收起的：托盘点击应当把它呼出。
        val f = fixture(windowVisible = false)
        backgroundScope.launch { f.controller.observeToggleRequests() }
        runCurrent()

        f.panel.requestToggle()
        runCurrent()

        assertTrue(f.state.value.windowVisible)
        assertEquals(1, f.hotkey.openRequests.value, "呼出后要聚焦搜索框")
    }

    // -------------------------------------------------------------------------------------
    // 让位与还焦点
    // -------------------------------------------------------------------------------------

    @Test
    fun `让位给设置窗口时 不抢回焦点也不请求隐藏`() = runTest {
        var settingsOpen = false
        val f = fixture(settingsWindowOpen = { settingsOpen })
        f.environment.frontmostPid = 42
        f.controller.showPanel()
        f.controller.onWindowGainedFocus()

        settingsOpen = true
        f.clock += FOCUS_GRACE_MILLIS + 100
        f.controller.onWindowLostFocus()

        assertFalse(f.state.value.windowVisible, "设置窗口抢焦点，面板让位")
        assertTrue(f.environment.restoredFocus.isEmpty(), "用户要的是设置窗口，抢回焦点等于把它挤到后面")
        assertEquals(0, f.panel.hideRequests.value, "让位不是「用户关闭主窗口」，不该牵连关闭设置窗口")
    }

    @Test
    fun `普通失焦收起会通知宿主`() = runTest {
        val f = fixture()
        f.controller.showPanel()
        f.controller.onWindowGainedFocus()

        f.clock += FOCUS_GRACE_MILLIS + 100
        f.controller.onWindowLostFocus()

        assertFalse(f.state.value.windowVisible)
        assertEquals(1, f.panel.hideRequests.value, "这才是用户关闭主窗口，预览要一并关闭")
    }

    @Test
    fun `收起面板时把焦点还给此前最前的外部应用`() = runTest {
        val f = fixture()
        f.environment.frontmostPid = 42
        f.controller.showPanel()

        f.controller.hidePanel()

        assertEquals(listOf(42L), f.environment.restoredFocus, "合成粘贴的 ⌘V 要落到取内容前的那个应用上")
        assertEquals(1, f.panel.hideRequests.value)
    }

    @Test
    fun `抓不到外部应用时不还焦点`() = runTest {
        val f = fixture() // frontmostPid 默认 -1：例如本应用已在最前
        f.controller.showPanel()

        f.controller.hidePanel()

        assertTrue(f.environment.restoredFocus.isEmpty())
    }

    @Test
    fun `呼出时标记为托盘触发`() = runTest {
        val f = fixture()

        f.controller.showPanel()

        assertTrue(f.state.value.windowVisible)
        assertTrue(f.state.value.panelOpenedByTray)
        assertEquals(PopupMode.TOGGLE, f.state.value.popupMode)
        assertEquals(1, f.hotkey.openRequests.value)
    }

    // -------------------------------------------------------------------------------------
    // 夹具
    // -------------------------------------------------------------------------------------

    private fun fixture(
        windowVisible: Boolean = true,
        settingsWindowOpen: () -> Boolean = { false },
        devToolsWindowOpen: () -> Boolean = { false },
    ): Fixture = Fixture(windowVisible, settingsWindowOpen, devToolsWindowOpen)

    /** 记录调用的替身：前台应用 pid 可设，还焦点与面板外点击都记账。 */
    private class FakePanelEnvironment : PanelEnvironment {
        var frontmostPid: Long = -1L
        val restoredFocus = mutableListOf<Long>()
        val clicks = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

        override fun frontmostExternalPid(): Long = frontmostPid

        override fun restoreFocus(pid: Long) {
            restoredFocus += pid
        }

        override fun outsideClicks(): Flow<Unit> = clicks
    }

    private class Fixture(
        windowVisible: Boolean,
        settingsWindowOpen: () -> Boolean,
        devToolsWindowOpen: () -> Boolean,
    ) {
        /**
         * 可变时钟：测试用 `+=` 相对推进，跨越两条宽限期。
         *
         * 起点取一个「远离 0」的值，是为了贴合生产：真实墙钟是巨大的毫秒数，而
         * 「最近一次面板外点击」这类字段初值为 `0`——若时钟从 0 起步，`now - 0 < 宽限`
         * 会把「从没点过」误判成「刚刚点过」。
         */
        var clock: Long = CLOCK_BASE

        val state = MutableStateFlow(DesktopShellUiState(windowVisible = windowVisible))
        val environment = FakePanelEnvironment()
        val panel = WindowController(now = { clock })
        val hotkey = HotkeyController()

        val controller = PanelPresentationController(
            state = state,
            panel = panel,
            hotkey = hotkey,
            isSettingsWindowOpen = settingsWindowOpen,
            isDevToolsWindowOpen = devToolsWindowOpen,
            environment = environment,
            now = { clock },
        )

        fun startOutsideClicks(scope: CoroutineScope) {
            scope.launch { controller.observeOutsideClicks() }
        }

        private companion object {
            const val CLOCK_BASE = 1_000_000L
        }
    }
}
