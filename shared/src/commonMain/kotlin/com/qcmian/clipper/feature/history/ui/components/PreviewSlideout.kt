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
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.ui.HorizontalResizePointerIcon
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.core.ui.components.HoverTooltip
import kotlin.math.roundToInt

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
    /**
     * 分隔条最多能拖到多宽：窗口宽度在拖动期间固定不变，这就是「窗口里留给预览的空间」——
     * 主列表让到它自己的下限为止，见 `HistoryScreen.maxDragWidth`。
     *
     * 到顶之后 [PreviewDivider] 会把拖动值夹回原值、一次回调都不发，界面停在那里不动：比
     * 「先发出去、再由上层收敛回去」干净，也不会让宽度值虚涨到往回拖要空拖一大段才见效。
     */
    maxDragWidth: Dp = Popup.maximumPreviewWidth,
    onLeft: Boolean,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onCopyExtractedText: () -> Unit,
    /** 拖动中：上报新的预览宽度，由界面直接渲染。**不**写设置，窗口因此一帧都不动。 */
    onWidthChange: (Int) -> Unit,
    /** 松手：界面据此把最终宽度写回设置（与新的主列表宽度一起）。 */
    onWidthChangeFinished: () -> Unit,
) {
    // 面板与分隔条必须交给同一个 `Row` 排版：外层 `AnimatedVisibility` 会把它的所有子节点
    // 都叠在 (0, 0)、再按其中最大的那个定尺寸。直接并列发出这两个子节点，分隔条会画在面板的
    // 左边缘上（停靠左侧时看起来就贴在窗口最左边），而且槽位宽度只剩面板宽度，与桌面端加宽
    // 窗口所用的 `Popup.slideoutWidth` 对不上。
    Row(Modifier.previewSlot()) {
        if (!onLeft) {
            PreviewDivider(previewWidth, maxDragWidth, onLeft, onWidthChange, onWidthChangeFinished)
        }

        PreviewPane(
            item = item,
            appIconBase64 = appIconBase64,
            onTogglePin = onTogglePin,
            onDelete = onDelete,
            onCopyExtractedText = onCopyExtractedText,
            // 面板宽度直接用上层给的值：稳态下它就是设置里的预览宽度，拖动中则是那个还没落盘
            // 的临时宽度。
            //
            // **不能**再拿「窗口此刻放得下多宽」去夹：并排稳态下那个值恰好等于面板当前宽度
            // （主列表用 `weight(1f)` 收走一切剩余空间），夹住之后面板永远长不大。窗口宽度在
            // 拖动期间固定不变，预览变宽挤窄的是主列表，上限由 [maxDragWidth] 给出。
            modifier = Modifier
                .width(previewWidth.dp.coerceAtLeast(Popup.minimumPreviewWidth))
                .fillMaxHeight(),
        )

        if (onLeft) {
            PreviewDivider(previewWidth, maxDragWidth, onLeft, onWidthChange, onWidthChangeFinished)
        }
    }
}

/**
 * 预览槽位（卡片本身，或左侧停靠时先占住的那段 `Spacer`）的宽度上限：**这一帧**的可用宽度减去
 * 内容区下限。
 *
 * 用的是这一帧的布局约束，而不是 `onSizeChanged` 量出来的窗口宽度——后者是**上一帧**的值，
 * 天生慢一拍：窗口已经收回去的那一帧它还说「放得下」，窗口刚加宽的那一帧它又说「放不下」。
 * 靠状态去猜，无论猜哪一边都会有一帧错位。夹在布局里就没有这个问题：
 *
 * - 窗口还没为预览加宽（刚打开）、或已经收回去（刚收起）的那一两帧，槽位被压成 0——卡片画不
 *   出来，主列表也不会被它挤窄；
 * - 窗口宽到位后回落到滑出面板本身的宽度，其余什么都不做。
 *
 * 夹的是**主列表在划分里的下限**（[Popup.minimumSplitContentWidth]，比窗口自身的下限小）而不是
 * 用户自定义的内容区宽度：并排稳态下窗口宽度 = 内容区宽度 + 滑出宽度，拿下限算只会更宽松；
 * 而分隔条拖动时主列表正是往这个下限让位，槽位必须跟着放宽到同一个数，否则拖动会被槽位当场
 * 夹住——预览一帧都不变宽，看起来就是「分隔条拖不动」。
 */
