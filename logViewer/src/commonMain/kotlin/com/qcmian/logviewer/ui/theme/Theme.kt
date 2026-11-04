package com.qcmian.logviewer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 视图专用设计令牌（对齐 UI 原型） */
data class LvColors(
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val surfaceHigh: Color,
    val border: Color,
    val borderStrong: Color,
    val text: Color,
    val textDim: Color,
    val textFaint: Color,
    val accent: Color,
    val accentSoft: Color,
    val markBg: Color,
    val markText: Color,
    val hover: Color,
    val active: Color,
    val currentLineBg: Color,
    val findMatchBg: Color,
    val dimContent: Float,
    val levelE: Color,
    val levelW: Color,
    val levelI: Color,
    val levelD: Color,
    val levelV: Color,
    val levelF: Color,
)

private val DarkPalette = LvColors(
    bg = Color(0xFF10141B),
    surface = Color(0xFF161B24),
    surfaceAlt = Color(0xFF1C222D),
    surfaceHigh = Color(0xFF222A37),
    border = Color(0xFF262E3D),
    borderStrong = Color(0xFF3A4458),
    text = Color(0xFFE6ECF5),
    textDim = Color(0xFF8693AB),
    textFaint = Color(0xFF5A6478),
    accent = Color(0xFF5B8CFF),
    accentSoft = Color(0x295B8CFF),
    markBg = Color(0x52FFD66E),
    markText = Color(0xFF1A1500),
    hover = Color(0x0BFFFFFF),
    active = Color(0x12FFFFFF),
    currentLineBg = Color(0x295B8CFF),
    findMatchBg = Color(0x66F5A524),
    dimContent = 0.38f,
    levelE = Color(0xFFFB7185),
    levelW = Color(0xFFF5A524),
    levelI = Color(0xFF5B8CFF),
    levelD = Color(0xFF8693AB),
    levelV = Color(0xFF6B7280),
    levelF = Color(0xFFF43F5E),
)

private val LightPalette = LvColors(
    bg = Color(0xFFFAFBFC),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF4F6FA),
    surfaceHigh = Color(0xFFE9EDF4),
    border = Color(0xFFD9DFE9),
    borderStrong = Color(0xFFC2C9D7),
    text = Color(0xFF1C2230),
    textDim = Color(0xFF5D677E),
    textFaint = Color(0xFF8590A6),
    accent = Color(0xFF2F6BFF),
    accentSoft = Color(0x1A2F6BFF),
    markBg = Color(0x80FFD66E),
    markText = Color(0xFF1A1500),
    hover = Color(0x0B14285A),
    active = Color(0x1414285A),
    currentLineBg = Color(0x242F6BFF),
    findMatchBg = Color(0x59F5A524),
    dimContent = 0.42f,
    levelE = Color(0xFFDC2626),
    levelW = Color(0xFFB45309),
    levelI = Color(0xFF2F6BFF),
    levelD = Color(0xFF5D677E),
    levelV = Color(0xFF8590A6),
    levelF = Color(0xFFB91C1C),
)

val LocalLvColors = staticCompositionLocalOf { DarkPalette }

/**
 * 工作台主题。
 *
 * [dark] 由宿主给（开发者工具面板跟着 Clipper 的明暗走）：上游那套「跟随系统 / 浅色 / 深色」
 * 的三态设置已经去掉，这里只负责把对应的配色与 Material 色板铺下去。
 */
@Composable
fun LogViewerTheme(dark: Boolean, content: @Composable () -> Unit) {
    val palette = if (dark) DarkPalette else LightPalette
    val scheme = if (dark) {
        darkColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            background = palette.bg,
            surface = palette.surface,
            onBackground = palette.text,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceAlt,
            onSurfaceVariant = palette.textDim,
            outline = palette.border,
            outlineVariant = palette.border,
            secondaryContainer = palette.surfaceAlt,
            onSecondaryContainer = palette.text,
        )
    } else {
        lightColorScheme(
            primary = palette.accent,
            onPrimary = Color.White,
            background = palette.bg,
            surface = palette.surface,
            onBackground = palette.text,
            onSurface = palette.text,
            surfaceVariant = palette.surfaceAlt,
            onSurfaceVariant = palette.textDim,
            outline = palette.border,
            outlineVariant = palette.border,
            secondaryContainer = palette.surfaceAlt,
            onSecondaryContainer = palette.text,
        )
    }
    CompositionLocalProvider(LocalLvColors provides palette) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

/** ARGB Long -> Compose Color */
fun Long.toComposeColor(): Color = Color(this.toULong() shl 32)
