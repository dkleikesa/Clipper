package com.qcmian.clipper.domain.usecase

import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.domain.model.ClipboardSnapshot
import com.qcmian.clipper.domain.repository.ClipboardPlatform
import com.qcmian.clipper.domain.repository.ClipboardRepository
import com.qcmian.clipper.domain.action.ClipAction
import com.qcmian.clipper.domain.action.pastes
import com.qcmian.clipper.domain.action.removesFormatting
import kotlinx.coroutines.delay

/** What [SelectClipUseCase] did with an activation. */
enum class SelectResult {
    /** An unsupported modifier combination; nothing happened. */
    IGNORED,

    /** The platform cannot represent the content; nothing was written. */
    UNSUPPORTED,

    /** The item is on the clipboard and the panel should close. */
    COPIED,

    /** The item is on the clipboard and a paste keystroke was scheduled. */
    PASTING,
}

/**
 * Port of Maccy's `History.select`: writes an item back to the system clipboard and, when the
 * resolved [ClipAction] asks for it, sends the paste keystroke to the previously focused app.
 *
 * The delayed paste is expressed with a plain [delay] inside a suspending function, so the
 * caller decides which scope (and therefore which lifecycle) it runs on.
 */
class SelectClipUseCase(
    private val repository: ClipboardRepository,
    private val platform: ClipboardPlatform,
) {
    /**
     * @param onHidePanel invoked before a synthetic paste so the panel steps aside and the
     *   keystroke reaches the application that was focused before.
     */
    suspend operator fun invoke(
        item: ClipItem,
        action: ClipAction,
        onHidePanel: () -> Unit,
    ): SelectResult {
        // Port of `History.select`: an unsupported modifier combination does nothing.
        if (action == ClipAction.UNKNOWN) return SelectResult.IGNORED

        val settings = repository.settings.value
        val removeFormatting = action.removesFormatting(settings)
        if (!platform.writeClipboard(snapshotFor(item, removeFormatting))) {
            repository.setStatusMessage("This platform can't copy that kind of content")
            return SelectResult.UNSUPPORTED
        }

        // Port of `History.select`: every branch closes the popup, copying included.
        onHidePanel()

        if (!action.pastes(settings)) return SelectResult.COPIED

        delay(PASTE_DELAY_MILLIS)
        if (!platform.paste()) {
            repository.setStatusMessage("Pasting is not supported on this platform")
        }
        return SelectResult.PASTING
    }

    private fun snapshotFor(item: ClipItem, removeFormatting: Boolean): ClipboardSnapshot {
        if (!removeFormatting) {
            return ClipboardSnapshot(
                text = item.text ?: if (item.imageBase64 == null && item.files.isEmpty()) item.previewableText else null,
                imageBase64 = item.imageBase64,
                files = item.files,
            )
        }

        // Port of `Clipboard.clearFormatting(_:)`: keep the plain string *and* the file URLs
        // so "paste without formatting" still pastes files. When the item carries no string
        // representation, behave exactly like a normal copy.
        if (item.text == null) {
            return ClipboardSnapshot(
                text = if (item.imageBase64 == null && item.files.isEmpty()) item.previewableText else null,
                imageBase64 = item.imageBase64,
                files = item.files,
            )
        }
        return ClipboardSnapshot(text = item.text, files = item.files)
    }

    private companion object {
        const val PASTE_DELAY_MILLIS = 160L
    }
}
