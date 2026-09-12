package com.qcmian.clipper.desktop.domain

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import com.qcmian.clipper.core.data.source.ScreenRect
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
    lastPosition: WindowPosition.Absolute?,
    screenIndex: Int,
    windowRect: ScreenRect?,
): WindowPosition = when (position) {
    PopupPosition.LAST_POSITION -> lastPosition ?: cursorPosition(size, screenIndex)
    PopupPosition.SCREEN_CENTER -> screenCenterPosition(size, screenIndex)
    PopupPosition.MENU_BAR -> menuBarPosition(size, screenIndex)
    PopupPosition.WINDOW_CENTER -> windowCenterPosition(size, windowRect, screenIndex)
    PopupPosition.CURSOR -> cursorPosition(size, screenIndex)
}

/**
 * 对应 `PopupPosition.statusItem`：正好在菜单栏下方。AWT 不暴露状态项自身的位置，
 * 因此改为把弹窗对齐到屏幕右边缘。
 */
internal fun menuBarPosition(size: DpSize, screenIndex: Int): WindowPosition {
    val bounds = screenBounds(screenIndex)
    return constrained(
        x = bounds.x + bounds.width - size.width.value.toInt() - 8,
        // `bounds` 已排除菜单栏，这里只留一点间距即可。
        y = bounds.y + 8,
        size = size,
        bounds = bounds,
    )
}

/** 对应 `PopupPosition.window`：最前应用窗口的中心。 */
internal fun windowCenterPosition(
    size: DpSize,
    windowRect: ScreenRect?,
    screenIndex: Int,
): WindowPosition {
    if (windowRect == null) return screenCenterPosition(size, screenIndex)

    return constrained(
        x = windowRect.x + (windowRect.width - size.width.value).toInt() / 2,
        y = windowRect.y + (windowRect.height - size.height.value).toInt() / 2,
        size = size,
        bounds = screenBounds(screenIndex),
    )
}

/**
 * 对应 `PopupPosition.origin`：索引 0 是鼠标所在的屏幕（活动屏幕），
 * 其它索引指向 `NSScreen.screens[index - 1]`。
 *
 * 返回的是**可用区域**（对应 `NSScreen.visibleFrame`），已去掉菜单栏与 Dock，
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

internal fun cursorPosition(size: DpSize, screenIndex: Int): WindowPosition {
    val mouse = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
        ?: return screenCenterPosition(size, screenIndex)
    // AWT 在 macOS 上报告的是逻辑点，与 Compose 的 dp 一一对应。
    return constrained(mouse.x, mouse.y, size, screenBounds(screenIndex))
}

internal fun screenCenterPosition(size: DpSize, screenIndex: Int): WindowPosition {
    val bounds = screenBounds(screenIndex)
    return constrained(
        x = bounds.x + (bounds.width - size.width.value).toInt() / 2,
        y = bounds.y + (bounds.height - size.height.value).toInt() / 2,
        size = size,
        bounds = bounds,
    )
}

/** 把面板保持在请求的屏幕内，对应 `PopupPosition.constrained`。 */
internal fun constrained(x: Int, y: Int, size: DpSize, bounds: Rectangle): WindowPosition {
    val maxX = (bounds.x + bounds.width - size.width.value).toInt()
    val maxY = (bounds.y + bounds.height - size.height.value).toInt()
    return WindowPosition.Absolute(
        x.coerceIn(bounds.x, maxOf(bounds.x, maxX)).dp,
        y.coerceIn(bounds.y, maxOf(bounds.y, maxY)).dp,
    )
}
