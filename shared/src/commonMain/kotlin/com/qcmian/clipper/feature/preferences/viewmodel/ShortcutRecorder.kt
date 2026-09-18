package com.qcmian.clipper.feature.preferences.viewmodel

import androidx.compose.ui.input.key.Key
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
 * 它原本长在 `PreferencesDialog` 的 composable body 里（本地 `remember` 状态 + 一个按键回调），
 * 抽出到这里有两个原因：
 * - 录制期间要向宿主上报（停掉系统级热键），这份状态不能随对话框的本地状态一起消失；
 * - 「按下什么组合算数」是一套与渲染无关的规则（裸键 / 与其它槽位重复 / 面板内置按键 / 被系统
 *   占用），它属于 viewmodel 层，与 `HistoryKeyboard.resolveKeyActions` 是同一类东西。
 *
 * 状态写在 [ClipboardUiState.shortcutRecording]（与 `HistorySearchController` 一样，本类与
 * [ClipboardViewModel] 共用同一份状态，不额外造第二份）。
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
    /** 只用来识别「按的是修饰键本身」；它不参与匹配，因此不需要跟随按键更新。 */
    private val modifiers = ModifierFlags()

    /** 开始录制 [slot]；上一次的失败提示随之作废。 */
    fun start(slot: ShortcutSlot) {
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

        // 只认「按下」，且不把修饰键本身当作一次录制：按住 ⌘ 应当继续等后面的键。
        if (event.type != KeyEventType.KeyDown || modifiers.isModifierKey(event)) return true

        // Escape 取消录制，而不是把它录成快捷键。
        if (event.key == Key.Escape) {
            cancel()
            return true
        }

        // 没有可录制字符的按键（大写锁定、无映射的键……）：不进也不退，继续等。
        val character = shortcutCharacterOf(event) ?: return true
        val spec = ShortcutSpec(
            character = character,
            control = event.isCtrlPressed,
            option = event.isAltPressed,
            shift = event.isShiftPressed,
            command = event.isMetaPressed,
        )

        // 先做纯本地检查（裸键 / 与其它槽位重复 / 面板内置按键），系统级槽位再问一次平台：
        // 这个组合有没有被别的应用占用。本来就是这个槽位的绑定则不必问——它已经注册过了。
        val rejected = shortcutProblem(spec, slot, current.settings)
            ?: if (slot.global && spec != current.settings.shortcut(slot) && !canUseGlobalShortcut(spec)) {
                ShortcutProblem.OCCUPIED
            } else {
                null
            }

        if (rejected == null) {
            state.update { it.copy(shortcutRecording = ShortcutRecording()) }
            updateSettings { it.withShortcut(slot, spec) }
        } else {
            // 不可用：**留在录制状态**等下一个组合，只把原因说出来。退出录制的话用户还得再点一次
            // 那一行——被占用本来就不该算「这一次录制结束了」。
            state.update { it.copy(shortcutRecording = it.shortcutRecording.copy(problem = rejected)) }
        }
        return true
    }
}
