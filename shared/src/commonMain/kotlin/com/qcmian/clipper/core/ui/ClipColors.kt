package com.qcmian.clipper.core.ui

import androidx.compose.ui.graphics.Color

/**
 * 把 `#RGB`、`#RRGGBB` 或 `#AARRGGBB` 字符串解析为 [Color]。
 * 用于为复制的十六进制颜色渲染色块。
 *
 * 8 位形式的首个字节是 alpha 通道（如 `#80FF0000` 为半透明红），原样保留；
 * 不带 alpha 的形式视为完全不透明。渲染方需为透明色提供棋盘格底纹，
 * 否则透明度在列表里看不出来。
 */
fun hexToColor(value: String): Color? {
    val hex = value.trim().removePrefix("#")
    val expanded = when (hex.length) {
        3 -> "FF" + hex.map { "$it$it" }.joinToString("")
        6 -> "FF$hex"
        8 -> hex
        else -> return null
    }
    val argb = expanded.toLongOrNull(16)?.toInt() ?: return null
    return Color(argb)
}
