package com.qcmian.clipper.core.ui

import androidx.compose.ui.input.pointer.PointerIcon
import java.awt.Cursor

/** AWT 的 `E_RESIZE`，在 macOS 上就是系统分隔条的 `resizeLeftRight`。 */
actual val HorizontalResizePointerIcon: PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR))

/** AWT 的 `N_RESIZE`，对应系统分隔条的 `resizeUpDown`。 */
actual val VerticalResizePointerIcon: PointerIcon =
    PointerIcon(Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR))
