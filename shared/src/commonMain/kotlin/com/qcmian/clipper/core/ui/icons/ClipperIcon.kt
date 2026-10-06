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
    /** 文稿右侧带一个向外的箭头；Base64 改用 [BASE64] 之后暂无人用，作为「导出文稿」的图形词汇留着。 */
    DOC_ARROW,
    /**
     * 一张带折角的文稿，纸上分两行写着 "Base" 与 "64"，Base64 编解码用。
     *
     * 与 [DOC_ARROW]、[TYPE_FILE] 一样都是「一张纸」，但三者说的不是一回事：这个说的是「这一页上
     * 写着编好码的内容」，[DOC_ARROW] 说的是「把文稿导出去」，[TYPE_FILE] 说的是「这条剪贴板记录
     * 是个文件」。合并了将来任一边调形状都会牵连另外两边。
     */
    BASE64,
    /** 两个 45° 斜置的环节扣在一起，URL 编解码用。 */
    LINK,
    /** 地球：一条经线加一条赤道。URL 编解码改用 [LINK] 之后暂无人用，作为「网络 / 全球」的图形词汇留着。 */
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
    /** 一枚指纹，Hash 摘要用。 */
    FINGERPRINT,
    /** 井号。Hash 摘要改用 [FINGERPRINT] 之后暂无人用，留着给以后要「井号」意象的工具。 */
    HASH,
    /** 证件卡片，UUID 生成用。 */
    ID_CARD,
    /** 计算器：机身里一块显示屏加两排按键，数学计算器用。 */
    CALCULATOR,
    /** 一页写着「BIN」的文档，二进制查看用。 */
    BINARY,

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
        ClipperIconKind.BASE64 -> {
            // 路径逐点搬自设计稿 SVG（`viewBox 0 0 1303 1024` 的两条 `fill` 路径）：一张带折角的
            // 文稿，纸上分两行写着 "Base" 与 "64"。那两行字笔画细、又带镂空（a、e、6 都有眼儿），
            // 手工近似必然走样，所以照设计稿画——与图钉（`drawPin`）、指纹
            // （[ClipperIconKind.FINGERPRINT]）同一套做法：解析一次，按两条路径的合并包围盒等比
            // 缩放并居中，再整条填充。
            val bounds = Base64Bounds
            val extent = maxOf(bounds.width, bounds.height)
            if (extent > 0f) {
                val scale = s * Base64ExtentRatio / extent
                withTransform({
                    // 画布变换按书写顺序左乘：先绕原点等比缩放，再平移到画布中心。
                    translate(
                        left = s / 2f - bounds.center.x * scale,
                        top = s / 2f - bounds.center.y * scale,
                    )
                    scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
                }) {
                    Base64Paths.forEach { drawPath(it, color, style = Fill) }
                }
            }
        }

        ClipperIconKind.GLOBE -> {
            drawCircle(color, 8.5f * g, p(12f, 12f), style = st)
            drawOval(color, p(8.5f, 3.5f), Size(7f * g, 17f * g), style = st)
            line(3.5f, 12f, 20.5f, 12f)
            
        }
        ClipperIconKind.LINK -> {
            // 两个环节各绕自己的中心转 -45°，长边因此落在一条 45° 对角线上；中间一段短杆把相扣
            // 的地方连起来。
            fun link(center: Offset, length: Float, width: Float) {
            withTransform({ rotate(degrees = -45f, pivot = center) }) {
            drawRoundRect(
            color = color,
            topLeft = Offset(center.x - length * g / 2f, center.y - width * g / 2f),
            size = Size(length * g, width * g),
            // 圆角取到半个宽，两端收成半圆——环节才是「环」而不是方框。
            cornerRadius = CornerRadius(width * g * 0.5f),
            style = st,
            )
            }
            }
            link(center = p(8.9f, 15.1f), length = 8.8f, width = 6.2f)
            link(center = p(15.1f, 8.9f), length = 8.8f, width = 6.2f)
            line(9.9f, 14.1f, 14.1f, 9.9f)
            
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
        ClipperIconKind.FINGERPRINT -> {
            // 路径逐点搬自设计稿（见 `FingerprintPathData`）：指纹这种密图形手工近似必然走样，
            // 照设计稿画最省事，做法与图钉（`drawPin`）一样——按包围盒等比缩放并居中。
            val bounds = FingerprintBounds
            val extent = maxOf(bounds.width, bounds.height)
            if (extent > 0f) {
            val scale = s * FingerprintExtentRatio / extent
            withTransform({
            // 画布变换按书写顺序左乘：先绕原点等比缩放，再平移到画布中心。
            translate(
            left = s / 2f - bounds.center.x * scale,
            top = s / 2f - bounds.center.y * scale,
            )
            scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
            }) {
            drawPath(FingerprintPath, color, style = Fill)
            }
            }
            
        }
        ClipperIconKind.ID_CARD -> {
            drawRoundRect(color, p(3f, 5.5f), Size(18f * g, 13f * g), CornerRadius(2.2f * g), style = st)
            drawCircle(color, 2.1f * g, p(8.5f, 11f), style = st)
            line(13f, 9.5f, 18f, 9.5f); line(13f, 13f, 18f, 13f)
            drawArc(color, 200f, 140f, false, topLeft = p(5.6f, 11.5f), size = Size(5.8f * g, 5.8f * g), style = Stroke(toolStrokeWidth * 0.85f, cap = StrokeCap.Round))
            
        }
        ClipperIconKind.CALCULATOR -> {
            drawRoundRect(color, p(5f, 3.5f), Size(14f * g, 17f * g), CornerRadius(2.2f * g), style = st)
            // 一块显示屏 + 两排按键。
            line(8f, 7.6f, 16f, 7.6f)
            listOf(11.2f, 15.2f).forEach { y ->
            listOf(8.5f, 12f, 15.5f).forEach { x ->
            drawCircle(color, 1f * g, p(x, y))
            }
            }
            
        }
        ClipperIconKind.BINARY -> {
            // 路径逐点搬自设计稿（见 `BinaryPathData`）：一页文档里排着 "BIN" 三个字母，这种密图形
            // 手工近似必然走样，照设计稿画最省事——做法与图钉（`drawPin`）、指纹（`FINGERPRINT`）
            // 一样，按包围盒等比缩放并居中。
            val bounds = BinaryBounds
            val extent = maxOf(bounds.width, bounds.height)
            if (extent > 0f) {
                val scale = s * BinaryExtentRatio / extent
                withTransform({
                    // 画布变换按书写顺序左乘：先绕原点等比缩放，再平移到画布中心。
                    translate(
                        left = s / 2f - bounds.center.x * scale,
                        top = s / 2f - bounds.center.y * scale,
                    )
                    scale(scaleX = scale, scaleY = scale, pivot = Offset.Zero)
                }) {
                    drawPath(BinaryPath, color, style = Fill)
                }
            }
            
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
 * 指纹图标的路径：**逐字**搬自设计稿 SVG（`viewBox 0 0 1024 1024` 的单条 `fill` 路径）。
 *
 * 不做手工近似：指纹是密图形，手画几道弧怎么调都走样。绘制时按包围盒等比缩放并居中，
 * 与 [PushpinPath] 同一套做法。
 */
private const val FingerprintPathData =
    "M113.431273 286.184727a23.272727 23.272727 0 0 1 40.494545 22.970182C114.362182 378.88 93.090909 " +
    "458.984727 93.090909 542.487273a488.727273 488.727273 0 0 0 1.349818 36.235636 23.272727 23.272727 0 " +
    "0 1-46.429091 3.444364A535.272727 535.272727 0 0 1 46.545455 542.487273c0-91.578182 " +
    "23.342545-179.595636 66.885818-256.302546zM959.069091 681.029818a23.272727 23.272727 0 0 " +
    "1-44.916364-12.241454c11.101091-40.657455 16.779636-83.083636 16.779637-126.301091 " +
    "0-91.066182-25.274182-178.013091-71.842909-251.741091a23.272727 23.272727 0 0 1 " +
    "39.330909-24.855273C949.690182 347.042909 977.454545 442.600727 977.454545 542.487273c0 " +
    "47.383273-6.237091 93.882182-18.385454 138.542545zM829.696 180.014545a23.272727 23.272727 0 1 " +
    "1-32.837818 32.977455C719.825455 136.378182 619.054545 93.090909 512 93.090909c-114.711273 " +
    "0-222.068364 49.687273-300.520727 136.331636a23.272727 23.272727 0 0 1-34.513455-31.232C264.075636 " +
    "101.981091 383.860364 46.545455 512 46.545455c119.621818 0 232.122182 48.290909 317.672727 " +
    "133.46909z m-455.028364 7.33091a23.272727 23.272727 0 0 1 18.152728 42.868363c-112.221091 " +
    "47.522909-186.693818 157.789091-186.786909 283.927273-8.378182 90.670545-34.909091 " +
    "156.997818-80.80291 198.050909a23.272727 23.272727 0 0 1-31.022545-34.676364c35.863273-32.093091 " +
    "58.112-87.668364 65.396364-165.515636a352.465455 352.465455 0 0 1 215.063272-324.654545zM807.098182 " +
    "888.110545a23.272727 23.272727 0 1 1-41.634909-20.805818c9.099636-18.199273 16.989091-37.189818 " +
    "23.691636-56.925091 27.531636-81.058909 33.349818-156.346182 " +
    "29.742546-261.003636l-0.698182-20.363636c-0.232727-7.074909-0.325818-12.381091-0.325818-17.012364a23.272727 " +
    "23.272727 0 0 1 46.545454 0c0 4.049455 0.093091 8.936727 0.302546 15.546182l0.698181 20.224c3.770182 " +
    "109.847273-2.420364 189.882182-32.209454 277.573818-7.354182 21.690182-16.058182 42.635636-26.112 " +
    "62.766545z m37.445818-493.079272a23.272727 23.272727 0 0 1-43.915636 15.453091 305.989818 305.989818 " +
    "0 0 0-305.640728-203.869091 23.272727 23.272727 0 0 1-2.56-46.498909 352.535273 352.535273 0 0 1 " +
    "352.116364 234.914909z m-273.850182-125.416728a23.272727 23.272727 0 0 1-7.68 " +
    "45.917091c-66.513455-11.101091-126.906182 5.376-182.760727 50.059637-35.025455 28.020364-50.176 " +
    "58.321455-57.879273 104.517818-1.466182 8.843636-2.373818 15.639273-4.421818 32.581818-5.678545 " +
    "46.312727-10.146909 67.444364-22.853818 92.858182a23.272727 23.272727 0 1 " +
    "1-41.634909-20.805818c9.541818-19.083636 13.265455-36.584727 18.269091-77.730909 2.164364-17.594182 " +
    "3.118545-24.808727 4.747636-34.56 9.425455-56.645818 29.719273-97.210182 74.705455-133.189819 " +
    "66.024727-52.829091 139.729455-72.936727 219.508363-59.648zM215.645091 669.207273a23.272727 " +
    "23.272727 0 0 1 44.148364 14.731636c-13.428364 40.285091-39.796364 79.825455-78.75491 " +
    "118.807273a23.272727 23.272727 0 1 1-32.930909-32.907637c34.187636-34.187636 56.576-67.770182 " +
    "67.537455-100.631272z m444.462545-287.045818a23.272727 23.272727 0 0 1 32.930909-32.907637c41.425455 " +
    "41.425455 61.672727 102.167273 61.672728 181.038546 0 195.909818-41.495273 343.458909-133.399273 " +
    "453.748363a23.272727 23.272727 0 1 1-35.770182-29.789091c84.014545-100.840727 122.624-238.033455 " +
    "122.624-423.959272 0-67.444364-16.337455-116.386909-48.058182-148.130909z m-15.127272 " +
    "130.094545a23.272727 23.272727 0 0 " +
    "1-46.545455-0.512c0.698182-60.136727-25.367273-86.178909-86.434909-86.178909-61.067636 0-87.109818 " +
    "26.065455-86.504727 87.970909-12.660364 189.998545-83.386182 325.026909-212.386909 " +
    "402.408727a23.272727 23.272727 0 1 1-23.947637-39.889454c114.827636-68.887273 178.199273-189.858909 " +
    "189.858909-363.799273-0.977455-86.155636 46.126545-133.236364 132.980364-133.236364s133.957818 " +
    "47.080727 132.980364 133.236364zM488.727273 512a23.272727 23.272727 0 0 1 46.545454 0c0 " +
    "189.253818-63.581091 341.876364-190.557091 456.145455a23.272727 23.272727 0 0 " +
    "1-31.138909-34.583273C430.405818 828.392727 488.727273 688.453818 488.727273 512z m109.986909 " +
    "106.170182a23.272727 23.272727 0 0 1 46.010182 7.074909c-25.181091 163.560727-82.408727 " +
    "284.392727-172.450909 361.565091a23.272727 23.272727 0 1 1-30.254546-35.328c80.616727-69.12 " +
    "133.096727-179.898182 156.695273-333.312z"

/** 解析一次即可：路径是常量，绘制时只做一次画布变换。 */
private val FingerprintPath: Path by lazy { PathParser().parsePathString(FingerprintPathData).toPath() }

/** [FingerprintPath] 的包围盒：用来把 1024 见方的设计稿等比缩放并居中到图标画布。 */
private val FingerprintBounds: Rect by lazy { FingerprintPath.getBounds() }

/** 指纹在图标画布中占的比例：设计稿几乎铺满它的 viewBox，留一点内边距才与其它图标等重。 */
private const val FingerprintExtentRatio = 0.92f

/**
 * 二进制查看图标的路径：**逐字**搬自设计稿 SVG（`viewBox 0 0 1024 1024` 的单条 `fill` 路径）。
 *
 * 一页文档里排着 "BIN" 三个字母，这种密图形手工近似必然走样，照设计稿画最省事——与
 * [PushpinPath]、[FingerprintPath] 同一套做法：按包围盒等比缩放并居中。
 */
private const val BinaryPathData =
    "M690.688 0c5.632 0 9.2672 1.024 13.312 3.1232 3.584 1.536 6.656 3.584 10.752 6.656 5.632 4.608 14.3872 11.264 24.5248 19.968 17.92 15.36 41.9328 36.864 71.168 62.8736a43.9296 43.9296 0 0 0 5.1712 4.608c22.016 20.0192 " +
    "44.544 40.9088 67.584 61.9008l22.016 20.48c4.096 4.096 6.656 6.656 8.192 7.68 6.3488 6.0928 8.96 14.6944 7.5776 " +
    "22.8352v251.7504H1024v434.7392h-102.912V1024H229.8368a127.8976 127.8976 0 0 1-127.8976-127.3856H0V461.8752h101.888V1.4848h486.3488c14.336 0 25.6 11.264 25.6 25.6 " +
    "0 14.4384-11.264 25.6512-25.6 25.6512h-435.2v408.7808H153.6v0.512h716.288V256.3072h-204.8V26.112c0-14.336 11.264-25.6 25.7024-26.112z " +
    "m179.1488 896.6144h-716.8c0.256 42.24 34.4576 76.288 76.7488 76.288h640v-76.288z" +
    "M287.3856 545.024H206.3872c-20.992 1.0752-32 11.5712-33.0752 31.3856v237.824c1.0752 19.8144 12.1344 30.2592 33.0752 31.3856h87.6032c62.8736-2.2016 95.3856-33.0752 97.5872-92.5184-3.328-38.5024-24.832-62.208-64.512-71.0144v-1.6384c26.4704-11.008 39.68-31.3856 39.68-61.1328-3.2768-46.2336-29.7472-71.0144-79.36-74.2912z " +
    "m205.824-3.328c-19.8656 1.1264-30.3616 12.1344-31.4368 33.024v241.152c1.0752 19.8144 11.5712 29.696 31.3856 29.696 19.8656 0 30.3104-9.8816 31.4368-29.696v-241.152c-1.1264-20.8896-11.5712-31.8976-31.4368-33.024z " +
    "m338.0224 0c-19.8144 1.1264-30.3104 12.1344-31.4368 33.024v160.256h-1.6384l-133.9392-178.3808a36.2496 36.2496 0 0 0-26.4704-14.848c-19.8144 1.024-30.3104 12.0832-31.3856 32.9728v241.152c1.0752 19.8144 11.5712 29.696 31.3856 29.696 19.8656 0 30.3616-9.8816 31.4368-29.696v-158.5664h1.6384l133.9392 176.7424c6.656 7.68 15.4624 11.5712 26.4704 11.5712 19.8656 0 30.3104-9.9328 31.4368-29.7472v-241.152c-1.1264-20.8896-11.5712-31.8976-31.4368-33.024z" +
    "M280.7808 720.0768c28.672 0 43.52 11.008 44.6464 33.024-1.1264 23.1424-14.336 35.2256-39.68 36.352h-49.664v-69.376h44.6976z " +
    "m-11.5712-118.8864c22.016 1.0752 33.6384 11.008 34.7136 29.696-1.0752 20.9408-12.6976 31.9488-34.7136 33.024h-33.0752v-62.72h33.0752z" +
    "M716.3904 77.824v128.256h142.1824a273.6128 273.6128 0 0 0-10.6496-9.728c-22.528-20.992-45.568-41.8816-67.072-61.44-2.56-2.4576-2.56-2.4576-5.12-4.608-23.4496-20.8384-43.3152-38.6048-59.392-52.48z"

/** 解析一次即可：路径是常量，绘制时只做一次画布变换。 */
private val BinaryPath: Path by lazy { PathParser().parsePathString(BinaryPathData).toPath() }

/** [BinaryPath] 的包围盒：用来把 1024 见方的设计稿等比缩放并居中到图标画布。 */
private val BinaryBounds: Rect by lazy { BinaryPath.getBounds() }

/** 图标在画布中占的比例：设计稿铺满它的 viewBox，留一点内边距才与其它图标等重。 */
private const val BinaryExtentRatio = 0.92f

/**
 * Base64 图标的两条路径：**逐字**搬自设计稿 SVG（`viewBox 0 0 1303 1024`）。
 *
 * 第一条是文稿本体——一圈细线勾出的带折角纸页；第二条是纸上的字：上排 "Base"、下排 "64"。
 * 那两行字笔画细、又带镂空（a、e、6 都有眼儿），手工近似必然走样，所以照设计稿画，与
 * [PushpinPath]、[FingerprintPath] 同一套做法：解析一次，按两条路径的**合并**包围盒等比缩放并居中。
 */
private const val Base64PagePathData =
    "M1282.786896 270.896107L1000.908905 18.527799a73.076031 73.076031 0 0 0-49.337958-18.525006" +
        "H105.750792S0 5.960584 0 95.141269v833.066759c1.117086 53.433939 47.9416 96.162472 " +
        "105.006068 95.79011h1093.254667S1303.266803 1019.995247 1303.266803 928.114938V316.510445" +
        "a64.232435 64.232435 0 0 0-21.13154-45.614338h0.651633z m-277.688919-162.535989" +
        "l182.550443 166.445789h-148.013873s-35.188204 0-35.188204-32.302398v-132.18849l0.651634-1.954901z " +
        "m229.095686 819.84791c0 8.657415-3.723619 16.942468-10.426135 22.90026a35.095113 35.095113 " +
        "0 0 1-24.855159 8.843596H105.192249a34.257299 34.257299 0 0 1-34.629661-31.743856V95.141269" +
        "c0-29.044232 34.53657-29.044232 34.536571-29.044231h830.367134v176.406471c0 89.180686 " +
        "104.261344 96.441743 104.261345 96.441743h194.559115v589.262776z"

private const val Base64TextPathData =
    "M344.993341 287.373123c68.235326 0 91.88031 22.527898 91.88031 61.25354a53.71321 53.71321 " +
        "0 0 1-39.191095 53.71321c27.089331 3.258167 47.476148 26.344608 47.196876 53.71321 0 " +
        "45.148886-29.509684 65.535702-90.763223 65.535703H255.254112V287.373123h89.739229z " +
        "m-45.614338 95.603929h41.890719c30.62677 0 53.71321-9.681411 53.71321-30.068227 0-20.479907" +
        "-17.687192-29.044232-53.71321-29.044232h-41.890719v59.112459z m0 98.862096h45.614338" +
        "c37.050013 0 53.71321-9.122868 53.71321-31.650765 0-22.620988-23.086441-30.71986-53.71321" +
        "-30.719861h-45.614338v62.370626z m294.910659-74.65857V401.222787c0-12.846487-9.681411" +
        "-24.668979-36.584561-24.668979a42.449262 42.449262 0 0 0-41.332175 21.410812l-39.191095" +
        "-5.306158a83.315985 83.315985 0 0 1 82.198899-48.872505c53.71321 0 77.265103 22.527898 " +
        "77.265103 58.553916v82.198899c0 9.681411 5.957791 11.822492 18.897369 9.122868v25.786064" +
        "a90.297771 90.297771 0 0 1-26.90315 3.72362 37.608556 37.608556 0 0 1-29.509684-12.287944 " +
        "78.47528 78.47528 0 0 1 0-10.798497 96.162472 96.162472 0 0 1-64.511707 23.086441" +
        "c-27.927146 0-59.577911-13.963573-59.577911-44.590343-2.792715-41.890719 39.098004-63.30153 " +
        "119.155822-71.400403z m-53.71321 83.222895a92.438853 92.438853 0 0 0 53.71321-17.687193V439.389886" +
        "c-40.30818 3.723619-79.033823 12.939578-79.033822 34.44348 2.792715 11.729401 9.681411 " +
        "16.570106 26.344607 16.570107h-1.023995z m171.286494-19.828274a44.0318 44.0318 0 0 0 " +
        "43.007805 21.969355c19.362821 0 31.650765-9.122868 31.650765-20.386817s-18.152645-18.804278" +
        "-41.332176-25.786064c-45.614338-12.381035-67.11824-26.81006-67.11824-53.713211s29.509684" +
        "-49.337958 74.658569-49.337957a75.775656 75.775656 0 0 1 75.775656 42.821623l-39.749638 " +
        "5.957792a38.167099 38.167099 0 0 0-36.026018-17.687193c-19.828274 0-32.209308 8.564325" +
        "-32.209308 19.362821 0 10.705406 19.828274 19.269731 36.491471 23.551893 49.989591 11.357039 " +
        "74.75166 25.320612 74.75166 53.806301 0 28.392598-31.185313 53.71321-78.47528 53.713211" +
        "a85.922519 85.922519 0 0 1-81.640356-45.614339l40.308181-8.657415z m235.891291-126.789242" +
        "a81.081813 81.081813 0 0 1 85.922519 87.039605v17.128649H902.139899a42.449262 42.449262 0 0 0 " +
        "47.196877 42.449262 49.337958 49.337958 0 0 0 44.683433-21.410812l39.098004 5.864701" +
        "a84.33998 84.33998 0 0 1-83.781437 48.407052 83.781437 83.781437 0 0 1-90.297772-89.739228 " +
        "83.781437 83.781437 0 0 1 88.715233-89.739229z m44.0318 73.541484a41.890719 41.890719 0 0 0" +
        "-44.0318-40.773633 42.449262 42.449262 0 0 0-45.148885 40.773633h89.180685zM536.759742 " +
        "560.407518a82.198899 82.198899 0 0 1 78.47528 47.755419l-43.007805 6.516334a39.191095 " +
        "39.191095 0 0 0-36.49147-21.503902c-35.467475 0-49.431048 38.632552-53.713211 76.334199" +
        "a67.11824 67.11824 0 0 1 56.878287-26.996241 71.493493 71.493493 0 0 1 77.358194 74.193117" +
        "c0 48.407053-30.068227 78.382189-87.505057 78.382189-57.52992 0-86.015609-33.791846-86.015609" +
        "-100.910087 0-82.850532 30.71986-133.771028 94.021391-133.771028zM531.546675 763.344778" +
        "a41.332176 41.332176 0 0 0 43.473257-44.0318 41.890719 41.890719 0 0 0-43.473257-44.0318 " +
        "44.0318 44.0318 0 1 0 0 88.0636zM752.171126 564.59659h62.370626v144.476434h29.509684v34.44348" +
        "H814.541752v47.196876h-43.007804v-47.289966H650.143954v-26.81006L752.171126 564.59659z " +
        "m19.362822 144.476434v-104.261344l-69.259322 104.261344h69.259322z"

/** 解析一次即可：路径是常量，绘制时只做一次画布变换。 */
private val Base64Paths: List<Path> by lazy {
    listOf(Base64PagePathData, Base64TextPathData).map {
        PathParser().parsePathString(it).toPath()
    }
}

/**
 * 两条路径的**合并**包围盒：文稿与纸上那行字一起缩放，两者之间的位置关系才不会变。
 *
 * 设计稿几乎铺满它的 viewBox（0..1303 × 0..1024），没必要逐条居中——合起来当成一张图。
 */
private val Base64Bounds: Rect by lazy {
    val boxes = Base64Paths.map { it.getBounds() }
    Rect(
        left = boxes.minOf { it.left },
        top = boxes.minOf { it.top },
        right = boxes.maxOf { it.right },
        bottom = boxes.maxOf { it.bottom },
    )
}

/**
 * Base64 图标在图标画布中占的比例，按**长边**算。
 *
 * 设计稿是横着的（1303 : 1024，宽高比约 1.27），长边取到画布边长的 80%，换算成 24 网格就是
 * 19.2 × 15.1，与相邻的 [ClipperIconKind.PICTURE]（18 × 14）同一档——并排放在侧边栏里，
 * 不会一个显大、一个显小。
 */
private const val Base64ExtentRatio = 0.80f

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
