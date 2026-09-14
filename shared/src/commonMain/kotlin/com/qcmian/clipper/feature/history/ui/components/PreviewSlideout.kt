package com.qcmian.clipper.feature.history.ui.components

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
 *
 * 分隔条永远夹在预览面板与主列表之间，也就是**靠主列表的那一侧**：预览停靠在右侧时它在面板
 * 左侧，停靠在左侧时它在面板右侧；[onLeft] 同时决定拖拽方向（往列表方向拖总是让预览变小）。
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
    // 面板与分隔条必须交给同一个 `Row` 排版：外层 `AnimatedVisibility` 会把它的所有子节点
    // 都叠在 (0, 0)、再按其中最大的那个定尺寸。直接并列发出这两个子节点，分隔条会画在面板的
    // 左边缘上（停靠左侧时看起来就贴在窗口最左边），而且槽位宽度只剩面板宽度，与桌面端加宽
    // 窗口所用的 `Popup.slideoutWidth` 对不上。
    Row {
        if (!onLeft) PreviewDivider(previewWidth, onLeft, onWidthChange)

        PreviewPane(
            item = item,
            appIconBase64 = appIconBase64,
            onTogglePin = onTogglePin,
            onDelete = onDelete,
            onCopyExtractedText = onCopyExtractedText,
            // 下限与 `Popup.slideoutWidth` 保持一致，槽位宽度才正好等于桌面端为预览加宽的宽度。
            modifier = Modifier
                .width(previewWidth.dp.coerceAtLeast(Popup.minimumPreviewWidth))
                .fillMaxHeight(),
        )

        if (onLeft) PreviewDivider(previewWidth, onLeft, onWidthChange)
    }
}

/** 拖动时把宽度写回 `Defaults[.previewWidth]` 的分隔条。 */
@Composable
private fun PreviewDivider(currentWidth: Int, onLeft: Boolean, onWidthChange: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxHeight()
            // 宽度写死为常量：桌面端按它给窗口加宽（`slideoutWidthOf`），两边必须一致。
            .width(Popup.previewDividerWidth)
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
