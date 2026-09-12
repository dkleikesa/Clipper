package com.qcmian.clipper.core.ui

import androidx.compose.ui.graphics.Color

/**
 * 把 `#RGB`、`#RRGGBB` 或 `#AARRGGBB` 字符串解析为 [Color]。
 * 用于为复制的十六进制颜色渲染色块。
 */
fun hexToColor(value: String): Color? {
    val hex = value.trim().removePrefix("#")
    val expanded = when (hex.length) {
        3 -> hex.map { "$it$it" }.joinToString("")
        6, 8 -> hex
        else -> return null
    }
    // 丢弃开头的 alpha 通道：色块只需要不透明的颜色。
    val rgb = if (expanded.length == 6) expanded else expanded.substring(2)
    val value32 = rgb.toLongOrNull(16)?.toInt() ?: return null
    return Color(0xFF000000.toInt() or value32)
}
