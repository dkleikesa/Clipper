package com.qcmian.clipper.core.ui

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

/** AWT 的 `E_RESIZE`，在 macOS 上就是系统分隔条的 `resizeLeftRight`。 */
internal actual val HorizontalResizePointerIcon: PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))
