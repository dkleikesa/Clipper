package com.qcmian.clipper.desktop.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Clipper 的标志：一碗面（碗 + 筷子）。
 *
 * 两个出口互不牵连：
 * - [ClipperAppIcon]：窗口 / 应用图标，「炭火夜市」彩色底板，与打包的 icns / ico / png 以及
 *   Android、iOS 图标同一口径；
 * - [clipperTrayIcon]：托盘 / 菜单栏图标，单色线稿（原始设计稿的极简形态），
 *   颜色按菜单栏深浅传入，换样式不影响上面那些。
 *
 * 全部用 [ImageVector] 自绘，不引入任何平台图标资源。
 */

// ---- 品牌配色：「炭火夜市」----
// 深炭棕底 + 碗底暖光，线条本体是奶白 → 橙金渐变。
// 与 design/app-icon/（主稿 SVG、Android 自适应图标、iOS / 桌面端位图）同一套规范。
// 渐变的收尾色一律用「透明 + 同色」，而不是 [Color.Transparent]——后者是透明黑，过渡时会发灰。
private val CoalTop = Color(0xFF3A1A0A)
private val CoalMid = Color(0xFF1A0A04)
private val CoalDeep = Color(0xFF0A0402)

/** 碗底暖光：像炭火 / 热气从碗底冒上来。 */
private val EmberGlow = Color(0x57FF7A1E)
private val EmberGlowFade = Color(0x00FF7A1E)

/** 右上冷光，避免一整片死棕；左上再来一层白色柔光提亮。 */
private val CoolGlow = Color(0x24FF9A3C)
private val CoolGlowFade = Color(0x00FF9A3C)
private val Sheen = Color(0x24FFFFFF)
private val SheenFade = Color(0x00FFFFFF)

/** 底板边缘的一圈细描边，图标看起来更精致。 */
private val Hairline = Color(0x29FFFFFF)

/** 碗内「汤」：透过线条的挖空区域露出来的暖色。 */
private val BrothStart = Color(0x33FF9A3C)
private val BrothEnd = Color(0x99FF6A12)

/** 线条本体：奶白 → 橙金。 */
private val LineTop = Color(0xFFFFE9C2)
private val LineMid = Color(0xFFFFC169)
private val LineEnd = Color(0xFFFF8A2E)

/** 碗内「汤」：设计稿 d 里碗内圈挖空子路径的等价写法（起点 498.1 899.5 即原 d 的当前点）。 */
private const val BOWL_PATH_DATA =
    "M498.1 899.5c-192.5 0-356.7-151.5-383.6-353.9h767.2C854.8 748 690.6 899.5 498.1 899.5z"

private val brothNodes: List<PathNode> by lazy { PathParser().pathStringToNodes(BOWL_PATH_DATA) }

/**
 * 设计稿 SVG 的 `<path d>`，原样照搬。
 *
 * 1024 画布的坐标，交给 [PathParser] 在运行时解析——手工把几十段相对命令
 * （`c` / `s` / `h` / `v`）换算成绝对坐标只会引入错误。同一份画布定义
 * 也用在 Android 的 `ic_launcher_foreground.xml` 里。
 */
private const val GLYPH_PATH_DATA =
    "M936.9 201.6c14.2 0 25.5-11.3 25.5-25.5v-9.9c0-14.2-11.3-25.5-25.5-25.5H627c-3.2-9.6-12.5-17-22.8-17h-9.9c-10.3 0-19.6 7.4-22.8 17h-30.9c-3.2-9.6-12.5-17-22.8-17H508c-10.3 0-19.6 7.4-22.8 17H450c-3.2-9.6-12.5-17-22.8-17h-9.9c-10.3 0-19.6 7.4-22.8 17h-70.7c-14.2 0-25.5 11.3-25.5 25.5v9.9c0 14.2 11.3 25.5 25.5 25.5h69.4v38.2h-69.4c-14.2 0-25.5 11.3-25.5 25.5v9.9c0 14.2 11.3 25.5 25.5 25.5h69.4v182.6c0 1.4 0.2 2.9 0.5 4.2H110.2c-32.6 0-58 28.3-53.8 60.9 28.3 230.7 215.2 409.1 441.7 409.1s413.3-178.4 441.7-409.1c4.2-32.6-19.8-60.9-52.4-60.9H627.8c0.3-1.4 0.5-2.8 0.5-4.2V300.7h308.6c14.2 0 25.5-11.3 25.5-25.5v-9.9c0-14.2-11.3-25.5-25.5-25.5H628.3v-38.2h308.6z m-485.6 0h32.6v38.2h-32.6v-38.2z m0 281.7V300.7h32.6v182.6c0 1.4 0.2 2.9 0.5 4.2h-33.1c0.1-1.3 0.2-2.8 0-4.2z m46.8 416.2c-192.5 0-356.7-151.5-383.6-353.9h767.2C854.8 748 690.6 899.5 498.1 899.5z m72.6-412h-29.2c0.3-1.4 0.5-2.8 0.5-4.2V300.7h28.3v182.6c-0.1 1.4 0.1 2.9 0.4 4.2z m-0.5-247.7h-28.3v-38.2h28.3v38.2z"

