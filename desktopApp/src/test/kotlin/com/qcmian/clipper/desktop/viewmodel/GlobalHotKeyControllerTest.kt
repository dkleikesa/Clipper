package com.qcmian.clipper.desktop.viewmodel

import com.qcmian.clipper.core.domain.repository.ClipboardRepository
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.desktop.domain.CYCLE_INTERVAL_MILLIS
import com.qcmian.clipper.desktop.domain.CYCLE_START_DELAY_MILLIS
import com.qcmian.clipper.desktop.domain.MODIFIER_POLL_MILLIS
import com.qcmian.clipper.desktop.domain.NS_COMMAND_MASK
import com.qcmian.clipper.desktop.domain.NS_SHIFT_MASK
import com.qcmian.clipper.desktop.domain.PopupMode
import com.qcmian.clipper.host.HotkeyController
import com.qcmian.clipper.host.WindowController
import com.qcmian.clipper.testing.RecordingNative
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `GlobalHotKeyController` 的「按住循环」状态机。
 *
 * 它几乎全是**时序**：按满 [CYCLE_START_DELAY_MILLIS] 才开始逐条走、松掉一部分只挂起、
 * 整组键都松开才收尾。这些边界在真实时钟下要么跑不到、要么慢到不能进测试，因此用 `runTest`
 * 的虚拟时间驱动——注入的时钟就是 `testScheduler.currentTime`，与 `delay` 走同一个调度器，
 * 「等了多久」与「推进了几条」因此完全确定。
 *
 * 唤醒它与系统时钟无关的部分：修饰键「按着什么程度」由假的 [RecordingNative] 现读
 * （见 `heldModifiers`），因此测试能精确地在「组合键完整 / 只松一部分 / 全松开」之间切换。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalHotKeyControllerTest {

    // -------------------------------------------------------------------------------------
    // 打开与按住
    // -------------------------------------------------------------------------------------

    @Test
    fun `首次按下打开面板并开一次按住会话 此时还不循环`() = runTest {
        val f = fixture().also { it.start(backgroundScope) }

        f.controller.onHotKeyPressed()
        runCurrent()

        assertTrue(f.state.value.windowVisible, "热键按下要呼出面板")
        assertEquals(PopupMode.OPENING, f.state.value.popupMode, "会话刚开始，本阶段是「打开中」")
        assertFalse(f.state.value.panelOpenedByTray, "热键呼出不是托盘触发")
        assertEquals(1, f.openedByHotKey, "表现层要记账：抓前台应用 + 标记非托盘呼出")
        assertEquals(1, f.hotkey.openRequests.value, "请求面板重新聚焦搜索框")
        assertEquals(0, f.hotkey.cycleRequests.value, "延迟没走完，一条都不该推进")
    }

    @Test
    fun `长按满延迟后进入循环 并每 120ms 推进一条`() = runTest {
        val f = fixture().also { it.start(backgroundScope) }
        f.controller.onHotKeyPressed()
        runCurrent()

        // 还差两个采样周期才到延迟：仍停在「打开中」，一条都没推进。
        advanceTimeBy(CYCLE_START_DELAY_MILLIS - MODIFIER_POLL_MILLIS * 2)
        runCurrent()
        assertEquals(PopupMode.OPENING, f.state.value.popupMode, "延迟没走完不该进入循环")
        assertEquals(0, f.hotkey.cycleRequests.value)

        // 越过延迟：进入循环，并先推进一条。
        advanceTimeBy(MODIFIER_POLL_MILLIS * 4)
        runCurrent()
        assertEquals(PopupMode.CYCLE, f.state.value.popupMode)
        val afterFirst = f.hotkey.cycleRequests.value
        assertTrue(afterFirst >= 1, "延迟刚结束时先推进一条，实际 $afterFirst")

        // 再等一个循环间隔：又推进一条。
        advanceTimeBy(CYCLE_INTERVAL_MILLIS + MODIFIER_POLL_MILLIS)
        runCurrent()
        assertTrue(
            f.hotkey.cycleRequests.value > afterFirst,
            "此后每 $CYCLE_INTERVAL_MILLIS 毫秒推进一条，实际 ${f.hotkey.cycleRequests.value}",
        )
    }

    // -------------------------------------------------------------------------------------
    // 松手：轻按 / 长按 / 松一部分
    // -------------------------------------------------------------------------------------

    @Test
    fun `轻按就松手 什么都不做 面板留在原地`() = runTest {
        val f = fixture().also { it.start(backgroundScope) }
        f.controller.onHotKeyPressed()
        runCurrent()

        // 远未到延迟就松手：这只是一次「呼出面板」。
        advanceTimeBy(MODIFIER_POLL_MILLIS * 2)
        runCurrent()
        releaseEverything(f)
        advanceTimeBy(MODIFIER_POLL_MILLIS * 2)
        runCurrent()

        assertEquals(0, f.hotkey.acceptRequests.value, "只点按一次不是「选中」")
        assertEquals(PopupMode.TOGGLE, f.state.value.popupMode, "会话已收尾")
        assertTrue(f.state.value.windowVisible, "面板留在原地")
    }

    @Test
    fun `长按进过循环后松手 选中高亮项`() = runTest {
        val f = fixture().also { it.start(backgroundScope) }
        f.controller.onHotKeyPressed()
        runCurrent()
        advanceToCycle(f)

        releaseEverything(f)
        advanceTimeBy(MODIFIER_POLL_MILLIS * 2)
        runCurrent()

        assertEquals(1, f.hotkey.acceptRequests.value, "进过循环，整组键松开即接受")
        assertEquals(PopupMode.TOGGLE, f.state.value.popupMode)
    }

    @Test
    fun `只抬起主键只是挂起 松掉修饰键才收尾`() = runTest {
        val f = fixture().also { it.start(backgroundScope) }
        f.controller.onHotKeyPressed()
        runCurrent()
        advanceToCycle(f)

        // 只抬主键、修饰键还按着：挂起，不选中也不关窗。
        f.controller.onHotKeyReleased()
        advanceTimeBy(MODIFIER_POLL_MILLIS * 3)
        runCurrent()
        assertEquals(0, f.hotkey.acceptRequests.value, "组合键只松了一部分，不是松手")
        assertEquals(PopupMode.OPENING, f.state.value.popupMode, "挂起时退回「打开中」")
        assertTrue(f.state.value.windowVisible, "挂起期间不关窗")

        // 修饰键也松开：整组键都松了才算松手，因为进过循环所以要选中。
        f.native.modifierFlags = 0
        advanceTimeBy(MODIFIER_POLL_MILLIS * 2)
        runCurrent()
        assertEquals(1, f.hotkey.acceptRequests.value, "整组键松开才收尾")
    }

    @Test
    fun `会话期间面板被收起 会话结束且不再推进`() = runTest {
        val f = fixture().also { it.start(backgroundScope) }
        f.controller.onHotKeyPressed()
        runCurrent()
        advanceToCycle(f)
        val before = f.hotkey.cycleRequests.value

        // 面板被别处收起（失焦、Esc、点了某一条……）。
        f.state.update { it.copy(windowVisible = false) }
        advanceTimeBy(CYCLE_INTERVAL_MILLIS * 3)
        runCurrent()

        assertEquals(before, f.hotkey.cycleRequests.value, "面板收起后会话应随即结束")
        assertEquals(0, f.hotkey.acceptRequests.value, "收起不是「松手」，不该触发接受")
    }

    // -------------------------------------------------------------------------------------
    // 已经显示时的再次按下
    // -------------------------------------------------------------------------------------

    @Test
    fun `托盘呼出的面板再按 挪到鼠标位置 不参与循环`() = runTest {
        val f = fixture(
            state = DesktopShellUiState(
                windowVisible = true,
                popupMode = PopupMode.TOGGLE,
                panelOpenedByTray = true,
            ),
        ).also { it.start(backgroundScope) }

        f.controller.onHotKeyPressed()
        runCurrent()

        assertEquals(1, f.movedToCursor, "托盘呼出的面板再按是把窗口移到鼠标处")
        assertEquals(0, f.hotkey.cycleRequests.value, "它不参与按住循环")
        assertEquals(0, f.openedByHotKey, "面板已经显示，不该再记一次「热键打开」")
    }

    @Test
    fun `已显示的面板再按 往下选一条并重新开一次会话`() = runTest {
        val f = fixture(
            state = DesktopShellUiState(
                windowVisible = true,
                popupMode = PopupMode.TOGGLE,
                panelOpenedByTray = false,
            ),
        ).also { it.start(backgroundScope) }

        f.controller.onHotKeyPressed()
        runCurrent()

        assertEquals(1, f.hotkey.cycleRequests.value, "短按即往下选一条")
        assertEquals(PopupMode.OPENING, f.state.value.popupMode, "同时重新开一次按住会话")
        assertEquals(0, f.movedToCursor, "热键呼出的面板不挪窗口")
    }

    // -------------------------------------------------------------------------------------
    // 开发者工具热键：叫到眼前 / 在眼前时关掉
    // -------------------------------------------------------------------------------------

    /**
     * 这一条是本文件里最要紧的一条：窗口开到后台之后再按快捷键，**不能**把它关掉。
     *
     * 「切换」在窗口不可见时等于「按了没反应」——关的是一个用户看不见的窗口；用户按这一下
     * 想要的是把它叫到眼前。判据是窗口的焦点位，见 `devToolsHotKeyActionOf`。
     */
    @Test
    fun `开发窗口开着但不在最前时 按住热键只请求叫到眼前 不关窗`() = runTest {
        val f = fixture(devToolsFocused = false).also { it.start(backgroundScope) }

        f.devToolsHotKeyTriggered()
        runCurrent()

        assertEquals(1, f.panel.devToolsShowRequests.value, "该把窗口叫到眼前")
        assertEquals(0, f.panel.devToolsToggleRequests.value, "不能把后台的窗口关掉——那看起来就是「按了没反应」")
    }

    @Test
    fun `开发窗口已经在最前时 按住热键仍然是关掉它`() = runTest {
        val f = fixture(devToolsFocused = true).also { it.start(backgroundScope) }

        f.devToolsHotKeyTriggered()
        runCurrent()

        assertEquals(1, f.panel.devToolsToggleRequests.value, "窗口就在眼前，这一下是「关掉」")
        assertEquals(0, f.panel.devToolsShowRequests.value, "已经在最前，没有可叫的")
    }

    /**
     * 关掉之后必须还能按回来。
     *
     * `devToolsOpen` 落到 `false` 要走一圈（宿主把请求转成动作、状态持有者更新、重组、窗口隐藏），
     * 下一次按下可能赶在它落地之前；那时焦点位若还是「在眼前」，这一次按下会又走「关掉」一路——
     * 窗口于是再也开不出来。所以走「关掉」这一路时要顺手把焦点位清掉。
     */
    @Test
    fun `刚关掉窗口后再按一次 是重新打开而不是再关一次`() = runTest {
        val f = fixture(devToolsFocused = true).also { it.start(backgroundScope) }

        f.devToolsHotKeyTriggered()
        assertFalse(f.panel.isDevToolsWindowFocused, "关掉之后不能再自称「在眼前」")

        f.devToolsHotKeyTriggered()

        assertEquals(1, f.panel.devToolsToggleRequests.value, "第二次按下是重新打开，不该再发一次关闭请求")
        assertEquals(1, f.panel.devToolsShowRequests.value, "第二次按下走的是「出现在眼前」这一路")
    }

    // -------------------------------------------------------------------------------------
    // 夹具
    // -------------------------------------------------------------------------------------

    /** 默认呼出键 `⇧⌘C` 的修饰键掩码。 */
    private val popupMask = NS_SHIFT_MASK or NS_COMMAND_MASK

    private fun TestScope.fixture(
        state: DesktopShellUiState = DesktopShellUiState(),
        devToolsFocused: Boolean = false,
    ): Fixture = Fixture(
        state = MutableStateFlow(state),
        repository = FakeClipboardRepository(AppSettings()),
        native = RecordingNative().apply { modifierFlags = popupMask },
        devToolsFocused = devToolsFocused,
        now = { testScheduler.currentTime },
    )

    /** 长按到确定进入循环（越过开始延迟再多等一个采样周期）。 */
    private fun TestScope.advanceToCycle(f: Fixture) {
        advanceTimeBy(CYCLE_START_DELAY_MILLIS + MODIFIER_POLL_MILLIS * 2)
        runCurrent()
        assertEquals(PopupMode.CYCLE, f.state.value.popupMode, "前置条件：长按应已进入循环")
        assertTrue(f.hotkey.cycleRequests.value >= 1, "前置条件：循环应已推进过")
    }

    /** 主键抬起 + 修饰键全松开：一次真正的松手。 */
    private fun releaseEverything(f: Fixture) {
        f.controller.onHotKeyReleased()
        f.native.modifierFlags = 0
    }

    private class Fixture(
        val state: MutableStateFlow<DesktopShellUiState>,
        repository: ClipboardRepository,
        val native: RecordingNative,
        devToolsFocused: Boolean,
        now: () -> Long,
    ) {
        val panel = WindowController(now = now)
        val hotkey = HotkeyController()

        var openedByHotKey = 0
        var movedToCursor = 0

        init {
            // 窗口的焦点位由窗口自己的焦点监听维护；这里直接摆好「按下的那一刻它在不在最前」。
            panel.isDevToolsWindowFocused = devToolsFocused
        }

        val controller = GlobalHotKeyController(
            state = state,
            repository = repository,
            native = native,
            panel = panel,
            hotkey = hotkey,
            onHotKeyOpened = { openedByHotKey++ },
            onMoveToCursor = { movedToCursor++ },
            now = now,
        )

        /**
         * 模拟按下开发者工具热键。
         *
         * 打的是**产品代码里那一个** [GlobalHotKeyController.onDevToolsHotKeyPressed]，而不是在
         * 测试里复刻一份分流：复刻会让「改坏了 `when` 的某一条分支」照样全绿。没法打到的只有
         * 外面那层注册（`observeShortcut` 直接调 `MacGlobalHotKey` 的静态原生接口），那一层
         * 没有可测的分支。
         */
        fun devToolsHotKeyTriggered() = controller.onDevToolsHotKeyPressed()

        fun start(scope: CoroutineScope) {
            scope.launch { controller.observeHotKeyHold() }
        }
    }
}
