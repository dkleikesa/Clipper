package com.qcmian.clipper.desktop.viewmodel

import com.qcmian.clipper.core.data.source.NativeDataSource
import com.qcmian.clipper.core.domain.repository.ClipboardRepository
import com.qcmian.clipper.core.platform.macos.GlobalShortcut
import com.qcmian.clipper.core.platform.macos.MacGlobalHotKey
import com.qcmian.clipper.core.platform.macos.MacModifierMonitor
import com.qcmian.clipper.core.settings.ShortcutSpec
import com.qcmian.clipper.desktop.domain.CYCLE_INTERVAL_MILLIS
import com.qcmian.clipper.desktop.domain.CYCLE_START_DELAY_MILLIS
import com.qcmian.clipper.desktop.domain.MODIFIER_POLL_MILLIS
import com.qcmian.clipper.desktop.domain.PopupMode
import com.qcmian.clipper.desktop.domain.nsModifierMask
import com.qcmian.clipper.host.HotkeyController
import com.qcmian.clipper.host.WindowController
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * 注册到 Carbon 的热键标识（`EventHotKeyID.id`）：事件里带的就是它，系统据此把按键派发给
 * 对应的注册。本应用只注册一条真实热键（呼出面板），其余可选键都在面板内匹配；
 * 占用探测自己用保留 id（见 `MacGlobalHotKey.isAvailable`），所以这里从 1 开始。
 */
private const val HOT_KEY_POPUP = 1

/**
 * 一次按住会话里，组合键当前按到什么程度。
 *
 * 关键在于区分「松掉一部分」和「全松开」：前者只挂起循环（用户可能只是松了一个修饰键，
 * 马上又按回去），后者才是一次真正的松手，要选中高亮项并关窗。
 */
private enum class ComboState { NONE, PARTIAL, COMPLETE }

/**
 * 全局热键与「按住循环」状态机。
 *
 * 两件事合在这里，是因为它们共享同一份时序状态：
 * - [observeShortcut]：把偏好里录制的呼出键注册成 Carbon 系统级热键（录制期间注销）；
 * - [onHotKeyPressed] / [observeHotKeyHold]：一次「按住」的会话时钟——按满
 *   [CYCLE_START_DELAY_MILLIS] 后开始逐条循环，松掉一部分只挂起，整组键都松开才收尾。
 *
 * 只有呼出面板是系统级热键，其余可录制快捷键都在面板内匹配（见 `ShortcutSlot.global`）——
 * 所以这里也不需要「暂停键不进按住循环」那类特例。
 *
 * 它与窗口几何、面板显隐之间只通过少量回调相接：呼出时通知「表现层」记录前台应用
 * （[onHotKeyOpened]），托盘呼出的面板再按则把窗口挪到鼠标处（[onMoveToCursor]）。
 */
