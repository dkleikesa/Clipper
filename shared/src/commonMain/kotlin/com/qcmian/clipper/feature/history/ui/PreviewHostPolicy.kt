package com.qcmian.clipper.feature.history.ui

import androidx.compose.ui.unit.Dp
import com.qcmian.clipper.core.ui.Popup

/**
 * 宿主为预览面板提供的空间约束——界面据此决定预览能不能与主列表并排。
 *
 * 收成一个值而不是几个散落的参数：它们描述的是同一件事（宿主愿意为预览腾出多少空间），
 * 改动时应当一起看，也不至于在 `App` → `HistoryScreen` 之间被拆散转发。
 *
 * 只覆盖桌面端：窗口永远为预览并排加宽（`autoWindowSize`），因此没有「覆盖在列表上」这条
 * 路径。窗口放不下时由宿主按屏幕余量把滑出宽度夹小（见 [maxPreviewWidth]），而不是让预览
 * 盖住列表。
 *
 * 刻意**不**给「窗口是否已为预览让位」留字段：桌面端窗口一次到位（`WINDOW_SLIDE_MILLIS` 为 0），
 * 判「预览该不该在画面上」只需要设置本身——窗口还没宽出来的那几帧卡片整块落在窗口之外，窗口
 * 还没收回的那几帧主列表铺满整窗把它盖住。让宿主再报一个「窗口已就位」的布尔，只会多出一个
 * 会滞后、会残留的真值源（见 `HistoryScreen` 的预览段）。
 *
 * @param onLeft 预览停靠的优先侧（宿主按屏幕空间决定）。
 * @param maxPreviewWidth 当前停靠侧「锚点到屏幕边缘」还能给预览多少宽度（已扣掉分隔条与主
 *   列表）
 */
data class PreviewHostPolicy(
    val onLeft: Boolean = false,
    val maxPreviewWidth: Dp = Popup.maximumPreviewWidth,
)

/**
 * 分隔条能拖到的上限 = min(窗口内剩余空间, 屏幕余量)：
 *
 * - 窗口内剩余空间 = 窗口宽 − 分隔条 − 主列表的**划分下限**（[Popup.minimumSplitContentWidth]，
 *   比窗口自身的下限小，因此默认窗口里也留得出余量）。窗口在拖动期间固定不变，预览变宽只能
 *   挤窄主列表，最多挤到这里；
 * - 屏幕余量由宿主给出（[PreviewHostPolicy.maxPreviewWidth]）：超过它，落盘时会被夹回来
 *   （窗口跟着缩一截）。
 *
 * 只用前者会拖出屏幕放不下的宽度；只用后者，主列表还站在下限上时同样拖不动。
 *
 * 「窗口内剩余空间」用实测的 [windowWidth] 而不是「内容区宽度 + 预览宽度」：两者稳态下相等，
 * 但窗口还没跟上设置的几帧里只有实测值是对的。
 */
internal fun previewMaxDragWidth(host: PreviewHostPolicy, windowWidth: Dp): Dp =
    minOf(
        windowWidth - Popup.previewDividerWidth - Popup.minimumSplitContentWidth,
        host.maxPreviewWidth,
    ).coerceAtLeast(Popup.minimumPreviewWidth)
