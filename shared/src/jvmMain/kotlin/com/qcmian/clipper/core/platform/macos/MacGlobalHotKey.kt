package com.qcmian.clipper.core.platform.macos

import com.sun.jna.Pointer
import com.sun.jna.platform.mac.Carbon
import com.sun.jna.ptr.PointerByReference
import javax.swing.SwingUtilities

/**
 * 通过 Carbon 的 `RegisterEventHotKey` 注册系统级快捷键，
 * 这也是所有 macOS 全局热键库最终都会用到的方式。
 *
 * 当组合键已被其它应用占用时注册会失败；此时调用方只是在没有全局快捷键的情况下继续运行。
 */
object MacGlobalHotKey {
    /** `kEventClassKeyboard`。 */
    private const val EVENT_CLASS_KEYBOARD = 0x6B657962

    /** `kEventHotKeyPressed`。 */
    private const val EVENT_HOT_KEY_PRESSED = 5

    /** `kEventHotKeyReleased`。 */
    private const val EVENT_HOT_KEY_RELEASED = 6

    /** `'CLPR'`，标识本热键的四字符码。 */
    private const val SIGNATURE = 0x434C5052
    private const val HOT_KEY_ID = 1

    private var pressedCallback: (() -> Unit)? = null
    private var releasedCallback: (() -> Unit)? = null
    private var pressedProcedure: Carbon.EventHandlerProcPtr? = null
    private var releasedProcedure: Carbon.EventHandlerProcPtr? = null
    private var pressedHandlerRef: PointerByReference? = null
    private var releasedHandlerRef: PointerByReference? = null
    private var hotKeyRef: PointerByReference? = null

    /**
     * 注册热键。[onTrigger] 在按下时调用，[onRelease] 在松开时调用。
     *
     * 注意 [onRelease] 未必会被调用：`kEventHotKeyReleased` 虽然写在 HIToolbox 的头文件里，
     * 但系统不会把它交给应用（只有 `kEventHotKeyPressed` 会到），因此调用方不能依赖它——
     * 这里装它只是「收到就用」，收不到时行为与只装按下处理器一致。
     */
    @Synchronized
    fun register(
        shortcut: GlobalShortcut,
        onTrigger: () -> Unit,
        onRelease: () -> Unit = {},
    ): GlobalHotKeyHandle? {
        unregister()
        pressedCallback = onTrigger
        releasedCallback = onRelease

        val target = Carbon.INSTANCE.GetEventDispatcherTarget()

        // Carbon 回调在被安装期间必须保持可达。
        val onPressed = object : Carbon.EventHandlerProcPtr {
            override fun callback(inHandlerCallRef: Pointer?, inEvent: Pointer?, inUserData: Pointer?): Int {
                // 回调发生在 Carbon 事件循环上；在触碰任何 Compose 状态之前先切到 AWT 线程。
                SwingUtilities.invokeLater { pressedCallback?.invoke() }
                return 0
            }
        }
        val onReleased = object : Carbon.EventHandlerProcPtr {
            override fun callback(inHandlerCallRef: Pointer?, inEvent: Pointer?, inUserData: Pointer?): Int {
                SwingUtilities.invokeLater { releasedCallback?.invoke() }
                return 0
            }
        }
        pressedProcedure = onPressed
        releasedProcedure = onReleased

        pressedHandlerRef = installHandler(target, EVENT_HOT_KEY_PRESSED, onPressed)
        if (pressedHandlerRef == null) {
            unregister()
            return null
        }
        // 与按下处理器同等对待：装不上就整体放弃。少了松开事件，主键松手就收不到，
        // 「按住循环」会变成松不开的半吊子状态。
        releasedHandlerRef = installHandler(target, EVENT_HOT_KEY_RELEASED, onReleased)
        if (releasedHandlerRef == null) {
            unregister()
            return null
        }

        val hotKeyId = Carbon.EventHotKeyID.ByValue().apply {
            signature = SIGNATURE
            id = HOT_KEY_ID
        }
        // 按用户录制的组合原样注册。以前无条件 OR 上 `⌘`，会让「实际注册要求的键」与
        // 「按住循环判定用的键」（`ShortcutSpec` 的修饰键）不是同一套。
        // 完全没有修饰键时才退回 `⌘`：否则一个裸键会成为全局热键，吞掉其它应用的输入。
        val modifiers = (if (shortcut.command) Carbon.cmdKey else 0) or
            (if (shortcut.shift) Carbon.shiftKey else 0) or
            (if (shortcut.option) Carbon.optionKey else 0) or
            (if (shortcut.control) Carbon.controlKey else 0)

        val hotKeyOut = PointerByReference()
        val registerStatus = Carbon.INSTANCE.RegisterEventHotKey(
            shortcut.keyCode,
            if (modifiers == 0) Carbon.cmdKey else modifiers,
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
        pressedHandlerRef?.value?.let { reference -> runCatching { Carbon.INSTANCE.RemoveEventHandler(reference) } }
        releasedHandlerRef?.value?.let { reference -> runCatching { Carbon.INSTANCE.RemoveEventHandler(reference) } }
        clear()
    }

    /** 装一个只监听 [kind] 的处理器；成功返回它的引用。 */
    private fun installHandler(
        target: Pointer?,
        kind: Int,
        procedure: Carbon.EventHandlerProcPtr,
    ): PointerByReference? {
        val eventType = Carbon.EventTypeSpec().apply {
            eventClass = EVENT_CLASS_KEYBOARD
            eventKind = kind
        }
        val handlerOut = PointerByReference()
        val status = Carbon.INSTANCE.InstallEventHandler(
            target,
            procedure,
            1,
            arrayOf(eventType),
            null,
            handlerOut,
        )
        return if (status == 0) handlerOut else null
    }

    private fun clear() {
        hotKeyRef = null
        pressedHandlerRef = null
        releasedHandlerRef = null
        pressedProcedure = null
        releasedProcedure = null
        pressedCallback = null
        releasedCallback = null
    }
}
