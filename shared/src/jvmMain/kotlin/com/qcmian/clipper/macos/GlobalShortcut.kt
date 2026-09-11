package com.qcmian.clipper.macos

import com.qcmian.clipper.settings.ShortcutSpec

/**
 * 一个系统级键盘快捷键，用 Carbon 虚拟键码描述。
 *
 * 这是 macOS 专属概念：它放在 JVM 源集中、与 [MacGlobalHotKey] 相邻，而不是放在 `commonMain`，
 * 从而让共享的 `NativeDataSource` 契约不掺杂平台专属的键码表。
 */
data class GlobalShortcut(
    val keyCode: Int,
    val command: Boolean = true,
    val shift: Boolean = false,
    val option: Boolean = false,
    val control: Boolean = false,
) {
    companion object {
        /** `⇧⌘C`，Maccy 用来呼出面板的快捷键。 */
        val POPUP = GlobalShortcut(keyCode = KEY_C, command = true, shift = true)

        /** `C` 的 Carbon 虚拟键码。 */
        const val KEY_C = 8

        /**
         * 对应 Maccy 的 `KeyChord`/`Sauce` 查表：把用户录制的字符转换成
         * `RegisterEventHotKey` 期望的 Carbon 虚拟键码。对没有稳定 ANSI 键码的字符返回 `null`。
         */
        fun fromSpec(spec: ShortcutSpec): GlobalShortcut? {
            val keyCode = carbonKeyCode(spec.character) ?: return null
            return GlobalShortcut(
                keyCode = keyCode,
                command = spec.command,
                shift = spec.shift,
                option = spec.option,
                control = spec.control,
            )
        }

        /** ANSI 虚拟键码，见 `HIToolbox/Events.h`。 */
        private val CARBON_KEY_CODES: Map<String, Int> = buildMap {
            put("A", 0); put("S", 1); put("D", 2); put("F", 3); put("H", 4); put("G", 5)
            put("Z", 6); put("X", 7); put("C", 8); put("V", 9); put("B", 11); put("Q", 12)
            put("W", 13); put("E", 14); put("R", 15); put("Y", 16); put("T", 17); put("1", 18)
            put("2", 19); put("3", 20); put("4", 21); put("6", 22); put("5", 23); put("=", 24)
            put("9", 25); put("7", 26); put("-", 27); put("8", 28); put("0", 29); put("]", 30)
            put("O", 31); put("U", 32); put("[", 33); put("I", 34); put("P", 35); put("L", 37)
            put("J", 38); put("'", 39); put("K", 40); put(";", 41); put("\\", 42); put(",", 43)
            put("/", 44); put("N", 45); put("M", 46); put(".", 47); put(" ", 49); put("`", 50)
            // 功能键，`kVK_F1`…`kVK_F12`。
            put("F1", 122); put("F2", 120); put("F3", 99); put("F4", 118)
            put("F5", 96); put("F6", 97); put("F7", 98); put("F8", 100)
            put("F9", 101); put("F10", 109); put("F11", 103); put("F12", 111)
            // 导航键区。
            put("\u2190", 123); put("\u2192", 124); put("\u2193", 125); put("\u2191", 126)
            put("\u2196", 115); put("\u2198", 119); put("\u21de", 116); put("\u21df", 121)
            put("\u21e5", 48); put("\u23ce", 36); put("\u2324", 76)
            put("\u2326", 117); put("\u238b", 53)
        }

        fun carbonKeyCode(character: String): Int? {
            val key = if (character.length == 1) character[0].uppercaseChar().toString() else character
            return CARBON_KEY_CODES[key]
        }
    }
}

/** [MacGlobalHotKey.register] 返回的句柄。 */
interface GlobalHotKeyHandle {
    fun unregister()
}
