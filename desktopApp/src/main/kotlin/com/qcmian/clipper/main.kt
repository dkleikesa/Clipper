package com.qcmian.clipper

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import com.qcmian.clipper.data.source.GlobalShortcut
import com.qcmian.clipper.data.source.ScreenRect
import com.qcmian.clipper.data.source.createNativeDataSource
import com.qcmian.clipper.settings.PopupPosition
import com.qcmian.clipper.ui.ClipperController
import kotlinx.coroutines.delay
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener

/** Port of Maccy's `PopupState`: toggle, opening (undecided) or cycle. */
private enum class PopupMode { TOGGLE, OPENING, CYCLE }

/** `NSEvent.ModifierFlags` bits used by the status item click. */
private const val NS_SHIFT_MASK = 1 shl 17
private const val NS_OPTION_MASK = 1 shl 19

/**
 * Shift / control / option / command. `NSEvent.modifierFlags` also carries caps lock and the
 * numeric pad, which must not keep the popup in cycle mode.
 */
private const val NS_MODIFIER_MASK = NS_SHIFT_MASK or (1 shl 18) or NS_OPTION_MASK or (1 shl 20)

fun main() = application {
    var windowVisible by remember { mutableStateOf(true) }
    var previewOpen by remember { mutableStateOf(false) }
    var preferredHeight by remember { mutableStateOf(400.dp) }
    var lastPosition by remember { mutableStateOf<WindowPosition.Absolute?>(null) }
    var popupMode by remember { mutableStateOf(PopupMode.TOGGLE) }
    var previewOnLeft by remember { mutableStateOf(false) }
    var frontmostWindowRect by remember { mutableStateOf<ScreenRect?>(null) }

    val controller = remember { ClipperController() }
    val native = remember { createNativeDataSource() }

    val settings = controller.settings

    val windowState = rememberWindowState(
        width = settings.windowWidth.dp,
        height = preferredHeight,
        position = WindowPosition(Alignment.Center),
    )

    // macOS template images invert automatically; Compose Desktop does not, so the menu bar
    // glyph is tinted for the current theme instead.
    val trayTint = if (isSystemInDarkTheme()) Color.White else Color.Black
    // `AppDelegate.isStatusItemDisabled`: the icon is dimmed while capture is paused.
    val trayColor = if (controller.isStatusItemDisabled) trayTint.copy(alpha = 0.4f) else trayTint
    val trayIcon = rememberVectorPainter(menuIconVector(controller.menuIcon, trayColor))

    fun hidePanel() {
        windowVisible = false
        popupMode = PopupMode.TOGGLE
        // `FloatingPanel.close()` closes the preview together with the popup.
        controller.requestHide()
        controller.clearSearch()
    }

    /**
     * `PopupPosition.window` anchors to the application the user was in before the popup, so
     * the frame has to be captured while that application is still frontmost.
     */
    fun captureFrontmostWindow() {
        frontmostWindowRect = runCatching { native.frontmostWindowRect() }.getOrNull()
    }

    LaunchedEffect(Unit) { captureFrontmostWindow() }

    // Port of `GlobalHotKey` + `Popup.handleKeyDown`: the first press opens the panel and
    // further presses cycle through the history (`PopupState.cycle`). Re-registers whenever
    // the user records a different shortcut.
    DisposableEffect(native, settings.popupShortcut) {
        val handle = GlobalShortcut.fromSpec(settings.popupShortcut)?.let { shortcut ->
            native.registerGlobalHotKey(shortcut) {
                if (!windowVisible) {
                    // `Popup.handleFirstKeyDown`: open and wait to see if the key is held.
                    captureFrontmostWindow()
                    windowVisible = true
                    popupMode = PopupMode.OPENING
                    controller.requestOpen()
                } else {
                    when (popupMode) {
                        // First repeat: switch to cycling and highlight the next item.
                        PopupMode.OPENING -> {
                            popupMode = PopupMode.CYCLE
                            controller.requestCycle()
                        }

                        PopupMode.CYCLE -> controller.requestCycle()
                        PopupMode.TOGGLE -> hidePanel()
                    }
                }
            }
        }
        onDispose { handle?.unregister() }
    }

    // Port of `Popup.handleFlagsChanged`: in cycle mode releasing the modifiers accepts the
    // highlighted item, while in opening mode it simply falls back to toggle mode.
    LaunchedEffect(windowVisible, popupMode, native) {
        if (!windowVisible || popupMode == PopupMode.TOGGLE) return@LaunchedEffect
        while (true) {
            delay(30)
            if (native.currentModifierFlags() and NS_MODIFIER_MASK != 0) continue
            when (popupMode) {
                PopupMode.CYCLE -> {
                    controller.requestAccept()
                    popupMode = PopupMode.TOGGLE
                }

                PopupMode.OPENING -> popupMode = PopupMode.TOGGLE
                PopupMode.TOGGLE -> return@LaunchedEffect
            }
        }
    }

    // The "reset" button next to `PopupPosition.lastPosition`.
    DisposableEffect(controller) {
        controller.resetPositionAction = { lastPosition = null }
        onDispose { controller.resetPositionAction = {} }
    }

    // Maccy's floating panel hugs its content and widens by the slideout width when the
    // preview opens.
    LaunchedEffect(previewOpen, preferredHeight, settings.windowWidth, settings.previewWidth) {
        windowState.size = DpSize(
            width = (settings.windowWidth + if (previewOpen) settings.previewWidth else 0).dp,
            height = preferredHeight,
        )
    }

    // Port of `PopupPosition.origin(size:statusBarButton:)`: the panel is re-positioned every
    // time it is shown, and its position is remembered when it is hidden.
    LaunchedEffect(windowVisible, settings.popupPosition, settings.popupScreen) {
        if (windowVisible) {
            windowState.position = resolvePosition(
                position = settings.popupPosition,
                size = windowState.size,
                lastPosition = lastPosition,
                screenIndex = settings.popupScreen,
                windowRect = frontmostWindowRect,
            )
        } else {
            lastPosition = windowState.position as? WindowPosition.Absolute
        }
    }

    // `SlideoutController.computePlacement`: dock the preview on the left when it would
    // otherwise spill over the right edge of the screen.
    LaunchedEffect(previewOpen, windowVisible, windowState.position, settings.windowWidth, settings.previewWidth) {
        val absolute = windowState.position as? WindowPosition.Absolute
        if (!previewOpen || !windowVisible || absolute == null) {
            previewOnLeft = false
            return@LaunchedEffect
        }
        val bounds = screenBounds(settings.popupScreen)
        val right = absolute.x.value + settings.windowWidth + settings.previewWidth
        previewOnLeft = right > bounds.x + bounds.width
    }

    Window(
        onCloseRequest = { hidePanel() },
        visible = windowVisible,
        title = "Clipper",
        state = windowState,
    ) {
        // Port of `FloatingPanel.resignKey()`: hide the panel when it loses focus, but not
        // while one of its dialogs is up. The timestamp is seeded so the transient focus
        // change during startup never hides the panel.
        var lastFocusGainedAt by remember { mutableStateOf(System.currentTimeMillis()) }
        DisposableEffect(window) {
            val listener = object : WindowFocusListener {
                override fun windowGainedFocus(event: WindowEvent?) {
                    lastFocusGainedAt = System.currentTimeMillis()
                }

                override fun windowLostFocus(event: WindowEvent?) {
                    if (!windowVisible || controller.isModalOpen) return
                    // Ignore the transient focus loss right after the panel is shown.
                    if (System.currentTimeMillis() - lastFocusGainedAt < 250) return
                    hidePanel()
                }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }

        // Hiding the window (instead of disposing it) keeps the clipboard listener alive,
        // so a "paste automatically" action reaches the previously focused application.
        App(
            onRequestHideWindow = { hidePanel() },
            onQuit = ::exitApplication,
            onPreviewOpenChange = { previewOpen = it },
            autoPreview = true,
            previewOnLeft = previewOnLeft,
            controller = controller,
            onPreferredHeightChange = { preferredHeight = it },
        )
    }

    if (settings.showInStatusBar) {
        val recentCopy = controller.recentCopyText
        Tray(
            icon = trayIcon,
            state = rememberTrayState(),
            tooltip = if (recentCopy.isNotEmpty()) "Clipper — $recentCopy" else "Clipper — 剪贴板历史记录",
            onAction = {
                // `AppDelegate.performStatusItemClick`: ⌥ toggles capture, ⇧⌥ pauses capture
                // for a single copy, a plain click toggles the panel.
                val modifiers = native.currentModifierFlags()
                if (modifiers and NS_OPTION_MASK != 0) {
                    controller.togglePause(onlyNext = modifiers and NS_SHIFT_MASK != 0)
                } else {
                    captureFrontmostWindow()
                    windowVisible = true
                    popupMode = PopupMode.TOGGLE
                }
            },
            menu = {
                Item(
                    text = "显示 Clipper",
                    onClick = {
                        captureFrontmostWindow()
                        windowVisible = true
                        popupMode = PopupMode.TOGGLE
                    },
                )
                Item(
                    text = if (controller.isPaused) "恢复记录" else "暂停记录",
                    onClick = { controller.togglePause() },
                )
                Separator()
                Item(text = "退出", onClick = ::exitApplication)
            },
        )
    }
}

private fun resolvePosition(
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
 * Port of `PopupPosition.statusItem`: just below the menu bar. The status item's own position
 * is not exposed by AWT, so the popup is aligned to the right edge of the screen instead.
 */
private fun menuBarPosition(size: DpSize, screenIndex: Int): WindowPosition {
    val bounds = screenBounds(screenIndex)
    return constrained(
        x = bounds.x + bounds.width - size.width.value.toInt() - 8,
        y = bounds.y + 25,
        size = size,
        bounds = bounds,
    )
}

/** Port of `PopupPosition.window`: the centre of the frontmost application's window. */
private fun windowCenterPosition(
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
 * Port of `PopupPosition.origin`: index 0 is the screen the mouse is on (Maccy's "active
 * screen"), every other index points at `NSScreen.screens[index - 1]`.
 */
private fun screenBounds(index: Int): Rectangle {
    val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
    val devices = environment.screenDevices
    if (index in 1..devices.size) {
        return devices[index - 1].defaultConfiguration.bounds
    }

    val mouse = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
    if (mouse != null) {
        devices.firstOrNull { it.defaultConfiguration.bounds.contains(mouse) }
            ?.let { return it.defaultConfiguration.bounds }
    }
    return environment.defaultScreenDevice.defaultConfiguration.bounds
}

private fun cursorPosition(size: DpSize, screenIndex: Int): WindowPosition {
    val mouse = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
        ?: return screenCenterPosition(size, screenIndex)
    // AWT reports logical points on macOS, which map one-to-one onto Compose dp.
    return constrained(mouse.x, mouse.y, size, screenBounds(screenIndex))
}

private fun screenCenterPosition(size: DpSize, screenIndex: Int): WindowPosition {
    val bounds = screenBounds(screenIndex)
    return constrained(
        x = bounds.x + (bounds.width - size.width.value).toInt() / 2,
        y = bounds.y + (bounds.height - size.height.value).toInt() / 2,
        size = size,
        bounds = bounds,
    )
}

/** Keeps the panel on the requested screen, like `PopupPosition.constrained`. */
private fun constrained(x: Int, y: Int, size: DpSize, bounds: Rectangle): WindowPosition {
    val maxX = (bounds.x + bounds.width - size.width.value).toInt()
    val maxY = (bounds.y + bounds.height - size.height.value).toInt()
    return WindowPosition.Absolute(
        x.coerceIn(bounds.x, maxOf(bounds.x, maxX)).dp,
        y.coerceIn(bounds.y, maxOf(bounds.y, maxY)).dp,
    )
}
