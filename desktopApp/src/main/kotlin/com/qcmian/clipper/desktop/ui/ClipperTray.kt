package com.qcmian.clipper.desktop.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.window.ApplicationScope
import com.qcmian.clipper.core.platform.macos.MacStatusItem
import com.qcmian.clipper.host.WindowController
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent

/** 原生菜单栏图标在菜单栏里的边长（pt）。 */
private const val TRAY_ICON_POINT_SIZE = 18.0

/**
 * 菜单栏 / 托盘图标（View 层）：从 [WindowController] 读取要显示的投影状态。
 *
 * macOS 上用 AppKit 的 `NSStatusItem`（原生高亮 / 原生内边距 / 真正的左右键），
 * 其它平台退回 AWT 托盘。图标是 [clipperTrayIcon] 单色线稿，颜色跟随菜单栏深浅色，
 * 与窗口图标 [com.qcmian.clipper.core.ui.icons.ClipperAppIcon] 有意分开。
 */
@Composable
fun ApplicationScope.ClipperTray(
    windowController: WindowController,
) {
    val host by windowController.hostUiState.collectAsState()
    if (!host.settings.showInStatusBar) return

    val tooltip = if (host.recentCopyText.isNotEmpty()) {
        "Clipper — ${host.recentCopyText}"
    } else {
        "Clipper — 剪贴板历史记录"
    }

    if (MacStatusItem.isSupported) {
        NativeTray(
            active = host.isStatusItemActive,
            disabled = host.isStatusItemDisabled,
            tooltip = tooltip,
            onPrimaryClick = { windowController.requestToggle() },
            windowVisible = host.isWindowVisible,
        )
    } else {
        FallbackTray(
            active = host.isStatusItemActive,
            disabled = host.isStatusItemDisabled,
            tooltip = tooltip,
            onPrimaryClick = { windowController.requestToggle() },
        )
    }
}

/**
 * 原生菜单栏项。
 *
 * 状态（按下高亮 / 置灰 / 提示文字）由 `SideEffect` 推给 [MacStatusItem]；那边内部会比对
 * 上次值，重复推同一个值不会真的落到 AppKit。点击瞬间翻转后的按下态在 install 的回调里
 * 同步预推（见 [MacStatusItem.setHighlighted] 的 force 参数），不经过这条重组链路。
 */
@Composable
private fun NativeTray(
    active: Boolean,
    disabled: Boolean,
    tooltip: String?,
    onPrimaryClick: () -> Unit,
    windowVisible: Boolean,
) {
    val currentOnPrimaryClick by rememberUpdatedState(onPrimaryClick)
    val currentWindowVisible by rememberUpdatedState(windowVisible)
    // 菜单栏外观跟随系统深浅色，`isSystemInDarkTheme` 与它同源。这条 JNA 链路上没有
    // 模板图（NSImage.isTemplate）能力，反色靠「换图」：外观变化时用对应颜色的位图重装。
    val tint = if (isSystemInDarkTheme()) Color.White else Color.Black
    val glyph = rememberVectorPainter(clipperTrayIcon(tint))

    DisposableEffect(tint) {
        MacStatusItem.install(glyph, TRAY_ICON_POINT_SIZE) {
            // 点击后面板可见性必然翻转，翻转后的按下态 = 「不可见」。在回调里同步预推，
            // 消除「松手清高亮 → 投影落地」之间的空档；投影随后给出的值一致，不会抖动。
            MacStatusItem.setHighlighted(!currentWindowVisible, force = true)
            currentOnPrimaryClick()
        }
        onDispose { MacStatusItem.remove() }
    }

    SideEffect {
        MacStatusItem.setHighlighted(active)
        MacStatusItem.setDisabled(disabled)
        MacStatusItem.setTooltip(tooltip)
    }
}

/** 非 macOS 平台的兜底：AWT 托盘 + 自绘的按下态胶囊。 */
@Composable
private fun FallbackTray(
    active: Boolean,
    disabled: Boolean,
    tooltip: String?,
    onPrimaryClick: () -> Unit,
) {
    val currentOnPrimaryClick by rememberUpdatedState(onPrimaryClick)
    val tint = if (isSystemInDarkTheme()) Color.White else Color.Black
    val glyph = rememberVectorPainter(clipperTrayIcon(tint))

    val highlight = if (active) Color.White.copy(alpha = 0.45f) else null
    val icon = remember(glyph, highlight, disabled) {
        TrayIconPainter(glyph, highlight, glyphAlpha = if (disabled) 0.4f else 1f)
    }

    AwtTray(
        icon = icon,
        tooltip = tooltip,
        onPrimaryClick = { currentOnPrimaryClick() },
    )
}

