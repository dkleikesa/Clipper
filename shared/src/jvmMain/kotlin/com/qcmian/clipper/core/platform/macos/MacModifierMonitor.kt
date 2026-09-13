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
 * [MacWorkspace.currentModifierFlags] 并不可靠：Carbon 注册的热键会把按键从事件流里摘走，
 * 松开修饰键那一次 `flagsChanged` 未必派发到本应用，于是读到的掩码一直停在按下时的值，
 * 循环停不下来。这里改成事件驱动——只要修饰键一变，AppKit 就把 `flagsChanged` 交给我们。
 *
 * 用本地监视器而不是全局监视器：全局的能覆盖应用未激活的情况，但键盘事件的全局监视
 * 需要辅助功能权限（鼠标事件则不需要，见 [MacOutsideClickMonitor]）。本地监视器在面板
 * 是 key window 时就能收到，不需要任何授权。
 *
 * 注意它只在应用激活时收到事件，因此进入一次「按住循环」之前要先 [resync] 一次：
 * 修饰键往往是在别的应用里按下的，那几次事件并没有派发到本应用。
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
     * 用一次轮询把当前状态对齐，之后继续由事件驱动。
     *
     * 只在开始一次「按住循环」之前调用：那时修饰键已经按下，而对应的事件本应用并没有收到；
     * 循环进行中不能调用，否则会把「已经读到松开」的结果覆盖回轮询的陈旧值。
     */
    fun resync() {
        _flags.value = MacWorkspace.currentModifierFlags()
    }
}
