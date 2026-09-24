package com.qcmian.clipper.feature.history.viewmodel

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.type
import com.qcmian.clipper.core.domain.action.ClipAction
import com.qcmian.clipper.core.domain.action.slot
import com.qcmian.clipper.core.settings.QUICK_SELECT_DIGITS
import com.qcmian.clipper.core.settings.ShortcutSpec
import com.qcmian.clipper.core.settings.shortcut
import com.qcmian.clipper.core.ui.KeyShortcut
import com.qcmian.clipper.core.ui.ModifierFlags
import com.qcmian.clipper.core.ui.matchesShortcut
import com.qcmian.clipper.core.ui.shiftVariant
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
 *
 * **全部快捷键都来自可录制的槽位**（见 `ShortcutSlot`）：过去写死的方向键、`⏎`、`⎋`、`⌘,`、
 * `⌘1…⌘9` 现在各自是一条槽位，解析时统一走 `matchesShortcut`。唯一保留的派生语义是
 * 「`⇧` + 上 / 下一条 = 连续选中」（见 [shiftVariant]）；激活的四种按法是四条独立绑定
 * （见 `ClipAction.slot`），不再从修饰键推导。
 */
fun resolveKeyActions(
    event: KeyEvent,
    state: ClipboardUiState,
    flags: ModifierFlags,
    composing: Boolean,
    shortcuts: Map<String, List<KeyShortcut>>,
    footerActions: List<FooterAction>,
): List<ClipboardUiAction> {
    // 输入法候选窗打开时忽略按键。
    if (composing) return emptyList()
    if (event.type != KeyEventType.KeyDown || flags.isModifierKey(event)) return emptyList()

    val settings = state.settings

    // ---------------------------------------------------------------- 列表导航
    // 上 / 下一条：基础绑定往下 / 上走，再按住 `⇧` 就是连续选中。
    if (matchesShortcut(event, settings.moveNextShortcut)) {
        return listOf(verticalStep(state, extend = false, delta = 1, plain = ClipboardUiAction.MoveNext()))
    }
    if (matchesShortcut(event, settings.moveNextShortcut?.shiftVariant())) {
        return listOf(verticalStep(state, extend = true, delta = 1, plain = ClipboardUiAction.MoveNext()))
    }
    if (matchesShortcut(event, settings.movePreviousShortcut)) {
        return listOf(ClipboardUiAction.MovePrevious)
    }
    if (matchesShortcut(event, settings.movePreviousShortcut?.shiftVariant())) {
        return listOf(verticalStep(state, extend = true, delta = -1, plain = ClipboardUiAction.MovePrevious))
    }
    if (matchesShortcut(event, settings.moveToLastShortcut)) return listOf(ClipboardUiAction.MoveToLast)
    if (matchesShortcut(event, settings.moveToFirstShortcut)) return listOf(ClipboardUiAction.MoveToFirst)

    // ---------------------------------------------------------------- 激活
    // 激活选中项的四种按法各有一条独立绑定：按下哪条就做哪件事（见 `ClipAction.slot`），
    // 因此这里不必再按修饰键推导含义。
    ClipAction.entries.firstOrNull { matchesShortcut(event, settings.shortcut(it.slot)) }
        ?.let { return activateActions(state, footerActions, it) }

    // ---------------------------------------------------------------- 条目操作
    if (matchesShortcut(event, settings.pinShortcut)) return listOf(ClipboardUiAction.TogglePinSelected)
    if (matchesShortcut(event, settings.togglePreviewShortcut)) return listOf(ClipboardUiAction.TogglePreview)
    // 暂停放在条目快捷键之前检查也无妨：它与其它槽位不会撞键（录制时已查重）。
    if (matchesShortcut(event, settings.pauseShortcut)) return listOf(ClipboardUiAction.ToggleRecordingPause)
    if (matchesShortcut(event, settings.deleteShortcut)) return listOf(ClipboardUiAction.DeleteSelected)

    // ---------------------------------------------------------------- 窗口
    if (matchesShortcut(event, settings.closeShortcut)) return listOf(ClipboardUiAction.Escape)
    if (matchesShortcut(event, settings.openSettingsShortcut)) return listOf(ClipboardUiAction.ShowPreferences)

    // ---------------------------------------------------------------- 快速粘贴（数字键）
    // 放在可录制条目快捷键**之后**：用户自己录进同一个组合时，以那次录制为准（录制时已拦重复）。
    // 只按物理键取字符（详见 `shortcutCharacterFor`），因此小键盘数字也能命中。
    val quickSelect = settings.quickSelectShortcut
    val character = shortcutCharacterFor(event)
    if (quickSelect != null && character != null && character in QUICK_SELECT_DIGITS &&
        matchesModifiers(event, quickSelect)
    ) {
        val index = state.results.indexOfFirst { result ->
            shortcuts[result.meta.id]?.any { it.character == character } == true
        }
        // 数字键只负责挑条目，动作固定是粘贴——「快速粘贴置顶项」的整个语义就这一句。
        if (index >= 0) return listOf(ClipboardUiAction.ActivateShortcut(index, ClipAction.PASTE))
    }

    return emptyList()
}

/**
 * 某条激活动作按下后应当派发什么：页脚高亮时运行页脚项，有结果时激活选中集，否则复制查询词。
 */
private fun activateActions(
    state: ClipboardUiState,
    footerActions: List<FooterAction>,
    action: ClipAction,
): List<ClipboardUiAction> = when {
    state.footerSelection >= 0 ->
        footerActions.getOrNull(state.footerSelection)?.let { listOf(ClipboardUiAction.RunFooter(it)) }
            ?: emptyList()

    state.results.isNotEmpty() -> listOf(ClipboardUiAction.Activate(action))

    // 没有选中任何条目，于是复制查询词本身。
    else -> listOf(ClipboardUiAction.CopySearchQuery)
}

/**
 * 修饰键是否与 [spec] 一致（不看字符），与 [matchesShortcut] 同口径：
 * 在没有 ⌘ 键的平台上 `Ctrl` 兼作 `⌘`，除非该快捷键显式要求 `⌃`。
 *
 * 单拿出来是因为快速粘贴按**字符**匹配任意数字键，不能直接走 `matchesShortcut`。
 */
private fun matchesModifiers(event: KeyEvent, spec: ShortcutSpec): Boolean {
    val commandPressed =
        if (spec.control) event.isMetaPressed else (event.isMetaPressed || event.isCtrlPressed)

    return commandPressed == spec.command &&
        event.isCtrlPressed == spec.control &&
        event.isAltPressed == spec.option &&
        event.isShiftPressed == spec.shift
}

/**
 * `↓` / `↑` 在按住 `⇧` 时改成**连续选中**。
 *
 * 目标下标在这里算成绝对值：界面与状态层因此只需要一个 `SelectRange`，
 * 不必各自再推一遍步长（`⇧↓` 到底之后停在最后一条，而不是绕回去）。
 */
private fun verticalStep(
    state: ClipboardUiState,
    extend: Boolean,
    delta: Int,
    plain: ClipboardUiAction,
): ClipboardUiAction = if (!extend) {
    plain
} else {
    ClipboardUiAction.SelectRange(
        (state.historySelection + delta).coerceIn(0, maxOf(0, state.results.lastIndex)),
    )
}
