package com.qcmian.clipper.core

import com.qcmian.clipper.settings.AppSettings

/** What happens when a history item is activated. Mirrors Maccy's `HistoryItemAction`. */
enum class ClipAction { COPY, PASTE, PASTE_WITHOUT_FORMATTING }

/**
 * Resolves the action for a plain activation, taking the "Paste automatically" and
 * "Paste without formatting" preferences into account.
 */
fun defaultAction(settings: AppSettings, shift: Boolean, alt: Boolean, meta: Boolean): ClipAction {
    val paste = settings.pasteByDefault
    val plain = if (paste) ClipAction.PASTE else ClipAction.COPY

    return when {
        meta && shift -> if (paste) ClipAction.PASTE else ClipAction.PASTE_WITHOUT_FORMATTING
        meta -> plain
        alt && shift -> ClipAction.PASTE_WITHOUT_FORMATTING
        alt -> if (paste) ClipAction.COPY else ClipAction.PASTE
        else -> plain
    }
}

fun ClipAction.removesFormatting(settings: AppSettings): Boolean =
    this == ClipAction.PASTE_WITHOUT_FORMATTING ||
        (this == ClipAction.COPY && settings.removeFormattingByDefault)
