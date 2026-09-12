package com.qcmian.clipper.core.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 应用使用的图标。全部用 [Canvas] 绘制，因此本项目不依赖任何平台专属的图标构件。
 */
enum class ClipperIconKind {
    SEARCH,
    CLEAR,
    TRASH,
    PIN,
    PIN_SLASH,
    SIDEBAR_LEFT,
    SIDEBAR_RIGHT,
    /** `text.viewfinder`，即工具栏中「复制图片里识别出的文字」的动作。 */
    TEXT_VIEWFINDER,
    SWATCH,
    IMAGE,
    COPY,
    PAUSE,
    /** `questionmark.app.dashed`，应用没有图标时显示的兜底图标。 */
    APP,
}

@Composable
fun ClipperIcon(
    kind: ClipperIconKind,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    tint: Color = Color.Unspecified,
) {
    val color = if (tint == Color.Unspecified) LocalContentColor.current else tint
    Canvas(modifier = modifier.size(size)) {
        drawClipperIcon(kind, color)
    }
}

private fun DrawScope.drawClipperIcon(kind: ClipperIconKind, color: Color) {
    val s = size.minDimension
    val strokeWidth = s * 0.10f
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)

    when (kind) {
        ClipperIconKind.SEARCH -> {
            drawCircle(color, radius = s * 0.30f, center = Offset(s * 0.42f, s * 0.42f), style = stroke)
            drawLine(
                color = color,
                start = Offset(s * 0.64f, s * 0.64f),
                end = Offset(s * 0.88f, s * 0.88f),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round,
            )
        }

        ClipperIconKind.CLEAR -> {
            drawLine(color, Offset(s * 0.26f, s * 0.26f), Offset(s * 0.74f, s * 0.74f), strokeWidth, StrokeCap.Round)
            drawLine(color, Offset(s * 0.74f, s * 0.26f), Offset(s * 0.26f, s * 0.74f), strokeWidth, StrokeCap.Round)
        }

        ClipperIconKind.TRASH -> {
            val lidY = s * 0.27f
            drawLine(color, Offset(s * 0.14f, lidY), Offset(s * 0.86f, lidY), strokeWidth, StrokeCap.Round)
            drawPath(
                path = Path().apply {
                    moveTo(s * 0.37f, lidY)
                    lineTo(s * 0.37f, s * 0.15f)
                    lineTo(s * 0.63f, s * 0.15f)
                    lineTo(s * 0.63f, lidY)
                },
                color = color,
                style = stroke,
            )
            drawPath(
                path = Path().apply {
                    moveTo(s * 0.26f, lidY)
                    lineTo(s * 0.31f, s * 0.88f)
                    lineTo(s * 0.69f, s * 0.88f)
                    lineTo(s * 0.74f, lidY)
                },
                color = color,
                style = stroke,
            )
        }

        ClipperIconKind.PIN -> drawPin(color, s, strokeWidth, slashed = false)
        ClipperIconKind.PIN_SLASH -> drawPin(color, s, strokeWidth, slashed = true)

        ClipperIconKind.SIDEBAR_LEFT -> drawSidebar(color, s, strokeWidth, panelOnLeft = true)
        ClipperIconKind.SIDEBAR_RIGHT -> drawSidebar(color, s, strokeWidth, panelOnLeft = false)

        ClipperIconKind.TEXT_VIEWFINDER -> drawTextViewfinder(color, s, strokeWidth)

        ClipperIconKind.SWATCH -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.16f, s * 0.16f),
                size = Size(s * 0.68f, s * 0.68f),
                cornerRadius = CornerRadius(s * 0.18f),
                style = Fill,
            )
        }

        ClipperIconKind.IMAGE -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.13f, s * 0.19f),
                size = Size(s * 0.74f, s * 0.62f),
                cornerRadius = CornerRadius(s * 0.12f),
                style = stroke,
            )
            drawCircle(color, radius = s * 0.07f, center = Offset(s * 0.34f, s * 0.37f), style = Fill)
            drawPath(
                path = Path().apply {
                    moveTo(s * 0.20f, s * 0.75f)
                    lineTo(s * 0.40f, s * 0.51f)
                    lineTo(s * 0.55f, s * 0.68f)
                    lineTo(s * 0.66f, s * 0.58f)
                    lineTo(s * 0.80f, s * 0.75f)
                },
                color = color,
                style = stroke,
            )
        }

        ClipperIconKind.COPY -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.13f, s * 0.13f),
                size = Size(s * 0.50f, s * 0.50f),
                cornerRadius = CornerRadius(s * 0.12f),
                style = stroke,
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.37f, s * 0.37f),
                size = Size(s * 0.50f, s * 0.50f),
                cornerRadius = CornerRadius(s * 0.12f),
                style = stroke,
            )
        }

        ClipperIconKind.PAUSE -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.28f, s * 0.20f),
                size = Size(s * 0.15f, s * 0.60f),
                cornerRadius = CornerRadius(s * 0.06f),
                style = Fill,
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.57f, s * 0.20f),
                size = Size(s * 0.15f, s * 0.60f),
                cornerRadius = CornerRadius(s * 0.06f),
                style = Fill,
            )
        }

        ClipperIconKind.APP -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.15f, s * 0.15f),
                size = Size(s * 0.70f, s * 0.70f),
                cornerRadius = CornerRadius(s * 0.20f),
                style = stroke,
            )
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.34f, s * 0.34f),
                size = Size(s * 0.32f, s * 0.32f),
                cornerRadius = CornerRadius(s * 0.10f),
                style = Fill,
            )
        }
    }
}

