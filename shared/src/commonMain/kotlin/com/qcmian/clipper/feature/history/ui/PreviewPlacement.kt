package com.qcmian.clipper.feature.history.ui

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
