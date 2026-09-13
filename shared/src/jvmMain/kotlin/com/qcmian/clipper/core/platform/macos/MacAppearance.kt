package com.qcmian.clipper.core.platform.macos

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Pointer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 系统外观（深色 / 浅色）的检测。
 *
 * macOS 在系统切换深浅色时会向分布式通知中心发
 * `AppleInterfaceThemeChangedNotification`；这里用 `MacStatusItem` 同款的
 * 「运行时建 ObjC 类 + JNA 回调」机制注册选择器式观察者（不需要 block），
 * 事件驱动地更新 [systemDark]，完全替代轮询。
 *
 * 线程：通知可能由任意线程派发，回调只写 `StateFlow`，线程安全。
 */
object MacAppearance {

    private const val TARGET_CLASS_NAME = "ClipperAppearanceTarget"
    private const val THEME_CHANGED_SELECTOR = "themeChanged:"
    private const val NOTIFICATION_NAME = "AppleInterfaceThemeChangedNotification"

    private val _systemDark = MutableStateFlow<Boolean?>(null)

    /** 系统外观是否为深色；`null` 表示尚未读到（原生层不可用）。 */
    val systemDark: StateFlow<Boolean?> = _systemDark.asStateFlow()

    /** 观察者对象。保活：JNA Pointer 只是裸地址，被回收后通知会投递到野指针。 */
    private var observer: Pointer? = null

    /** 通知回调。保存在字段里防止 JNA `Callback` 被 GC（函数指针已写进运行时建的类）。 */
    private val callback: Callback = object : ThemeChangedCallback {
        override fun onThemeChanged(self: Pointer?, command: Pointer?, notification: Pointer?) {
            refresh()
        }
    }

    private interface ThemeChangedCallback : Callback {
        fun onThemeChanged(self: Pointer?, command: Pointer?, notification: Pointer?)
    }

    /**
     * 注册系统外观通知观察者；成功返回 `true`。幂等，重复调用直接返回。
     * 失败（非 macOS / 原生层缺失）返回 `false`，调用方退回轮询。
     */
    fun install(): Boolean {
        if (observer != null) return true
        return runCatching {
            val implementation = CallbackReference.getFunctionPointer(callback) ?: return false
            val targetClass = MacNative.clazz(TARGET_CLASS_NAME)
                ?: MacNative.allocateClass("NSObject", TARGET_CLASS_NAME)?.also {
                    MacNative.addMethod(it, THEME_CHANGED_SELECTOR, implementation, "v@:@")
                    MacNative.registerClass(it)
                }
                ?: return false
            val newObserver = MacNative.send(MacNative.send(targetClass, "alloc"), "init")
                ?: return false
            observer = newObserver

            // 立即读一次初始值，之后交给通知驱动。
            refresh()

            val center = MacNative.send(
                MacNative.clazz("NSDistributedNotificationCenter"),
                "defaultCenter",
            ) ?: return false
            MacNative.send(
                center,
                "addObserver:selector:name:object:",
                newObserver,
                MacNative.selector(THEME_CHANGED_SELECTOR),
                MacNative.nsString(NOTIFICATION_NAME),
                null,
            )
            true
        }.getOrDefault(false)
    }

    /** 重新读取系统外观并广播。 */
    fun refresh() {
        _systemDark.value = MacWorkspace.isSystemAppearanceDark()
    }
}
