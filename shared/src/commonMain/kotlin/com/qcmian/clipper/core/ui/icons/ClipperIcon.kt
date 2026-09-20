package com.qcmian.clipper.core.ui.icons

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
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
    COPY,
    PAUSE,
    /** `questionmark.app.dashed`，应用没有图标时显示的兜底图标。 */
    APP,
    /** 下拉箭头（实心向下三角形）。 */
    CHEVRON_DOWN,
    /** 升序箭头。 */
    ARROW_UP,
    /** 降序箭头。 */
    ARROW_DOWN,
    /** 对号（勾选标记）。 */
    CHECKMARK,
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

        ClipperIconKind.CHEVRON_DOWN -> {
            drawPath(
                Path().apply {
                    moveTo(s * 0.18f, s * 0.34f)
                    lineTo(s * 0.50f, s * 0.66f)
                    lineTo(s * 0.82f, s * 0.34f)
                    close()
                },
                color,
                style = Fill,
            )
        }

        ClipperIconKind.ARROW_UP -> {
            drawLine(
                color, Offset(s * 0.50f, s * 0.82f), Offset(s * 0.50f, s * 0.22f),
                strokeWidth, StrokeCap.Round,
            )
            drawLine(
                color, Offset(s * 0.30f, s * 0.44f), Offset(s * 0.50f, s * 0.22f),
                strokeWidth, StrokeCap.Round,
            )
            drawLine(
                color, Offset(s * 0.70f, s * 0.44f), Offset(s * 0.50f, s * 0.22f),
                strokeWidth, StrokeCap.Round,
            )
        }

        ClipperIconKind.ARROW_DOWN -> {
            drawLine(
                color, Offset(s * 0.50f, s * 0.18f), Offset(s * 0.50f, s * 0.78f),
                strokeWidth, StrokeCap.Round,
            )
            drawLine(
                color, Offset(s * 0.30f, s * 0.56f), Offset(s * 0.50f, s * 0.78f),
                strokeWidth, StrokeCap.Round,
            )
            drawLine(
                color, Offset(s * 0.70f, s * 0.56f), Offset(s * 0.50f, s * 0.78f),
                strokeWidth, StrokeCap.Round,
            )
        }

        ClipperIconKind.CHECKMARK -> {
            drawLine(
                color, Offset(s * 0.22f, s * 0.52f), Offset(s * 0.42f, s * 0.72f),
                strokeWidth, StrokeCap.Round,
            )
            drawLine(
                color, Offset(s * 0.42f, s * 0.72f), Offset(s * 0.78f, s * 0.28f),
                strokeWidth, StrokeCap.Round,
            )
        }
    }
}

/**
 * 预览工具栏「置顶」按钮的图钉图形。
 *
 * 数据来自设计稿 SVG（`viewBox 0 0 1024 1024`）的 `path`：它是一个自相交的闭合轮廓，填充后
 * 得到的正是设计稿里的线稿图钉（描边由轮廓本身构成）。轮廓本身的线宽很细（缩放后约为图标
 * 边长的 3%），与工具栏里同样尺寸、描边宽度为 10% 的删除图标相比明显偏轻，所以填充之外
 * 再补一圈描边，把线宽提到相近的量级。
 *
 * 颜色不写死在路径里：绘制时用调用方传入的 tint（工具栏传 `colorScheme.onSurface`），
 * 因此深色 / 浅色模式自动跟随主题。
 */