internal class GlobalHotKeyController(
    private val state: MutableStateFlow<DesktopShellUiState>,
    private val repository: ClipboardRepository,
    private val native: NativeDataSource,
    private val panel: WindowController,
    private val hotkey: HotkeyController,
    /** 热键在面板隐藏时按下：由表现层完成「记为热键呼出 + 抓取前台应用」。 */
    private val onHotKeyOpened: () -> Unit,
    /** 托盘呼出的面板再按热键：把窗口移到鼠标位置（几何层的动作）。 */
    private val onMoveToCursor: () -> Unit,
) {
    /**
     * 每一次热键按下自增，驱动 [observeHotKeyHold] 里的按住会话。
     *
     * 热键的按下事件是「用户按了一次快捷键」唯一可靠的信号：Carbon 既不自动重复，
     * 也几乎不发松开（见 [MacGlobalHotKey.register]）。因此每一次按下都重新开一次会话，
     * 延迟从头计——松开一个键再按回来，同样算「又按了一次」。
     */
    private val holdPresses = MutableStateFlow(0)

    /** 本次按住会话按下的时刻；0 表示没有会话。用绝对时刻，采样协程重建也不会把进度弄丢。 */
    private var holdStartedAt = 0L

    /** 是否有进行中的按住会话；由 [runHoldSession] 的采样循环结束。 */
    private var holdActive = false

    /** 本次会话是否已经推进过：推进过，整组键松开时才选中高亮项；轻按（没到延迟）什么都不做。 */
    private var holdCycled = false

    /**
     * 这一次「按住修饰键」期间按了几次主键。
     *
     * 对应两条交互约定：按一次是「呼出面板」或「往下选一条」，整组键松开后什么都不做；
     * 按两次及以上说明用户是在挑条目，整组键松开时直接选中并关窗。
     * 会话可以因为「只抬起主键」而挂起，但计数跟着这一次按住走，不跟着会话走。
     */
    private var holdPressCount = 0

    /** 本次会话上一次推进的时刻。 */
    private var lastCycleAt = 0L

    /**
     * 主键（`⇧⌘C` 里的 `C`）是否按着，由 Carbon 的按下 / 松开事件维护。
     *
     * 真机日志确认 macOS **会**把 `kEventHotKeyReleased` 送到应用（每次短按都能收到），
     * 所以这个状态值得采信：只抬起主键、修饰键还按着时，它是唯一能说明「组合键已经不完整」的信号。
     */
    private var mainKeyDown = false

    /**
     * 本进程是否收到过热键松开事件。
     *
     * 收不到时（是否派发取决于系统版本）就不能拿 [mainKeyDown] 判松手，否则会话永远收不了尾、
     * 面板再也关不掉；那种情况下退回「修饰键全松开即松手」。
     */
    private var mainKeyReleaseSupported = false

    /**
     * 全局热键：未显示则打开；已显示则逐条循环，托盘呼出的面板再按则把窗口移到鼠标位置。
     */
    fun onHotKeyPressed() {
        val current = state.value
        mainKeyDown = true
        if (!current.windowVisible) {
            // 表现层记账（抓前台应用 + 标记这次不是托盘呼出），随后自己把可见性置起来。
            onHotKeyOpened()
            state.update {
                it.copy(windowVisible = true, popupMode = PopupMode.OPENING, panelOpenedByTray = false)
            }
            hotkey.requestOpen()
            beginHoldSession()
            return
        }
        // 托盘呼出的面板：再按（含连按）是把窗口移到鼠标位置，不参与「按住循环」；
        // 热键呼出的面板不挪窗口——否则连按几下窗口就跟着光标跑了。
        if (current.popupMode == PopupMode.TOGGLE && current.panelOpenedByTray) {
            onMoveToCursor()
            return
        }
        if (holdActive) {
            // 同一次「按住修饰键」里又按了一下主键：往下选一条（短按就是逐条往下选），
            // 并把延迟重新计满——松了其中一个键再按回来，也算新的一次按住。
            // 已经循环过的事实保留：这一次修饰键一直没抬起来，松手时仍然要选中。
            hotkey.requestCycle()
            holdPressCount++
            holdStartedAt = System.currentTimeMillis()
            lastCycleAt = 0L
            mainKeyDown = true
            MacModifierMonitor.assumeHeld(requiredModifierMask())
            setPopupMode(PopupMode.OPENING)
            return
        }
        // 修饰键也已经全松开，这是新的一次按键：先往下选一条（Maccy 的「连按往下选」），
        // 再开始计时。
        hotkey.requestCycle()
        beginHoldSession()
    }

    /**
     * 全局热键松开：主键抬起了。它不直接触发选中——选中要等「整组键都松开」，
     * 见 [runHoldSession]：修饰键还按着时只挂起，不选中也不关窗。
     */
    fun onHotKeyReleased() {
        mainKeyReleaseSupported = true
        mainKeyDown = false
    }

    /** 开启一次新的按住会话；上一次会话（如果还在跑）会被 [observeHotKeyHold] 随即取代。 */
    private fun beginHoldSession() {
        holdStartedAt = System.currentTimeMillis()
        holdActive = true
        holdCycled = false
        holdPressCount = 1
        lastCycleAt = 0L
        mainKeyDown = true
        // 按下事件本身就证明组合键此刻是按全的：把监视器就位成「按全」。它此刻的旧值往往
        // 停在别的应用里按下的那一刻，拿它去比会让松手变成「没有变化」，从而永远收不了尾。
        MacModifierMonitor.assumeHeld(requiredModifierMask())
        setPopupMode(PopupMode.OPENING)
        holdPresses.update { it + 1 }
    }

    /**
     * 「按住热键循环」状态机：每一次按下（[holdPresses]）开启一次按住会话，会话里每
     * [MODIFIER_POLL_MILLIS] 采一次组合键按到什么程度（[ComboState]）。
     *
     * 三个刻意的设计，报出来的毛病都出在这里：
     *
     * 1. 采样间隔独立于循环间隔。原来循环期间每 [CYCLE_INTERVAL_MILLIS]（120ms）才看一眼
     *    组合键，松掉一个键再按回来（一两百毫秒）会被整段跳过——循环于是停不下来，再按时
     *    也直接续上。现在固定 30ms 采样，且只在会话进行中采样，空闲时一次原生调用都不发。
     * 2. 计时用绝对时刻、状态放在控制器里，而不是放在「由 Compose 状态驱动的协程」里。
     *    原来那个协程以 `(windowVisible, popupMode)` 为键，模式一变就被取消重建，
     *    「已经等了多久」跟着丢；而且只有从「打开中」进入才会计时，面板已显示时按住根本不循环。
     * 3. 「松掉一个键」和「整组键都松开」是两件事：前者只挂起，后者才选中并关窗。
     */
    suspend fun observeHotKeyHold() {
        // 修饰键监视器只是「松手」的第二来源，装不上也不影响：那时只剩轮询。
        MacModifierMonitor.install()
        holdPresses.collectLatest { presses ->
            if (presses > 0) runHoldSession()
        }
    }

    /**
     * 一次「按住」的采样循环。按全组合键满 [CYCLE_START_DELAY_MILLIS] 之后开始逐条往下走，
     * 中途松掉哪个键都只是挂起（[ComboState.PARTIAL]），直到整组键都松开才收尾。
     *
     * 收尾时是否选中由「这一次按住怎么用的」决定：进过循环，或点按了两次及以上 → 选中并关窗；
     * 只点按一次 → 什么都不做（那只是呼出面板 / 往下看一眼）。
     */
    private suspend fun runHoldSession() {
        val mask = requiredModifierMask()
        // 按下这一刻组合键一定是按全的：Carbon 只在按全时才报按下。
        var previous = ComboState.COMPLETE
        while (true) {
            delay(MODIFIER_POLL_MILLIS)
            // 面板已经被收起（失焦、Esc、点了某一条…）：会话就此结束。
            if (!state.value.windowVisible) {
                holdActive = false
                return
            }

            val held = heldModifiers(mask)
            // 收不到松开事件时只能当主键一直按着（退回「修饰键全松开即松手」）。
            val mainDown = mainKeyDown || !mainKeyReleaseSupported
            val combo = when {
                // 整组键都松开了：这才是真正的松手。
                held == 0 && !mainDown -> ComboState.NONE
                // 组合键完整按着：可以循环。
                held == mask && mainDown -> ComboState.COMPLETE
                // 松了一部分（只抬主键、或只松某个修饰键）：挂起，不选中也不关窗。
                else -> ComboState.PARTIAL
            }
            if (combo == ComboState.NONE) {
                holdActive = false
                // 整组键都松开了才收尾。选中条件：
                // - 这一次按住进过循环（长按）；或者
                // - 这一次按住里点按了两次及以上（用户在挑条目）。
                // 只点按一次是「呼出面板」或「往下选一条」，松手不做任何事，面板留在原地。
                val accept = holdCycled || holdPressCount >= 2
                if (accept) hotkey.requestAccept()
                setPopupMode(PopupMode.TOGGLE)
                return
            }

            if (combo == ComboState.COMPLETE) {
                if (previous != ComboState.COMPLETE) {
                    // 松了一个键又按回来：这是一次新的「按住」，延迟从头计。
                    holdStartedAt = System.currentTimeMillis()
                    lastCycleAt = 0L
                }
                val now = System.currentTimeMillis()
                if (now - holdStartedAt < CYCLE_START_DELAY_MILLIS) {
                    setPopupMode(PopupMode.OPENING)
                } else {
                    setPopupMode(PopupMode.CYCLE)
                    // 延迟刚结束时先推进一条，之后每 [CYCLE_INTERVAL_MILLIS] 一条。
                    if (!holdCycled || now - lastCycleAt >= CYCLE_INTERVAL_MILLIS) {
                        holdCycled = true
                        lastCycleAt = now
                        hotkey.requestCycle()
                    }
                }
            } else {
                // 只松了一部分：停在这里等剩下的键，不选中也不关窗。
                setPopupMode(PopupMode.OPENING)
            }
            previous = combo
        }
    }

    /** 呼出热键要求的修饰键掩码；呼出快捷键被清除时没有热键，也就没有掩码。 */
    private fun requiredModifierMask(): Int =
        // 读仓库里的偏好，而不是 `panel.hostUiState` 投影：投影要等 `App` 组合才更新，
        // 启动瞬间还是默认值，用它算出来的掩码会和真正注册的热键对不上。
        repository.settings.value.popupShortcut?.let { nsModifierMask(it) } ?: 0

    /**
     * 当前正按着的、落在 [mask] 里的修饰键：轮询与事件监视器**取交集**（任一来源看到某个键
     * 松了，就认为它松了）。
     *
     * - 轮询 `NSEvent.modifierFlags`（[NativeDataSource.currentModifierFlags]）是主来源，
     *   每个采样点都读一次当前状态；
     * - [MacModifierMonitor] 的 `flagsChanged` 是事件来源：本应用是 key window 时它最灵敏。
     *   轮询在热键按住期间未必读得到松手（当初加这个监视器就是为了它），所以松手这件事必须
     *   由它兜住；反过来它的值可能是本次会话之前的旧值，因此会话开始时用
     *   [MacModifierMonitor.assumeHeld] 就位成「按全」。
     *
     * 取交集而不是只看其中一条：任何一条来源看到松手都足以让循环停下来，而两条都看不到的
     * 情况本来也无法判定。
     */
    private fun heldModifiers(mask: Int): Int =
        native.currentModifierFlags() and mask and (MacModifierMonitor.flags.value and mask)

    /** 只写「弹窗阶段」这一项；值没变时不产生新状态。 */
    private fun setPopupMode(mode: PopupMode) {
        state.update { if (it.popupMode == mode) it else it.copy(popupMode = mode) }
    }

    /** 用户录制了不同的快捷键时重新注册全局热键。 */
    suspend fun observeShortcut() {
        // 等持久化偏好加载完成，并以仓库中的真实设置为注册来源：
        // hostUiState 是 App 组合时才镜像的投影，启动瞬间仍是默认值（⇧⌘C），
        // 按它注册的热键不是用户真正录制的那个，启动头几秒会「按了没反应」。
        repository.settingsLoaded.first { it }
        combine(
            repository.settings.map { it.popupShortcut }.distinctUntilChanged(),
            // 录制快捷键期间把热键**注销**掉，而不是只在回调里忽略它：Carbon 注册的组合会被系统
            // 从窗口事件里吞掉，留着它那一次按键就到不了录制器；注销之后它就只是一次普通按键——
            // 既不触发原动作，也能被设置页里的录制器收下。
            //
            // 条件是「面板正显示着录制器」：面板被托盘收起时录制界面已经不在眼前（组合会留在
            // 那份保留下来的组合里），继续压着全局热键只会让人以为热键坏了。
            panel.hostUiState
                .map { it.isRecordingShortcut && it.isWindowVisible }
                .distinctUntilChanged(),
        ) { spec, recording -> spec.takeUnless { recording } }
            .collectLatest { spec ->
                applyGlobalHotKey(spec)
                try {
                    awaitCancellation()
                } finally {
                    MacGlobalHotKey.unregister(HOT_KEY_POPUP)
                }
            }
    }

    /**
     * 注册呼出面板的系统级热键；`null`（设置页里清除了绑定）表示不注册。
     *
     * 只有它是系统级的，其余可录制快捷键都在面板内匹配（见 `ShortcutSlot.global`）——
     * 所以这里也不需要「暂停键不进按住循环」那类特例。
     */
    private fun applyGlobalHotKey(spec: ShortcutSpec?) {
        MacGlobalHotKey.unregister(HOT_KEY_POPUP)
        if (!native.supportsGlobalHotKeys) return
        val shortcut = spec?.let { GlobalShortcut.fromSpec(it) } ?: return
        MacGlobalHotKey.register(
            id = HOT_KEY_POPUP,
            shortcut = shortcut,
            onTrigger = { onHotKeyPressed() },
            onRelease = { onHotKeyReleased() },
        )
    }
}
