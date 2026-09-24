package com.qcmian.clipper.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.qcmian.clipper.core.settings.ThemeMode

private val Accent = Color(0xFF0A84FF)

/**
 * 提示 / 说明类文字的颜色（设置页各分区里的副标题、支持说明、空状态等）。
 *
 * 比 `onSurfaceVariant` 更浅一档的中性灰（贴近 macOS 的 secondaryLabel），
 * 用来和分区标题、设置项标题拉开层次——此前两者共用 `onSurfaceVariant`，看上去一样重。
 */
private val Hint = Color(0xFF8E8E93)

internal val LocalHintColor = staticCompositionLocalOf { Hint }

/** 当前主题下的提示文字颜色，用法与 `MaterialTheme.colorScheme.*` 对应。 */
val MaterialTheme.hintColor: Color
    @Composable
    @ReadOnlyComposable
    get() = LocalHintColor.current

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E9FF),
    onPrimaryContainer = Color(0xFF00284D),
    background = Color(0xFFF5F5F7),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE6E6EB),
    onSurfaceVariant = Color(0xFF5A5A63),
    outline = Color(0xFFC9C9D1),
)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1C3D63),
    onPrimaryContainer = Color(0xFFD6E9FF),
    background = Color(0xFF1B1B1E),
    onBackground = Color(0xFFEDEDF0),
    surface = Color(0xFF26262A),
    onSurface = Color(0xFFEDEDF0),
    surfaceVariant = Color(0xFF35353B),
    onSurfaceVariant = Color(0xFFB4B4BD),
    outline = Color(0xFF4A4A52),
)

/**
 * 该用深色还是浅色：主题偏好 + 系统外观。
 *
 * 面板与设置窗口是**两个独立的组合**，主题必须由同一个算式给出——各算各的，或者各拿一份
 * 各自的系统外观，「跟随系统」时两个窗口就会深浅不一。
 *
 * @param systemDarkTheme 宿主提供的系统外观（桌面端不实时跟随 `isSystemInDarkTheme()`，
 *   由原生通知驱动）；`null` 表示宿主未提供，退回 Compose 的判断。
 */
@Composable
fun rememberClipperDarkTheme(themeMode: ThemeMode, systemDarkTheme: Boolean?): Boolean =
    when (themeMode) {
        ThemeMode.SYSTEM -> systemDarkTheme ?: isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

@Composable
fun ClipperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
    ) {
        // 提示文字颜色跟随解析后的深浅色偏好（`ThemeMode` 强制浅色 / 深色时也正确）。
        CompositionLocalProvider(LocalHintColor provides Hint) {
            content()
        }
    }
}