private const val PushpinPathData =
    "M51.195797 1024c-13.021885 " +
    "0-26.01777-5.090955-36.020681-15.093866-18.11384-18.164839-20.262821-46.049592-5.091955-66.337413l213.058113-288.323446L56.158753 " +
    "487.263754c-16.295856-16.296856-19.519827-41.77763-7.853931-61.989451 7.981929-12.611888 " +
    "76.699321-112.821001 229.481968-75.009335 2.532978 0.306997 5.269953 0.536995 8.211927 " +
    "0.792993 6.267944 0.536995 13.277882 1.17699 20.875815 2.404978 32.337714 5.244954 " +
    "89.516207-20.722816 139.147767-63.037441 47.559579-40.497641 78.284307-87.750223 " +
    "78.284307-120.342934 " +
    "0-7.572933-0.178998-15.631862-0.357997-23.536792-1.278989-30.623729-3.222971-77.696312 " +
    "31.979717-112.874 41.649631-41.699631 107.552047-45.051601 153.268642-7.853931a28.467748 " +
    "28.467748 0 0 1 2.583977 2.379979h-0.024999c24.584782 24.047787 276.60555 275.812557 " +
    "279.240526 278.473534 21.694808 21.694808 33.642702 50.526552 33.693702 81.175281 0.025 " +
    "30.674728-11.896895 59.455473-33.539703 81.099281-35.02369 35.04969-82.352271 " +
    "33.053707-113.563994 " +
    "31.722719-7.393935-0.152999-15.477863-0.331997-23.024796-0.331997-30.828727 0-67.6934 " +
    "21.591809-103.715082 60.759462-50.80855 55.259511-82.096273 126.637878-79.410296 " +
    "158.616595 1.12599 10.259909 3.222971 28.371749 3.606968 30.929726 36.891673 " +
    "149.611675-63.113441 217.84207-74.626339 225.108006-20.696817 12.483889-46.356589 " +
    "9.388917-63.011442-7.239936L359.01407 " +
    "790.117072c-10.003911-10.002911-10.003911-26.171768 0-36.17468s26.170768-10.002911 " +
    "36.17468 0l178.39142 178.39142c7.85393-5.089955 80.101291-54.645516 " +
    "51.319545-171.765479-0.510995-2.353979-3.043973-23.561791-4.373961-35.969681-4.297962-51.115547 " +
    "35.585685-136.026795 92.688179-198.117245 32.439713-35.253688 83.273262-77.287315 " +
    "141.347748-77.287316 7.90493 0 16.398855 0.179998 24.661781 0.358997 32.413713 1.354988 " +
    "58.048486 0.971991 75.777329-16.782851 11.972894-11.972894 18.547836-27.885753 " +
    "18.547836-44.847603-0.025-17.012849-6.676941-33.002708-18.700834-45.052601-2.634977-2.634977-271.0036-270.619603-278.90853-278.217536-24.279785-19.724825-60.709462-17.882842-83.785258 " +
    "5.219954-17.908841 17.907841-18.317838 43.490615-17.012849 75.086335 0.203998 8.799922 " +
    "0.383997 17.242847 0.383996 25.147777 0 48.378571-35.995681 107.936044-96.270147 " +
    "159.281589-49.478562 42.135627-122.978911 83.810258-180.490401 " +
    "74.60034-6.292944-1.022991-12.049893-1.509987-17.191848-1.943983-3.325971-0.280998-6.420943-0.562995-9.311918-0.920992-2.455978-0.076999-4.859957-0.536995-7.188936-1.304988-117.758957-29.036743-167.595516 " +
    "43.440615-172.891468 51.806541l182.741381 182.024387c8.953921 8.953921 10.027911 " +
    "23.101795 2.480978 33.309705L51.169797 " +
    "973.114451l238.615886-174.528454c11.434899-8.365926 27.424757-5.806949 35.739684 " +
    "5.60295s5.806949 27.399757-5.602951 35.713684L81.102532 1014.022088C72.149611 " +
    "1020.700029 61.684704 1024 51.195797 1024z"

/** 解析一次即可：路径是常量，绘制时只做一次画布变换。 */
private val PushpinPath: Path by lazy { PathParser().parsePathString(PushpinPathData).toPath() }

/** [PushpinPath] 的包围盒：用来把斜向的图钉等比缩放并居中到图标画布。 */
private val PushpinBounds: Rect by lazy { PushpinPath.getBounds() }

/** 图钉在图标画布中占的比例（设计稿是斜向图形，留一点内边距才与其它图标等重）。 */
private const val PushpinExtentRatio = 0.86f

/**
 * 图钉线稿的补描边宽度（相对图标边长，最终线宽 ≈ 轮廓自带的 3% + 这里的值）。
 *
 * 取值参考工具栏其它图标的描边宽度（[DrawScope.drawClipperIcon] 里是 10%）：补完之后图钉
 * 的线宽与删除图标基本齐平，视觉重量一致，又不会重到把图钉头部的镂空糊掉。
 */
private const val PushpinStrokeRatio = 0.055f

/**
 * 预览工具栏使用的 `pin` / `pin.slash` 图形。
 *
 * [slashed] 为 `true` 时在整枚图钉上叠一条斜线，表示「已钉住，点击取消钉住」。
 */
private fun DrawScope.drawPin(color: Color, s: Float, strokeWidth: Float, slashed: Boolean) {
    val bounds = PushpinBounds
    val extent = maxOf(bounds.width, bounds.height)
    if (extent > 0f) {
        val scale = s * PushpinExtentRatio / extent
        withTransform({
            // 画布变换按书写顺序左乘：先绕原点等比缩放，再平移到画布中心。
            translate(
                left = s / 2f - bounds.center.x * scale,
                top = s / 2f - bounds.center.y * scale,
            )
            scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
        }) {
            drawPath(PushpinPath, color, style = Fill)
            // 补一圈描边把线稿加粗。描边宽度在路径坐标系里指定，会被上面的 `scale` 一起放大，
            // 所以先按画布尺寸算好再换算回去。
            drawPath(
                path = PushpinPath,
                color = color,
                style = Stroke(
                    width = s * PushpinStrokeRatio / scale,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
        }
    }
    if (slashed) {
        drawLine(
            color = color,
            start = Offset(s * 0.12f, s * 0.12f),
            end = Offset(s * 0.88f, s * 0.88f),
            // 比常规描边细一点：图钉本身是线稿，斜线压过重会盖掉图形。
            strokeWidth = strokeWidth * 0.7f,
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
