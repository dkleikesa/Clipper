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

/** The actions Maccy exposes in the footer. */
enum class FooterAction { CLEAR, CLEAR_ALL, PREFERENCES, ABOUT, QUIT }

/** One footer row, equivalent to a Maccy `FooterItem`. */
data class FooterEntry(
    val action: FooterAction,
    val title: String,
    val shortcut: KeyShortcut?,
)

/**
 * Port of Maccy's `FooterView`. The rows are ordinary list rows, so they look and navigate
 * exactly like the history entries. The first row swaps between "清除" and "全部清除"
 * depending on the modifiers that are held down, which is why Maccy shows `⌥⇧⌘⌫` while
 * Shift is pressed.
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

/** Mirrors `FooterView.clearAllModifiersPressed`. */
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
        add(FooterEntry(FooterAction.ABOUT, "关于", null))
        if (showQuit) {
            add(FooterEntry(FooterAction.QUIT, "退出", KeyShortcut("Q", command = true)))
        }
    }
}