/** 同一次点击可能同时到达 `MouseListener` 与 `ActionListener`，这个间隔内的重复都忽略。 */
private const val CLICK_DEDUPE_MILLIS = 250L

/**
 * 托盘图标的渲染尺寸，也就是按下态高亮胶囊的大小：高度沿用 Compose 自带 `Tray` 的选择
 * （Windows 16pt），宽度放大让胶囊比图标本体宽。
 */
private val TRAY_ICON_SIZE: Size =
    if (System.getProperty("os.name").orEmpty().startsWith("Windows")) Size(24f, 16f) else Size(33f, 22f)

/** 图标边长与画布高度的比例；只跟高度挂钩，托盘无论给什么宽高比都不会把图标拉变形。 */
private const val GLYPH_SIZE_RATIO = 0.85f

/**
 * 托盘图标：按下态胶囊铺满整块画布，图标本体按自身比例居中。
 *
 * 不能让矢量直接填满画布——`Painter.toAwtImage` 会按 AWT 请求的尺寸重新栅格化，而
 * `VectorPainter` 用的是 `scaleX = size.width / viewportWidth`、`scaleY = size.height / viewportHeight`
 * 两个**独立**比例，`isImageAutoSize = true` 时托盘请求的宽高比与画布不一致，图标就被拉变形。
 * 这里图标本体的边长只由画布高度决定，因此托盘怎么缩放都不会走形。
 */
private class TrayIconPainter(
    private val glyph: Painter,
    /** `null` 表示未按下，不画背景。 */
    private val highlight: Color?,
    /** 暂停记录时压暗图标。 */
    private val glyphAlpha: Float,
) : Painter() {
    override val intrinsicSize: Size = TRAY_ICON_SIZE

    override fun DrawScope.onDraw() {
        highlight?.let { color ->
            drawRoundRect(color = color, cornerRadius = CornerRadius(size.height / 2f))
        }

        val edge = size.height * GLYPH_SIZE_RATIO
        translate(left = (size.width - edge) / 2f, top = (size.height - edge) / 2f) {
            // `draw` 是 `Painter` 上以 `DrawScope` 为扩展接收者的成员函数，只能在
            // 「调度接收者 + 扩展接收者」都就位时调用，因此用 `with` 而不是 `glyph.draw(...)`。
            with(glyph) {
                draw(Size(edge, edge), alpha = glyphAlpha)
            }
        }
    }
}

/**
 * 直接包装 AWT 的 [TrayIcon]，而不是用 Compose 的 `Tray`。
 *
 * Compose 的 `Tray` 在 macOS 上把左键锁给弹出菜单、只把右键交给 `onAction`
 * （见其源码注释 "double click on Windows, right click on macOs"）。挂在 [TrayIcon]
 * 自己的 `MouseListener` 上才拿得到左键。
 */
@Composable
private fun AwtTray(
    icon: Painter,
    tooltip: String?,
    onPrimaryClick: () -> Unit,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val currentTooltip by rememberUpdatedState(tooltip)
    val currentOnPrimaryClick by rememberUpdatedState(onPrimaryClick)

    val awtIcon = remember(icon, density, layoutDirection) {
        icon.toAwtImage(density, layoutDirection, TRAY_ICON_SIZE)
    }

    val tray = remember {
        TrayIcon(awtIcon).apply {
            isImageAutoSize = true

            // 左右键做同一件事：切换面板。
            //
            // AWT 在 macOS 上会把点击同时派发给 `MouseListener`，又会为右键触发
            // `ActionListener`，两个入口都接上再按时间戳去重，保证一次点击只触发一次。
            // 两个回调都在 EDT 上，不需要额外同步。
            var lastHandledAt = 0L
            fun handleClick() {
                val now = System.currentTimeMillis()
                if (now - lastHandledAt < CLICK_DEDUPE_MILLIS) return
                lastHandledAt = now
                currentOnPrimaryClick()
            }

            addMouseListener(object : MouseAdapter() {
                override fun mousePressed(event: MouseEvent) {
                    if (event.button == MouseEvent.BUTTON1 || event.button == MouseEvent.BUTTON3) {
                        handleClick()
                    }
                }
            })
            addActionListener { handleClick() }
        }
    }

    SideEffect {
        if (tray.image != awtIcon) tray.image = awtIcon
        if (tray.toolTip != currentTooltip) tray.toolTip = currentTooltip
    }

    DisposableEffect(Unit) {
        // 系统不支持托盘时静默降级，不要让整个应用起不来。
        val systemTray = runCatching { SystemTray.getSystemTray() }.getOrNull()
        if (systemTray != null) runCatching { systemTray.add(tray) }
        onDispose { systemTray?.remove(tray) }
    }
}
