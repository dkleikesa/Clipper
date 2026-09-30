package com.qcmian.clipper.desktop.domain

import com.qcmian.clipper.core.platform.macos.MacStatusItem
import com.qcmian.clipper.core.platform.macos.MenuBarAnchor
import java.awt.Rectangle

/**
 * 窗口几何读取的那部分「外界」：屏幕可见区域与菜单栏图标的位置。
 *
 * 抽成注入点（测试缝）是为了让 [com.qcmian.clipper.desktop.viewmodel.WindowGeometryController]
 * 能在受控输入下跑起来——几何里真正反复出问题的是**时序与原子性**（位置尺寸必须同帧、尺寸通知
 * 滞后要用历史去认、锚点生命周期），而它们全都要读这两样外界信息与系统时钟，直接读 AWT / AppKit
 * 就没有单测可言。
 *
 * 生产路径用 [AwtWindowEnvironment]（照抄原调用），测试注入一块固定的屏幕与锚点。
 */
internal interface WindowEnvironment {

    /** 某块屏幕的可见区域（已去掉菜单栏与 Dock）；索引语义见 [screenBounds]。 */
    fun visibleScreenBounds(index: Int): Rectangle

    /** 菜单栏图标当前的水平范围；取不到时返回 `null`。 */
    fun statusItemAnchor(): MenuBarAnchor?
}

/** 生产实现：屏幕走 AWT、锚点走 AppKit。 */
internal object AwtWindowEnvironment : WindowEnvironment {
    override fun visibleScreenBounds(index: Int): Rectangle = screenBounds(index)
    override fun statusItemAnchor(): MenuBarAnchor? = MacStatusItem.currentAnchor()
}
