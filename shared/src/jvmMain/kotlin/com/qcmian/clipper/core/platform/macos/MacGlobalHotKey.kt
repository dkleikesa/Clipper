package com.qcmian.clipper.core.platform.macos

import com.sun.jna.Pointer
import com.sun.jna.platform.mac.Carbon
import com.sun.jna.ptr.PointerByReference
import java.nio.IntBuffer
import javax.swing.SwingUtilities

/**
 * 通过 Carbon 的 `RegisterEventHotKey` 注册系统级快捷键，
 * 这也是所有 macOS 全局热键库最终都会用到的方式。
 *
 * 注册按调用方给的 [id] 分别登记（同一个应用本来就可以同时注册多个热键）。本应用只用一条真实
 * 热键（呼出面板），但这个结构是必要的：[isAvailable] 要在**不碰已有注册**的前提下试注册一次，
 * 靠的就是给它自己的 id。两类事件（按下 / 松开）各只有一个处理器，靠事件里携带的热键标识
 * 分派给对应的注册（见 [hotKeyIdOf]）。
 *
 * 当组合键已被其它应用占用时注册会失败；此时调用方只是在没有该快捷键的情况下继续运行，
 * 也可以先用 [isAvailable] 探一下再落盘用户的录制结果。
 */
object MacGlobalHotKey {
    /** `kEventClassKeyboard`。 */
    private const val EVENT_CLASS_KEYBOARD = 0x6B657962

    /** `kEventHotKeyPressed`。 */
    private const val EVENT_HOT_KEY_PRESSED = 5

    /** `kEventHotKeyReleased`。 */
    private const val EVENT_HOT_KEY_RELEASED = 6

    /** `'CLPR'`，标识本应用注册的热键的四字符码。 */
    private const val SIGNATURE = 0x434C5052

    /** `kEventParamDirectObject`（`'----'`）：事件里携带热键标识的那个参数。 */
    private const val PARAM_DIRECT_OBJECT = 0x2D2D2D2D

    /** `typeEventHotKeyID`（`'hkid'`）：上面那个参数的类型。 */
    private const val TYPE_HOT_KEY_ID = 0x686B6964

    /** 占用探测用的注册 id；调用方分配的 id 必须避开它（见 [register]）。 */
    private const val PROBE_ID = 0

    /** 取不到热键标识时的返回值：不对应任何注册。 */
    private const val UNKNOWN_HOT_KEY_ID = -1

    /** 一次注册的全部内容。 */
    private class Registration(
        val hotKeyRef: Pointer,
        val onTrigger: () -> Unit,
        val onRelease: () -> Unit,
    )

    /** 已注册的热键，按调用方给的 id 索引。所有访问都在 [Synchronized] 方法里。 */
    private val registrations = mutableMapOf<Int, Registration>()

    /**
     * Carbon 回调在被安装期间必须保持可达：它们的函数指针写在运行时建的类里，
     * 被 GC 回收后就变成野指针。
     *
     * 处理器整个进程只装一次（装了就不能再装第二次，否则一次按键会派发两遍），因此这些引用
     * 从头保留到尾，**不**随热键注销而清空。
     */
    private var pressedProcedure: Carbon.EventHandlerProcPtr? = null
    private var releasedProcedure: Carbon.EventHandlerProcPtr? = null
    private var pressedHandlerRef: PointerByReference? = null
    private var releasedHandlerRef: PointerByReference? = null

