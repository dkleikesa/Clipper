package com.qcmian.logviewer.platform

import androidx.compose.ui.input.pointer.PointerIcon

/** 桌面：AWT 东西向（左右）调整大小光标 */
actual val colResizeIcon: PointerIcon =
    PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.E_RESIZE_CURSOR))
