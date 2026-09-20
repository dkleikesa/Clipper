package com.qcmian.clipper.core.domain.action

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
 * 偏好项 `pasteByDefault` 与 `removeFormattingByDefault` 都会改变每一种组合的含义，
 * 因此这里需要覆盖全部十二种情况。
 */
fun defaultAction(settings: AppSettings, shift: Boolean, alt: Boolean, meta: Boolean): ClipAction {
    val paste = settings.pasteByDefault
    val removeFormatting = settings.removeFormattingByDefault

    return when {
        // ⌘
        meta && !alt && !shift -> when {
            !paste -> ClipAction.COPY
            !removeFormatting -> ClipAction.PASTE
            else -> ClipAction.PASTE_WITHOUT_FORMATTING
        }

        // ⌥
        alt && !meta && !shift -> when {
            !paste && !removeFormatting -> ClipAction.PASTE
            !paste && removeFormatting -> ClipAction.PASTE_WITHOUT_FORMATTING
            else -> ClipAction.COPY
        }

        // ⌥⇧
        alt && shift && !meta -> when {
            !paste && !removeFormatting -> ClipAction.PASTE_WITHOUT_FORMATTING
            !paste && removeFormatting -> ClipAction.PASTE
            else -> ClipAction.UNKNOWN
        }

        // ⌘⇧
        meta && shift && !alt -> when {
            paste && !removeFormatting -> ClipAction.PASTE_WITHOUT_FORMATTING
            paste && removeFormatting -> ClipAction.PASTE
            else -> ClipAction.UNKNOWN
        }

        // 完全不按修饰键：按 `removeFormattingByDefault` 处理，
        // 且只有 `pasteByDefault` 开启时才粘贴。
        !shift && !alt && !meta -> ClipAction.DEFAULT

        else -> ClipAction.UNKNOWN
    }
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
 * 会触发 [action] 的修饰键组合，
 * 偏好设置窗口用它来解释当前的映射关系。
 *
 * 由 [defaultAction] 反推，而不是另写一张表：正反两个方向共用同一份规则，改映射不会漂移。
 * 一个动作可能被多个组合命中（取决于两个 `*ByDefault` 偏好），取最简的那个。
 */
fun modifierFlagsOf(action: ClipAction, settings: AppSettings): String =
    MODIFIER_COMBOS
        .firstOrNull { defaultAction(settings, it.shift, it.alt, it.meta) == action }
        ?.label
        .orEmpty()
