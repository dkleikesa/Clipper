/*
 * Copyright 2026 Jason Monk
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Originally based on CodeMirror 6 by Marijn Haverbeke, licensed under MIT.
 * See NOTICE file for details.
 */
package com.monkopedia.kodemirror.view

import androidx.compose.ui.input.key.KeyEvent
import com.monkopedia.kodemirror.state.Extension
import com.monkopedia.kodemirror.state.Facet

/**
 * A key binding maps a key name (e.g. `"Ctrl-Enter"`) to a command.
 *
 * @param key The key name for non-Mac platforms.
 * @param mac The key name for macOS (uses Meta instead of Ctrl for Mod-).
 * @param win Windows-specific binding (falls back to [key] if absent).
 * @param linux Linux-specific binding (falls back to [key] if absent).
 * @param run Command to run. Returns true if handled.
 * @param shift Shift-variant of the command.
 * @param any Called for every key event matching the base key (shift insensitive).
 * @param preventDefault Whether to prevent the default browser action.
 * @param stopPropagation Whether to stop event propagation.
 */
data class KeyBinding(
    val key: String? = null,
    val mac: String? = null,
    val win: String? = null,
    val linux: String? = null,
    val run: ((EditorSession) -> Boolean)? = null,
    val shift: ((EditorSession) -> Boolean)? = null,
    val any: ((EditorSession, KeyEvent) -> Boolean)? = null,
    /**
     * Raw key handler for keys that don't produce a Compose [KeyEvent].
     * Called with the browser's key string and modifier state.
     * On wasmJs, Skiko doesn't generate Compose KeyEvents for some symbol
     * keys on the canvas (/, ?, ~, etc.). This handler receives them from
     * the document-level keydown listener.
     */
    val anyRaw: ((EditorSession, String, Boolean, Boolean, Boolean, Boolean) -> Boolean)? = null,
    val preventDefault: Boolean = false,
    val stopPropagation: Boolean = false
)

/** Facet that collects all key bindings from extensions. */
val keymap: Facet<List<KeyBinding>, List<KeyBinding>> = Facet.define(
    combine = { values -> values.flatten() }
)

/**
 * Facet for extensions that need to suppress text input under certain conditions.
 *
 * Each provider returns a function that returns `true` when text input should be
 * suppressed. This is used by the hidden BasicTextField's `onValueChange` handler
 * to prevent character insertion when an extension (like vim in normal mode)
 * handles keys as commands rather than text input.
 *
 * This is the equivalent of CM6's `EditorView.inputHandler` returning `true`.
 */
val inputSuppressor: Facet<() -> Boolean, List<() -> Boolean>> = Facet.define()

/**
 * A block cursor position to render as an overlay rectangle.
 *
 * @param offset Document offset of the cursor position.
 * @param alpha Opacity (0.0 to 1.0). Normal = 1.0, status pending = 0.5, overwrite = 0.2.
 */
data class BlockCursorSpec(val offset: Int, val alpha: Float = 1f)

/**
 * Facet for extensions that provide block cursor positions.
 *
 * Each provider returns a function that produces the current list of block
 * cursor positions. The overlay draws filled rectangles at these positions,
 * covering the full line height and one character width.
 */
val blockCursorProvider: Facet<() -> List<BlockCursorSpec>, List<() -> List<BlockCursorSpec>>> =
    Facet.define()

/** Convenience: create an extension from key bindings. */
fun keymapOf(vararg bindings: KeyBinding): Extension = keymap.of(bindings.toList())

/** Convenience: create an extension from a list of key bindings. */
fun keymapOf(bindings: List<KeyBinding>): Extension = keymap.of(bindings)

/**
 * Builder scope for defining key bindings via DSL.
 *
 * Example:
 * ```kotlin
 * val myKeymap = keymapOf {
 *     "Ctrl-s" { save(it); true }
 *     "Ctrl-z" { undo(it); true }
 *     bind("Ctrl-f", mac = "Meta-f") { openSearch(it); true }
 * }
 * ```
 */
@KeymapDsl
class KeymapBuilder @PublishedApi internal constructor() {
    @PublishedApi
    internal val bindings = mutableListOf<KeyBinding>()

    /** Bind a key to a command. */
    operator fun String.invoke(run: (EditorSession) -> Boolean) {
        bindings.add(KeyBinding(key = this, run = run))
    }

    /** Bind a key with platform-specific overrides. */
    fun bind(
        key: String? = null,
        mac: String? = null,
        win: String? = null,
        linux: String? = null,
        run: (EditorSession) -> Boolean
    ) {
        bindings.add(KeyBinding(key = key, mac = mac, win = win, linux = linux, run = run))
    }
}

/** Marks DSL scope for [KeymapBuilder]. */
@DslMarker
annotation class KeymapDsl

/**
 * Create a keymap extension using a DSL builder.
 *
 * ```kotlin
 * val myKeymap = keymapOf {
 *     "Ctrl-s" { save(it); true }
 *     "Ctrl-z" { undo(it); true }
 * }
 * ```
 */
inline fun keymapOf(block: KeymapBuilder.() -> Unit): Extension =
    keymap.of(KeymapBuilder().apply(block).bindings)

/**
 * Normalize a key name string to a canonical form, resolving modifier aliases.
 *
 * Examples:
 * - `"Ctrl-Enter"` → `"Ctrl-Enter"`
 * - `"cmd-k"` → `"Meta-k"`
 * - `"mod-s"` → `"Ctrl-s"` (or `"Meta-s"` on Mac — caller must pass [mac])
 * - `"Shift-Alt-F"` → `"Alt-Shift-F"` (alphabetical modifier order)
 *
 * @param name The key binding string to normalize.
 * @param mac If true, treat `mod` as `Meta`; otherwise as `Ctrl`.
 */
internal fun normalizeKeyName(name: String, mac: Boolean = false): String {
    // Split on `-` that is not at the very end of the string, so "Ctrl--" works.
    val parts = name.split(Regex("-(?!$)"))
    var result = parts.last()
    if (result == "Space") result = " "

    var alt = false
    var ctrl = false
    var shift = false
    var meta = false

    for (i in 0 until parts.size - 1) {
        when (parts[i].lowercase()) {
            "cmd", "meta", "m" -> meta = true
            "a", "alt" -> alt = true
            "c", "ctrl", "control" -> ctrl = true
            "s", "shift" -> shift = true
            "mod" -> if (mac) meta = true else ctrl = true
            else -> throw IllegalArgumentException("Unrecognized modifier name: ${parts[i]}")
        }
    }

    // Prepend in reverse canonical order so result reads: Alt-Ctrl-Meta-Shift-key
    if (shift) result = "Shift-$result"
    if (meta) result = "Meta-$result"
    if (ctrl) result = "Ctrl-$result"
    if (alt) result = "Alt-$result"
    return result
}
