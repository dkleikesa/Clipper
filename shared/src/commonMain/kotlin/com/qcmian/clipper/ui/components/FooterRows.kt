package com.qcmian.clipper.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.ui.KeyShortcut
import com.qcmian.clipper.ui.ModifierFlags
import com.qcmian.clipper.ui.Popup

/** Maccy 在页脚中提供的动作。 */
enum class FooterAction { CLEAR, CLEAR_ALL, PREFERENCES, QUIT }

/** 一个页脚行，等价于 Maccy 的一个 `FooterItem`。 */
data class FooterEntry(
    val action: FooterAction,
    val title: String,
    val shortcut: KeyShortcut?,
)

/**
 * 对应 Maccy 的 `FooterView`。这些行就是普通的列表行，因此外观与导航方式都与历史条目完全一致。
 * 第一行会在「清除」与「全部清除」之间随按下的修饰键切换，这正是 Maccy 在按住 Shift 时
 * 显示 `⌥⇧⌘⌫` 的原因。
 */
@Composable
fun FooterRows(
    flags: ModifierFlags,
    selectedIndex: Int,
    showQuit: Boolean,
    onAction: (FooterAction) -> Unit,
    onHover: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = footerEntries(flags, showQuit)

    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
            modifier = Modifier
                .padding(horizontal = Popup.horizontalSeparatorPadding)
                .padding(bottom = Popup.verticalSeparatorPadding),
        )

        entries.forEachIndexed { index, entry ->
            ListItemRow(
                isSelected = index == selectedIndex,
                shortcut = entry.shortcut,
                onClick = { onAction(entry.action) },
                onHover = { onHover(index) },
            ) {
                RowTitle(entry.title)
            }
        }

        Spacer(Modifier.height(Popup.verticalPadding))
    }
}

/** 对应 `FooterView.clearAllModifiersPressed`。 */
fun clearAllModifiersPressed(flags: ModifierFlags): Boolean {
    val pressed = flags.pressedNames
    if (pressed.isEmpty()) return false
    val clearModifiers = setOf("command", "option")
    val clearAllModifiers = setOf("command", "option", "shift")
    return !clearModifiers.containsAll(pressed) && clearAllModifiers.containsAll(pressed)
}

fun footerEntries(flags: ModifierFlags, showQuit: Boolean): List<FooterEntry> {
    val delete = "\u232b"
    return buildList {
        if (clearAllModifiersPressed(flags)) {
            add(
                FooterEntry(
                    action = FooterAction.CLEAR_ALL,
                    title = "全部清除",
                    shortcut = KeyShortcut(delete, option = true, shift = true, command = true),
                ),
            )
        } else {
            add(
                FooterEntry(
                    action = FooterAction.CLEAR,
                    title = "清除",
                    shortcut = KeyShortcut(delete, option = true, command = true),
                ),
            )
        }
        add(FooterEntry(FooterAction.PREFERENCES, "偏好设置…", KeyShortcut(",", command = true)))
        if (showQuit) {
            add(FooterEntry(FooterAction.QUIT, "退出", KeyShortcut("Q", command = true)))
        }
    }
}
