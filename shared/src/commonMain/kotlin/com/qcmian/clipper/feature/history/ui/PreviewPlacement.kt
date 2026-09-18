package com.qcmian.clipper.feature.history.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.ui.Popup

/** 低于此宽度时，预览面板改为覆盖在列表上，而不是并排显示。 */
internal val OverlayThreshold = 700.dp

/** 宽度比较容差，吸收 dp↔px 取整。 */
internal val WidthTolerance = 2.dp

/**
 * 宿主为预览面板提供的空间约束——界面据此决定预览能不能与主列表并排。
 *
 * 收成一个值而不是三个散落的布尔参数：三者描述的是同一件事（宿主愿意为预览腾出多少空间），
 * 改动时应当一起看，也不至于在 `App` → `HistoryScreen` 之间被拆散转发。
 *
 * @param onLeft 预览停靠的优先侧（宿主按屏幕空间决定）。
 * @param overlays 两侧都放不下：预览只能覆盖在列表上，窗口尺寸不变。
 * @param expandsWindow 宿主会在预览打开时为它加宽窗口（桌面端）。固定尺寸窗口（手机等）为
 *   `false`，此时窗口本身够宽就并排，否则退回覆盖层。
 */
data class PreviewHostPolicy(
    val onLeft: Boolean = false,
    val overlays: Boolean = false,
    val expandsWindow: Boolean = false,
    /**
     * 宿主已经在窗口里为预览让出位置（那次加宽 / 收回的几何**已经应用**）。
     *
     * 桌面端加宽窗口是异步的，界面要等它到位再让预览进场（[PreviewPlacement.WAITING_FOR_RESIZE]）。
     * 这个信号必须由宿主给出，**不能**让界面拿量出来的窗口宽度去猜：实测值要等下一次布局才
     * 更新，比窗口本身慢一帧——预览收起那一帧窗口已经收窄、界面却还认为放得下，卡片就会画在
     * 已经变窄的窗口里（看起来是主面板闪了一下预览内容）。
     *
     * 固定尺寸窗口没有「加宽」这回事，保持 `false` 即可。
     */
    val windowReady: Boolean = false,
    /**
     * 当前停靠侧「锚点到屏幕边缘」还能给预览多少宽度（已扣掉分隔条与主列表）。
     *
     * 分隔条的拖动上限取它与「窗口内剩余空间」的**较小值**：窗口内的空间决定能不能在窗口内
     * 重新分配，屏幕余量决定这个宽度会不会在落盘时被宿主夹回来。只看前者，贴边的窗口会允许拖出
     * 一个屏幕放不下的宽度（松手被夹回、窗口反而缩一截）；只看后者，主列表还站在下限上时也一样
     * 拖不动。
     */
    val maxPreviewWidth: Dp = Popup.maximumPreviewWidth,
)

/**
 * 预览面板的落位。
 *
 * 同一时刻只会命中一种落位，预览的进场动画因此只会跑一次——不会出现「先盖在列表上闪一下、
 * 再被并排面板取代」。
 */
internal enum class PreviewPlacement {
    /** 预览未打开。 */
    HIDDEN,

    /**
     * 预览已打开，但窗口还没为它让出位置（正在加宽）。
     *
     * 与 [HIDDEN] 分开：两者在界面上都不画卡片，但成因不同——这里是「再等一等」，加宽到位后
     * 会切到 [DOCK_LEFT] / [DOCK_RIGHT]。
     */
    WAITING_FOR_RESIZE,

    /** 与主列表并排，停在列表左侧。 */
    DOCK_LEFT,

    /** 与主列表并排，停在列表右侧。 */
    DOCK_RIGHT,

    /** 窗口放不下并排布局（手机等固定尺寸窗口）：覆盖在列表上。 */
    OVERLAY,
}

/**
 * 由「预览是否打开」「宿主给了多少空间」与「窗口是否已经为预览让出位置」推出预览的落位。
 *
 * [docked] 表示窗口已经宽到能与主列表并排。桌面端（[PreviewHostPolicy.expandsWindow]）窗口
 * 加宽是异步的：界面先看到「预览已打开」，宿主随后才把窗口加宽，中间会有一两帧窗口仍然很窄。
 * 若此刻就把预览画出来，它会先以覆盖层盖住列表、等窗口加宽后又被并排面板取代——一次打开闪
 * 两下，而且这两帧里主列表会被压窄。因此加宽完成之前先不显示
 * （[PreviewPlacement.WAITING_FOR_RESIZE]）。
 *
 * 固定尺寸窗口没有「加宽」这回事：窗口本身够宽就并排，否则退回覆盖层。
 */
