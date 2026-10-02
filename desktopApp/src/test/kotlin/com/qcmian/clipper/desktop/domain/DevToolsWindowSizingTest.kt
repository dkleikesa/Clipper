package com.qcmian.clipper.desktop.domain

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.settings.AppSettings
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 开发者工具窗口的尺寸算式。
 *
 * 三条约束互相牵制，顺序错了就会开出一个「伸出屏幕」或「比下限还小」的窗口，而这两种错在
 * 界面上都只表现为「窗口位置怪怪的」，很难倒推回算式。所以这里把顺序钉住：
 * **先按屏幕夹首选值，再用屏幕夹用户值，最后才用下限兜底**。
 */
class DevToolsWindowSizingTest {

    /** 常见的大屏（16 寸 MacBook 的可用区域，已去掉菜单栏与 Dock）。 */
    private val largeScreen = Rectangle(0, 25, 1728, 1050)

    /** 小屏（13 寸 Air）：首选尺寸在它上面会被比例夹住。 */
    private val smallScreen = Rectangle(0, 25, 1280, 720)

    @Test
    fun `没有存过尺寸时按屏幕给首选值`() {
        val size = devToolsWindowSizeOf(AppSettings(), largeScreen)

        assertEquals(1180.dp, size.width, "大屏上应当开到首选宽度")
        assertEquals(760.dp, size.height, "大屏上应当开到首选高度")
    }

    @Test
    fun `小屏上首选值按比例收敛，不铺满整块屏幕`() {
        val size = devToolsWindowSizeOf(AppSettings(), smallScreen)

        assertTrue(size.width < 1180.dp, "小屏上不该硬开到首选宽度")
        assertTrue(size.height < 760.dp, "小屏上不该硬开到首选高度")
        // 1280 × 0.8 = 1024，720 × 0.8 = 576。
        assertEquals(1024.dp, size.width)
        assertEquals(576.dp, size.height)
    }

    @Test
    fun `存过尺寸时用存下来的值，屏幕再大也不跟着涨`() {
        val settings = AppSettings(devToolsWindowWidth = 980, devToolsWindowHeight = 620)

        val size = devToolsWindowSizeOf(settings, largeScreen)

        assertEquals(980.dp, size.width)
        assertEquals(620.dp, size.height)
    }

    @Test
    fun `存下来的尺寸会被屏幕夹住——副屏拔掉或换小屏之后不能伸出屏幕`() {
        val settings = AppSettings(devToolsWindowWidth = 1600, devToolsWindowHeight = 1000)

        val size = devToolsWindowSizeOf(settings, smallScreen)

        assertEquals(smallScreen.width.dp, size.width, "宽度不该超过屏幕可用宽度")
        assertEquals(smallScreen.height.dp, size.height, "高度不该超过屏幕可用高度")
    }

    @Test
    fun `存下来的尺寸比下限还小时抬到下限`() {
        val settings = AppSettings(devToolsWindowWidth = 300, devToolsWindowHeight = 200)

        val size = devToolsWindowSizeOf(settings, largeScreen)

        assertEquals(720.dp, size.width, "宽度下限是能摆下一对编辑区的位置")
        assertEquals(480.dp, size.height)
    }

    @Test
    fun `屏幕本身比下限还小时以屏幕为准——下限不能反过来把窗口顶出屏幕`() {
        val tinyScreen = Rectangle(0, 0, 640, 400)

        val size = devToolsWindowSizeOf(AppSettings(), tinyScreen)

        assertTrue(size.width <= 640.dp, "窗口宽度不该超过屏幕，否则右侧内容会被挤出可视区")
        assertTrue(size.height <= 400.dp, "窗口高度不该超过屏幕")
        assertEquals(DpSize(640.dp, 400.dp), minimumDevToolsWindowSize(tinyScreen))
    }

    @Test
    fun `宽高两个方向各自独立：只存了宽度时高度仍按屏幕算`() {
        val settings = AppSettings(devToolsWindowWidth = 1000)

        val size = devToolsWindowSizeOf(settings, largeScreen)

        assertEquals(1000.dp, size.width)
        assertEquals(760.dp, size.height, "没存过的高度应当回落到按屏幕算的首选值")
    }
}
