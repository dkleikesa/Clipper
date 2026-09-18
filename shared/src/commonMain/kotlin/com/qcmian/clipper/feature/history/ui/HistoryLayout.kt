package com.qcmian.clipper.feature.history.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.ui.Popup

/** 高度估算的安全余量，见 [HistoryHeightMetrics.preferredHeight] 的注释。 */
internal val HeightSlack = 4.dp

/**
 * 列表 `contentPadding` 中落在条目之下的部分：列表底部内边距 + 置顶分隔线的间距。
 *
 * 窗口高度如果只补到「条目总高」，列表视口就比内容少这一条，`canScrollForward` 会一直是
 * true——表现为**内容明明全部放得下，右侧滚动条却始终显示**，还能滚几个像素。
 * 补上它，内容放得下时窗口才真正装得下全部内容。
 */
internal val ListPaddingBelowItems = Popup.verticalSeparatorPadding + Popup.verticalSeparatorPadding - 1.dp

/**
 * 窗口与列表的高度推导结果。
 *
 * 条目高度是**精确值**，不是估算（见 `historyRowHeight`）：因此这里没有「实测覆盖」的兜底，
 * 窗口高度是内容的纯函数。
 */
internal data class HistoryHeightMetrics(
    /** 列表 `contentPadding` 落在条目之下的部分。 */
    val listBottomPadding: Dp,
    /** 未置顶条目的总高。 */
    val itemsHeight: Dp,
    /** 滑动区（条目 + 上下内边距）的总高。 */
    val listHeight: Dp,
    /** 滑动区之外的固定区块：头部、置顶区、页脚。 */
    val chromeHeight: Dp,
    /** 窗口的下限高度：滑动区下限加上 [chromeHeight]。 */
    val minimumHeight: Dp,
    /** 内容希望得到的高度：贴合内容。 */
    val preferredHeight: Dp,
)

/**
 * 由各区块的实测高度与条目总高推导窗口高度。
 *
 * 对应 `Popup.suitableHeight(for:)` + `Popup.preferredHeight(for:)`。
 *
 * - `minimumHeight`：滑动区（内容区）至少 [Popup.minimumContentHeight]——也就是剪贴板为空时的
 *   默认值，因此历史很少时窗口也不会缩成一条缝。置顶区与头部 / 页脚都在滑动区之外，先由
 *   [chromeHeight] 计入，所以置顶项再多也只是把窗口顶高，不会吃掉滑动区的高度。
 * - `preferredHeight`：内容高度已经是精确值，这里只吸收 dp↔px 的取整：AWT 窗口尺寸按整数点
 *   应用，而内容高可能带小数，差一点点就会让列表「差一点装得下」——残留一小段可滚动区间和一
 *   截滚动条。留一点余量，内容本该放得下时窗口总是略高于内容。
 *
 * 预览不参与这里——预览面板的高度恒等于窗口高度（`fillMaxHeight`），打开或关闭预览都不会改变
 * 窗口尺寸，也就不会出现「开预览时窗口突然长高」的跳动。
 */
internal fun historyHeightMetrics(
    itemsHeight: Dp,
    headerHeight: Dp,
    topPinsHeight: Dp,
    bottomPinsHeight: Dp,
    footerHeight: Dp,
    pinsAtTop: Boolean,
    pinsSeparator: Boolean,
): HistoryHeightMetrics {
    val listBottomPadding = if (!pinsAtTop && pinsSeparator) {
        Popup.verticalSeparatorPadding
    } else {
        Popup.verticalSeparatorPadding - 1.dp
    }
    val listHeight = itemsHeight + Popup.verticalSeparatorPadding + listBottomPadding
    val chromeHeight = headerHeight + topPinsHeight + bottomPinsHeight + footerHeight
    val suitableHeight = listHeight + chromeHeight
    val minimumHeight = (chromeHeight + Popup.minimumContentHeight)
        .coerceAtLeast(headerHeight + Popup.verticalPadding)
    val preferredHeight = (suitableHeight + ListPaddingBelowItems + HeightSlack)
        .coerceAtLeast(minimumHeight)
    return HistoryHeightMetrics(
        listBottomPadding = listBottomPadding,
        itemsHeight = itemsHeight,
        listHeight = listHeight,
        chromeHeight = chromeHeight,
        minimumHeight = minimumHeight,
        preferredHeight = preferredHeight,
    )
}
