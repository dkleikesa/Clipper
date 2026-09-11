package com.qcmian.clipper.ui.components

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
import com.qcmian.clipper.data.model.ClipItem
import com.qcmian.clipper.ui.Popup
import kotlin.math.roundToInt

/** Upper bound for the draggable preview width, mirroring Maccy's practical limit. */
private const val PreviewMaxWidth = 900

/**
 * Port of `SlideoutView` + `SlideoutController.startResize(.slideout)`: the preview pane with
 * its draggable divider, docked on the right by default and on the left when there is no room
 * for it next to the popup.
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

/** The divider that stores `Defaults[.previewWidth]` while it is dragged. */
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
                    // Dragging the divider towards the list always shrinks the preview.
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
