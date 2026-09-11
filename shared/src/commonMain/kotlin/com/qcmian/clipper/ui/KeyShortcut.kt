package com.qcmian.clipper.ui

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
import com.qcmian.clipper.domain.model.ShortcutSpec

/**
 * The modifier keys currently held down. Counterpart of `NSEvent.ModifierFlags`, including
 * the `⌃⌥⇧⌘` rendering order used by AppKit.
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

    /** `⌃⌥⇧⌘` in AppKit's order. */
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

    /** `true` when [event] only carries a modifier key. */
    fun isModifierKey(event: KeyEvent): Boolean = when (event.key) {
        Key.ShiftLeft, Key.ShiftRight,
        Key.AltLeft, Key.AltRight,
        Key.MetaLeft, Key.MetaRight,
        Key.CtrlLeft, Key.CtrlRight,
        -> true

        else -> false
    }

    /**
     * Keeps the flags in sync with the platform. Modifier keys arrive as their own key
     * events, every other event carries the current flags.
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

/** A single rendered shortcut, equivalent to one `KeyShortcut` in Maccy. */
data class KeyShortcut(
    val character: String,
    val control: Boolean = false,
    val option: Boolean = false,
    val shift: Boolean = false,
    val command: Boolean = false,
) {
    /** `⌥⌘`, rendered as its own text run by `KeyboardShortcutView`. */
    val modifiers: String
        get() = buildString {
            if (control) append('\u2303')
            if (option) append('\u2325')
            if (shift) append('\u21e7')
            if (command) append('\u2318')
        }

    /** `⌥⌘⌫`, the full description. */
    val label: String get() = modifiers + character
}

/**
 * Port of `KeyShortcut.create(character:)`: the plain ⌘ variant, the ⌥ variant and the
 * "paste without formatting" variant.
 */
fun keyShortcuts(character: String, pasteByDefault: Boolean): List<KeyShortcut> = listOf(
    KeyShortcut(character = character, command = true),
    KeyShortcut(character = character, option = true),
    if (pasteByDefault) {
        KeyShortcut(character = character, command = true, shift = true)
    } else {
        KeyShortcut(character = character, option = true, shift = true)
    },
)

/** Port of `KeyShortcut.isVisible(_:_:)`: picks the variant matching the pressed modifiers. */
fun visibleShortcut(shortcuts: List<KeyShortcut>, flags: ModifierFlags): KeyShortcut? {
    if (shortcuts.isEmpty()) return null
    if (shortcuts.size == 1) return shortcuts.first()

    val commandVariant = shortcuts.first { it.command && !it.option && !it.shift && !it.control }
    if (flags.isEmpty) return commandVariant
    return shortcuts.firstOrNull { flags.matches(it) } ?: commandVariant
}

/** The recordable character of a key event, `null` when the key only carries a modifier. */
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
    // Maccy's `KeyboardShortcuts.Recorder` accepts every key, so the function keys and the
    // navigation cluster are recordable as well.
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

/** The character a recorded key event represents, or `null` when it cannot be recorded. */
fun shortcutCharacterOf(event: KeyEvent): String? = RECORDABLE_KEYS[event.key]

/** Normalises a recorded character: single letters are uppercased, symbols stay as they are. */
fun normalizeShortcutCharacter(character: String): String =
    if (character.length == 1) character.uppercase() else character

/** The Compose `Key` a recorded character maps back to. */
fun keyForShortcutCharacter(character: String): Key? {
    val normalized = normalizeShortcutCharacter(character)
    return RECORDABLE_KEYS.entries.firstOrNull { it.value == normalized }?.key
}

/**
 * Port of Maccy's `KeyChord` matching for the shortcuts the user can record
 * (`pin`, `delete`, `togglePreview`).
 *
 * On platforms without a ⌘ key, `Ctrl` doubles as `⌘`, which is why [ShortcutSpec.command]
 * also accepts Ctrl unless the shortcut explicitly asks for [ShortcutSpec.control].
 */
fun matchesShortcut(event: KeyEvent, spec: ShortcutSpec): Boolean {
    val expected = keyForShortcutCharacter(spec.character) ?: return false
    if (event.key != expected) return false

    val commandPressed =
        if (spec.control) event.isMetaPressed else (event.isMetaPressed || event.isCtrlPressed)

    return commandPressed == spec.command &&
        event.isCtrlPressed == spec.control &&
        event.isAltPressed == spec.option &&
        event.isShiftPressed == spec.shift
}
