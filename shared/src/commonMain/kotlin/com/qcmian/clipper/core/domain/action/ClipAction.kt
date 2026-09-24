package com.qcmian.clipper.core.domain.action

import com.qcmian.clipper.core.settings.ActivateAction
import com.qcmian.clipper.core.settings.AppSettings

/**
 * 激活一条历史记录时会发生什么。，包含 `unknown`
 * （不支持的修饰键组合，必须保持面板不变）与 `default`（未按任何修饰键的普通激活）。
 */
enum class ClipAction { DEFAULT, COPY, PASTE, PASTE_WITHOUT_FORMATTING, UNKNOWN }

/**
 * 为当前按下的确切修饰键组合解析出动作。
 * [meta] 是 `⌘`（没有该键的平台上是 `Ctrl`）。
 *
 * 带修饰键的三条组合（`⌘` / `⌥` / `⌥⇧`）由用户在设置里**直接指定**
 * （[AppSettings.activateWithCommand] / [AppSettings.activateWithOption] /
 * [AppSettings.activateWithShiftOption]）；**不带修饰键**的那一条是 `DEFAULT`，它的粘贴与去格式
 * 仍然由 `pasteByDefault` / `removeFormattingByDefault` 决定。
 *
 * `⌘⇧` 与「只按 `⇧`」等其余组合一律 `UNKNOWN`（面板保持不变）：它们没有对应的设置项。
 */
fun defaultAction(settings: AppSettings, shift: Boolean, alt: Boolean, meta: Boolean): ClipAction =
    when {
        // ⌘
        meta && !alt && !shift -> settings.activateWithCommand.toClipAction()

        // ⌥
        alt && !meta && !shift -> settings.activateWithOption.toClipAction()

        // ⌥⇧
        alt && shift && !meta -> settings.activateWithShiftOption.toClipAction()

        // 完全不按修饰键：按 `removeFormattingByDefault` 处理，
        // 且只有 `pasteByDefault` 开启时才粘贴。
        !shift && !alt && !meta -> ClipAction.DEFAULT

        else -> ClipAction.UNKNOWN
    }

/** 设置里的动作（三选一）→ 按键解析用的动作。 */
fun ActivateAction.toClipAction(): ClipAction = when (this) {
    ActivateAction.COPY -> ClipAction.COPY
    ActivateAction.PASTE -> ClipAction.PASTE
    ActivateAction.PASTE_WITHOUT_FORMATTING -> ClipAction.PASTE_WITHOUT_FORMATTING
}

/**
 * 只有普通激活与显式的「不带格式粘贴」会去掉格式。
 * 即便开启了 `removeFormattingByDefault`，显式的 `⌘` 复制也从不这么做。
 */
fun ClipAction.removesFormatting(settings: AppSettings): Boolean = when (this) {
    ClipAction.PASTE_WITHOUT_FORMATTING -> true
    ClipAction.DEFAULT -> settings.removeFormattingByDefault
    else -> false
}

/** 该动作是否同时触发一次粘贴。 */
fun ClipAction.pastes(settings: AppSettings): Boolean = when (this) {
    ClipAction.PASTE, ClipAction.PASTE_WITHOUT_FORMATTING -> true
    ClipAction.DEFAULT -> settings.pasteByDefault
    else -> false
}

/** 一个可能触发动作的修饰键组合。 */
private data class ModifierCombo(val shift: Boolean, val alt: Boolean, val meta: Boolean) {
    /** AppKit 顺序（`⌃⌥⇧⌘`）下的渲染；这里不涉及 `⌃`。 */
    val label: String
        get() = buildString {
            if (alt) append('\u2325')
            if (shift) append('\u21e7')
            if (meta) append('\u2318')
        }
}

/**
 * 可能触发动作的修饰键组合，按「最简优先」排列。
 *
 * 只有这四种：完全不按修饰键是 `DEFAULT`（没有对应的组合可显示），而「只按 `⇧`」与
 * 「`⌥⌘` 同按」在 [defaultAction] 里都是 `UNKNOWN`。
 */
private val MODIFIER_COMBOS = listOf(
    ModifierCombo(shift = false, alt = false, meta = true), // ⌘
    ModifierCombo(shift = false, alt = true, meta = false), // ⌥
    ModifierCombo(shift = true, alt = true, meta = false), // ⌥⇧
    ModifierCombo(shift = true, alt = false, meta = true), // ⌘⇧
)

/**
 * 会触发 [action] 的修饰键组合（`⌘` / `⌥` / `⌥⇧` / `⌘⇧` 中的第一个）。
 *
 * 由 [defaultAction] 反推，而不是另写一张表：正反两个方向共用同一份规则，改映射不会漂移。
 * 多个组合指向同一动作时取最简的那个（`MODIFIER_COMBOS` 已按此排序）。
 */
fun modifierFlagsOf(action: ClipAction, settings: AppSettings): String =
    MODIFIER_COMBOS
        .firstOrNull { defaultAction(settings, it.shift, it.alt, it.meta) == action }
        ?.label
        .orEmpty()

/**
 * 触发 [action] 的那条激活组合（修饰键 + 激活键本身），例如 `⌘⏎`；没有对应组合时为空串。
 *
 * 右键菜单用它现算「复制 / 粘贴」旁边该显示什么键位：映射由设置指定，写死 `↵` / `⌥↵`
 * 会在用户改过映射之后失真。
 */
fun activateComboLabel(action: ClipAction, settings: AppSettings): String {
    val modifiers = modifierFlagsOf(action, settings)
    val key = settings.activateShortcut?.character ?: return ""
    return if (modifiers.isEmpty()) "" else modifiers + key
}
