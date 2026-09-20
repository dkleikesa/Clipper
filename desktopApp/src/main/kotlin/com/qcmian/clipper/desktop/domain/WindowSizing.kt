package com.qcmian.clipper.desktop.domain

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.ui.Popup
import java.awt.Rectangle
import kotlin.math.abs

/** 面板的初始高度，与 `HistoryScreen` 的测量起点一致。 */
val InitialPanelHeight: Dp = 400.dp

/** 应用自己调整窗口尺寸后，忽略尺寸通知的时长。 */
internal const val RESIZE_SETTLE_MILLIS = 250L

/** 区分用户拖动与应用设定的尺寸时的容差，单位为 dp。 */
internal const val RESIZE_TOLERANCE_DP = 2f

/**
 * 「程序自己应用过的尺寸」保留的条数。
 *
 * 尺寸通知会滞后于程序的最新一次设定：预览打开 / 收起、内容高度变化、偏好改动都可能在几帧内
 * 连着改几次几何，第 N 次的尺寸通知常常在第 N+1 次之后才到。只记住最后那一个值，这些自己造成
 * 的通知就会被判成「用户在拖窗口边缘」，因此要留一小段历史（够覆盖那几帧的滞后即可）。
 */
internal const val APPLIED_SIZE_HISTORY = 8

/**
 * 对应 `SlideoutController.computeSizeWithPreview` + `FloatingPanel.windowWillResize`：
 * 宽度是内容宽度（预览打开时再加上滑出宽度），高度由内容决定（自动模式）或取自自定义高度，
 * 再用「锚点下方还剩多少」截一刀，因此面板永远从光标处向下长、不会越过屏幕底边。
 *
 * 截的必须是**锚点**（跟随时是光标、挂菜单栏时是图标）到屏幕底边的距离，不是窗口当前的 y：
 * 后者会随窗口自身的摆放变化——窗口一矮，位置下移，下方空间更小，于是更矮（反向则越变越高），
 * 形成只减不增 / 只增不减的棘轮，内容变多或变少都纠正不回来。锚点在面板显示期间是固定的，
 * 不存在这条反馈回路。
 *
 * 锚点贴屏幕底边时下方只剩几十 dp，面板会没法用，因此下限取 [minimumHeight]（「剪贴板为空」
 * 那一档）；这样算出来的窗口会伸到屏幕外，由 `constrained` 负责把它**上移**——移动窗口而不是
 * 继续压缩它，也是原生 `constrainFrameRect` 的语义。
 */
internal fun autoWindowSize(
    settings: AppSettings,
    /** 实际给预览让出的宽度；`null` 表示预览关着（桌面端永远并排，放不下时夹小宽度而非覆盖）。 */
    slideoutWidth: Dp?,
    preferredHeight: Dp,
    minimumHeight: Dp,
    anchorY: Int,
    bounds: Rectangle,
): DpSize {
    // 自定义宽度只代表主列表（内容区）宽度；预览并排时窗口要在它之外再容纳滑出面板，
    // 否则窗口不够宽，预览会退化成盖在列表上的浮层。
    val width = contentWidthOf(settings) + (slideoutWidth ?: 0.dp)

    val available = (bounds.y + bounds.height - anchorY).dp
        .coerceAtLeast(minimumHeight)
        .coerceAtMost(bounds.height.dp)

    val height = contentHeightOf(settings, preferredHeight, minimumHeight).coerceAtMost(available)
    return DpSize(width, height)
}

/** 主列表（内容区）宽度：用户拖出的自定义宽度优先，但不小于下限。 */
internal fun contentWidthOf(settings: AppSettings): Dp =
    // 与界面、状态层共用同一个算式：预览分隔条的拖动上下限也按它算（见 `Popup.contentWidthOf`）。
    Popup.contentWidthOf(settings.customWindowWidth)

/**
 * 窗口高度：用户拖出的自定义高度优先，但不低于 [minimumHeight]。
 *
 * [minimumHeight] 由界面报上来（滑动区下限 + 置顶区 + 头部 / 页脚，见 `HistoryScreen`），
 * 因此无论窗口多矮，滑动区都留着「剪贴板为空时」那一档高度。
 */
internal fun contentHeightOf(
    settings: AppSettings,
    preferredHeight: Dp,
    minimumHeight: Dp,
): Dp =
    (settings.customWindowHeight?.dp ?: preferredHeight).coerceAtLeast(minimumHeight)

/**
 * 窗口允许的最小尺寸，交给 AWT 的 `window.minimumSize`。
 *
 * 宽度**恒为内容区下限**，不随预览开没开变——这一点是被踩出来的：下限一旦大于当前窗口宽度，
 * 系统会**立刻**把窗口撑到下限，那是一次程序没安排的、时机不受控的原生 resize（预览停靠左侧时
 * 还会带着窗口一起移动），而且它在 Compose 看来跟「用户拖窗口」无法区分，会顺手打开 250ms 的
 * 拖拽静默期。预览并排需要的宽度由几何公式保证（窗口宽 = 内容区 + 滑出宽度），不靠下限兜。
 *
 * 预览开着时用户把窗口拖窄，预览会被压住（卡片按设置宽度画、超出部分被窗口裁掉）；松手落盘时
 * `observeUserResize` 会按「整窗宽度 − 滑出宽度」重算内容区宽度，窗口随即被补回并排所需的宽度。
 *
 * 高度下限直接取 [minimumHeight]——它已经把置顶区与头部 / 页脚算进去了，置顶项再多也不会挤掉
 * 滑动区那几行。
 */
internal fun minimumWindowSizeOf(minimumHeight: Dp): DpSize =
    DpSize(width = Popup.minimumContentWidth, height = minimumHeight)

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
