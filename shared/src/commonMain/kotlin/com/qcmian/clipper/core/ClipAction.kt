package com.qcmian.clipper.core

import com.qcmian.clipper.settings.AppSettings

/**
 * What happens when a history item is activated. Mirrors Maccy's `HistoryItemAction`,
 * including `unknown` (an unsupported modifier combination that must leave the panel
 * untouched) and `default` (a plain activation with no modifier held down).
 */
enum class ClipAction { DEFAULT, COPY, PASTE, PASTE_WITHOUT_FORMATTING, UNKNOWN }

/**
 * Port of `HistoryItemAction.init(_:)`: resolves the action for the exact modifier
 * combination that is held down. [meta] is `⌘` (or `Ctrl` on platforms without one).
 *
 * The preferences `pasteByDefault` and `removeFormattingByDefault` both change the
 * meaning of every combination, so all twelve Maccy cases are reproduced here.
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

        // No modifier at all: `History.select` applies `removeFormattingByDefault` and
        // pastes only when `pasteByDefault` is on.
        !shift && !alt && !meta -> ClipAction.DEFAULT

        else -> ClipAction.UNKNOWN
    }
}

/**
 * Port of `History.select`: only the plain activation and the explicit
 * "paste without formatting" strip the formatting. An explicit `⌘`-copy never does,
 * even when `removeFormattingByDefault` is enabled.
 */
fun ClipAction.removesFormatting(settings: AppSettings): Boolean = when (this) {
    ClipAction.PASTE_WITHOUT_FORMATTING -> true
    ClipAction.DEFAULT -> settings.removeFormattingByDefault
    else -> false
}

/** Port of `History.select`: whether the action also triggers a paste. */
fun ClipAction.pastes(settings: AppSettings): Boolean = when (this) {
    ClipAction.PASTE, ClipAction.PASTE_WITHOUT_FORMATTING -> true
    ClipAction.DEFAULT -> settings.pasteByDefault
    else -> false
}

/**
 * Port of `HistoryItemAction.modifierFlags`: the modifier combination that triggers
 * [action], used by the preferences window to explain the current mapping.
 */
fun modifierFlagsOf(action: ClipAction, settings: AppSettings): String {
    val paste = settings.pasteByDefault
    val removeFormatting = settings.removeFormattingByDefault

    return when {
        action == ClipAction.COPY && !paste -> "⌘"
        action == ClipAction.COPY && paste -> "⌥"
        action == ClipAction.PASTE && paste && !removeFormatting -> "⌘"
        action == ClipAction.PASTE && !paste && !removeFormatting -> "⌥"
        action == ClipAction.PASTE && !paste && removeFormatting -> "⌥⇧"
        action == ClipAction.PASTE && paste && removeFormatting -> "⌘⇧"
        action == ClipAction.PASTE_WITHOUT_FORMATTING && paste && removeFormatting -> "⌘"
        action == ClipAction.PASTE_WITHOUT_FORMATTING && !paste && removeFormatting -> "⌥"
        action == ClipAction.PASTE_WITHOUT_FORMATTING && !paste && !removeFormatting -> "⌥⇧"
        action == ClipAction.PASTE_WITHOUT_FORMATTING && paste && !removeFormatting -> "⌘⇧"
        else -> ""
    }
}
