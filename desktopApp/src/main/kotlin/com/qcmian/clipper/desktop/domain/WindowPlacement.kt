package com.qcmian.clipper.desktop.domain

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import com.qcmian.clipper.core.platform.macos.MenuBarAnchor
import com.qcmian.clipper.core.settings.PopupPosition
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Toolkit

/**
 * 桌面窗口的定位（Model 层，纯函数）。
 *
 * 把「希望弹窗出现在哪里」翻译成具体的窗口坐标。
 * 这些函数不持有任何状态，输入相同则结果相同，因此与 Compose 或窗口生命周期无关。
 */
internal fun resolvePosition(
    position: PopupPosition,
    size: DpSize,
    bounds: Rectangle,
    statusItem: MenuBarAnchor?,
): WindowPosition = when (position) {
    PopupPosition.SCREEN_CENTER -> screenCenterPosition(size, bounds)
    PopupPosition.MENU_BAR -> menuBarPosition(size, bounds, statusItem)
    PopupPosition.CURSOR -> cursorAnchor(bounds)
}

/** 自己按 [screenIndex] 取屏幕的版本；索引语义见 [screenBounds]。 */
internal fun resolvePosition(
    position: PopupPosition,
    size: DpSize,
    screenIndex: Int,
    statusItem: MenuBarAnchor?,
): WindowPosition = resolvePosition(position, size, screenBounds(screenIndex), statusItem)

/**
 * 面板挂在菜单栏图标正下方，左边缘对齐图标左边缘。
 *
 * [statusItem] 是图标的真实水平范围，因此图标被拖到菜单栏别处时也跟得住；
 * 取不到时（非 AppKit 宿主）退回屏幕右边缘。
 */
internal fun menuBarPosition(size: DpSize, bounds: Rectangle, statusItem: MenuBarAnchor?): WindowPosition {
    // 左边缘对齐图标左边缘——图标被拖到菜单栏别处时弹窗跟着走。
    val x = statusItem?.left ?: (bounds.x + bounds.width - size.width.value.toInt())
    return constrained(
        x = x,
        // `bounds` 已排除菜单栏，这里只留一点间距即可。
        y = bounds.y + 8,
        size = size,
        bounds = bounds,
    )
}

internal fun menuBarPosition(size: DpSize, screenIndex: Int, statusItem: MenuBarAnchor?): WindowPosition =
    menuBarPosition(size, screenBounds(screenIndex), statusItem)

/**
 * 索引 0 是鼠标所在的屏幕（活动屏幕），其它索引指向 `NSScreen.screens[index - 1]`。
 *
 * 返回的是**可用区域**（`NSScreen.visibleFrame`），已去掉菜单栏与 Dock，
 * 因此弹窗定位与自动高度都不会延伸到 Dock 之下。
 */
internal fun screenBounds(index: Int): Rectangle {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val devices = environment.screenDevices
    if (index in 1..devices.size) {
        return devices[index - 1].visibleBounds()
    }

    val mouse = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
    if (mouse != null) {
        devices.firstOrNull { it.defaultConfiguration.bounds.contains(mouse) }
            ?.let { return it.visibleBounds() }
    }
    return environment.defaultScreenDevice.visibleBounds()
}

/** `NSScreen.visibleFrame`：屏幕矩形减去菜单栏与 Dock 之后剩下的区域。 */
internal fun GraphicsDevice.visibleBounds(): Rectangle {
    val configuration = defaultConfiguration
    val bounds = configuration.bounds
    // 无显示的环境（headless）取不到 insets，此时退回完整屏幕。
    val insets = runCatching { Toolkit.getDefaultToolkit().getScreenInsets(configuration) }
        .getOrNull() ?: return bounds
    return Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        bounds.width - insets.left - insets.right,
        bounds.height - insets.top - insets.bottom,
    )
}

