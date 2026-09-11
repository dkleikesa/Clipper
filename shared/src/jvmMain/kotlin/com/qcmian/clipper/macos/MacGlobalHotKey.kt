package com.qcmian.clipper.macos

import com.sun.jna.Pointer
import com.sun.jna.platform.mac.Carbon
import com.sun.jna.ptr.PointerByReference
import javax.swing.SwingUtilities

/**
 * 对应 Maccy 的 `GlobalHotKey`：通过 Carbon 的 `RegisterEventHotKey` 注册系统级快捷键，
 * 这也是所有 macOS 全局热键库最终都会用到的方式。
 *
 * 当组合键已被其它应用占用时注册会失败；此时调用方只是在没有全局快捷键的情况下继续运行。
 */
object MacGlobalHotKey {
    /** `kEventClassKeyboard`。 */
    private const val EVENT_CLASS_KEYBOARD = 0x6B657962

    /** `kEventHotKeyPressed`。 */
    private const val EVENT_HOT_KEY_PRESSED = 5

    /** `'CLPR'`，标识本热键的四字符码。 */
    private const val SIGNATURE = 0x434C5052
    private const val HOT_KEY_ID = 1

    private var callback: (() -> Unit)? = null
    private var procedure: Carbon.EventHandlerProcPtr? = null
    private var eventHandlerRef: PointerByReference? = null
    private var hotKeyRef: PointerByReference? = null

    @Synchronized
    fun register(shortcut: GlobalShortcut, onTrigger: () -> Unit): GlobalHotKeyHandle? {
        unregister()
        callback = onTrigger

        // Carbon 回调在被安装期间必须保持可达。
        val proc = object : Carbon.EventHandlerProcPtr {
            override fun callback(inHandlerCallRef: Pointer?, inEvent: Pointer?, inUserData: Pointer?): Int {
                // 回调发生在 Carbon 事件循环上；在触碰任何 Compose 状态之前先切到 AWT 线程。
                SwingUtilities.invokeLater { callback?.invoke() }
                return 0
            }
        }
        procedure = proc

        val eventType = Carbon.EventTypeSpec().apply {
            eventClass = EVENT_CLASS_KEYBOARD
            eventKind = EVENT_HOT_KEY_PRESSED
        }

        val target = Carbon.INSTANCE.GetEventDispatcherTarget()
        val handlerOut = PointerByReference()
        val installStatus = Carbon.INSTANCE.InstallEventHandler(
            target,
            proc,
            1,
            arrayOf(eventType),
            null,
            handlerOut,
        )
        if (installStatus != 0) {
            clear()
            return null
        }
        eventHandlerRef = handlerOut

        val hotKeyId = Carbon.EventHotKeyID.ByValue().apply {
            signature = SIGNATURE
            id = HOT_KEY_ID
        }
        val modifiers = Carbon.cmdKey or
            (if (shortcut.shift) Carbon.shiftKey else 0) or
            (if (shortcut.option) Carbon.optionKey else 0) or
            (if (shortcut.control) Carbon.controlKey else 0)

        val hotKeyOut = PointerByReference()
        val registerStatus = Carbon.INSTANCE.RegisterEventHotKey(
            shortcut.keyCode,
            modifiers,
            hotKeyId,
            target,
            0,
            hotKeyOut,
        )
        if (registerStatus != 0) {
            unregister()
            return null
        }
        hotKeyRef = hotKeyOut

        return object : GlobalHotKeyHandle {
            override fun unregister() = this@MacGlobalHotKey.unregister()
        }
    }

    @Synchronized
    fun unregister() {
        hotKeyRef?.value?.let { reference -> runCatching { Carbon.INSTANCE.UnregisterEventHotKey(reference) } }
        eventHandlerRef?.value?.let { reference -> runCatching { Carbon.INSTANCE.RemoveEventHandler(reference) } }
        clear()
    }

    private fun clear() {
        hotKeyRef = null
        eventHandlerRef = null
        procedure = null
        callback = null
    }
}
