package com.qcmian.clipper.desktop.viewmodel

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.desktop.domain.InitialPanelHeight
import com.qcmian.clipper.desktop.domain.PopupMode

/**
 * 桌面外壳的 UI 状态（下行）：视图（[com.qcmian.clipper.desktop.ui.ClipperWindow]）只读取它。
 *
 * 注意：窗口的尺寸与位置不在这里——它们由 Compose Desktop 的
 * [androidx.compose.ui.window.WindowState] 持有，`WindowGeometryController` 直接读写该对象，
 * 属于与平台 API 的桥接，而非 UI 状态。
 *
 * 它是各控制器（`PanelPresentationController` / `WindowGeometryController` /
 * `GlobalHotKeyController`）共同读写的**唯一**共享状态：控制器之间因此不必互相持有引用，
 * 只通过这里收敛。
 */
data class DesktopShellUiState(
    /** 面板是否显示。启动时隐藏，静默驻留等待热键 / 托盘唤起。 */
    val windowVisible: Boolean = false,

    /** 当前这次可见是否由托盘（点击 / 菜单）触发。热键呼出时托盘不画按下态。 */
    val panelOpenedByTray: Boolean = false,

    /** 内容希望得到的高度（由 `HistoryScreen` 上报）。 */
    val preferredHeight: Dp = InitialPanelHeight,

    /**
     * 窗口的下限高度（由 `HistoryScreen` 上报）：滑动区下限 + 置顶区 + 头部 / 页脚。
     *
     * 手动拖拽时不会低于它。首次上报之前用滑动区的下限兜底，界面第一帧就会纠正。
     */
    val minimumHeight: Dp = Popup.minimumContentHeight,

    /** 预览是否改从左侧滑出（右侧放不下、左侧放得下时）。 */
    val previewOnLeft: Boolean = false,

    /**
     * 当前停靠侧「锚点到屏幕边缘」还能给预览多少宽度（已扣掉分隔条与主列表）。
     *
     * 只作为分隔条的拖动上限报给界面（见 `PreviewHostPolicy.maxPreviewWidth`）：贴边的窗口若
     * 允许拖出屏幕放不下的宽度，落盘时会被夹回来、窗口跟着缩一截。
     */
    val maxPreviewWidth: Dp = Popup.maximumPreviewWidth,

    /**
     * 窗口已经为预览让出位置（加宽 / 收回的那次几何**已经应用**），界面据此让预览卡片进场。
     *
     * 必须是宿主算出来的信号，不能让界面拿量到的窗口宽度去猜——实测值慢窗口一帧，收起预览时
     * 会让卡片在已经收窄的窗口里多画一帧（见 `PreviewHostPolicy.windowReady`）。
     */
    val previewWindowReady: Boolean = false,

    /** 当前弹窗交互阶段：按住会话的投影，由 `GlobalHotKeyController` 写；界面目前不读它。 */
    val popupMode: PopupMode = PopupMode.TOGGLE,
)
