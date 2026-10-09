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

/**
 * Dock 图标两次切换之间至少隔多久。
 *
 * 见 `ClipperDevToolsWindow` 里那段说明：挨得太近的翻转会让系统留下回收不掉的孤儿图标。
 * 实测 150ms 会留、300ms 不留，取 300ms；首下一律立即生效，只有连按时才会用到这个间隔。
 */
internal const val DOCK_MIN_INTERVAL_MILLIS = 300L

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

/**
 * 开发者工具窗口的首选尺寸。
 *
 * 这个值是被**内容**推出来的，不是拍脑袋定的：左右并排两个编辑区，每个要放得下约 50 个等宽
 * 字符（12sp 实测步进 7.9dp），加上装订线、内边距与 216dp 的侧边栏，1180dp 正好卡在这个位置。
 * 900dp（旧默认）只给得起 32 个字符，连一行稍长的 JSON 都放不下。
 *
 * 高度取 760dp：一屏能看下 30 行左右，同时不至于在 1117dp 高的屏幕上顶天立地。
 */
private val PreferredDevToolsWindowSize = DpSize(1180.dp, 760.dp)

/**
 * 首选尺寸最多占屏幕**可用区域**的这个比例。
 *
 * 取 0.8 而不是 1：小屏（13 寸 Air 的 1440×900）上若按首选值铺开，窗口会几乎顶满，作为
 * 「按快捷键呼出来看一眼」的伴随窗口太重。留两成余量，用户还看得见底下的东西。
 */
private const val DEV_TOOLS_SCREEN_FRACTION = 0.8f

/**
 * 开发者工具窗口该多大：用户拖过就用用户的，否则按屏幕算一个（见 [PreferredDevToolsWindowSize]）。
 *
 * 先按屏幕夹再取用户值，**不能反过来**：用户值是在某块屏幕上拖出来的，换到一块更小的屏幕
 * （或副屏被拔掉）之后那个尺寸可能根本放不下，夹一次才不会开出一个伸出屏幕的窗口。
 *
 * 下限用 [minimumDevToolsWindowSize] 兜底：旧存档里若留着一个比下限还小的值（下限曾经调过），
 * 直接开出来会让布局挤成一团。
 */
internal fun devToolsWindowSizeOf(settings: AppSettings, bounds: Rectangle): DpSize {
    val capped = DpSize(
        width = minOf(PreferredDevToolsWindowSize.width, bounds.width.dp * DEV_TOOLS_SCREEN_FRACTION),
        height = minOf(PreferredDevToolsWindowSize.height, bounds.height.dp * DEV_TOOLS_SCREEN_FRACTION),
    )
    val minimum = minimumDevToolsWindowSize(bounds)
    return DpSize(
        width = (settings.devToolsWindowWidth?.dp ?: capped.width).coerceIn(minimum.width, bounds.width.dp),
        height = (settings.devToolsWindowHeight?.dp ?: capped.height).coerceIn(minimum.height, bounds.height.dp),
    )
}

/**
 * 开发者工具窗口的尺寸下限：再小就摆不下一对编辑区了（用户拖边缩放时由框架据此拦下）。
 *
 * 宽度取 720dp：这是「两个编辑区各约 21 个等宽字符」的位置，已经是能用的边缘——真要更窄，
 * 该由界面把侧边栏收成图标栏去腾地方，而不是让窗口继续缩。
 */
private val DevToolsWindowMinimumSize = DpSize(720.dp, 480.dp)

/** 尺寸下限与屏幕可用区域的交集：屏幕本身比下限还小时（极端缩放）以屏幕为准。 */
internal fun minimumDevToolsWindowSize(bounds: Rectangle): DpSize = DpSize(
    width = minOf(DevToolsWindowMinimumSize.width, bounds.width.dp),
    height = minOf(DevToolsWindowMinimumSize.height, bounds.height.dp),
)

