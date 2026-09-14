package com.qcmian.clipper.desktop.domain

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.ui.Popup
import kotlin.math.abs

/** 面板的初始高度，与 `HistoryScreen` 的测量起点一致。 */
val InitialPanelHeight: Dp = 400.dp

/** 应用自己调整窗口尺寸后，忽略尺寸通知的时长。 */
internal const val RESIZE_SETTLE_MILLIS = 250L

/** 区分用户拖动与应用设定的尺寸时的容差，单位为 dp。 */
internal const val RESIZE_TOLERANCE_DP = 2f

/**
 * 对应 `SlideoutController.computeSizeWithPreview` + `FloatingPanel.windowWillResize`：
 * 宽度是内容宽度（预览打开时再加上滑出宽度），高度随内容增长但停在屏幕可用区域底部，
 * 因此面板永远不会超出屏幕或压到 Dock。
 */
internal fun autoWindowSize(
    settings: AppSettings,
    previewOpen: Boolean,
    preferredHeight: Dp,
    top: Int,
): DpSize {
    // 自定义宽度只代表主列表（内容区）宽度；预览打开时窗口要在它之外再容纳滑出面板，
    // 否则窗口不够宽，预览会退化成盖在列表上的浮层。
    val width = contentWidthOf(settings) + if (previewOpen) slideoutWidthOf(settings) else 0.dp

    val bounds = screenBounds(settings.popupScreen)
    val spaceToBottom = (bounds.y + bounds.height - top).coerceAtLeast(0)
    val height = settings.customWindowHeight?.dp ?: preferredHeight
    return DpSize(width, height.coerceAtMost(spaceToBottom.dp))
}

/** 主列表（内容区）宽度：用户拖出的自定义宽度优先，但不小于下限。 */
internal fun contentWidthOf(settings: AppSettings): Dp =
    (settings.customWindowWidth?.dp ?: Popup.contentWidth).coerceAtLeast(Popup.minimumContentWidth)

/**
 * 预览滑出面板占用的总宽度：面板本身加上与主列表之间的分隔条。
 *
 * 与界面共用 [Popup.slideoutWidth]：界面用同一个值判断「窗口是否已经宽到能与主列表并排」，
 * 两处一旦不一致，窗口加宽之后主列表就会被挤掉分隔条那一条（表现为打开预览时列表宽度变窄、
 * 文字重新折行）。
 */
internal fun slideoutWidthOf(settings: AppSettings): Dp =
    Popup.slideoutWidth(settings.previewWidth)

/** 两个尺寸在 [RESIZE_TOLERANCE_DP] 之内一致时返回 `true`，忽略亚像素舍入。 */
internal fun DpSize.nearlyEquals(other: DpSize): Boolean =
    abs(width.value - other.width.value) <= RESIZE_TOLERANCE_DP &&
        abs(height.value - other.height.value) <= RESIZE_TOLERANCE_DP
