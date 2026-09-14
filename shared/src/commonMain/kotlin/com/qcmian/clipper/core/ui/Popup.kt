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
    val contentWidth = 450.dp
    val minimumContentWidth = 200.dp

    /**
     * 预览滑出面板的最小宽度。
     *
     * 面板高度不作为常量：它永远等于窗口高度（`fillMaxHeight`），窗口高度只由内容决定。
     */
    val minimumPreviewWidth = 200.dp

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
}
