package com.qcmian.clipper.feature.history.viewmodel

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.qcmian.clipper.core.domain.action.ClipAction
import com.qcmian.clipper.core.domain.action.defaultAction
import com.qcmian.clipper.core.ui.KeyShortcut
import com.qcmian.clipper.core.ui.ModifierFlags
import com.qcmian.clipper.core.ui.matchesShortcut
import com.qcmian.clipper.core.ui.shortcutCharacterFor
import com.qcmian.clipper.feature.history.state.FooterAction
import com.qcmian.clipper.feature.history.state.ClipboardUiAction
import com.qcmian.clipper.feature.history.state.ClipboardUiState

/**
 * /`KeyHandlingView`：把按键事件翻译成界面应当派发的动作。
 * 把它放在 composable 之外，正是界面得以保持为 [ClipboardUiState] 纯渲染器的原因。
 *
 * 当事件未被处理、应当继续传播时（例如普通字符输入要进入搜索框）返回空列表。
 *
 * 注意这里只处理 `KeyDown`：平台还会为同一个按键补送一个字符事件（AWT 的 `KEY_TYPED`），
 * 它由调用方在预览阶段吞掉——见 `HistoryScreen` 的 `keyHandler`。
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

    return when {
        // moveToLast：⌃⌥N
        control && alt && event.key == Key.N -> listOf(ClipboardUiAction.MoveToLast)

        // moveToFirst：⌃⌥P
        control && alt && event.key == Key.P -> listOf(ClipboardUiAction.MoveToFirst)

        // moveToNext：↓ / ⇧↓
        event.key == Key.DirectionDown && !alt && !meta -> listOf(ClipboardUiAction.MoveNext())

        // moveToPrevious：↑ / ⇧↑
        event.key == Key.DirectionUp && !alt && !meta -> listOf(ClipboardUiAction.MovePrevious)

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

        // pause：可录制的 `pause`，默认 `⌘P`。放在条目快捷键之前，免得同一个组合先被当成
        // 「快速选择某一条」（`⌘1`…`⌘9` 的任意修饰键变体都走最后那一支）。
        matchesShortcut(event, settings.pauseShortcut) -> listOf(ClipboardUiAction.ToggleRecordingPause)

        // openPreferences：⌘,
        event.key == Key.Comma && meta -> listOf(ClipboardUiAction.ShowPreferences)

        // deleteCurrentItem：可录制的 `delete`，默认 `⌥⌫`
        matchesShortcut(event, settings.deleteShortcut) -> listOf(ClipboardUiAction.DeleteSelected)

        else -> {
            // `History.pressedShortcutItem` + `HistoryItemAction`：条目快捷键只按物理键匹配，
            // 由修饰键决定复制 / 粘贴 / 不带格式粘贴，因此 ⌥1、⌃1、⌘⇧1 与 ⌥⇧1 也都可用
            // （按字符匹配会漏掉那些「字符随修饰键变化」的组合，见 `shortcutCharacterFor`）。
            val character = shortcutCharacterFor(event)
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
