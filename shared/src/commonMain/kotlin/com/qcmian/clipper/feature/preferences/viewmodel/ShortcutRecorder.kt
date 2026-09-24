package com.qcmian.clipper.feature.preferences.viewmodel

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.ShortcutSlot
import com.qcmian.clipper.core.settings.ShortcutSpec
import com.qcmian.clipper.core.settings.shortcut
import com.qcmian.clipper.core.settings.withShortcut
import com.qcmian.clipper.core.ui.ModifierFlags
import com.qcmian.clipper.core.ui.ShortcutProblem
import com.qcmian.clipper.core.ui.shortcutCharacterOf
import com.qcmian.clipper.core.ui.shortcutProblem
import com.qcmian.clipper.feature.history.state.ClipboardUiState
import com.qcmian.clipper.feature.preferences.state.ShortcutRecording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * 设置页里「录制一个快捷键」的完整状态机。
 *
 * 它原本长在设置页的 composable body 里（本地 `remember` 状态 + 一个按键回调），
 * 抽出到这里有两个原因：
 * - 录制期间要向宿主上报（停掉系统级热键），这份状态不能随对话框的本地状态一起消失；
 * - 「按下什么组合算数」是一套与渲染无关的规则（裸键 / 与其它槽位重复 / 面板内置按键 / 被系统
 *   占用），它属于 viewmodel 层，与 `HistoryKeyboard.resolveKeyActions` 是同一类东西。
 *
 * 状态写在 [ClipboardUiState.shortcutRecording]（与 `HistorySearchController` 一样，本类与
 * [ClipboardViewModel] 共用同一份状态，不额外造第二份）。
 *
 * **没有「取消录制」的按键**：`⎋` 本身就是默认的「清空搜索 / 关闭面板」，把它占作取消键，
 * 用户就再也没法把 `⎋` 录回绑定里。取消改由界面承担——再点一次那条正在录制的行
 * （见 `ShortcutRow`），关掉设置窗口同样会收回录制。
 *
 * 两处只服务于「快速粘贴置顶项」的特例（它本质是数字键 `1…9` 的**修饰键前缀**，而不是一条
 * 按键绑定）：
 * - 允许**只按修饰键**、松手即录（见 [onModifierReleased]）——否则用户必须多按一个数字；
 * - 落库前把字符归一到 `1`，那一行才永远显示 `⌘1` 这种形式，而不是「录了 `⌘5` 却管着九个键」。
 *
 * @param updateSettings 落盘一次绑定；与设置页其它改动走同一条路径，因此窗口 / 托盘 / 热键
 *   的连带副作用都由既有观察者处理。
 * @param canUseGlobalShortcut 试注册一次全局快捷键，判断它是否已被系统或其它应用占用（见
 *   `NativeDataSource.isGlobalShortcutAvailable`）。平台无法判断时必须返回 `true`
 *   ——无从判断不该拦住用户。
 */