private val glyphNodes: List<PathNode> by lazy { PathParser().pathStringToNodes(GLYPH_PATH_DATA) }

/**
 * 设计稿里图形的包围盒重心，用来把标志摆到画布正中——如果直接让 1024 画布居中，
 * 标志会偏下（画布下方比上方空得多）。
 *
 * 取自 1024 导图的实测包围盒 x[56..962] y[123..957]；Android 的
 * `ic_launcher_foreground.xml` 用的是同一组数值。
 */
private const val GLYPH_CENTER_X = 509f
private const val GLYPH_CENTER_Y = 540f

// ---- 1024 画布 ----
private const val CANVAS = 1024f
private const val TILE_RADIUS = 232f

/** 标志占底板的比例。设计稿本身四周留了约 12%，这里再收一点，图标才不像「贴边」。 */
private const val APP_GLYPH_SCALE = 0.72f

/**
 * 窗口 / 应用图标。
 *
 * 与打包产物（icns / ico / png）、Android 自适应图标、iOS AppIcon 是同一份
 * 「炭火夜市」设计稿——要动应用图标的样子，得设计稿和各平台资产一起改，
 * 不要在这里单独发挥；托盘样式请去 [ClipperTrayIcon]。
 *
 * 桌面端只把它喂给 `Window(icon = ...)`：那一步会按 dp 尺寸栅格化一次，
 * 所以这里取 256dp（macOS 程序坞的最大显示尺寸），不必按 1024 出图。
 */
val ClipperAppIcon: ImageVector by lazy { appIconBuilder("ClipperAppIcon").apply { addEmberTile() }.build() }

/**
 * 托盘 / 菜单栏图标：单色线稿，也就是原始设计稿的极简形态。
 *
 * 菜单栏图标的老规矩：纯色图形、内容行走真实负空间，深浅两种菜单栏都读得出来。
 * 颜色由调用方按菜单栏外观传入——菜单栏跟随系统深浅色，Compose 侧
 * [androidx.compose.foundation.isSystemInDarkTheme] 取到的值与它同源。
 *
 * 有意与 [ClipperAppIcon] 分开：托盘只有十几 pt，换样式、改粗细都不波及窗口图标
 * 与任何平台的打包图标。
 */
fun clipperTrayIcon(color: Color): ImageVector = ImageVector.Builder(
    name = "ClipperTrayIcon",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = MARK_CANVAS,
    viewportHeight = MARK_CANVAS,
).apply { addClipperMark(color) }.build()

private fun appIconBuilder(name: String) = ImageVector.Builder(
    name = name,
    defaultWidth = 256.dp,
    defaultHeight = 256.dp,
    viewportWidth = CANVAS,
    viewportHeight = CANVAS,
)

/**
 * 「炭火夜市」画法：底板 + 三盏光 + 细描边 + 碗内汤色 + 线条。[ClipperAppIcon] 专用。
 */
