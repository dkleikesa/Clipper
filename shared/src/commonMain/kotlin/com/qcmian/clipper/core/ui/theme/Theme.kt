package com.qcmian.clipper.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF0A84FF)

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

@Composable
fun ClipperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
