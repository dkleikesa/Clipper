package com.qcmian.clipper.core.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 弹窗的布局常量。 */
object Popup {
    val verticalPadding = 8.dp
    val horizontalPadding = 8.dp
    val verticalSeparatorPadding = 6.dp
    val horizontalSeparatorPadding = 6.dp

    /** 原为 4dp（macOS 26 以下）；为与设置页统一改为 10dp。 */
    val cornerRadius = 10.dp

    /** macOS 26 以下版本的 `Popup.itemHeight`。 */
    val itemHeight = 22.dp

    /** 主面板内容区的宽度（预览滑出面板不含在内）。 */
    val contentWidth = 400.dp

    /**
     * 内容区（滑动列表）允许的最小宽度，直接取**默认宽度**：手动调整只能变宽、不能变窄
     * （更窄的面板体验太差）。
     *
     * 拖拽由框架读 `window.minimumSize` 拦下；预览并排时窗口还要再给它加上滑出面板那一段。
     * 注意它约束的是**窗口本身**的下限，预览分隔条的划分用的是更小的
     * [minimumSplitContentWidth]。
     */
    val minimumContentWidth = contentWidth

    /**
     * 预览并排时，主列表在**划分**里允许的最小宽度。
     *
     * 必须小于 [minimumContentWidth]：主列表正好站在 [minimumContentWidth] 上时（默认窗口、"恢复
     * 默认尺寸"之后都是这种状态），它一点宽度都让不出来，分隔条的拖动上限会恰好等于当前预览
     * 宽度——一次都拖不动。让出来的那一段留在同一个窗口里（窗口总宽不变），预览因此可以按用户
     * 的意愿变宽，主列表收窄到这里为止。
     */
    val minimumSplitContentWidth = 300.dp

    /**
     * 主列表（内容区）的宽度：用户拖出的自定义宽度优先，但不小于 [minimumSplitContentWidth]。
     *
     * 预览分隔条拖动时，界面（`HistoryScreen`）、状态层（`ClipboardViewModel`）与宿主的窗口几何
     * 都要用它算「主列表还有多宽」：几处必须用同一个值，否则界面停住的位置、落盘的宽度与窗口
     * 尺寸会各差一截。
     */
    fun contentWidthOf(customWindowWidth: Int?): Dp =
        (customWindowWidth?.dp ?: contentWidth).coerceAtLeast(minimumSplitContentWidth)

    /** 内容区至少要放下的条目数：历史很少时面板也不会缩成一条缝。 */
    const val minimumVisibleItems = 6

    /**
     * 内容区（滑动列表，不含置顶区与头部 / 页脚）允许的最小高度，直接取**剪贴板为空时的
     * 默认值**：正好 [minimumVisibleItems] 行。
     *
     * 置顶区与头部 / 页脚都在滑动区之外，不参与这个下限——它们的高度先由 `HistoryScreen`
     * 测出来算进 `chromeHeight`，窗口的最小高度是「chromeHeight + 它」，因此置顶项再多也不会
     * 把滑动区压到下限以下（见 `WindowSizing.minimumWindowSizeOf`）。
     */
    val minimumContentHeight = itemHeight * minimumVisibleItems

    /**
     * 预览滑出面板的最小宽度。
     *
     * 面板高度不作为常量：它永远等于窗口高度（`fillMaxHeight`），窗口高度只由内容决定。
     */
    val minimumPreviewWidth = 200.dp

    /**
     * 预览滑出面板的最大宽度：拖动本身的硬顶。
     *
     * 真正生效的上限通常比它更小——窗口宽度在拖动期间固定不变，预览最多只能把主列表挤到
     * [minimumSplitContentWidth]（见 `HistoryScreen.maxDragWidth`）。到顶之后分隔条就不再
     * 响应拖动，一次回调都不发。
     */
    val maximumPreviewWidth = 900.dp

    /**
     * 预览面板与主列表之间那条可拖拽分隔条的宽度：两侧内边距 + 1dp 分隔线。
     *
     * 桌面端给滑出面板加宽窗口时必须把它算进去（`slideoutWidthOf`），否则窗口比并排布局需要
     * 的宽度少这一条，主列表会被压窄 17dp。分隔线粗细与 `PreviewDivider` 里的
     * `VerticalDivider` 保持一致。
     */
    val previewDividerWidth = horizontalPadding * 2 + 1.dp

    /**
     * 预览滑出面板占用的总宽度：面板本身加上与主列表之间的分隔条。
     *
     * 桌面端按它给窗口加宽（`slideoutWidthOf`），界面按它判断「窗口是否已经宽到能与主列表
     * 并排」，两处必须一致——否则窗口加宽之后主列表会被挤掉分隔条那一条。
     */
    fun slideoutWidth(previewWidth: Int): Dp =
        previewWidth.dp.coerceAtLeast(minimumPreviewWidth) + previewDividerWidth

    /** 预览滑出面板的最小占位宽度（面板下限 + 分隔条）：比它还小的空间就算「放不下预览」。 */
    val minimumSlideoutWidth = minimumPreviewWidth + previewDividerWidth
}