private fun ImageVector.Builder.addEmberTile() {
    // 圆角底板：深炭棕对角渐变铺底，再叠三盏光（碗底暖光 / 右上冷光 / 左上柔光）。
    path(
        fill = Brush.linearGradient(
            0f to CoalTop,
            0.55f to CoalMid,
            1f to CoalDeep,
            start = Offset(0f, 0f),
            end = Offset(CANVAS, CANVAS),
        ),
    ) {
        roundedRect(0f, 0f, CANVAS, CANVAS, TILE_RADIUS)
    }
    path(
        fill = Brush.radialGradient(
            0f to EmberGlow,
            1f to EmberGlowFade,
            center = Offset(512f, 880f),
            radius = 320f,
        ),
    ) {
        roundedRect(0f, 0f, CANVAS, CANVAS, TILE_RADIUS)
    }
    path(
        fill = Brush.radialGradient(
            0f to CoolGlow,
            1f to CoolGlowFade,
            center = Offset(900f, 140f),
            radius = 520f,
        ),
    ) {
        roundedRect(0f, 0f, CANVAS, CANVAS, TILE_RADIUS)
    }
    path(
        fill = Brush.radialGradient(
            0f to Sheen,
            1f to SheenFade,
            center = Offset(220f, 90f),
            radius = 620f,
        ),
    ) {
        roundedRect(0f, 0f, CANVAS, CANVAS, TILE_RADIUS)
    }
    // 内描边：一圈白色细线，边缘更精致。
    path(stroke = SolidColor(Hairline), strokeLineWidth = 4f) {
        roundedRect(3f, 3f, CANVAS - 6f, CANVAS - 6f, TILE_RADIUS - 3f)
    }

    // 图形与画刷都定义在 1024 画布坐标系里：Compose 的 group 用的是绘制变换
    // （`groupMatrix?.let { transform(it) }`），画刷会跟着 group 一起缩放平移，
    // 所以这里直接写设计稿坐标，语义与 SVG / Android VectorDrawable 一致。
    group(
        scaleX = APP_GLYPH_SCALE,
        scaleY = APP_GLYPH_SCALE,
        translationX = CANVAS / 2f - GLYPH_CENTER_X * APP_GLYPH_SCALE,
        translationY = CANVAS / 2f - GLYPH_CENTER_Y * APP_GLYPH_SCALE,
    ) {
        // 碗内「汤」：先画，透过线条本体的挖空区域露出来。
        addPath(
            brothNodes,
            fill = Brush.linearGradient(
                0f to BrothStart,
                1f to BrothEnd,
                start = Offset(512f, 545f),
                end = Offset(512f, 905f),
            ),
        )
        addPath(
            glyphNodes,
            fill = Brush.linearGradient(
                0f to LineTop,
                0.55f to LineMid,
                1f to LineEnd,
                start = Offset(400f, 110f),
                end = Offset(660f, 960f),
            ),
        )
    }
}

// ---- 24 画布的单色线稿 ----
private const val MARK_CANVAS = 24f
private const val MARK_GLYPH_SCALE = MARK_CANVAS / CANVAS

/**
 * 把单色标志追加到 [ImageVector.Builder]（24 画布），供菜单栏 / 托盘图标使用。
 *
 * 单色版直接用设计稿的实心路径，所以内容行是「留白」而不是另画一层——
 * 菜单栏底色会随系统主题和壁纸变化，只有真实的负空间在深浅两种底色上都读得出来。
 */
fun ImageVector.Builder.addClipperMark(color: Color) {
    group(
        scaleX = MARK_GLYPH_SCALE,
        scaleY = MARK_GLYPH_SCALE,
        translationX = MARK_CANVAS / 2f - GLYPH_CENTER_X * MARK_GLYPH_SCALE,
        translationY = MARK_CANVAS / 2f - GLYPH_CENTER_Y * MARK_GLYPH_SCALE,
    ) {
        addPath(glyphNodes, fill = SolidColor(color))
    }
}

/**
 * 用四段四分之一圆弧连接四条边，这是 [PathBuilder] 表达圆角矩形的方式。
 */
private fun PathBuilder.roundedRect(
    x: Float,
    y: Float,
    w: Float,
    h: Float,
    radius: Float,
) {
    moveTo(x + radius, y)
    lineTo(x + w - radius, y)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x + w, y1 = y + radius)
    lineTo(x + w, y + h - radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x + w - radius, y1 = y + h)
    lineTo(x + radius, y + h)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x, y1 = y + h - radius)
    lineTo(x, y + radius)
    arcTo(radius, radius, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = x + radius, y1 = y)
    close()
}
