package com.qcmian.clipper.feature.history.ui.components

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.qcmian.clipper.core.ui.components.ScrollbarTarget
import com.qcmian.clipper.core.ui.components.ScrollbarTrack
import com.qcmian.clipper.core.ui.components.ThumbGeometry
import com.qcmian.clipper.core.ui.components.scrollOffsetForThumbTop
import com.qcmian.clipper.core.ui.components.thumbHeightFor
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 未置顶列表的像素高度模型。
 *
 * 每条记录的高度只取决于它自身（文本行固定 `Popup.itemHeight`，图片行固定
 * `imageMaxHeight + ImageRowPadding`），因此可以对整份内容做精确的前缀和推算，而不必再
 * 依赖「可见行的平均高度」——那种估算会随可见集合逐帧变化，估算一变，滑块的长度与位置
 * 就跟着跳。
 *
 * 前缀和给出的「条目顶端偏移」与「滚动像素」严格互逆；滚动条的绘制与拖拽共用同一份数据，
 * 于是手指移动多少、滑块就移动多少。
 */
private class ListHeightModel(private val heights: FloatArray) {
    /** `starts[i]` 是第 i 条顶端相对内容起点的偏移，末项为全部条目的总高。 */
    private val starts = FloatArray(heights.size + 1).also { array ->
        heights.forEachIndexed { index, height -> array[index + 1] = array[index] + height }
    }

    val count: Int get() = heights.size

    /** 全部条目的总高，不含列表的 contentPadding。 */
    val contentHeight: Float get() = starts[starts.size - 1]

    /** 第 [index] 条顶端相对内容起点的偏移；越界时收敛到首尾。 */
    fun startOf(index: Int): Float = starts[index.coerceIn(0, count)]

    /** 距离内容起点 [offset] 像素处对应的条目下标。 */
    fun indexAt(offset: Float): Int {
        if (count == 0) return 0
        var low = 0
        var high = count - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (starts[mid] <= offset) low = mid else high = mid - 1
        }
        return low
    }
}

/**
 * 历史列表右侧的细滚动条（自绘 overlay）：列表不可滚动时整条隐藏（连点击热区一起消失）。
 * 滑块的位置与长度来自 [heights] 的确定性前缀和，绘制与拖拽共用同一套换算（见
 * [ScrollbarTrack]）。
 *
 * @param heights 每条未置顶记录的像素高度，顺序与列表一致。
 * @param contentPadding 列表上下 contentPadding 之和（像素）；它不属于任何一条记录，
 *   因此单独计入内容总高。
 */
@Composable
internal fun HistoryScrollbar(
    state: LazyListState,
    heights: FloatArray,
    contentPadding: Float,
    modifier: Modifier = Modifier,
) {
    // 精确的「可滚动」判定（LazyListState 内部算好，不用估算），只在越过边界时变化，
    // 不会因为普通滚动逐帧重组。
    val scrollable by remember(state) {
        derivedStateOf { state.canScrollForward || state.canScrollBackward }
    }
    if (!scrollable) return

    val target = remember(state, heights, contentPadding) {
        LazyListScrollbarTarget(state, ListHeightModel(heights), contentPadding)
    }
    ScrollbarTrack(target, modifier)
}

/**
 * `LazyListState` 的滚动条适配：`LazyColumn` 不报告内容总高，因此用 [ListHeightModel]
 * 的前缀和代替。
 */
private class LazyListScrollbarTarget(
    private val state: LazyListState,
    private val model: ListHeightModel,
    private val contentPadding: Float,
) : ScrollbarTarget {

    override fun thumb(trackHeight: Float): ThumbGeometry? {
        if (model.count == 0 || trackHeight <= 0f || state.layoutInfo.visibleItemsInfo.isEmpty()) return null

        // 轨道高就是列表的可见高度；可滚动内容 = 条目总高 + 上下的 contentPadding。
        val viewport = trackHeight
        val contentHeight = model.contentHeight + contentPadding
        if (contentHeight <= viewport) return null

        val thumbHeight = thumbHeightFor(trackHeight, viewport, contentHeight)
        val maxScroll = contentHeight - viewport
        val scrolled = model.startOf(state.firstVisibleItemIndex) + state.firstVisibleItemScrollOffset

        // 估算高度与真实布局之间可能有几像素出入，首尾改用 LazyListState 的精确判定兜住，
        // 这样滚到头时滑块一定贴住轨道两端。
        val fraction = when {
            !state.canScrollBackward -> 0f
            !state.canScrollForward -> 1f
            else -> (scrolled / maxScroll).coerceIn(0f, 1f)
        }
        return ThumbGeometry(top = fraction * (trackHeight - thumbHeight), height = thumbHeight)
    }

    override fun scrollTo(scope: CoroutineScope, trackHeight: Float, thumbTop: Float) {
        val geometry = thumb(trackHeight) ?: return
        val maxScroll = model.contentHeight + contentPadding - trackHeight
        val target = scrollOffsetForThumbTop(
            thumbTop = thumbTop,
            thumbHeight = geometry.height,
            trackHeight = trackHeight,
            maxScroll = maxScroll,
        ) ?: return

        val index = model.indexAt(target)
        val offset = (target - model.startOf(index)).roundToInt()
        // 每次指针移动都新起一个协程，但不必自己合并：`scrollToItem` 内部用 `MutatorMutex`
        // 串行化，后一次调用会取消前一次，最终生效的必然是最后一个目标。拖动期间产生的协程数
        // 跟着指针事件走、有界且很小，不值得为此再引入通道或额外的状态。
        scope.launch { state.scrollToItem(index, offset) }
    }
}
