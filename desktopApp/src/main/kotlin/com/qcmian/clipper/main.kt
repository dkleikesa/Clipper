package com.qcmian.clipper

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.ui.window.rememberTrayState

fun main() = application {
    var windowVisible by remember { mutableStateOf(true) }

    val windowState = rememberWindowState(
        width = 420.dp,
        height = 640.dp,
        position = WindowPosition(Alignment.Center),
    )
    val trayIcon = rememberVectorPainter(clipperTrayIcon())

    Window(
        onCloseRequest = { windowVisible = false },
        visible = windowVisible,
        title = "Clipper",
        state = windowState,
    ) {
        // Hiding the window (instead of disposing it) keeps the clipboard listener alive,
        // so a "paste automatically" action reaches the previously focused application.
        App(onRequestHideWindow = { windowVisible = false })
    }

    Tray(
        icon = trayIcon,
        state = rememberTrayState(),
        tooltip = "Clipper — clipboard history",
        onAction = { windowVisible = true },
        menu = {
            Item("Show Clipper", onClick = { windowVisible = true })
            Separator()
            Item("Quit", onClick = ::exitApplication)
        },
    )
}

private fun clipperTrayIcon(): ImageVector = ImageVector.Builder(
    name = "ClipperTray",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    val accent = SolidColor(Color(0xFF0A84FF))
    path(fill = accent) {
        moveTo(4f, 2f)
        lineTo(14f, 2f)
        lineTo(14f, 4.4f)
        lineTo(6.4f, 4.4f)
        lineTo(6.4f, 16f)
        lineTo(4f, 16f)
        close()
    }
    path(fill = accent) {
        moveTo(9f, 6f)
        lineTo(20f, 6f)
        lineTo(20f, 22f)
        lineTo(9f, 22f)
        close()
    }
}.build()
