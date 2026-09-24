package com.qcmian.clipper.feature.history.ui

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.qcmian.clipper.core.ui.Popup

/**
 * 预览卡的揭示动画容器。
 *
 * 揭示进度 [reveal] 是动画状态，展示 / 收起期间逐帧变化。把它圈在这个小组件里（而不是
 * `HistoryScreen` 顶层），动画期间就只重组这个轻量的 `Box`——列表、度量、`fold` 都不会
 * 跟着每帧重算；[content]（[PreviewSlideout]）的参数在动画期间不变，因此能整体跳过。
 *
 * 只有 graphicsLayer 的平移每帧读取 [reveal]（那是绘制阶段，本就只重绘不重组）。
 */
@Composable
internal fun BoxScope.PreviewSlideoutHost(
    previewOpen: Boolean,
    slideoutWidth: Dp,
    onLeft: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val slideoutPx = with(LocalDensity.current) { slideoutWidth.toPx() }
    val reveal by animateFloatAsState(
        targetValue = if (previewOpen) 1f else 0f,
        animationSpec = tween(Popup.previewRevealMillis, easing = EaseInOutCubic),
        label = "previewReveal",
    )
    // 刚关上时卡片要留到动画走完（宿主同样等动画走完才缩窗口），归零后自然为假、整块消失。
    if (!previewOpen && reveal <= 0f) return
    Box(
        Modifier
            .requiredWidth(slideoutWidth)
            .align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd)
            .fillMaxHeight()
            // 往主列表那一侧平移「还没揭示的宽度」：reveal = 0 时整块卡在列表下面，= 1 时归位。
            .graphicsLayer {
                val hidden = (1f - reveal) * slideoutPx
                translationX = if (onLeft) hidden else -hidden
            }
            .background(colors.background),
    ) {
        content()
    }
}