@Composable
internal fun Modifier.previewSlot(): Modifier {
    val minimumSplitContentWidthPx =
        with(LocalDensity.current) { Popup.minimumSplitContentWidth.roundToPx() }
    return layout { measurable, constraints ->
        val slot = (constraints.maxWidth - minimumSplitContentWidthPx)
            .coerceIn(0, constraints.maxWidth)
        val placeable = measurable.measure(constraints.copy(minWidth = 0, maxWidth = slot))
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }
}

/**
 * 拖动改变预览宽度的分隔条。
 *
 * 拖动中只往上抛新的宽度（[onWidthChange]），由界面直接渲染——上层不会写设置，因此窗口
 * 一帧都不动，两个面板只是在这块固定宽度里重新划分。松手时 [onWidthChangeFinished] 才让上层
 * 把最终宽度与新的主列表宽度一次写回设置。
 *
 * 整条 [Popup.previewDividerWidth]（17dp）都参与命中。两侧的内边距只作用在可见的那条线上
 * （见 `VerticalDivider`），**不能**写进这里的 modifier 链：指针输入节点会跟着被缩到中间
 * 那 1dp，结果就是「看得见一条竖线、鼠标却几乎压不中」，悬停提示也不触发。
 *
 * 上限取 [maxWidth]（窗口里留给预览的空间）与 [Popup.maximumPreviewWidth] 的较小值。到顶之后
 * `clamped` 恒等于 [currentWidth]，一次回调都不会发出去——这正是「到极限就拖不动」要的效果。
 * **不能**改用面板渲染用的那个上限（窗口此刻放得下多宽）：并排稳态下它恰好等于面板当前宽度，
 * 拿它当上限会把分隔条当场锁死。
 */
@Composable
private fun PreviewDivider(
    currentWidth: Int,
    maxWidth: Dp,
    onLeft: Boolean,
    onWidthChange: (Int) -> Unit,
    onWidthChangeFinished: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    // 下限兜住 `coerceIn` 的前提（[maxWidth] 理论上不会小于最小宽度，这里只是不让它成为一颗
    // 雷：`coerceIn(200, 小于 200)` 会直接抛异常）。
    val upperBound = minOf(Popup.maximumPreviewWidth, maxWidth).value.toInt()
        .coerceAtLeast(Popup.minimumPreviewWidth.value.toInt())
    // 分隔条是全高的，上下都贴着窗口边缘：气泡锚在上方只会画到窗口外，因此改成锚向预览面板
    // 那一侧，落点正好是窗口的水平 / 垂直中间。
    HoverTooltip(
        text = "拖动调整预览宽度",
        positioning = if (onLeft) TooltipAnchorPosition.Left else TooltipAnchorPosition.Right,
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                // 宽度写死为常量：桌面端按它给窗口加宽（`slideoutWidthOf`），两边必须一致。
                .width(Popup.previewDividerWidth)
                // 指针变成左右箭头：光看一条竖线看不出这里能拖。
                .pointerHoverIcon(HorizontalResizePointerIcon)
                .draggable(
                    orientation = Orientation.Horizontal,
                    // 松手才落盘：拖动中写设置会让宿主每帧重算窗口宽度，整个窗口跟着一帧帧地
                    // 变宽 / 变窄，正是「拖分隔条却动了窗口」的成因。
                    onDragStopped = { onWidthChangeFinished() },
                    state = rememberDraggableState { delta ->
                        // 把分隔条往列表方向拖，总是让预览变小。
                        val signed = if (onLeft) -delta else delta
                        val next = currentWidth - signed / density.density
                        val clamped = next.roundToInt().coerceIn(
                            Popup.minimumPreviewWidth.value.toInt(),
                            upperBound,
                        )
                        if (clamped != currentWidth) onWidthChange(clamped)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            VerticalDivider(
                color = colors.outline.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxHeight()
                    // 线本身上下各留 16dp，与原视觉一致；它只影响绘制，不影响命中。
                    .padding(vertical = 16.dp),
            )
        }
    }
}
