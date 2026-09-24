package com.qcmian.clipper.core.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import com.qcmian.clipper.core.settings.ShortcutSpec
import com.qcmian.clipper.core.settings.hasModifiers

/**
 * 当前按下的修饰键，包含 AppKit 使用的 `⌃⌥⇧⌘` 渲染顺序。
 */
class ModifierFlags {
    var control by mutableStateOf(false)
        private set
    var option by mutableStateOf(false)
        private set
    var shift by mutableStateOf(false)
        private set
    var command by mutableStateOf(false)
        private set

    val isEmpty: Boolean get() = !control && !option && !shift && !command

    /** AppKit 顺序下的 `⌃⌥⇧⌘`。 */
    val description: String
        get() = buildString {
            if (control) append('\u2303')
            if (option) append('\u2325')
            if (shift) append('\u21e7')
            if (command) append('\u2318')
        }

    val pressedNames: Set<String>
        get() = buildSet {
            if (control) add("control")
            if (option) add("option")
            if (shift) add("shift")
            if (command) add("command")
        }

    /**
     * 忘掉所有修饰键状态。
     *
     * 修饰键状态是靠「按下置真、松开置假」自己维护的，而录制结束后到达的那次**松开**会被丢在
     * 门外（那时已经没有在录了），状态就停在「还按着」。下一次录制如果照着它算，
     * 上一次按过的 `⌃` 会一直粘在新组合里——录制开始时清一次，才不会串味。
     */
    fun reset() {
        control = false
        option = false
        shift = false
        command = false
    }

    /** 当 [event] 只是按下某个修饰键本身时返回 `true`。 */
    fun isModifierKey(event: KeyEvent): Boolean = when (event.key) {
        Key.ShiftLeft, Key.ShiftRight,
        Key.AltLeft, Key.AltRight,
        Key.MetaLeft, Key.MetaRight,
        Key.CtrlLeft, Key.CtrlRight,
        -> true

        else -> false
    }

    /**
     * 让这些标志与平台保持同步。修饰键以独立的按键事件到达，其它事件则携带当前的修饰键状态。
     */
    fun update(event: KeyEvent) {
        val pressed = event.type == KeyEventType.KeyDown
        when (event.key) {
            Key.ShiftLeft, Key.ShiftRight -> shift = pressed
            Key.AltLeft, Key.AltRight -> option = pressed
            Key.MetaLeft, Key.MetaRight -> command = pressed
            Key.CtrlLeft, Key.CtrlRight -> control = pressed
            else -> {
                shift = event.isShiftPressed
                option = event.isAltPressed
                command = event.isMetaPressed
                control = event.isCtrlPressed
            }
        }
    }

    fun matches(shortcut: KeyShortcut): Boolean =
        control == shortcut.control &&
            option == shortcut.option &&
            shift == shortcut.shift &&
            command == shortcut.command
}

/** 单个被渲染出来的快捷键。 */
data class KeyShortcut(
    val character: String,
    val control: Boolean = false,
    val option: Boolean = false,
    val shift: Boolean = false,
    val command: Boolean = false,
) {
    /** `⌥⌘`，渲染成独立的一段文本。 */
    val modifiers: String
        get() = buildString {
            if (control) append('\u2303')
            if (option) append('\u2325')
            if (shift) append('\u21e7')
            if (command) append('\u2318')
        }

    /** `⌥⌘⌫`，完整的描述。 */
    val label: String get() = modifiers + character
}

/**
 * 「同一按键再加一个 `⇧`」的变体；用于「`⇧` 连续选中」这类派生组合。
 *
 * 绑定本身已带修饰键（`⌘` / `⌥` / `⌃`，或已是 `⇧`）时返回 `null`：这时没有可扩展的余地，
 * 避免与基础绑定撞成同一个组合。
 */
fun ShortcutSpec.shiftVariant(): ShortcutSpec? =
    if (hasModifiers) null else copy(shift = true)

/** 挑出与当前按下修饰键匹配的那个变体。 */
fun visibleShortcut(shortcuts: List<KeyShortcut>, flags: ModifierFlags): KeyShortcut? {
    if (shortcuts.isEmpty()) return null
    if (shortcuts.size == 1) return shortcuts.first()

    val commandVariant = shortcuts.first { it.command && !it.option && !it.shift && !it.control }
    if (flags.isEmpty) return commandVariant
    return shortcuts.firstOrNull { flags.matches(it) } ?: commandVariant
}