internal class ShortcutRecorder(
    private val state: MutableStateFlow<ClipboardUiState>,
    private val updateSettings: ((AppSettings) -> AppSettings) -> Unit,
    private val canUseGlobalShortcut: (ShortcutSpec) -> Boolean = { true },
) {
    /** 修饰键的当前按下状态；只认「按的是不是修饰键」与「现在按着哪些」。 */
    private val modifiers = ModifierFlags()

    /**
     * 本轮「按修饰键」期间是否还按过别的键。
     *
     * 用来区分「`⌘` + `C` 这种组合」与「只按了 `⌥` 就松手」：前者松修饰键时不该再录一次，
     * 后者正是快速粘贴要的输入方式。每按下一个修饰键就重置（那是新一轮的开始）。
     */
    private var typedKeyInChord = false

    /** 开始录制 [slot]；上一次的失败提示随之作废。 */
    fun start(slot: ShortcutSlot) {
        // 丢掉上一轮残留的修饰键状态：录制成功那一刻会提前结束，用户随后**松开**修饰键的那次
        // 事件已经没人接（录制不在进行中），状态就停在「还按着」。不清的话，上一次按过的 `⌃`
        // 会一直粘在之后每一次录制里。
        modifiers.reset()
        typedKeyInChord = false
        state.update { it.copy(shortcutRecording = ShortcutRecording(slot = slot)) }
    }

    /**
     * 结束录制并忘掉失败提示。
     *
     * 对话框离开屏幕时也要调用：录制状态下系统级热键是停着的，不收回来就会一直哑着。
     * 没有在录制时是空操作。
     */
    fun cancel() {
        if (!state.value.shortcutRecording.isActive) return
        state.update { it.copy(shortcutRecording = ShortcutRecording()) }
    }

    /**
     * 录制期间截获一次按键。
     *
     * 返回 `true` 表示这次按键已被录制器消费——录制中的所有按键都返回 `true`：否则用户按下的
     * 组合会继续往下传，打进对话框里的输入框、或落到面板自己的快捷键上。
     * 没有在录制时返回 `false`，事件照常流转。
     */
    fun onKeyEvent(event: KeyEvent): Boolean {
        val current = state.value
        val slot = current.shortcutRecording.slot ?: return false

        if (modifiers.isModifierKey(event)) {
            onModifierEvent(event, slot, current)
            return true
        }

        if (event.type != KeyEventType.KeyDown) return true

        // 按过实体键就说明这一轮不是「只按修饰键」；哪怕它没有可录制字符（大写锁定等）也算。
        typedKeyInChord = true

        // 没有可录制字符的按键（大写锁定、无映射的键……）：不进也不退，继续等。
        val character = shortcutCharacterOf(event) ?: return true
        val spec = ShortcutSpec(
            character = character,
            control = event.isCtrlPressed,
            option = event.isAltPressed,
            shift = event.isShiftPressed,
            command = event.isMetaPressed,
        )
        tryRecord(spec, slot, current.settings)
        return true
    }

    /**
     * 修饰键的按下 / 松开。
     *
     * 「快速粘贴」之外，修饰键只是组合的一部分：按住 `⌘` 要继续等后面的实体键，因此这里不动。
     * 对「快速粘贴」而言，松手本身就是一次完整的输入——数字 `1…9` 是固定的，用户要选的只是
     * 修饰键。判定条件严格一些：这一轮里**没有**按过任何实体键，且松手时确实按着修饰键。
     */
    private fun onModifierEvent(event: KeyEvent, slot: ShortcutSlot, current: ClipboardUiState) {
        if (event.type == KeyEventType.KeyDown) {
            typedKeyInChord = false
            modifiers.update(event)
            return
        }
        if (event.type != KeyEventType.KeyUp) return

        // 松开前那一刻按着哪些修饰键；`update` 会把这一次松开也写进去，因此必须先取。
        val held = ShortcutSpec(
            character = "1",
            control = modifiers.control,
            option = modifiers.option,
            shift = modifiers.shift,
            command = modifiers.command,
        )
        modifiers.update(event)

        val onlyModifiers = slot == ShortcutSlot.QUICK_SELECT &&
            !typedKeyInChord &&
            (held.control || held.option || held.shift || held.command)
        if (onlyModifiers) tryRecord(held, slot, current.settings)
    }

    /**
     * 把一次候选组合交给校验，通过就落库、否则留在录制状态说出原因。
     *
     * 不可用时**不退出录制**：退出的话用户还得再点一次那一行——被拒绝本来就不该算「这一次
     * 录制结束了」。
     */
    private fun tryRecord(spec: ShortcutSpec, slot: ShortcutSlot, settings: AppSettings) {
        // 先做纯本地检查（裸键 / 与其它槽位重复 / 面板内置按键），系统级槽位再问一次平台：
        // 这个组合有没有被别的应用占用。本来就是这个槽位的绑定则不必问——它已经注册过了。
        val rejected = shortcutProblem(spec, slot, settings)
            ?: if (slot.global && spec != settings.shortcut(slot) && !canUseGlobalShortcut(spec)) {
                ShortcutProblem.OCCUPIED
            } else {
                null
            }

        if (rejected == null) {
            // 快速粘贴的字符只是占位（真正生效的是修饰键），统一归一到 `1`：
            // 否则按 `⌘5` 录进去，那一行写着 `⌘5`，实际却管着 `⌘1`…`⌘9`。
            val stored = if (slot == ShortcutSlot.QUICK_SELECT) spec.copy(character = "1") else spec
            state.update { it.copy(shortcutRecording = ShortcutRecording()) }
            updateSettings { it.withShortcut(slot, stored) }
        } else {
            state.update { it.copy(shortcutRecording = it.shortcutRecording.copy(problem = rejected)) }
        }
    }
}
