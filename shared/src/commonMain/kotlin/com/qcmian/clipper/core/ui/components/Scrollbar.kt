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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** 滚动条宽度；调用方需要为它预留空间时用这个值。 */
val VerticalScrollbarWidth = 10.dp

/**
 * 滑块的最小高度：内容极长时也要留出一点看得见、抓得住的长度。
 *
 * 它直接决定拖拽手感，取值有两条约束，缺一个都会出问题：
 *
 * 1. **必须是 dp，而且必须是「绝对」下限**。裸像素在 Retina 上只剩一半：原先的 `12f` 画出来
 *    只有 6pt，几乎只是一点；而只要下限还跟着轨道一起缩，矮窗口里它同样会小到看不见——
 *    两种情况都等于没设。因此这里给的是固定的视觉高度。
 * 2. **但不能把轨道吃光**。滑块占满轨道就没有行程、拖不动了，所以下限本身还要再受
 *    [ThumbMinHeightTrackRatio] 约束。那个约束只在轨道矮到放不下这个下限时才该生效——
 *    取严了就会反过来把下限抹掉（见该常量的说明）。
 *
 * 内容极长时滑块会缩到接近这个下限，精确抓取改由 [ScrollbarHitPadding] 的命中区承担，
 * 因此下限只需保证「看得出是滑块」，不必更大。
 */
internal val ScrollbarThumbMinHeight = 18.dp

/**
 * 滑块最小高度最多能占轨道的几分之一：取 `2`，即不超过轨道的一半。
 *
 * 它的唯一职责是给下限兜底——再保底也不能把轨道吃光，否则没有行程、拖不动。取值刻意宽松：
 * 若取得很严（原来的 `8`），矮轨道下 `轨道 / 8` 会比 [ScrollbarThumbMinHeight] 更小，
 * 于是「最小高度」被这条约束悄悄压回去，滑块重新小到看不见，下限形同虚设。
 */
private const val ThumbMinHeightTrackRatio = 2f

/**
 * 滑块命中区的额外尺寸：每侧各让出这么多，命中区因此比画出来的滑块宽、也更高。
 *
 * 视觉尺寸必须严格按比例（见 [ScrollbarThumbMinHeight]），内容极长时滑块会细到只有
 * 二十几像素——可抓取性改由命中区承担，落在滑块上下的这几像素内同样算抓住滑块。
 */
private val ScrollbarHitPadding = 4.dp

/** 滑块的像素几何：顶端 [top]、高度 [height]，均在轨道坐标系内。 */
internal data class ThumbGeometry(val top: Float, val height: Float)

/**
 * 滑块高度：轨道高 × 可视高 / 内容高，并夹在最小高度与轨道高之间。
 *
 * @param minThumbHeight [ScrollbarThumbMinHeight] 的**像素**值。这里是纯函数，拿不到
 *   `LocalDensity`，因此由调用方（两个 `ScrollbarTarget` 的构造点）换算后传进来。
 *
 * 最小高度还会再受「轨道的 [ThumbMinHeightTrackRatio] 分之一」约束，避免滑块把轨道吃光
 * ——那会让行程归零、拖不动（见 [ScrollbarThumbMinHeight]）。该约束只在轨道矮到放不下
 * 这个下限时才生效，因此正常情况下最小高度就是一个固定值。
 */
internal fun thumbHeightFor(
    trackHeight: Float,
    viewportHeight: Float,
    contentHeight: Float,
    minThumbHeight: Float,
): Float {
    val floor = minThumbHeight.coerceAtMost(trackHeight / ThumbMinHeightTrackRatio)
    return (trackHeight * viewportHeight / contentHeight).coerceIn(floor, trackHeight)
}

/**
 * 滑块顶端落在轨道的 [thumbTop] 处时，内容应当滚动到的像素位置。
 *
 * 与滑块几何的换算严格互逆，因此拖动时滑块与手指 1:1 跟随。轨道或内容没有可滚动空间时
 * 返回 `null`。
 *
 * 注意 [thumbHeight] 必须与 [thumbHeightFor] 算出的**同一个**值（含最小高度带来的垫高）：
 * 正向几何与反向落点若各按一套滑块长度换算，两者的行程就不一样长，拖到底会差出一截内容。
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

    val minThumbHeight = with(LocalDensity.current) { ScrollbarThumbMinHeight.toPx() }
    val target = remember(scrollState, minThumbHeight) {
        ScrollStateTarget(scrollState, minThumbHeight)
    }
    ScrollbarTrack(target, modifier)
}

/**
 * 滚动条本体：拖拽手势与滑块绘制。两种滚动容器共用，差异全部由 [target] 承担。
 *
 * 命中区比画出来的滑块宽 [ScrollbarHitPadding]、高 2×[ScrollbarHitPadding]：滑块长度必须严格
 * 按比例（见 [ScrollbarThumbMinHeight]），内容很长时它只有二十几像素，靠命中区才抓得住。
 * 多出来的部分让在列表这一侧——[VerticalScrollbarWidth] 是「调用方要预留多少宽度」，
 * 没有调用方真的预留了，所以加宽命中区只会多压住内容 4dp，观感与原来一致。
 */
@Composable
internal fun ScrollbarTrack(target: ScrollbarTarget, modifier: Modifier = Modifier) {
    val thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    val scope = rememberCoroutineScope()
    val hitPadding = with(LocalDensity.current) { ScrollbarHitPadding.toPx() }
    val drawWidth = with(LocalDensity.current) { VerticalScrollbarWidth.toPx() }
    Canvas(
        modifier
            .width(VerticalScrollbarWidth + ScrollbarHitPadding)
            .pointerInput(target) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    val trackHeight = size.height.toFloat()
                    val geometry = target.thumb(trackHeight)
                    val grab = when {
                        // 抓住滑块：记住手指相对滑块顶端的位置，拖动全程保持这个关系才会跟手。
                        // 命中区在滑块上下各多出 [ScrollbarHitPadding]，所以判定也放宽同样的量。
                        geometry != null &&
                            down.position.y in
                            (geometry.top - hitPadding)..(geometry.top + geometry.height + hitPadding) ->
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
        // 滑块画满滚动条本体的宽度；多出来的命中区让给内容那一侧，不参与绘制。
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset(0f, geometry.top),
            size = Size(drawWidth, geometry.height),
            cornerRadius = CornerRadius(drawWidth / 2f),
        )
    }
}

/**
 * [ScrollState] 的滚动条适配。
 *
 * [ScrollState] 的取值域是 `0..maxValue`，`maxValue` 即「内容高 − 可视高」，
 * 因此内容高为 `viewportSize + maxValue`，滑块长度与轨道长度之比就等于两者之比。
 */
private class ScrollStateTarget(
    private val scrollState: ScrollState,
    /** 滑块的最小高度（像素），见 [thumbHeightFor]。 */
    private val minThumbHeight: Float,
) : ScrollbarTarget {

    override fun thumb(trackHeight: Float): ThumbGeometry? {
        val maxValue = scrollState.maxValue
        if (trackHeight <= 0f || maxValue <= 0) return null

        val viewport = scrollState.viewportSize.toFloat()
        val contentHeight = viewport + maxValue
        if (contentHeight <= 0f) return null

        val thumbHeight = thumbHeightFor(trackHeight, viewport, contentHeight, minThumbHeight)
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
