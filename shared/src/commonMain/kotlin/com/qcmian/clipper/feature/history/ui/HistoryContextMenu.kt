package com.qcmian.clipper.feature.history.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import com.qcmian.clipper.core.ui.components.AppContextMenu
import com.qcmian.clipper.core.ui.components.AppContextMenuAction
import com.qcmian.clipper.core.ui.components.AppContextMenuDivider

/**
 * 选中集的右键菜单。
 *
 * 长相与摆位都在 [AppContextMenu] 里（那一份是应用自己的右键菜单，主面板与代码框共用）：这里只
 * 决定「哪几项、按什么顺序、键位提示写什么」。弹出点只在打开的那一帧定一次，之后不跟鼠标走。
 *
 * 每一项的动作里由调用方顺手把菜单收掉（`contextMenuAt = null`）：主面板点完之后还要跟着清选中态，
 * 收不收、顺带做什么只有它知道，因此 [AppContextMenu] 不替它做这件事。
 */
@Composable
internal fun SelectionContextMenu(
    at: Offset,
    selectionCount: Int,
    /** 复制 / 粘贴的键位提示；映射里没有对应组合时为 `null`（那一项就不显示提示）。 */
    copyHint: String?,
    pasteHint: String?,
    allPinned: Boolean,
    pinHint: String?,
    deleteHint: String?,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onOpenDevTools: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onClearSelection: () -> Unit,
    onDismiss: () -> Unit,
) {
    val multi = selectionCount > 1
    val suffix = if (multi) " $selectionCount 条" else ""

    AppContextMenu(
        at = at,
        onDismiss = onDismiss,
        entries = buildList {
            add(AppContextMenuAction(title = "复制$suffix", shortcut = copyHint, onClick = onCopy))
            add(
                AppContextMenuAction(
                    title = if (multi) "逐条粘贴$suffix" else "粘贴",
                    shortcut = pasteHint,
                    onClick = onPaste,
                )
            )
            add(AppContextMenuDivider)
            // 单独一段：它是「把这条拿去分析」，与复制 / 粘贴、与条目管理都不是一类动作。
            add(AppContextMenuAction(title = "在开发者工具中打开", onClick = onOpenDevTools))
            add(AppContextMenuDivider)
            // 文案跟着 `allPinned` 走，点下去发生的一定就是它写的那件事（见
            // `ClipboardViewModel.togglePinSelected`）。
            add(
                AppContextMenuAction(
                    title = (if (allPinned) "取消置顶" else "置顶") + suffix,
                    shortcut = pinHint,
                    onClick = onTogglePin,
                )
            )
            add(AppContextMenuAction(title = "删除$suffix", shortcut = deleteHint, onClick = onDelete))
            if (multi) {
                add(AppContextMenuDivider)
                add(AppContextMenuAction(title = "取消多选", shortcut = "Esc", onClick = onClearSelection))
            }
        },
    )
}
