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
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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
    ARROW_UP,
    ARROW_DOWN,
    CHECKMARK,

    // ---------------------------------------------------------------- 设置页侧边栏
    /** 圆柱形数据库，设置页「存储与数据」用。 */
    DATABASE,
    /** 两条带滑块的轨道，设置页「行为」用。 */
    SLIDERS,
    /** 键盘，设置页「快捷键」用。 */
    KEYBOARD,
    /** 明暗对半的圆，设置页「外观」用。 */
    APPEARANCE,
    /** 逆时针回旋箭头，设置页「重置」用。 */
    RESET,

    // ---------------------------------------------------------------- 开发者工具
    /** 一对花括号，JSON 工具用。 */
    BRACES,
    /** 一对尖括号，XML 工具用。 */
    TAG,
    /** 文件夹，编辑区「打开文件」用。 */
    FOLDER,
    /** 向下落进托盘的箭头，编辑区「保存文件」用（与系统 `square.and.arrow.down` 同义）。 */
    SAVE,

    // ------------------------------------------- 开发者工具 · 工具图标（按 ROADMAP 的规划备好）
    //
    // 这一组是为**还没做出来的工具**预留的图形词汇：它们现在没有引用者，因此不会出现在任何界面
    // 上——侧边栏画的是「工具自己声明的图标」，工具没做出来，图标就无从显示。
    //
    // 提前备好的理由：这一套的形状与线宽是**一起**定的（同一个 24 网格、同一条 1.9 的线宽），
    // 先落地再逐个补，比将来零散地加更容易保持齐整；新工具接上它只需在 `DevToolMetadata` 里
    // 写一个名字。
    /** 一摞圆盘，SQL 格式化用。与 [DATABASE] 形状相近但**不是同一个**：那个表「存储」，这个表
     * 「SQL 语句」，各自跟着自己的设计稿走，合并了将来任一边调形状都会牵连另一边。 */
    SQL,
    /** 相框里一座山与一轮太阳，Base64 图片编解码用。与 [TYPE_IMAGE] 的关系同 [SQL] 与 [DATABASE]。 */
    PICTURE,
    /** 文稿右侧带一个向外的箭头，Base64 文本编解码用。 */
    DOC_ARROW,
    /** 地球：一条经线加一条赤道，URL 编解码用。 */
    GLOBE,
    /** 三个定位角加几个点，二维码用。 */
    QR_CODE,
    /** 盾牌加一个勾，证书解析用。 */
    SHIELD,
    /** 一个实心点加一个星号，即 `.*`，正则表达式用。 */
    REGEX,
    /** 三条横线，文本处理用。 */
    TEXT_LINES,
    /** 时钟，时间戳转换用。 */
    CLOCK,
    /** 一上一下两个箭头，格式转换用。 */
    SWAP,
    /** 钥匙，随机密码生成用。 */
    KEY,
    /** 井号，Hash 生成与校验用。 */
    HASH,
    /** 证件卡片，UUID 生成用。 */
    ID_CARD,

    // ---------------------------------------------------------------- 条目类型
    /** 纯文本：一个带衬线的 "T"。 */
    TYPE_TEXT,
    /** 图片：相框里一座山与一轮太阳。 */
    TYPE_IMAGE,
    /** 文件：带折角的空白文稿。 */
    TYPE_FILE,
    /** 富文本："T" 右侧跟着三行短横线，下面两行通栏长线。 */
    TYPE_RICH_TEXT,
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

    // 开发者工具那一组**按设计稿的 24 网格**画：坐标直接写 0..24 上的数，由 `p()` 换算成像素，
    // 因此与设计稿逐点一致，而不是「照着样子重写一遍」——重写必然会在某几个坐标上偏一点。
    // 线宽取设计稿的 1.9（缩放后约为边长的 7.9%，这里是 10%）：这一组自成一个家族，只出现在
    // 侧边栏、图标栏与标题栏的工具名旁，细一档不会与其余图标显得凌乱。
    val g = size.minDimension / 24f
    fun p(x: Float, y: Float) = Offset(x * g, y * g)
    val toolStrokeWidth = 1.9f * g
    val st = Stroke(width = toolStrokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float = toolStrokeWidth) =
        drawLine(color, p(x1, y1), p(x2, y2), width, StrokeCap.Round)

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
            // 后面那张纸只画**露在前面那张之外**的部分，否则它的描边会横穿前面那张纸，在
            // 14dp 下糊成一团。露出来的是一个「L」：上半条 + 左半条。
            //
            // 分两次裁剪、把整张后纸各画一遍，而不是手算圆角处的切线：两块的并集正好是那个 L，
            // 重叠区画两遍对不透明色没有影响。手算的话，两个内圆角的起止点都得跟着描边宽度走，
            // 改一次线宽就要重算一次。
            val backSheet = {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * 0.13f, s * 0.13f),
                    size = Size(s * 0.50f, s * 0.50f),
                    cornerRadius = CornerRadius(s * 0.12f),
                    style = stroke,
                )
            }
            clipRect(0f, 0f, s * 0.37f, s) { backSheet() }
            clipRect(0f, 0f, s, s * 0.37f) { backSheet() }
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

        ClipperIconKind.DATABASE -> {
            val cx = s * 0.5f
            val rx = s * 0.30f
            val ry = s * 0.12f
            val topY = s * 0.26f
            val bottomY = s * 0.74f
            // 顶面是完整的椭圆，中间与底面只画下半弧，看上去才是一摞圆盘。
            drawOval(
                color = color,
                topLeft = Offset(cx - rx, topY - ry),
                size = Size(rx * 2f, ry * 2f),
                style = stroke,
            )
            drawLine(color, Offset(cx - rx, topY), Offset(cx - rx, bottomY), strokeWidth, StrokeCap.Round)
            drawLine(color, Offset(cx + rx, topY), Offset(cx + rx, bottomY), strokeWidth, StrokeCap.Round)
            listOf(topY + (bottomY - topY) / 2f, bottomY).forEach { y ->
                drawArc(
                    color = color,
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(cx - rx, y - ry),
                    size = Size(rx * 2f, ry * 2f),
                    style = stroke,
                )
            }
        }

        ClipperIconKind.SLIDERS -> {
            // 两条轨道各带一个实心滑块：与设置页里的滑杆控件是同一套语义。
            drawLine(color, Offset(s * 0.16f, s * 0.34f), Offset(s * 0.84f, s * 0.34f), strokeWidth, StrokeCap.Round)
            drawCircle(color, radius = s * 0.10f, center = Offset(s * 0.66f, s * 0.34f), style = Fill)
            drawLine(color, Offset(s * 0.16f, s * 0.66f), Offset(s * 0.84f, s * 0.66f), strokeWidth, StrokeCap.Round)
            drawCircle(color, radius = s * 0.10f, center = Offset(s * 0.34f, s * 0.66f), style = Fill)
        }

        ClipperIconKind.KEYBOARD -> {
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.08f, s * 0.26f),
                size = Size(s * 0.84f, s * 0.48f),
                cornerRadius = CornerRadius(s * 0.10f),
                style = stroke,
            )
            // 一排小键 + 一条空格键。
            listOf(0.20f, 0.42f, 0.64f).forEach { x ->
                drawRoundRect(
                    color = color,
                    topLeft = Offset(s * x, s * 0.37f),
                    size = Size(s * 0.10f, s * 0.09f),
                    cornerRadius = CornerRadius(s * 0.03f),
                    style = Fill,
                )
            }
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.30f, s * 0.55f),
                size = Size(s * 0.40f, s * 0.09f),
                cornerRadius = CornerRadius(s * 0.03f),
                style = Fill,
            )
        }

        ClipperIconKind.APPEARANCE -> {
            // 右半边填充、外圈描边：明暗各一半，与「主题」最贴。
            val radius = s * 0.32f
            val c = Offset(s * 0.5f, s * 0.5f)
            drawArc(
                color = color,
                startAngle = -90f,
                sweepAngle = 180f,
                useCenter = true,
                topLeft = Offset(c.x - radius, c.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Fill,
            )
            drawCircle(color = color, radius = radius, center = c, style = stroke)
        }

        ClipperIconKind.RESET -> drawResetArrow(color, s, stroke)

        // ---------------------------------------------------------------- 开发者工具
        //
        // 这一组按同一个 0..1 网格画，线宽也统一取 `strokeWidth`（边长的 10%）：工具图标会并排
        // 出现在侧边栏与图标栏里，线重差一点就会显得有的重、有的轻。
        ClipperIconKind.BRACES -> {
            val l = Path().apply {
            moveTo(10.6f * g, 4f * g)
            cubicTo(8.7f * g, 4f * g, 8.9f * g, 6.3f * g, 8.9f * g, 8.4f * g)
            cubicTo(8.9f * g, 10.5f * g, 7.8f * g, 11.3f * g, 6.3f * g, 12f * g)
            cubicTo(7.8f * g, 12.7f * g, 8.9f * g, 13.5f * g, 8.9f * g, 15.6f * g)
            cubicTo(8.9f * g, 17.7f * g, 8.7f * g, 20f * g, 10.6f * g, 20f * g)
            }
            val r = Path().apply {
            moveTo(13.4f * g, 4f * g)
            cubicTo(15.3f * g, 4f * g, 15.1f * g, 6.3f * g, 15.1f * g, 8.4f * g)
            cubicTo(15.1f * g, 10.5f * g, 16.2f * g, 11.3f * g, 17.7f * g, 12f * g)
            cubicTo(16.2f * g, 12.7f * g, 15.1f * g, 13.5f * g, 15.1f * g, 15.6f * g)
            cubicTo(15.1f * g, 17.7f * g, 15.3f * g, 20f * g, 13.4f * g, 20f * g)
            }
            drawPath(l, color, style = st)
            drawPath(r, color, style = st)
            
        }
        ClipperIconKind.TAG -> {
            line(9f, 7f, 4.5f, 12f); line(4.5f, 12f, 9f, 17f)
            line(15f, 7f, 19.5f, 12f); line(19.5f, 12f, 15f, 17f)
            line(13.5f, 6f, 10.5f, 18f)
            
        }
        ClipperIconKind.SQL -> {
            val rx = 7.5f * g; val ry = 3f * g
            drawOval(color, p(4.5f, 6f - 3f), Size(rx * 2, ry * 2), style = st)
            line(4.5f, 6f, 4.5f, 18f); line(19.5f, 6f, 19.5f, 18f)
            listOf(12f, 18f).forEach { y ->
            drawArc(
            color, 0f, 180f, false,
            topLeft = p(4.5f, y - 3f), size = Size(rx * 2, ry * 2), style = st,
            )
            }
            
        }
        ClipperIconKind.PICTURE -> {
            drawRoundRect(
            color, p(3f, 5f), Size(18f * g, 14f * g),
            CornerRadius(2.5f * g), style = st,
            )
            val m = Path().apply {
            moveTo(6f * g, 16f * g); lineTo(10f * g, 11f * g)
            lineTo(13f * g, 14f * g); lineTo(15.5f * g, 11.5f * g); lineTo(18f * g, 16f * g)
            }
            drawPath(m, color, style = Stroke(toolStrokeWidth * 0.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawCircle(color, 1.3f * g, p(8f, 9f))
            
        }
        ClipperIconKind.DOC_ARROW -> {
            drawRoundRect(color, p(3.5f, 4f), Size(11f * g, 16f * g), CornerRadius(2f * g), style = st)
            line(6.5f, 8f, 11.5f, 8f); line(6.5f, 12f, 11.5f, 12f); line(6.5f, 16f, 9.5f, 16f)
            line(15f, 12f, 20.5f, 12f)
            drawPath(
            Path().apply {
            moveTo(18.3f * g, 9.6f * g); lineTo(20.8f * g, 12f * g); lineTo(18.3f * g, 14.4f * g)
            },
            color, style = st,
            )
            
        }
        ClipperIconKind.GLOBE -> {
            drawCircle(color, 8.5f * g, p(12f, 12f), style = st)
            drawOval(color, p(8.5f, 3.5f), Size(7f * g, 17f * g), style = st)
            line(3.5f, 12f, 20.5f, 12f)
            
        }
        ClipperIconKind.QR_CODE -> {
            listOf(p(3.5f, 3.5f), p(15f, 3.5f), p(3.5f, 15f)).forEach { o ->
            drawRoundRect(color, o, Size(5.5f * g, 5.5f * g), CornerRadius(1f * g), style = st)
            }
            drawCircle(color, 1.2f * g, p(17.8f, 17.8f))
            drawCircle(color, 1.2f * g, p(13.5f, 20.5f))
            drawCircle(color, 1.2f * g, p(20.5f, 13.5f))
            
        }
        ClipperIconKind.SHIELD -> {
            val sh = Path().apply {
            moveTo(12f * g, 3.5f * g); lineTo(20f * g, 6.5f * g); lineTo(20f * g, 12f * g)
            cubicTo(20f * g, 17.5f * g, 16.5f * g, 20f * g, 12f * g, 21f * g)
            cubicTo(7.5f * g, 20f * g, 4f * g, 17.5f * g, 4f * g, 12f * g)
            lineTo(4f * g, 6.5f * g); close()
            }
            drawPath(sh, color, style = st)
            drawPath(
            Path().apply {
            moveTo(9f * g, 12f * g); lineTo(11.2f * g, 14.3f * g); lineTo(15.2f * g, 9.8f * g)
            },
            color, style = st,
            )
            
        }
        ClipperIconKind.REGEX -> {
            drawCircle(color, 1.6f * g, p(6.5f, 18f))
            line(15f, 5.5f, 15f, 15.5f)
            line(10.7f, 8f, 19.3f, 13f)
            line(19.3f, 8f, 10.7f, 13f)
            
        }
        ClipperIconKind.TEXT_LINES -> {
            line(4f, 6.5f, 20f, 6.5f); line(4f, 12f, 20f, 12f); line(4f, 17.5f, 13f, 17.5f)
            
        }
        ClipperIconKind.CLOCK -> {
            drawCircle(color, 8.5f * g, p(12f, 12f), style = st)
            line(12f, 7f, 12f, 12.3f); line(12f, 12.3f, 16f, 14.3f)
            
        }
        ClipperIconKind.SWAP -> {
            line(4f, 9f, 19f, 9f); line(4f, 15f, 19f, 15f)
            drawPath(Path().apply { moveTo(16f * g, 5.8f * g); lineTo(19.5f * g, 9f * g); lineTo(16f * g, 12.2f * g) }, color, style = st)
            drawPath(Path().apply { moveTo(8f * g, 11.8f * g); lineTo(4.5f * g, 15f * g); lineTo(8f * g, 18.2f * g) }, color, style = st)
            
        }
        ClipperIconKind.KEY -> {
            drawCircle(color, 4f * g, p(8f, 8f), style = st)
            line(10.8f, 10.8f, 19f, 19f)
            line(16f, 16f, 18.3f, 13.7f)
            line(18.6f, 18.6f, 20.9f, 16.3f)
            
        }
        ClipperIconKind.HASH -> {
            line(9.5f, 4f, 7.5f, 20f); line(16.5f, 4f, 14.5f, 20f)
            line(4.5f, 9f, 20f, 9f); line(4f, 15f, 19.5f, 15f)
            
        }
        ClipperIconKind.ID_CARD -> {
            drawRoundRect(color, p(3f, 5.5f), Size(18f * g, 13f * g), CornerRadius(2.2f * g), style = st)
            drawCircle(color, 2.1f * g, p(8.5f, 11f), style = st)
            line(13f, 9.5f, 18f, 9.5f); line(13f, 13f, 18f, 13f)
            drawArc(color, 200f, 140f, false, topLeft = p(5.6f, 11.5f), size = Size(5.8f * g, 5.8f * g), style = Stroke(toolStrokeWidth * 0.85f, cap = StrokeCap.Round))
            
        }
        ClipperIconKind.FOLDER -> {
            drawPath(
            Path().apply {
            moveTo(3.5f * g, 19f * g)
            lineTo(3.5f * g, 6.5f * g)
            lineTo(9.3f * g, 6.5f * g)
            lineTo(11.3f * g, 9.2f * g)
            lineTo(20.5f * g, 9.2f * g)
            lineTo(20.5f * g, 19f * g)
            close()
            },
            color, style = st,
            )
            
        }
        ClipperIconKind.SAVE -> {
            line(12f, 4f, 12f, 14.5f)
            drawPath(
            Path().apply {
            moveTo(7.8f * g, 10.3f * g); lineTo(12f * g, 14.5f * g); lineTo(16.2f * g, 10.3f * g)
            },
            color, style = st,
            )
            drawPath(
            Path().apply {
            moveTo(4.5f * g, 15.5f * g); lineTo(4.5f * g, 19.5f * g); lineTo(19.5f * g, 19.5f * g); lineTo(19.5f * g, 15.5f * g)
            },
            color, style = st,
            )
            
        }

        // ---------------------------------------------------------------- 条目类型
        ClipperIconKind.TYPE_TEXT -> {
            // 一个**带衬线**的 "T"。衬线只保留三处：顶横杠两端向下的小竖、竖笔底部的短横——
            // 更细的收笔在 13dp 下根本看不见，反而糊成一团。
            val topY = s * 0.24f
            drawLine(color, Offset(s * 0.18f, topY), Offset(s * 0.82f, topY), strokeWidth * 1.15f, StrokeCap.Round)
            drawLine(color, Offset(s * 0.18f, topY), Offset(s * 0.18f, topY + s * 0.10f), strokeWidth * 0.8f, StrokeCap.Round)
            drawLine(color, Offset(s * 0.82f, topY), Offset(s * 0.82f, topY + s * 0.10f), strokeWidth * 0.8f, StrokeCap.Round)
            drawLine(color, Offset(s * 0.50f, topY), Offset(s * 0.50f, s * 0.80f), strokeWidth * 1.25f, StrokeCap.Round)
            drawLine(color, Offset(s * 0.34f, s * 0.80f), Offset(s * 0.66f, s * 0.80f), strokeWidth * 0.9f, StrokeCap.Round)
        }

        ClipperIconKind.TYPE_IMAGE -> {
            // 相框 + 一座山 + 一轮太阳。山用折线而不是实心块：这个尺寸下实心会糊成一团。
            drawRoundRect(
                color = color,
                topLeft = Offset(s * 0.12f, s * 0.18f),
                size = Size(s * 0.76f, s * 0.64f),
                cornerRadius = CornerRadius(s * 0.12f),
                style = stroke,
            )
            drawPath(
                path = Path().apply {
                    moveTo(s * 0.22f, s * 0.72f)
                    lineTo(s * 0.41f, s * 0.48f)
                    lineTo(s * 0.55f, s * 0.64f)
                    lineTo(s * 0.63f, s * 0.55f)
                    lineTo(s * 0.79f, s * 0.72f)
                },
                color = color,
                style = Stroke(width = strokeWidth * 0.85f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            drawCircle(color, radius = s * 0.075f, center = Offset(s * 0.34f, s * 0.35f), style = Fill)
        }

        ClipperIconKind.TYPE_FILE -> drawDocument(color, s, stroke)

        ClipperIconKind.TYPE_RICH_TEXT -> {
            // 「一个 T 带几行文字」：左上角一个小 "T"，右侧三行短横线，下面两行通栏长线。
            // 与 [ClipperIconKind.TYPE_TEXT] 的差别就是这些**行**——一眼看出是带格式的文本。
            val thin = strokeWidth * 0.85f
            drawLine(color, Offset(s * 0.12f, s * 0.22f), Offset(s * 0.42f, s * 0.22f), strokeWidth, StrokeCap.Round)
            drawLine(color, Offset(s * 0.27f, s * 0.22f), Offset(s * 0.27f, s * 0.54f), strokeWidth, StrokeCap.Round)
            listOf(0.22f to 0.88f, 0.38f to 0.88f, 0.54f to 0.74f).forEach { (y, end) ->
                drawLine(color, Offset(s * 0.54f, s * y), Offset(s * end, s * y), thin, StrokeCap.Round)
            }
            listOf(0.70f, 0.86f).forEach { y ->
                drawLine(color, Offset(s * 0.12f, s * y), Offset(s * 0.88f, s * y), thin, StrokeCap.Round)
            }
        }
    }
}

/**
 * 「一张纸」的图形：右上角折角，纸内是空的。
 *
 * 文件条目用它，只表达「这是一个文档」。富文本另用「一个 T 带几行文字」的图形
 * （见 [ClipperIconKind.TYPE_RICH_TEXT]）——两者**不再共用轮廓**：设计稿给的就是两种样子，
 * 而「纸内有没有横线」在 13dp 下本来也分不出来。
 */
private fun DrawScope.drawDocument(color: Color, s: Float, stroke: Stroke) {
    val left = s * 0.20f
    val top = s * 0.12f
    val right = s * 0.80f
    val bottom = s * 0.88f
    val fold = s * 0.20f

    drawPath(
        path = Path().apply {
            moveTo(left, top)
            lineTo(right - fold, top)
            lineTo(right, top + fold)
            lineTo(right, bottom)
            lineTo(left, bottom)
            close()
        },
        color = color,
        style = stroke,
    )
    // 折角本身：补两条短线，把切掉的那一角勾出来。
    drawPath(
        path = Path().apply {
            moveTo(right - fold, top)
            lineTo(right - fold, top + fold)
            lineTo(right, top + fold)
        },
        color = color,
        style = Stroke(width = stroke.width * 0.8f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/**
 * 逆时针回旋箭头：一段留出缺口的圆环 + 一个落在环末端的实心三角箭头。
 *
 * 缺口留在正上方，箭头落在左上（顺时针方向的末端），指向切线方向——与系统里
 * `arrow.counterclockwise` 的观感一致。
 */
private fun DrawScope.drawResetArrow(color: Color, s: Float, stroke: Stroke) {
    val radius = s * 0.30f
    val center = Offset(s * 0.5f, s * 0.5f)
    drawArc(
        color = color,
        startAngle = -45f,
        sweepAngle = 270f,
        useCenter = false,
        topLeft = Offset(center.x - radius, center.y - radius),
        size = Size(radius * 2f, radius * 2f),
        style = stroke,
    )

    // 弧末端（-135°）的位置与顺时针切线方向；箭头沿切线指出去。
    val endAngle = -135.0 * PI / 180.0
    val end = Offset(
        x = center.x + radius * cos(endAngle).toFloat(),
        y = center.y + radius * sin(endAngle).toFloat(),
    )
    val tangent = Offset(-sin(endAngle).toFloat(), cos(endAngle).toFloat())
    val normal = Offset(-tangent.y, tangent.x)
    val head = s * 0.13f
    val half = s * 0.09f
    drawPath(
        path = Path().apply {
            moveTo(end.x + tangent.x * head, end.y + tangent.y * head)
            lineTo(end.x + normal.x * half, end.y + normal.y * half)
            lineTo(end.x - normal.x * half, end.y - normal.y * half)
            close()
        },
        color = color,
        style = Fill,
    )
}

/**
 * 预览工具栏「置顶」按钮的图钉图形：顶帽 + 圆盘 + 针，按 0..1 的比例画。
 *
 * 这里**曾经**是从设计稿 SVG 抄来的一段 `path`（`viewBox 0 0 1024 1024`）。那段数据是坏的：
 * 它描述的轮廓填充出来是一个四角星，不是图钉，而且换 `EvenOdd` 填充规则、改成只描边都还是同一
 * 个星形——错的不是画法，是数据本身。它还很隐蔽：整整 2767 个字符的坐标，抄错任何一处都只会
 * 让形状「稍微怪一点」，直到有人把它放大才看得出整枚图标根本不是图钉。
 *
 * 因此改回与其它图标同一套做法：用比例坐标现画。好处不只是修好这一个图标——它落在同一个
 * 0..1 网格上，线宽与 `drawClipperIcon` 的 `strokeWidth`（边长的 10%）一致，和并排的删除、
 * 识别文字图标视觉重量相同；改尺寸时也不必再维护那张路径表。
 *
 * [slashed] 为 `true` 时叠一条斜线，表示「已钉住，点击取消钉住」。
 */
private fun DrawScope.drawPin(color: Color, s: Float, strokeWidth: Float, slashed: Boolean) {
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)

    // 顶帽：略窄的圆角矩形。
    drawRoundRect(
        color = color,
        topLeft = Offset(s * 0.33f, s * 0.08f),
        size = Size(s * 0.34f, s * 0.19f),
        cornerRadius = CornerRadius(s * 0.07f),
        style = stroke,
    )
    // 圆盘：图钉压在纸面上的那一圈，比顶帽宽。
    drawRoundRect(
        color = color,
        topLeft = Offset(s * 0.18f, s * 0.27f),
        size = Size(s * 0.64f, s * 0.18f),
        cornerRadius = CornerRadius(s * 0.06f),
        style = stroke,
    )
    // 针：从圆盘底下扎出去。
    drawLine(
        color = color,
        start = Offset(s * 0.5f, s * 0.45f),
        end = Offset(s * 0.5f, s * 0.90f),
        strokeWidth = strokeWidth,
        cap = StrokeCap.Round,
    )

    if (slashed) {
        drawLine(
            color = color,
            start = Offset(s * 0.14f, s * 0.14f),
            end = Offset(s * 0.86f, s * 0.86f),
            // 与 `drawClipperIcon` 里那条清除图标的斜线同宽：两条斜线并排时不至于一粗一细。
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
