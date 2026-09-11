package com.qcmian.clipper.ui

import androidx.compose.ui.unit.dp

/** Layout constants of Maccy's `Popup`, kept identical so the replica measures the same. */
object Popup {
    val verticalPadding = 5.dp
    val horizontalPadding = 5.dp
    val verticalSeparatorPadding = 6.dp
    val horizontalSeparatorPadding = 6.dp

    /** `Popup.cornerRadius` on macOS versions below 26. */
    val cornerRadius = 4.dp

    /** `Popup.itemHeight` on macOS versions below 26. */
    val itemHeight = 22.dp

    /** `Popup.minimumPreviewHeight`: keep the popup tall enough for the preview to be usable. */
    val minimumPreviewHeight = 150.dp

    /** Maccy's `SlideoutController` widths. */
    val contentWidth = 450.dp
    val minimumContentWidth = 200.dp
    val previewWidth = 400.dp
    val minimumPreviewWidth = 200.dp
}