/** 预览工具栏使用的 `pin` / `pin.slash` 图形。 */
private fun DrawScope.drawPin(color: Color, s: Float, strokeWidth: Float, slashed: Boolean) {
    drawRoundRect(
        color = color,
        topLeft = Offset(s * 0.30f, s * 0.14f),
        size = Size(s * 0.40f, s * 0.11f),
        cornerRadius = CornerRadius(s * 0.055f),
        style = Fill,
    )
    drawPath(
        path = Path().apply {
            moveTo(s * 0.36f, s * 0.29f)
            lineTo(s * 0.64f, s * 0.29f)
            lineTo(s * 0.57f, s * 0.60f)
            lineTo(s * 0.43f, s * 0.60f)
            close()
        },
        color = color,
        style = Fill,
    )
    drawLine(
        color = color,
        start = Offset(s * 0.50f, s * 0.60f),
        end = Offset(s * 0.50f, s * 0.88f),
        strokeWidth = strokeWidth * 0.9f,
        cap = StrokeCap.Round,
    )
    if (slashed) {
        drawLine(
            color = color,
            start = Offset(s * 0.14f, s * 0.16f),
            end = Offset(s * 0.86f, s * 0.88f),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round,
        )
    }
}

/** `text.viewfinder` 图形：一个取景框，内部有两行文字。 */
private fun DrawScope.drawTextViewfinder(color: Color, s: Float, strokeWidth: Float) {
    val inset = s * 0.14f
    val arm = s * 0.20f
    val edge = s - inset
    val w = strokeWidth * 0.9f

    listOf(
        Offset(inset, inset) to Offset(inset + arm, inset),
        Offset(inset, inset) to Offset(inset, inset + arm),
        Offset(edge, inset) to Offset(edge - arm, inset),
        Offset(edge, inset) to Offset(edge, inset + arm),
        Offset(inset, edge) to Offset(inset + arm, edge),
        Offset(inset, edge) to Offset(inset, edge - arm),
        Offset(edge, edge) to Offset(edge - arm, edge),
        Offset(edge, edge) to Offset(edge, edge - arm),
    ).forEach { (start, end) ->
        drawLine(color, start, end, w, StrokeCap.Round)
    }

    drawLine(color, Offset(s * 0.34f, s * 0.42f), Offset(s * 0.66f, s * 0.42f), w, StrokeCap.Round)
    drawLine(color, Offset(s * 0.34f, s * 0.58f), Offset(s * 0.57f, s * 0.58f), w, StrokeCap.Round)
}

/** 头部预览开关使用的 `sidebar.left` / `sidebar.right` 图形。 */
private fun DrawScope.drawSidebar(color: Color, s: Float, strokeWidth: Float, panelOnLeft: Boolean) {
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    val panelX = if (panelOnLeft) 0.13f else 0.59f
    val dividerX = if (panelOnLeft) 0.41f else 0.59f

    drawRoundRect(
        color = color,
        topLeft = Offset(s * panelX, s * 0.20f),
        size = Size(s * 0.28f, s * 0.60f),
        cornerRadius = CornerRadius(s * 0.13f),
        style = Fill,
    )
    drawRoundRect(
        color = color,
        topLeft = Offset(s * 0.13f, s * 0.20f),
        size = Size(s * 0.74f, s * 0.60f),
        cornerRadius = CornerRadius(s * 0.13f),
        style = stroke,
    )
    drawLine(
        color = color,
        start = Offset(s * dividerX, s * 0.20f),
        end = Offset(s * dividerX, s * 0.80f),
        strokeWidth = strokeWidth * 0.8f,
        cap = StrokeCap.Round,
    )
}
