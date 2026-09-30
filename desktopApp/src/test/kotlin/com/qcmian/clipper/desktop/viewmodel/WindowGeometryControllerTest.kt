package com.qcmian.clipper.desktop.viewmodel

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.PopupPosition
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.desktop.domain.RESIZE_SETTLE_MILLIS
import com.qcmian.clipper.desktop.domain.WindowEnvironment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 窗口几何**对外可观察的行为**——落在原生窗口上的调用、写进偏好的值、报给界面的状态。
 *
 * 断言的是「发生了什么、发生几次、什么顺序、什么时候」，不是「算出来的数字对不对」
 * （那是纯函数的公式，改布局就会变，测了也只是复述实现）。这里注入固定的屏幕 / 锚点与
 * 可变时钟，用虚拟时间驱动，覆盖纯计算够不到的那一半：
 *
 * - 位置、尺寸、下限必须落在**同一次**窗口更新里，且下限先于尺寸；
 * - 程序自己设过的尺寸通知不能被误判成「用户在拖」；
 * - 用户拖动期间不接受程序化几何，松手后**只落盘一次**；
 * - 收起预览要等界面揭示动画走完才缩窗口。
 *
 * 注意：`observeWindowGeometry` 的 `combine` 里含 `snapshotFlow`，它要收集器真正跑起来才吐初值；
 * `backgroundScope` 的协程靠 `runCurrent()` 推进（`advanceUntilIdle()` 在本环境不启动它们）；
 * 快照状态变更用 [Snapshot.withMutableSnapshot] 才会派发 apply 通知。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WindowGeometryControllerTest {

    /** 一块 1440×860、左上角在原点的屏幕。 */
    private val screen = Rectangle(0, 0, 1440, 860)

    @Test
    fun `showing the panel updates the native window once, minimum size before bounds`() = runTest {
        val f = fixture()
        startGeometry(f)

        assertEquals(
            listOf("min(400,100)", "bounds(520,130,400,400)"),
            f.events,
            "一次窗口更新里：先设下限、再设尺寸位置（下限若排在后面，系统会先撑大一次）",
        )
        // 位置与尺寸同步进 WindowState，否则 Compose 会分两次应用（尺寸先到、位置后到）而抖一下。
        assertEquals(DpSize(400.dp, 400.dp), f.windowState.size)
        assertEquals(WindowPosition.Absolute(520.dp, 130.dp), f.windowState.position)
    }

    @Test
    fun `a geometry recomputation that lands on the same rect is not pushed again`() = runTest {
        val f = fixture()
        startGeometry(f)
        val before = f.events.toList()

        // 位置只是触发键、不参与计算：再触发一次应收敛到同一结果并被去重，不再动原生窗口。
        Snapshot.withMutableSnapshot { f.windowState.position = WindowPosition.Absolute(1_234.dp, 567.dp) }
        runCurrent()

        assertEquals(before, f.events)
    }

    @Test
    fun `a hidden panel never touches the window`() = runTest {
        val f = fixture(state = DesktopShellUiState(windowVisible = false))
        startGeometry(f)

        assertTrue(f.events.isEmpty())
    }

    @Test
    fun `opening the preview widens the window and closing waits for the reveal`() = runTest {
        val f = fixture()
        startGeometry(f)
        assertEquals(1, f.applied.size)

        // 打开：窗口一次到位（宽出来的那段此刻透明，看不出来）。
        f.repository.setSettings(f.repository.settings.value.copy(previewOpen = true))
        runCurrent()
        assertEquals(Rectangle(520, 130, 717, 400), f.applied.last())

        // 收起：先不落地，等界面把预览卡收回主列表后面。
        f.repository.setSettings(f.repository.settings.value.copy(previewOpen = false))
        runCurrent()
        advanceTimeBy(Popup.previewRevealMillis.toLong() - 1)
        assertEquals(2, f.applied.size, "揭示动画走完之前不该动窗口")

        advanceTimeBy(2)
        runCurrent()
        assertEquals(3, f.applied.size, "动画走完才缩回窗口")
        assertEquals(Rectangle(520, 130, 400, 400), f.applied.last())
    }

    @Test
    fun `a user drag freezes program geometry and settles into settings once`() = runTest {
        var clock = 0L
        val f = fixture(now = { clock })
        startGeometry(f)
        val eventsBeforeDrag = f.events.size

        backgroundScope.launch { f.controller.observeUserResize() }
        runCurrent()
        // 初始的尺寸通知就是程序刚设过的那个，不该被当成拖动。
        assertTrue(f.repository.settingsWrites.isEmpty(), "程序自设的尺寸不该触发落盘")
        assertFalse(f.state.value.userResizing)

        // 用户把窗口拖大：出现一个程序没设过的尺寸。
        Snapshot.withMutableSnapshot { f.windowState.size = DpSize(500.dp, 700.dp) }
        runCurrent()

        assertTrue(f.state.value.userResizing, "拖动期间要把 userResizing 报给界面")
        assertEquals(eventsBeforeDrag, f.events.size, "拖动期间不接受程序化几何")

        // 手松开，静默期走完。
        clock += RESIZE_SETTLE_MILLIS + 1
        advanceTimeBy(RESIZE_SETTLE_MILLIS + 1)
        runCurrent()

        assertEquals(1, f.repository.settingsWrites.size, "整段拖动只落盘一次")
        val written = f.repository.settingsWrites.single()
        assertEquals(500, written.customWindowWidth)
        assertEquals(700, written.customWindowHeight)
        assertFalse(f.state.value.userResizing)
        assertTrue(f.events.size > eventsBeforeDrag, "落盘后补一次程序化几何")
    }

    // ------------------------------------------------------------------ 夹具

    /** 启动几何观察者并推进到它落地一次。 */
    private fun TestScope.startGeometry(f: Fixture) {
        backgroundScope.launch { f.controller.observeWindowGeometry() }
        // 收敛需要几次同刻调度：combine 的三个上游依次就绪，快照初值也要一个调度回合。
        repeat(3) { runCurrent() }
    }

    private class Fixture(
        val state: MutableStateFlow<DesktopShellUiState>,
        val repository: FakeClipboardRepository,
        val windowState: WindowState,
        /** 落在原生窗口上的每一次调用（位置 / 尺寸），用于断言「动了几次、动了哪一条」。 */
        val applied: MutableList<Rectangle>,
        /** 原生调用的时间线（下限与尺寸的先后），用于断言顺序。 */
        val events: MutableList<String>,
        val controller: WindowGeometryController,
    )

    private fun fixture(
        settings: AppSettings = AppSettings(popupPosition = PopupPosition.SCREEN_CENTER),
        state: DesktopShellUiState = DesktopShellUiState(
            windowVisible = true,
            preferredHeight = 400.dp,
            minimumHeight = 100.dp,
        ),
        now: () -> Long = { 0L },
    ): Fixture {
        val stateFlow = MutableStateFlow(state)
        val repository = FakeClipboardRepository(settings)
        val windowState = WindowState().apply {
            size = DpSize(400.dp, 600.dp)
            position = WindowPosition.Absolute(0.dp, 0.dp)
        }
        val applied = mutableListOf<Rectangle>()
        val events = mutableListOf<String>()
        val controller = WindowGeometryController(
            state = stateFlow,
            repository = repository,
            windowState = windowState,
            applyBounds = { x, y, w, h ->
                events += "bounds($x,$y,$w,$h)"
                applied += Rectangle(x, y, w, h)
            },
            applyMinimumSize = { w, h -> events += "min($w,$h)" },
            openedByTray = { false },
            environment = object : WindowEnvironment {
                override fun visibleScreenBounds(index: Int): Rectangle = screen
                override fun statusItemAnchor() = null
            },
            now = now,
        )
        return Fixture(stateFlow, repository, windowState, applied, events, controller)
    }
}
