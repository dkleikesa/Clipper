package com.qcmian.clipper.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 滚动条宽度；调用方需要为它预留空间时用这个值。 */
val VerticalScrollbarWidth = 10.dp

/** 滑块的最小高度：内容极长时也要留出可抓取的长度。 */
private const val ThumbMinHeight = 36f

/** 滑块的像素几何：顶端 [top]、高度 [height]，均在轨道坐标系内。 */
internal data class ThumbGeometry(val top: Float, val height: Float)

/** 滑块高度：轨道高 × 可视高 / 内容高，并夹在 [ThumbMinHeight] 与轨道高之间。 */
internal fun thumbHeightFor(trackHeight: Float, viewportHeight: Float, contentHeight: Float): Float =
    (trackHeight * viewportHeight / contentHeight)
        .coerceIn(ThumbMinHeight.coerceAtMost(trackHeight), trackHeight)

/**
 * 滑块顶端落在轨道的 [thumbTop] 处时，内容应当滚动到的像素位置。
 *
 * 与滑块几何的换算严格互逆，因此拖动时滑块与手指 1:1 跟随。轨道或内容没有可滚动空间时
 * 返回 `null`。
 */
internal fun scrollOffsetForThumbTop(
    thumbTop: Float,
    thumbHeight: Float,
    trackHeight: Float,
    maxScroll: Float,
): Float? {
    val travel = trackHeight - thumbHeight
    if (travel <= 0f || maxScroll <= 0f) return null
    return (thumbTop / travel).coerceIn(0f, 1f) * maxScroll
}

/**
 * 滚动条与滚动容器之间的桥：只有两件事——「滑块现在在哪」与「滚到某个滑块位置」。
 *
 * 手势与绘制由 [ScrollbarTrack] 统一实现，因此 [ScrollState] 与 `LazyListState` 两种容器
 * 只需各自提供实现，交互与观感必然一致。
 */
internal interface ScrollbarTarget {
    /** 轨道高度为 [trackHeight] 时滑块的几何；内容放得下或尚未布局时为 `null`。 */
    fun thumb(trackHeight: Float): ThumbGeometry?

    /** 把滑块顶端移到轨道的 [thumbTop] 像素处。 */
    fun scrollTo(scope: CoroutineScope, trackHeight: Float, thumbTop: Float)
}

/**
 * 竖向滚动条（自绘 overlay）。
 *
 * Compose 的滚动容器默认不画滚动条，各平台的公共 API 也没有滚动条构件（桌面端的
 * `VerticalScrollbar` 只在 JVM 源集可用），因此这里直接按 [ScrollState] 的数值自己画一条。
 *
 * 内容放得下（[ScrollState.maxValue] 为 0）时整条隐藏，连点击热区一起消失；内容超长时
 * 才会出现。交互与系统滚动条一致：按住滑块拖动时保持按下瞬间的相对位置（1:1 跟手），
 * 按住轨道空白处则把滑块中心移过去，等同于点击跳转。
 */
@Composable
fun VerticalScrollbar(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
) {
    // 只在「能否滚动」这一位变化时重组，普通滚动不会触发。
    val scrollable by remember(scrollState) {
        derivedStateOf { scrollState.maxValue > 0 }
    }
    if (!scrollable) return

    val target = remember(scrollState) { ScrollStateTarget(scrollState) }
    ScrollbarTrack(target, modifier)
}

/**
 * 滚动条本体：拖拽手势与滑块绘制。两种滚动容器共用，差异全部由 [target] 承担。
 */
@Composable
internal fun ScrollbarTrack(target: ScrollbarTarget, modifier: Modifier = Modifier) {
    val thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    val scope = rememberCoroutineScope()
    Canvas(
        modifier
            .width(VerticalScrollbarWidth)
            .pointerInput(target) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    val trackHeight = size.height.toFloat()
                    val geometry = target.thumb(trackHeight)
                    val grab = when {
                        // 抓住滑块：记住手指相对滑块顶端的位置，拖动全程保持这个关系才会跟手。
                        geometry != null &&
                            down.position.y in geometry.top..(geometry.top + geometry.height) ->
                            down.position.y - geometry.top
                        // 按在轨道空白处：把滑块中心对到手指。
                        geometry != null -> geometry.height / 2f
                        else -> 0f
                    }
                    target.scrollTo(scope, trackHeight, down.position.y - grab)

                    var lastY = down.position.y
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        if (change.position.y != lastY) {
                            lastY = change.position.y
                            target.scrollTo(scope, trackHeight, lastY - grab)
                        }
                    }
                }
            },
    ) {
        val geometry = target.thumb(size.height) ?: return@Canvas
        // 滑块画满整个轨道宽度（不再是细条居中）。
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(0f, geometry.top),
            size = Size(size.width, geometry.height),
            cornerRadius = CornerRadius(size.width / 2f),
        )
    }
}

/**
 * [ScrollState] 的滚动条适配。
 *
 * [ScrollState] 的取值域是 `0..maxValue`，`maxValue` 即「内容高 − 可视高」，
 * 因此内容高为 `viewportSize + maxValue`，滑块长度与轨道长度之比就等于两者之比。
 */
private class ScrollStateTarget(private val scrollState: ScrollState) : ScrollbarTarget {

    override fun thumb(trackHeight: Float): ThumbGeometry? {
        val maxValue = scrollState.maxValue
        if (trackHeight <= 0f || maxValue <= 0) return null

        val viewport = scrollState.viewportSize.toFloat()
        val contentHeight = viewport + maxValue
        if (contentHeight <= 0f) return null

        val thumbHeight = thumbHeightFor(trackHeight, viewport, contentHeight)
        val fraction = (scrollState.value.toFloat() / maxValue).coerceIn(0f, 1f)
        return ThumbGeometry(top = fraction * (trackHeight - thumbHeight), height = thumbHeight)
    }

    override fun scrollTo(scope: CoroutineScope, trackHeight: Float, thumbTop: Float) {
        val geometry = thumb(trackHeight) ?: return
        val target = scrollOffsetForThumbTop(
            thumbTop = thumbTop,
            thumbHeight = geometry.height,
            trackHeight = trackHeight,
            maxScroll = scrollState.maxValue.toFloat(),
        ) ?: return
        // 每次指针移动都新起一个协程，但不必自己合并：`scrollTo` 内部用 `MutatorMutex`
        // 串行化，后一次调用会取消前一次，最终生效的必然是最后一个目标。
        scope.launch { scrollState.scrollTo(target.toInt()) }
    }
}
