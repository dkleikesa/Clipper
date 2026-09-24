package com.qcmian.clipper.feature.history.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import com.qcmian.clipper.core.ui.KeyShortcut
import com.qcmian.clipper.core.ui.ModifierFlags
import com.qcmian.clipper.feature.history.state.ClipboardUiAction
import com.qcmian.clipper.feature.history.state.ClipboardUiState
import com.qcmian.clipper.feature.history.state.FooterAction
import com.qcmian.clipper.feature.history.viewmodel.resolveKeyActions

/** 指针按下期间按住的修饰键；普通可变容器，写入不触发重组（见 [HistoryScreen] 内注释）。 */
internal class PointerModifiers {
    var shift = false
    var alt = false
    var command = false
    var control = false
}

/**
 * 记录指针按下期间按住的修饰键（`⌥`-点击粘贴、`⌘⇧`-点击不带格式粘贴，见
 * `HistoryItemView.performSelect`），并管理右键菜单的开与关。
 *
 * 两个判据都靠回调读，而不是把状态搬进来：菜单开没开是界面的状态、修饰键容器跨重组稳定，
 * 因此这个手势循环只需挂一次（`pointerInput(Unit)`），不必随重组重启。
 */
internal fun Modifier.trackHistoryPointer(
    modifiers: PointerModifiers,
    isContextMenuOpen: () -> Boolean,
    onPointerMoved: () -> Unit,
    onOpenContextMenu: (Offset) -> Unit,
    onCloseContextMenu: () -> Unit,
): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            when (event.type) {
                // `MouseMovedViewModifier`：鼠标移动会结束键盘导航，
                // 于是悬停重新开始选择。
                PointerEventType.Move -> onPointerMoved()
                PointerEventType.Press -> {
                    modifiers.shift = event.keyboardModifiers.isShiftPressed
                    modifiers.alt = event.keyboardModifiers.isAltPressed
                    modifiers.command = event.keyboardModifiers.isMetaPressed
                    modifiers.control = event.keyboardModifiers.isCtrlPressed

                    if (event.buttons.isSecondaryPressed) {
                        // 菜单作用于**当前选中集**，不改变选择（这里也做不了行的命中测试）。
                        onOpenContextMenu(event.changes.last().position)
                        // 就地消费：`Initial` 阶段跑在最外层，消费之后行上的 `clickable`
                        // 看不到这次按下，不会顺手激活这一条。
                        event.changes.forEach { it.consume() }
                    } else if (isContextMenuOpen()) {
                        // 菜单开着时的第一下左键只关菜单，不激活指针底下那一行。
                        onCloseContextMenu()
                        event.changes.forEach { it.consume() }
                    }
                }

                else -> Unit
            }
        }
    }
}

/**
 * 面板的按键处理：修饰键状态、右键菜单收场、快捷键解析，以及「补送字符」的吞掉逻辑。
 *
 * 平台会为带修饰键的按键**额外补送一个字符事件**（AWT 的 `KEY_TYPED`，在 Compose 里类型是
 * [KeyEventType.Unknown]，字符就是 `utf16CodePoint`）：macOS 上 `⌃1` 送 `1`、`⌥1` 送 `¡`。
 * 它不是用户想搜索的内容，必须在预览阶段吞掉，否则条目快捷键会一边生效一边把字符打进搜索框
 * （`⌘` 组合不补送该事件，所以只有 `⌃` / `⌥` 变体会漏）。
 *
 * 只吞「刚刚被面板处理过的那一次按键」补送的字符：普通输入照常进搜索框；⌃K 那种有意不被
 * 面板消费、留给搜索框的按键也照旧（见 `resolveKeyActions` 的 ⌃K 分支）。
 *
 * 吞字符这一位是纯按键处理状态（不参与渲染），因此留在函数内部、由 `remember` 保住。
 */
@Composable
internal fun rememberHistoryKeyHandler(
    state: ClipboardUiState,
    flags: ModifierFlags,
    composing: Boolean,
    shortcuts: Map<String, List<KeyShortcut>>,
    /** 页脚各行对应的动作，供 [resolveKeyActions] 解析页脚快捷键。 */
    footerActions: List<FooterAction>,
    /** 右键菜单此刻是否开着：任意按键先把它收掉。 */
    contextMenuOpen: Boolean,
    onCloseContextMenu: () -> Unit,
    onAction: (ClipboardUiAction) -> Unit,
): (KeyEvent) -> Boolean {
    var swallowTypedCharacter by remember { mutableStateOf(false) }

    return handler@{ event ->
        flags.update(event)
        if (event.type == KeyEventType.Unknown) {
            val swallow = swallowTypedCharacter
            swallowTypedCharacter = false
            swallow
        } else {
            // 菜单开着时任意按键先收掉它；`Esc` 到此为止，不顺带清多选、也不关面板。
            if (contextMenuOpen) {
                onCloseContextMenu()
                if (event.key == Key.Escape) return@handler true
            }
            val actions = resolveKeyActions(
                event = event,
                state = state,
                flags = flags,
                composing = composing,
                shortcuts = shortcuts,
                footerActions = footerActions,
            )
            // 这次按键已被面板消费（无论是条目快捷键还是别的动作），它随后补送的字符不该再落进搜索框。
            swallowTypedCharacter = actions.isNotEmpty()
            actions.forEach(onAction)
            actions.isNotEmpty()
        }
    }
}
