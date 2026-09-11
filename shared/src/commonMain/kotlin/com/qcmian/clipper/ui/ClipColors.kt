package com.qcmian.clipper.ui

import androidx.compose.ui.graphics.Color

/**
 * Parses a `#RGB`, `#RRGGBB` or `#AARRGGBB` string into a [Color].
 * Used to render the color swatch for copied hex colors, like Maccy's `ColorImage`.
 */
fun hexToColor(value: String): Color? {
    val hex = value.trim().removePrefix("#")
    val expanded = when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("")
        6, 8 -> hex
        else -> return null
    }
    // Drop a leading alpha channel: the swatch only needs the opaque color.
    val rgb = if (expanded.length == 6) expanded else expanded.substring(2)
    val value32 = rgb.toLongOrNull(16)?.toInt() ?: return null
    return Color(0xFF000000.toInt() or value32)
}
