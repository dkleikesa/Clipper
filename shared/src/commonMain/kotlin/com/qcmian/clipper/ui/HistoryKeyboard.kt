package com.qcmian.clipper.ui

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
import com.qcmian.clipper.domain.action.ClipAction
import com.qcmian.clipper.domain.action.defaultAction
import com.qcmian.clipper.ui.components.FooterAction
import com.qcmian.clipper.ui.state.ClipboardUiAction
import com.qcmian.clipper.ui.state.ClipboardUiState

/**
 * Port of Maccy's `KeyChord`/`KeyHandlingView`: translates a key event into the actions the
 * screen should dispatch. Keeping this out of the composable is what lets the screen stay a
 * pure renderer of [ClipboardUiState].
 *
 * Returns an empty list when the event is not handled and should keep propagating (so plain
 * typing reaches the search field).
 */
fun resolveKeyActions(
    event: KeyEvent,
    state: ClipboardUiState,
    flags: ModifierFlags,
    composing: Boolean,
    shortcuts: Map<String, List<KeyShortcut>>,
    footerActions: List<FooterAction>,
): List<ClipboardUiAction> {
    // Port of `KeyHandlingView`: ignore input while an IME candidate window is open.
    if (composing) return emptyList()
    if (event.type != KeyEventType.KeyDown || flags.isModifierKey(event)) return emptyList()

    val settings = state.settings
    val meta = event.isMetaPressed || event.isCtrlPressed
    val alt = event.isAltPressed
    val control = event.isCtrlPressed
    val shift = event.isShiftPressed
    val firstItemHighlighted = state.footerSelection < 0 && state.historySelection == 0

    return when {
        // moveToLast: ⌃⌥N, Maccy's (.n, [.control, .option])
        control && alt && event.key == Key.N -> listOf(ClipboardUiAction.MoveToLast)

        // moveToFirst: ⌃⌥P, Maccy's (.p, [.control, .option])
        control && alt && event.key == Key.P -> listOf(ClipboardUiAction.MoveToFirst)

        // moveToNext: ↓ / ⇧↓ / ⌃N / ⌃⇧N / ⌃J
        (event.key == Key.DirectionDown && !alt && !meta) ||
            (control && (event.key == Key.N || event.key == Key.J)) -> listOf(ClipboardUiAction.MoveNext())

        // moveToPrevious: ↑ / ⇧↑ / ⌃P / ⌃⇧P
        (event.key == Key.DirectionUp && !alt && !meta) ||
            (control && event.key == Key.P) -> listOf(ClipboardUiAction.MovePrevious)

        // moveToPrevious: ⌃K, but only while the first item is not highlighted; otherwise the
        // key falls through and types into the search field (#1055).
        control && event.key == Key.K && !firstItemHighlighted -> listOf(ClipboardUiAction.MovePrevious)

        // moveToLast: ⌘↓ / ⌥↓ / PageDown
        (event.key == Key.DirectionDown && (meta || alt)) || event.key == Key.PageDown ->
            listOf(ClipboardUiAction.MoveToLast)

        // moveToFirst: ⌘↑ / ⌥↑ / PageUp
        (event.key == Key.DirectionUp && (meta || alt)) || event.key == Key.PageUp ->
            listOf(ClipboardUiAction.MoveToFirst)

        // selectCurrentItem
        event.key == Key.Enter || event.key == Key.NumPadEnter -> when {
            state.footerSelection >= 0 ->
                footerActions.getOrNull(state.footerSelection)?.let { listOf(ClipboardUiAction.RunFooter(it)) }
                    ?: emptyList()

            state.results.isNotEmpty() -> listOf(ClipboardUiAction.Activate(state.historySelection, shift, alt, meta))

            // Port of `AppState.select`: nothing is selected, so the query itself is copied.
            else -> listOf(ClipboardUiAction.CopySearchQuery)
        }

        // close: `KeyChord.close` closes the popup, and `ListHeaderView` clears the search
        // whenever the panel stops being the key window.
        event.key == Key.Escape -> listOf(ClipboardUiAction.Escape)

        // pinOrUnpin: the recordable `KeyboardShortcuts.Name.pin`, `⌥P` by default
        matchesShortcut(event, settings.pinShortcut) -> listOf(ClipboardUiAction.TogglePinSelected)

        // togglePreview: the recordable `togglePreview`, `⌃Space` by default
        matchesShortcut(event, settings.togglePreviewShortcut) -> listOf(ClipboardUiAction.TogglePreview)

        // openPreferences: ⌘,
        event.key == Key.Comma && meta -> listOf(ClipboardUiAction.ShowPreferences)

        // clearSearch: ⌃U
        control && event.key == Key.U -> listOf(ClipboardUiAction.ClearSearch)

        // deleteOneCharFromSearch: ⌃H
        control && event.key == Key.H -> listOf(ClipboardUiAction.DeleteSearchChar)

        // deleteLastWordFromSearch: ⌃W
        control && event.key == Key.W -> listOf(ClipboardUiAction.DeleteSearchWord)

        // clearHistory / clearHistoryAll: ⌥⌘⌫ and ⌥⇧⌘⌫
        (event.key == Key.Delete || event.key == Key.Backspace) && alt && meta ->
            listOf(ClipboardUiAction.RequestClear(all = shift))

        // deleteCurrentItem: the recordable `delete`, `⌥⌫` by default
        matchesShortcut(event, settings.deleteShortcut) -> listOf(ClipboardUiAction.DeleteSelected)

        else -> {
            // `History.pressedShortcutItem` + `HistoryItemAction`: the item shortcut is matched
            // by key alone and the modifiers pick copy / paste / paste-without-formatting, so
            // ⌥1, ⌘⇧1 and ⌥⇧1 work too.
            val character = event.utf16CodePoint
                .takeIf { it > 0 }
                ?.toChar()
                ?.uppercaseChar()
                ?.toString()
            val action = if (character == null) {
                ClipAction.UNKNOWN
            } else {
                defaultAction(settings, shift, alt, meta)
            }
            // A plain key press (`default`) must keep typing into the search field.
            val selectable = action != ClipAction.UNKNOWN && action != ClipAction.DEFAULT
            val index = if (character != null && selectable) {
                state.results.indexOfFirst { result ->
                    shortcuts[result.item.id]?.any { it.character == character } == true
                }
            } else {
                -1
            }
            if (index >= 0) listOf(ClipboardUiAction.ActivateShortcut(index, action)) else emptyList()
        }
    }
}
