@file:OptIn(ExperimentalComposeUiApi::class)

package com.qcmian.clipper.desktop.ui

import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.use
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * 程序坞图标：Dock 里那张图是**我们自己栅格化**的（见 `toDockIconPng`）。
 *
 * 为什么非得自己给：不是从 `.app` 启动时（`gradlew run`、IDE 里跑）进程没有 bundle 图标，系统给
 * 的是一张「可执行文件」的通用图——一个 `>_` 的终端样子，与「开发者工具是个正经编辑面」完全不
 * 搭。这条用例守的就是那张图真的画得出来：尺寸对、圆角外透明、中心实心。
 *
 * 得跑一遍组合：`ImageVector` 的 painter 只有组合里拿得到（`rememberVectorPainter`），而它正是
 * 窗口图标与 Dock 图标共用的那一份。
 */
class ClipperAppIconTest {

    @Test
    fun `栅格化出来的图标是 512 的透明底 PNG`() {
        var png: ByteArray? = null
        ImageComposeScene(width = 512, height = 512, density = Density(1f)) {
            val painter = rememberVectorPainter(ClipperAppIcon)
            // 与 `ClipperDevToolsWindow` 里那个副作用等价：拿到 painter 就顺手编一份 PNG。
            SideEffect { png = painter.toDockIconPng(Density(1f), LayoutDirection.Ltr) }
        }.use { it.render() }

        val image = ImageIO.read(assertNotNull(png, "图标没编出来").inputStream())
        assertEquals(512, image.width)
        assertEquals(512, image.height)
        // 圆角之外必须**透明**（不是黑角），中心是底板必须实心：两样任一不对，Dock 里就是一张
        // 黑角方块或者一片空。
        assertEquals(0, image.getRGB(0, 0) ushr 24, "左上角在圆角之外，应当是透明的")
        assertEquals(255, image.getRGB(256, 256) ushr 24, "中心是底板，必须实心")
        assertEquals(0, image.getRGB(511, 511) ushr 24, "右下角同理")
    }
}
