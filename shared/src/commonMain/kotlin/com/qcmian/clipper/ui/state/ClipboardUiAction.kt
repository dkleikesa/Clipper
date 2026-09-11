package com.qcmian.clipper.ui.state

import com.qcmian.clipper.data.model.ClipItem
import com.qcmian.clipper.domain.action.ClipAction
import com.qcmian.clipper.settings.AppSettings
import com.qcmian.clipper.ui.components.FooterAction

/**
 * Everything the user (or the host) can ask the history screen to do. The UI only ever sends
 * these; the `ClipboardViewModel` is the only place that reacts to them.
 */
sealed interface ClipboardUiAction {

    // ------------------------------------------------------------------ search
    data class UpdateQuery(val value: String) : ClipboardUiAction
    data object ClearSearch : ClipboardUiAction
    data object DeleteSearchChar : ClipboardUiAction
    data object DeleteSearchWord : ClipboardUiAction
    data object CopySearchQuery : ClipboardUiAction

    // ------------------------------------------------------------------ navigation
    /** `MouseMovedViewModifier`: mouse movement ends keyboard navigation. */
    data object PointerMoved : ClipboardUiAction
    data class HoverHistory(val index: Int) : ClipboardUiAction
    data class HoverFooter(val index: Int) : ClipboardUiAction
    data class SelectHistory(val index: Int) : ClipboardUiAction
    data class MoveNext(val allowCycle: Boolean = false) : ClipboardUiAction
    data object MovePrevious : ClipboardUiAction
    data object MoveToFirst : ClipboardUiAction
    data object MoveToLast : ClipboardUiAction

    // ------------------------------------------------------------------ activation
    /** Enter / click on a history row; the modifiers pick the actual [ClipAction]. */
    data class Activate(
        val index: Int,
        val shift: Boolean = false,
        val alt: Boolean = false,
        val meta: Boolean = false,
    ) : ClipboardUiAction

    /** `⌘1`…`⌘9` / `⌘<letter>`: the action is already resolved. */
    data class ActivateShortcut(val index: Int, val action: ClipAction) : ClipboardUiAction

    data class RunFooter(val action: FooterAction) : ClipboardUiAction

    /** `Esc`: clears the search and closes the panel, exactly like `KeyChord.close`. */
    data object Escape : ClipboardUiAction

    // ------------------------------------------------------------------ item actions
    data object TogglePinSelected : ClipboardUiAction
    data object DeleteSelected : ClipboardUiAction
    data object TogglePreview : ClipboardUiAction
    data object CopyExtractedText : ClipboardUiAction
    data class SetPreviewWidth(val width: Int) : ClipboardUiAction

    data class TogglePin(val item: ClipItem) : ClipboardUiAction
    data class DeleteItem(val item: ClipItem) : ClipboardUiAction

    // ------------------------------------------------------------------ preferences
    data class UpdateSettings(val transform: (AppSettings) -> AppSettings) : ClipboardUiAction
    data class UpdatePin(val item: ClipItem, val pin: String?) : ClipboardUiAction
    data class UpdateTitle(val item: ClipItem, val title: String) : ClipboardUiAction
    data class UpdateContent(val item: ClipItem, val text: String) : ClipboardUiAction
    data object PickIgnoredApplication : ClipboardUiAction
    data class OpenUrl(val url: String) : ClipboardUiAction

    // ------------------------------------------------------------------ dialogs
    data object ShowPreferences : ClipboardUiAction
    data object DismissPreferences : ClipboardUiAction
    data object ShowAbout : ClipboardUiAction
    data object DismissAbout : ClipboardUiAction
    data class RequestClear(val all: Boolean, val hidePanel: Boolean = true) : ClipboardUiAction
    data object ConfirmClear : ClipboardUiAction
    data object DismissClear : ClipboardUiAction

    // ------------------------------------------------------------------ status
    data object DismissStatus : ClipboardUiAction

    // ------------------------------------------------------------------ host driven
    /** The global hot key opened the panel (`Popup.handleFirstKeyDown`). */
    data object Opened : ClipboardUiAction
    /** The global hot key repeated while the panel is open (`PopupState.cycle`). */
    data object Cycle : ClipboardUiAction
    /** The modifiers were released in cycle mode (`Popup.handleFlagsChanged`). */
    data object Accept : ClipboardUiAction
    /** The host hid the panel; the preview closes with it (`FloatingPanel.close`). */
    data object Hidden : ClipboardUiAction
    /** ⌥ / ⇧⌥ click on the status item. */
    data class TogglePause(val onlyNext: Boolean = false) : ClipboardUiAction
}
