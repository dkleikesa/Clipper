package com.qcmian.clipper

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.settings.MenuIcon

/**
 * The four status bar glyphs of Maccy's `MenuIcon` enum, drawn as vectors.
 *
 * macOS template images invert automatically with the menu bar appearance; Compose Desktop
 * does not expose that, so the caller passes the colour matching the current theme.
 */
fun menuIconVector(icon: MenuIcon, color: Color): ImageVector = when (icon) {
    MenuIcon.MACCY -> clipperLogo(color)
    MenuIcon.CLIPBOARD -> clipboardIcon(color)
    MenuIcon.SCISSORS -> scissorsIcon(color)
    MenuIcon.PAPERCLIP -> paperclipIcon(color)
}

private fun builder(name: String) = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
)

private fun clipperLogo(color: Color): ImageVector = builder("ClipperLogo").apply {
    val fill = SolidColor(color)
    path(fill = fill) {
        moveTo(4f, 2f)
        lineTo(14f, 2f)
        lineTo(14f, 4.6f)
        lineTo(6.6f, 4.6f)
        lineTo(6.6f, 16f)
        lineTo(4f, 16f)
        close()
    }
    path(fill = fill) {
        moveTo(9f, 6f)
        lineTo(20f, 6f)
        lineTo(20f, 22f)
        lineTo(9f, 22f)
        close()
    }
}.build()

private fun clipboardIcon(color: Color): ImageVector = builder("Clipboard").apply {
    val stroke = SolidColor(color)
    path(stroke = stroke, strokeLineWidth = 1.9f, strokeLineJoin = StrokeJoin.Round) {
        moveTo(7f, 6.5f)
        lineTo(17f, 6.5f)
        lineTo(17f, 21f)
        lineTo(7f, 21f)
        close()
    }
    path(stroke = stroke, strokeLineWidth = 1.9f, strokeLineJoin = StrokeJoin.Round) {
        moveTo(9.5f, 3f)
        lineTo(14.5f, 3f)
        lineTo(14.5f, 6.5f)
        lineTo(9.5f, 6.5f)
        close()
    }
    path(stroke = stroke, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round) {
        moveTo(10f, 12.5f)
        lineTo(14f, 12.5f)
    }
}.build()

private fun scissorsIcon(color: Color): ImageVector = builder("Scissors").apply {
    val stroke = SolidColor(color)
    path(stroke = stroke, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round) {
        moveTo(7.5f, 3.5f)
        lineTo(16f, 15.5f)
    }
    path(stroke = stroke, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round) {
        moveTo(16.5f, 3.5f)
        lineTo(8f, 15.5f)
    }
    path(stroke = stroke, strokeLineWidth = 1.9f) {
        circle(centerX = 7f, centerY = 18.5f, radius = 2.6f)
    }
    path(stroke = stroke, strokeLineWidth = 1.9f) {
        circle(centerX = 17f, centerY = 18.5f, radius = 2.6f)
    }
}.build()

private fun paperclipIcon(color: Color): ImageVector = builder("Paperclip").apply {
    val stroke = SolidColor(color)
    path(
        stroke = stroke,
        strokeLineWidth = 1.9f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
    ) {
        moveTo(16.5f, 6f)
        lineTo(16.5f, 17f)
        curveTo(16.5f, 20f, 9f, 20f, 9f, 17f)
        lineTo(9f, 5f)
        curveTo(9f, 2.5f, 13.5f, 2.5f, 13.5f, 5f)
        lineTo(13.5f, 15f)
    }
}.build()

/** Draws a circle with two half arcs, which is how `PathBuilder` expresses one. */
private fun PathBuilder.circle(centerX: Float, centerY: Float, radius: Float) {
    moveTo(centerX - radius, centerY)
    arcTo(
        horizontalEllipseRadius = radius,
        verticalEllipseRadius = radius,
        theta = 0f,
        isMoreThanHalf = false,
        isPositiveArc = true,
        x1 = centerX + radius,
        y1 = centerY,
    )
    arcTo(
        horizontalEllipseRadius = radius,
        verticalEllipseRadius = radius,
        theta = 0f,
        isMoreThanHalf = false,
        isPositiveArc = true,
        x1 = centerX - radius,
        y1 = centerY,
    )
    close()
}
