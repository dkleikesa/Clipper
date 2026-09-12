package com.qcmian.clipper.feature.history.ui.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.ui.Popup
import kotlin.math.roundToInt

/** 可拖拽预览宽度的上限，实际限制。 */
private const val PreviewMaxWidth = 900

/**
 * 对应 `SlideoutView` + `SlideoutController.startResize(.slideout)`：带可拖拽分隔条的预览面板，
 * 默认停靠在右侧；当弹窗旁边放不下时改为停靠在左侧。
 */
@Composable
fun PreviewSlideout(
    item: ClipItem?,
    appIconBase64: String?,
    previewWidth: Int,
    onLeft: Boolean,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onCopyExtractedText: () -> Unit,
    onWidthChange: (Int) -> Unit,
) {
    if (!onLeft) PreviewDivider(previewWidth, onLeft, onWidthChange)

    PreviewPane(
        item = item,
        appIconBase64 = appIconBase64,
        onTogglePin = onTogglePin,
        onDelete = onDelete,
        onCopyExtractedText = onCopyExtractedText,
        modifier = Modifier.width(previewWidth.dp).fillMaxHeight(),
    )

    if (onLeft) PreviewDivider(previewWidth, onLeft, onWidthChange)
}

/** 拖动时把宽度写回 `Defaults[.previewWidth]` 的分隔条。 */
@Composable
private fun PreviewDivider(currentWidth: Int, onLeft: Boolean, onWidthChange: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .padding(vertical = 16.dp)
            .padding(horizontal = Popup.horizontalPadding)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    // 把分隔条往列表方向拖，总是让预览变小。
                    val signed = if (onLeft) -delta else delta
                    val next = currentWidth - signed / density.density
                    val clamped = next.roundToInt().coerceIn(
                        Popup.minimumPreviewWidth.value.toInt(),
                        PreviewMaxWidth,
                    )
                    if (clamped != currentWidth) onWidthChange(clamped)
                },
            ),
    ) {
        VerticalDivider(color = colors.outline.copy(alpha = 0.5f))
    }
}
