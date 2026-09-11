package com.qcmian.clipper.macos

import com.qcmian.clipper.core.GlobalHotKeyHandle
import com.qcmian.clipper.core.GlobalShortcut
import com.sun.jna.Pointer
import com.sun.jna.platform.mac.Carbon
import com.sun.jna.ptr.PointerByReference
import javax.swing.SwingUtilities

/**
 * Port of Maccy's `GlobalHotKey`: a system-wide shortcut registered through Carbon's
 * `RegisterEventHotKey`, which is what every macOS global hotkey library ends up using.
 *
 * Registration can fail when another application already owns the combination; the caller
 * then simply runs without a global shortcut.
 */
object MacGlobalHotKey {
    /** `kEventClassKeyboard`. */
    private const val EVENT_CLASS_KEYBOARD = 0x6B657962

    /** `kEventHotKeyPressed`. */
    private const val EVENT_HOT_KEY_PRESSED = 5

    /** `'CLPR'`, the four character code identifying our hot key. */
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

        // The Carbon callback must stay reachable for as long as it is installed.
        val proc = object : Carbon.EventHandlerProcPtr {
            override fun callback(inHandlerCallRef: Pointer?, inEvent: Pointer?, inUserData: Pointer?): Int {
                // Delivered on the Carbon event loop; hop onto the AWT thread before
                // touching any Compose state.
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