internal fun previewPlacement(
    previewOpen: Boolean,
    host: PreviewHostPolicy,
    docked: Boolean,
): PreviewPlacement = when {
    !previewOpen -> PreviewPlacement.HIDDEN
    docked -> if (host.onLeft) PreviewPlacement.DOCK_LEFT else PreviewPlacement.DOCK_RIGHT
    host.overlays -> PreviewPlacement.OVERLAY
    // 宿主正在为预览加宽窗口：等加宽完成再让预览进场。
    host.expandsWindow -> PreviewPlacement.WAITING_FOR_RESIZE
    else -> PreviewPlacement.OVERLAY
}

/**
 * 预览槽位的几何判定：窗口能不能并排、槽位要不要先占住、分隔条能拖多宽。
 *
 * 三者由同一组输入推出，改动时应当一起看，因此收成一个值。
 */
internal data class PreviewSlotMetrics(
    /** 窗口已经宽到能与主列表并排。 */
    val docked: Boolean,
    /**
     * 槽位「已经让出来、但还没被卡片占住」的那几帧。
     *
     * - 打开时：窗口正在加宽，卡片还没进场；
     * - 收起时：卡片已经移除，窗口还没收回来——窗口比主列表宽出来的那一段正是残留的槽位。
     *
     * 这几帧要把主列表的宽度钉在打开前的值，否则它会先占满整窗（文字重排、滚动条跟着跑）再被
     * 窗口收回来——一次收起闪两下。
     *
     * 宽度差只在「恰好一块滑出面板」时才算残留槽位：别的差值只是两者尚未同步，据此钉住列表会
     * 把它永久留在旧宽度上。
     *
     * 判据用「窗口比**内容区**宽出多少」，而不是「窗口比主列表宽出多少」：主列表被钉住的那几帧
     * 里测出来的正是那个钉住的宽度，拿它去比会自我印证——差值一旦落在容差里就再也解不开，表现
     * 为预览收起后主列表右侧永远留一条空白（窗口已经收回来了，这里却还认为槽位没让出）。内容区
     * 宽度取自设置，与钉不钉住无关，因此窗口一收窄，这个差值必定落到 0。
     *
     * 覆盖层占位不走这条路：那种情况窗口尺寸不变，列表照旧跟随窗口。
     *
     * 拖动分隔条期间**不**算槽位空着：那时窗口一帧都不动（`docked` 恒为真），预览变宽挤窄的本来
     * 就该是主列表，钉住反而会让两个面板一起溢出窗口。
     */
    val slotReserved: Boolean,
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
     */
    val maxDragWidth: Dp,
)

/**
 * 由「预览是否打开」「宿主约束」与两个实测宽度推出槽位几何。
 *
 * 「窗口内剩余空间」用实测的 [windowWidth] 而不是「内容区宽度 + 预览宽度」：两者稳态下相等，
 * 但窗口还没跟上设置的几帧里只有实测值是对的。
 */
internal fun previewSlotMetrics(
    previewOpen: Boolean,
    host: PreviewHostPolicy,
    windowWidth: Dp,
    listWidth: Dp,
    contentWidth: Dp,
    slideoutWidth: Dp,
): PreviewSlotMetrics {
    val docked = when {
        !previewOpen -> false
        // 固定尺寸窗口（手机）：窗口本身够宽才并排，否则退回覆盖层。
        !host.expandsWindow -> windowWidth >= OverlayThreshold
        // 桌面端：窗口有没有让出位置，由宿主说了算（见 [PreviewHostPolicy.windowReady]）。
        // 它和那次加宽 / 收回是同一次计算的产物，因此不会落后窗口一帧。
        //
        // 刻意**不**用界面量到的窗口宽度（`windowWidth`）判断：那是上一帧的测量值。窗口收起是
        // 宿主在同一瞬间用原生调用完成的，界面却要在下一帧才知道——照实测值判断，收起的那一帧
        // 会认为「还放得下」，把卡片画在已经变窄的窗口里（主面板闪一下预览内容）。
        else -> host.windowReady
    }
    val leftoverSlot = windowWidth - contentWidth
    val slotLeftOver = leftoverSlot > WidthTolerance &&
        leftoverSlot <= slideoutWidth + WidthTolerance
    val slotReserved = host.expandsWindow && !host.overlays &&
        listWidth > 0.dp && !docked && (previewOpen || slotLeftOver)
    val maxDragWidth = minOf(
        windowWidth - Popup.previewDividerWidth - Popup.minimumSplitContentWidth,
        host.maxPreviewWidth,
    ).coerceAtLeast(Popup.minimumPreviewWidth)
    return PreviewSlotMetrics(
        docked = docked,
        slotReserved = slotReserved,
        maxDragWidth = maxDragWidth,
    )
}
