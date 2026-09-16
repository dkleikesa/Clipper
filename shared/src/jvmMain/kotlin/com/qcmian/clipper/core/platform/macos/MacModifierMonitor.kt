package com.qcmian.clipper.core.platform.macos

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Memory
import com.sun.jna.Pointer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NSEvent 本地监视器：跟踪修饰键的按下 / 松开。
 *
 * 「按住呼出快捷键循环」需要知道修饰键什么时候松开。轮询
 * [MacWorkspace.currentModifierFlags] 并不总是读得到，事件驱动是最灵敏的来源：只要修饰键一变，
 * AppKit 就把 `flagsChanged` 交给我们。
 *
 * 用本地监视器而不是全局监视器：全局的能覆盖应用未激活的情况，但键盘事件的全局监视
 * 需要辅助功能权限（鼠标事件则不需要，见 [MacOutsideClickMonitor]）。本地监视器在面板
 * 是 key window 时就能收到，不需要任何授权。
 *
 * 因此它只在应用激活时收到事件，[flags] 的**绝对值可能是过期的**（修饰键常常是在别的应用里
 * 按下的，那几次事件并没有派发到本应用）。使用方在开启一次按住会话时用 [assumeHeld] 把它
 * 对齐到「组合键按全」——那一刻确实是按全的，热键的按下事件就是证据；对齐之后它收到的每一次
 * 变化都是本次会话里的真实状态，可以放心采信。
 *
 * 线程：监视器回调可能由任意线程派发，只写 `StateFlow`，线程安全。
 *
 * 监视器有意常驻整个进程生命周期、不提供移除入口（与 [MacOutsideClickMonitor] 相同）：
 * 它只跟系统要一次事件、开销可忽略，而反复装卸反而更容易踩到 block 保活的坑。
 */
object MacModifierMonitor {

    /** `NSEventTypeFlagsChanged`。 */
    private const val FLAGS_CHANGED_MASK = 1L shl 12

    private val _flags = MutableStateFlow(0)

    /** `NSEvent.modifierFlags` 的原始值；未经任何掩码过滤。 */
    val flags: StateFlow<Int> = _flags.asStateFlow()

    private val callback: Callback = object : Handler {
        override fun invoke(self: Pointer?, event: Pointer?): Pointer? {
            // 事件自带的修饰键就是这一刻的真实状态，比类方法可靠。
            _flags.value = MacNative.sendLong(event, "modifierFlags").toInt()
            return event
        }
    }

    /** 本地监视器回调：返回事件本身表示不拦截，让它继续派发。 */
    private interface Handler : Callback {
        fun invoke(self: Pointer?, event: Pointer?): Pointer?
    }

    // 地址被写进 ObjC block，被 GC 就是野指针。
    private var block: Memory? = null
    private var descriptor: Memory? = null

    private var installed = false

    /** 安装监视器；成功返回 `true`。幂等。 */
    fun install(): Boolean {
        if (installed) return true
        return runCatching {
            val invoke = CallbackReference.getFunctionPointer(callback) ?: return false
            val (newBlock, newDescriptor) = MacNative.globalBlock(invoke) ?: return false
            val nsevent = MacNative.clazz("NSEvent") ?: return false

            block = newBlock
            descriptor = newDescriptor

            MacNative.send(
                nsevent,
                "addLocalMonitorForEventsMatchingMask:handler:",
                FLAGS_CHANGED_MASK,
                newBlock,
            )
            installed = true
            true
        }.getOrDefault(false)
    }

    /**
     * 把当前值就位成「[mask] 这一组修饰键都按着」。
     *
     * 只在开启一次按住会话时调用：热键的按下事件本身就证明那一刻组合键是按全的，而监视器
     * 此刻的值往往是修饰键在别的应用里按下时留下的旧值（那几次 `flagsChanged` 没有派发到
     * 本应用）。就位之后，它随后收到的任何 `flagsChanged` 都是本次会话里的真实状态——
     * 尤其是「松开」那一次，这正是轮询读不到、必须靠它兜住的信号。
     */
    fun assumeHeld(mask: Int) {
        _flags.value = mask
    }
}