/**
 * `PopupPosition.origin`：面板从光标处向下展开。
 *
 * 锚点只取光标本身（只夹进屏幕可见区域），**不**按窗口尺寸往上夹：窗口放不下时该由摆放阶段
 * （`applyWindowGeometry` 里的 `constrained`）把整个窗口上移。
 *
 * 一旦按「当前窗口高度」去夹锚点，锚点就会被推高，而高度又是按「锚点下方还剩多少」算的——
 * 高度等于自己的旧高度，于是鼠标往下移时窗口只是被推回原位，**高度再也降不回来**。
 */
internal fun cursorAnchor(bounds: Rectangle): WindowPosition {
    val mouse = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
        ?: return screenCenterPosition(DpSize.Zero, bounds)
    // AWT 在 macOS 上报告的是逻辑点，与 Compose 的 dp 一一对应。
    return constrained(mouse.x, mouse.y, DpSize.Zero, bounds)
}

internal fun cursorAnchor(screenIndex: Int): WindowPosition = cursorAnchor(screenBounds(screenIndex))

internal fun screenCenterPosition(size: DpSize, bounds: Rectangle): WindowPosition =
    screenCenterLocation(size, bounds).let { WindowPosition.Absolute(it.x.dp, it.y.dp) }

internal fun screenCenterPosition(size: DpSize, screenIndex: Int): WindowPosition =
    screenCenterPosition(size, screenBounds(screenIndex))

/**
 * [size] 这么大的窗口在 [screenIndex] 那块屏幕上居中的左上角坐标（整点）。
 *
 * 索引语义同 [screenBounds]：**0 是鼠标所在的那块屏幕**，1 及以上是固定的第 N 块。
 *
 * 返回整点坐标而不是 [WindowPosition]，是给「打开时定位一次」的窗口（设置、开发者工具）用的：
 * 那些窗口的尺寸由自己固定，定位直接 `window.setLocation(...)` 一步到位最省事——改 Compose 的
 * `WindowState.position` 要到下一帧才落到窗口上，多屏下能看到窗口先出现在主屏、再跳过来。
 */
internal fun screenCenterLocation(size: DpSize, bounds: Rectangle): IntOffset {
    return clampedLocation(
        x = bounds.x + (bounds.width - size.width.value).toInt() / 2,
        y = bounds.y + (bounds.height - size.height.value).toInt() / 2,
        size = size,
        bounds = bounds,
    )
}

internal fun screenCenterLocation(size: DpSize, screenIndex: Int): IntOffset =
    screenCenterLocation(size, screenBounds(screenIndex))

/**
 * [bounds]（某个窗口当前的屏幕矩形）是否落在 [screenIndex] 那块屏幕的可见区域内。
 *
 * 判定用窗口**中心**，而不是「整块被包含」：用户把窗口摆在屏幕边缘、甚至一半探到外面去都是
 * 正常用法，那种情况不该被判成「跑错屏了」而把它拉回中央。
 *
 * 它只服务一件事——多屏下判断「要不要把这个窗口挪到鼠标那块屏去」（见
 * `ClipperSettingsWindow` / `ClipperDevToolsWindow`）。
 */
internal fun isOnScreen(bounds: Rectangle, screenIndex: Int): Boolean =
    screenBounds(screenIndex).contains(bounds.centerX.toInt(), bounds.centerY.toInt())

/** 把面板保持在请求的屏幕内。 */
internal fun constrained(x: Int, y: Int, size: DpSize, bounds: Rectangle): WindowPosition =
    clampedLocation(x, y, size, bounds).let { WindowPosition.Absolute(it.x.dp, it.y.dp) }

/** 把左上角夹进 [bounds]；窗口比屏幕还大时保左 / 上边，至少不会跑到屏幕外面去。 */
private fun clampedLocation(x: Int, y: Int, size: DpSize, bounds: Rectangle): IntOffset {
    val maxX = (bounds.x + bounds.width - size.width.value).toInt()
    val maxY = (bounds.y + bounds.height - size.height.value).toInt()
    return IntOffset(
        x.coerceIn(bounds.x, maxOf(bounds.x, maxX)),
        y.coerceIn(bounds.y, maxOf(bounds.y, maxY)),
    )
}
