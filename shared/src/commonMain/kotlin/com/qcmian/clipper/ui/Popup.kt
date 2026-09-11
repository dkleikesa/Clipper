package com.qcmian.clipper.ui

import androidx.compose.ui.unit.dp

/** Maccy `Popup` 的布局常量，保持完全一致，使复刻版量出的尺寸相同。 */
object Popup {
    val verticalPadding = 5.dp
    val horizontalPadding = 5.dp
    val verticalSeparatorPadding = 6.dp
    val horizontalSeparatorPadding = 6.dp

    /** macOS 26 以下版本的 `Popup.cornerRadius`。 */
    val cornerRadius = 4.dp

    /** macOS 26 以下版本的 `Popup.itemHeight`。 */
    val itemHeight = 22.dp

    /** `Popup.minimumPreviewHeight`：让弹窗保持足够高，以便预览可用。 */
    val minimumPreviewHeight = 150.dp

    /** Maccy `SlideoutController` 的宽度。 */
    val contentWidth = 450.dp
    val minimumContentWidth = 200.dp
    val previewWidth = 400.dp
    val minimumPreviewWidth = 200.dp
}
