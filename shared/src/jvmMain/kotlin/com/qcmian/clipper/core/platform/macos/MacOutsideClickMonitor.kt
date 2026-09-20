package com.qcmian.clipper.core.platform.macos

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NSEvent 全局 / 本地监视器：识别「点击落在面板之外」。
 *
 * 点击系统菜单栏或其它应用的托盘图标时，AWT 不会向本窗口报告 `windowLostFocus`；
 * 这里补上「失去焦点即关闭」的语义：
 *
 * - **全局监视器**：收到其它应用的鼠标抬起（点别的应用窗口、桌面、Dock、其它托盘）→ 面板外；
 * - **本地监视器**：收到本应用的鼠标抬起，若事件窗口不是面板（菜单栏、菜单）→ 面板外；
 *   是面板自身则忽略（正常交互）。点本应用托盘图标也会判为面板外，随后的
 *   `togglePanel` 有 250ms 宽限位，不会把刚收起的面板重新打开。
 *
 * `addGlobalMonitorForEventsMatchingMask:handler:` 的 handler 是 ObjC block，
 * JNA 没有现成桥接，block 由 [MacNative.globalBlock] 手工拼出来。
 * 鼠标事件的全局监视不需要辅助功能权限（只有键盘事件需要）。
 *
 * 线程：监视器回调可能由任意线程派发，只写 `StateFlow`，线程安全。
 */
object MacOutsideClickMonitor {

    /** 关心的鼠标抬起事件：左键(type 2)、右键(type 4)、其它键(type 26)。 */
    private const val MOUSE_UP_MASK = (1L shl 2) or (1L shl 4) or (1L shl 26)

    private val _outsideClicks = MutableStateFlow(0)

    /** 面板外点击计数，每次自增；观察方据此收起面板。 */
    val outsideClicks: StateFlow<Int> = _outsideClicks.asStateFlow()

    // 以下字段全部保活：它们的地址被写进了 ObjC block / 注册表，被 GC 就是野指针。

    private val globalCallback: Callback = object : GlobalHandler {
        override fun invoke(self: Pointer?, event: Pointer?) {
            _outsideClicks.value += 1
        }
    }

    private interface GlobalHandler : Callback {
        fun invoke(self: Pointer?, event: Pointer?)
    }

    private val localCallback: Callback = object : LocalHandler {
        override fun invoke(self: Pointer?, event: Pointer?): Pointer? {
            if (!isPanelEvent(event)) _outsideClicks.value += 1
            return event
        }
    }

    private interface LocalHandler : Callback {
        fun invoke(self: Pointer?, event: Pointer?): Pointer?
    }

    private var globalBlock: Memory? = null
    private var globalDescriptor: Memory? = null
    private var localBlock: Memory? = null
    private var localDescriptor: Memory? = null

    private var installed = false
    private var panelTitle: String = ""

    /**
     * 安装监视器；成功返回 `true`。幂等。
     * 失败（非 macOS / 原生层缺失 / block 构造失败）返回 `false`，调用方退回轮询。
     *
     * @param panelTitle 面板窗口的 `NSWindow.title`，本地事件据此判定「点击在面板内」。
     */
    fun install(panelTitle: String): Boolean {
        if (installed) return true
        return runCatching {
            val globalInvoke = CallbackReference.getFunctionPointer(globalCallback) ?: return false
            val localInvoke = CallbackReference.getFunctionPointer(localCallback) ?: return false
            val (newGlobalBlock, newGlobalDescriptor) = MacNative.globalBlock(globalInvoke) ?: return false
            val (newLocalBlock, newLocalDescriptor) = MacNative.globalBlock(localInvoke) ?: return false
            val nsevent = MacNative.clazz("NSEvent") ?: return false

            globalBlock = newGlobalBlock
            globalDescriptor = newGlobalDescriptor
            localBlock = newLocalBlock
            localDescriptor = newLocalDescriptor
            this.panelTitle = panelTitle

            MacNative.send(
                nsevent,
                "addGlobalMonitorForEventsMatchingMask:handler:",
                MOUSE_UP_MASK,
                newGlobalBlock,
            )
            MacNative.send(
                nsevent,
                "addLocalMonitorForEventsMatchingMask:handler:",
                MOUSE_UP_MASK,
                newLocalBlock,
            )
            installed = true
            true
        }.getOrDefault(false)
    }

    /** 事件窗口是否为本面板（按标题判定；菜单栏、菜单、弹窗等都不是）。 */
    private fun isPanelEvent(event: Pointer?): Boolean {
        val window = MacNative.send(event, "window") ?: return false
        val title = MacNative.string(MacNative.send(window, "title"))
        return title == panelTitle
    }
}