/** 按键事件中可被录制的字符；当该键只是修饰键时为 `null`。 */
private val RECORDABLE_KEYS: Map<Key, String> = buildMap {
    put(Key.A, "A"); put(Key.B, "B"); put(Key.C, "C"); put(Key.D, "D"); put(Key.E, "E")
    put(Key.F, "F"); put(Key.G, "G"); put(Key.H, "H"); put(Key.I, "I"); put(Key.J, "J")
    put(Key.K, "K"); put(Key.L, "L"); put(Key.M, "M"); put(Key.N, "N"); put(Key.O, "O")
    put(Key.P, "P"); put(Key.Q, "Q"); put(Key.R, "R"); put(Key.S, "S"); put(Key.T, "T")
    put(Key.U, "U"); put(Key.V, "V"); put(Key.W, "W"); put(Key.X, "X"); put(Key.Y, "Y")
    put(Key.Z, "Z")
    put(Key.Zero, "0"); put(Key.One, "1"); put(Key.Two, "2"); put(Key.Three, "3")
    put(Key.Four, "4"); put(Key.Five, "5"); put(Key.Six, "6"); put(Key.Seven, "7")
    put(Key.Eight, "8"); put(Key.Nine, "9")
    put(Key.Comma, ","); put(Key.Period, "."); put(Key.Slash, "/"); put(Key.Semicolon, ";")
    put(Key.Apostrophe, "'"); put(Key.LeftBracket, "["); put(Key.RightBracket, "]")
    put(Key.Backslash, "\\"); put(Key.Minus, "-"); put(Key.Equals, "="); put(Key.Grave, "`")
    put(Key.Spacebar, " ")
    put(Key.Backspace, "\u232b")
    // 录制器接受任意按键，因此功能键与导航键区也可录制。
    put(Key.F1, "F1"); put(Key.F2, "F2"); put(Key.F3, "F3"); put(Key.F4, "F4")
    put(Key.F5, "F5"); put(Key.F6, "F6"); put(Key.F7, "F7"); put(Key.F8, "F8")
    put(Key.F9, "F9"); put(Key.F10, "F10"); put(Key.F11, "F11"); put(Key.F12, "F12")
    put(Key.DirectionUp, "\u2191"); put(Key.DirectionDown, "\u2193")
    put(Key.DirectionLeft, "\u2190"); put(Key.DirectionRight, "\u2192")
    put(Key.MoveHome, "\u2196"); put(Key.MoveEnd, "\u2198")
    put(Key.PageUp, "\u21de"); put(Key.PageDown, "\u21df")
    put(Key.Tab, "\u21e5"); put(Key.Enter, "\u23ce"); put(Key.NumPadEnter, "\u2324")
    put(Key.Delete, "\u2326"); put(Key.Escape, "\u238b")
}

/** 一次录制的按键事件所代表的字符；无法录制时为 `null`。 */
fun shortcutCharacterOf(event: KeyEvent): String? = RECORDABLE_KEYS[event.key]

/** 规范化录制的字符：单个字母转为大写，符号保持原样。 */
fun normalizeShortcutCharacter(character: String): String =
    if (character.length == 1) character.uppercase() else character

/** 某个录制字符反向映射回的 Compose `Key`。 */
fun keyForShortcutCharacter(character: String): Key? {
    val normalized = normalizeShortcutCharacter(character)
    return RECORDABLE_KEYS.entries.firstOrNull { it.value == normalized }?.key
}

/**
 * 条目快捷键（`⌘1`…`⌘9` / `⌘<字母>`）在某次按键上对应的字符。
 *
 * 以**物理键**为准，而不是平台送来的字符：同一个组合键在不同修饰键与输入法下送来的字符并不
 * 相同（macOS 上 `⌥1` 是 `¡`、`⌃1` 在中文输入法下也未必是 `1`），而条目快捷键要求
 * 「`⌘` / `⌥` / `⌃` / `⇧` 各变体命中同一个条目」。因此先按键位查表，只有查不到键位的
 * （如小键盘数字）才回退到字符本身。
 */
fun shortcutCharacterFor(event: KeyEvent): String? =
    shortcutCharacterOf(event)
        ?: event.utf16CodePoint
            .takeIf { it > 0 }
            ?.toChar()
            ?.let { normalizeShortcutCharacter(it.toString()) }

/**
 * 匹配逻辑，用于用户可录制的快捷键
 * （`pin`、`delete`、`togglePreview`）。
 *
 * [spec] 为 `null`（该槽位在设置页里被清除）时恒为 `false`：没有绑定就没有触发。
 *
 * 在没有 ⌘ 键的平台上，`Ctrl` 兼作 `⌘`，因此 [ShortcutSpec.command] 也接受 Ctrl，
 * 除非该快捷键显式要求 [ShortcutSpec.control]。
 */
fun matchesShortcut(event: KeyEvent, spec: ShortcutSpec?): Boolean {
    if (spec == null) return false
    val expected = keyForShortcutCharacter(spec.character) ?: return false
    if (event.key != expected) return false

    val commandPressed =
        if (spec.control) event.isMetaPressed else (event.isMetaPressed || event.isCtrlPressed)

    return commandPressed == spec.command &&
        event.isCtrlPressed == spec.control &&
        event.isAltPressed == spec.option &&
        event.isShiftPressed == spec.shift
}