    /**
     * 注册（或替换）一个热键。[onTrigger] 在按下时调用，[onRelease] 在松开时调用。
     *
     * @param id 调用方分配的热键标识，同一 id 会被新注册取代；必须避开 [PROBE_ID]。
     *
     * [onRelease] 是否派发取决于系统：真机日志确认当前的 macOS **会**在组合键被拆开时送来
     * `kEventHotKeyReleased`（抬起主键、修饰键仍按着时也一样），因此调用方可以据此判断
     * 「主键已经抬起」；但不要假设它一定到——调用方仍需准备收不到时的退路。
     *
     * @return 注册是否成功；被别的应用（或本应用的其它 id）占用时为 `false`。
     */
    @Synchronized
    fun register(
        id: Int,
        shortcut: GlobalShortcut,
        onTrigger: () -> Unit,
        onRelease: () -> Unit = {},
    ): Boolean {
        unregister(id)

        val target = Carbon.INSTANCE.GetEventDispatcherTarget()
        if (!installHandlers(target)) return false

        val hotKeyId = Carbon.EventHotKeyID.ByValue().apply {
            signature = SIGNATURE
            this.id = id
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
        if (registerStatus != 0) return false

        // 注册成功却没有句柄是不该发生的：留着它既注销不掉、也永远会截获按键。
        val hotKeyRef = hotKeyOut.value
        if (hotKeyRef == null) {
            runCatching { Carbon.INSTANCE.UnregisterEventHotKey(hotKeyOut.value) }
            return false
        }
        registrations[id] = Registration(hotKeyRef, onTrigger, onRelease)
        return true
    }

    /** 注销 [id] 对应的热键；没有注册过时什么都不做。 */
    @Synchronized
    fun unregister(id: Int) {
        val registration = registrations.remove(id) ?: return
        runCatching { Carbon.INSTANCE.UnregisterEventHotKey(registration.hotKeyRef) }
    }

    /**
     * 试注册一次以判断这个组合当前是否可用：macOS 没有「某组合被谁注册」的查询接口，
     * 唯一可靠的判断就是真的注册一次——成功即说明没被占用，随即注销。
     *
     * 用 [PROBE_ID] 注册，因此不会碰到调用方真正注册的那些热键。
     */
    @Synchronized
    fun isAvailable(shortcut: GlobalShortcut): Boolean {
        val available = register(PROBE_ID, shortcut, onTrigger = {})
        unregister(PROBE_ID)
        return available
    }

    /**
     * 从事件里取出触发它的热键 id。
     *
     * 两类事件只有一套处理器，唯一的区分就是事件的 `kEventParamDirectObject` 参数
     * （`typeEventHotKeyID`）——注册时填进去的是 [SIGNATURE] 与 id。
     */
    private fun hotKeyIdOf(event: Pointer?): Int {
        if (event == null) return UNKNOWN_HOT_KEY_ID
        val out = Carbon.EventHotKeyID()
        val status = runCatching {
            Carbon.INSTANCE.GetEventParameter(
                event,
                PARAM_DIRECT_OBJECT,
                TYPE_HOT_KEY_ID,
                null,
                out.size(),
                IntBuffer.allocate(1),
                out,
            )
        }.getOrNull() ?: return UNKNOWN_HOT_KEY_ID
        if (status != 0) return UNKNOWN_HOT_KEY_ID
        out.read()
        return if (out.signature == SIGNATURE) out.id else UNKNOWN_HOT_KEY_ID
    }

    /**
     * 把一次 Carbon 事件分派给对应的注册。
     *
     * 回调发生在 Carbon 事件循环上，而 Compose 状态只能在 AWT 线程上碰，因此先
     * `invokeLater` 切线程；入队之后这次注册可能已经被注销（用户改了快捷键），
     * 所以真正执行时按 id 再查一次，查不到就丢掉。
     */
    private fun dispatch(id: Int, action: (Registration) -> Unit) {
        if (id == UNKNOWN_HOT_KEY_ID) return
        SwingUtilities.invokeLater {
            registrationOrNull(id)?.let(action)
        }
    }

    @Synchronized
    private fun registrationOrNull(id: Int): Registration? = registrations[id]

    /** 装好两类事件的处理器；已经装过就什么都不做。 */
    @Synchronized
    private fun installHandlers(target: Pointer?): Boolean {
        if (pressedHandlerRef != null && releasedHandlerRef != null) return true

        val onPressed = pressedProcedure ?: object : Carbon.EventHandlerProcPtr {
            override fun callback(inHandlerCallRef: Pointer?, inEvent: Pointer?, inUserData: Pointer?): Int {
                dispatch(hotKeyIdOf(inEvent)) { it.onTrigger() }
                return 0
            }
        }.also { pressedProcedure = it }
        val onReleased = releasedProcedure ?: object : Carbon.EventHandlerProcPtr {
            override fun callback(inHandlerCallRef: Pointer?, inEvent: Pointer?, inUserData: Pointer?): Int {
                dispatch(hotKeyIdOf(inEvent)) { it.onRelease() }
                return 0
            }
        }.also { releasedProcedure = it }

        pressedHandlerRef = installHandler(target, EVENT_HOT_KEY_PRESSED, onPressed)
        if (pressedHandlerRef == null) {
            pressedProcedure = null
            return false
        }
        // 与按下处理器同等对待：装不上就整体放弃。少了松开事件，主键松手就收不到，
        // 「按住循环」会变成松不开的半吊子状态。
        releasedHandlerRef = installHandler(target, EVENT_HOT_KEY_RELEASED, onReleased)
        if (releasedHandlerRef == null) {
            pressedHandlerRef?.value?.let { runCatching { Carbon.INSTANCE.RemoveEventHandler(it) } }
            pressedHandlerRef = null
            releasedHandlerRef = null
            pressedProcedure = null
            releasedProcedure = null
            return false
        }
        return true
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
}
