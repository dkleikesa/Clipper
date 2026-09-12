package com.qcmian.clipper.core.ui

import androidx.compose.ui.unit.dp

/** 弹窗的布局常量。 */
object Popup {
    val verticalPadding = 5.dp
    val horizontalPadding = 5.dp
    val verticalSeparatorPadding = 6.dp
    val horizontalSeparatorPadding = 6.dp

    /** 原为 4dp（macOS 26 以下）；为与设置页统一改为 10dp。 */
    val cornerRadius = 10.dp

    /** macOS 26 以下版本的 `Popup.itemHeight`。 */
    val itemHeight = 22.dp

    /** `Popup.minimumPreviewHeight`：让弹窗保持足够高，以便预览可用。 */
    val minimumPreviewHeight = 150.dp

 /** 预览滑出面板的宽度。 */
    val contentWidth = 450.dp
    val minimumContentWidth = 200.dp
    val previewWidth = 400.dp
    val minimumPreviewWidth = 200.dp
}
