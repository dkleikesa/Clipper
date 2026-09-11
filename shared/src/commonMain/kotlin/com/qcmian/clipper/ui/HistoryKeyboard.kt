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
 * 对应 Maccy 的 `KeyChord`/`KeyHandlingView`：把按键事件翻译成界面应当派发的动作。
 * 把它放在 composable 之外，正是界面得以保持为 [ClipboardUiState] 纯渲染器的原因。
 *
 * 当事件未被处理、应当继续传播时（例如普通字符输入要进入搜索框）返回空列表。
 */
fun resolveKeyActions(
    event: KeyEvent,
    state: ClipboardUiState,
    flags: ModifierFlags,
    composing: Boolean,
    shortcuts: Map<String, List<KeyShortcut>>,
    footerActions: List<FooterAction>,
): List<ClipboardUiAction> {
    // 对应 `KeyHandlingView`：输入法候选窗打开时忽略按键。
    if (composing) return emptyList()
    if (event.type != KeyEventType.KeyDown || flags.isModifierKey(event)) return emptyList()

    val settings = state.settings
    val meta = event.isMetaPressed || event.isCtrlPressed
    val alt = event.isAltPressed
    val control = event.isCtrlPressed
    val shift = event.isShiftPressed
    val firstItemHighlighted = state.footerSelection < 0 && state.historySelection == 0

    return when {
        // moveToLast：⌃⌥N，Maccy 的 (.n, [.control, .option])
        control && alt && event.key == Key.N -> listOf(ClipboardUiAction.MoveToLast)

        // moveToFirst：⌃⌥P，Maccy 的 (.p, [.control, .option])
        control && alt && event.key == Key.P -> listOf(ClipboardUiAction.MoveToFirst)

        // moveToNext：↓ / ⇧↓ / ⌃N / ⌃⇧N / ⌃J
        (event.key == Key.DirectionDown && !alt && !meta) ||
            (control && (event.key == Key.N || event.key == Key.J)) -> listOf(ClipboardUiAction.MoveNext())

        // moveToPrevious：↑ / ⇧↑ / ⌃P / ⌃⇧P
        (event.key == Key.DirectionUp && !alt && !meta) ||
            (control && event.key == Key.P) -> listOf(ClipboardUiAction.MovePrevious)

        // moveToPrevious：⌃K，但仅当第一个条目未被高亮时；否则该键会落下去输入到搜索框（#1055）。
        control && event.key == Key.K && !firstItemHighlighted -> listOf(ClipboardUiAction.MovePrevious)

        // moveToLast：⌘↓ / ⌥↓ / PageDown
        (event.key == Key.DirectionDown && (meta || alt)) || event.key == Key.PageDown ->
            listOf(ClipboardUiAction.MoveToLast)

        // moveToFirst：⌘↑ / ⌥↑ / PageUp
        (event.key == Key.DirectionUp && (meta || alt)) || event.key == Key.PageUp ->
            listOf(ClipboardUiAction.MoveToFirst)

        // selectCurrentItem
        event.key == Key.Enter || event.key == Key.NumPadEnter -> when {
            state.footerSelection >= 0 ->
                footerActions.getOrNull(state.footerSelection)?.let { listOf(ClipboardUiAction.RunFooter(it)) }
                    ?: emptyList()

            state.results.isNotEmpty() -> listOf(ClipboardUiAction.Activate(state.historySelection, shift, alt, meta))

            // 对应 `AppState.select`：没有选中任何条目，于是复制查询词本身。
            else -> listOf(ClipboardUiAction.CopySearchQuery)
        }

        // close：`KeyChord.close` 关闭弹窗，`ListHeaderView` 在面板不再是主窗口时清空搜索。
        event.key == Key.Escape -> listOf(ClipboardUiAction.Escape)

        // pinOrUnpin：可录制的 `KeyboardShortcuts.Name.pin`，默认 `⌥P`
        matchesShortcut(event, settings.pinShortcut) -> listOf(ClipboardUiAction.TogglePinSelected)

        // togglePreview：可录制的 `togglePreview`，默认 `⌃Space`
        matchesShortcut(event, settings.togglePreviewShortcut) -> listOf(ClipboardUiAction.TogglePreview)

        // openPreferences：⌘,
        event.key == Key.Comma && meta -> listOf(ClipboardUiAction.ShowPreferences)

        // clearSearch：⌃U
        control && event.key == Key.U -> listOf(ClipboardUiAction.ClearSearch)

        // deleteOneCharFromSearch：⌃H
        control && event.key == Key.H -> listOf(ClipboardUiAction.DeleteSearchChar)

        // deleteLastWordFromSearch：⌃W
        control && event.key == Key.W -> listOf(ClipboardUiAction.DeleteSearchWord)

        // clearHistory / clearHistoryAll：⌥⌘⌫ 与 ⌥⇧⌘⌫
        (event.key == Key.Delete || event.key == Key.Backspace) && alt && meta ->
            listOf(ClipboardUiAction.RequestClear(all = shift))

        // deleteCurrentItem：可录制的 `delete`，默认 `⌥⌫`
        matchesShortcut(event, settings.deleteShortcut) -> listOf(ClipboardUiAction.DeleteSelected)

        else -> {
            // `History.pressedShortcutItem` + `HistoryItemAction`：条目快捷键只按按键匹配，
            // 由修饰键决定复制 / 粘贴 / 不带格式粘贴，因此 ⌥1、⌘⇧1 与 ⌥⇧1 也都可用。
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
            // 普通按键（`default`）必须继续输入到搜索框。
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
