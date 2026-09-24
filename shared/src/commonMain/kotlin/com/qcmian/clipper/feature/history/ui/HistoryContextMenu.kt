package com.qcmian.clipper.feature.history.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * 选中集的右键菜单。
 *
 * 用 `Popup(TopStart + offset)` 而不是 `DropdownMenu`：后者的偏移是「从锚点往下展开」的语义，
 * 要落在指针处得反过来推算；`Popup` 就是「把左上角摆在这里」，且同样自带点外面收掉。
 *
 * 配色只取主题里**确实定义过**的 token：`ClipperTheme` 只填了 colorScheme 的一部分，
 * `surfaceContainer*` 那些没被指定的会退回 M3 基线色（带紫调），和这套中性灰不是一家人。
 *
 * 尺寸由常量算出来，所以打开前就能把它夹进窗口——`Popup` 不会自己躲开窗口边缘。
 */
@Composable
internal fun SelectionContextMenu(
    at: Offset,
    selectionCount: Int,
    copyHint: String,
    pasteHint: String,
    allPinned: Boolean,
    pinHint: String?,
    deleteHint: String?,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onClearSelection: () -> Unit,
    onDismiss: () -> Unit,
    windowWidth: Dp,
    windowHeight: Dp,
) {
    val colors = MaterialTheme.colorScheme
    val multi = selectionCount > 1
    // 「复制 / 粘贴 / 置顶 / 删除」四项，多选时再多一行「取消多选」；分隔线数量跟着走。
    val itemCount = if (multi) 5 else 4
    val separatorCount = if (multi) 2 else 1
    val estimatedHeight = ContextMenuItemHeight * itemCount +
        ContextMenuPadding * 2 +
        ContextMenuSeparatorHeight * separatorCount
    val suffix = if (multi) " $selectionCount 条" else ""
    val offset = with(LocalDensity.current) {
        val margin = ContextMenuMargin.toPx()
        val maxX = (windowWidth.toPx() - ContextMenuWidth.toPx() - margin).coerceAtLeast(margin)
        val maxY = (windowHeight.toPx() - estimatedHeight.toPx() - margin).coerceAtLeast(margin)
        IntOffset(
            x = at.x.coerceIn(margin, maxX).roundToInt(),
            y = at.y.coerceIn(margin, maxY).roundToInt(),
        )
    }

    Popup(
        alignment = Alignment.TopStart,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = colors.surfaceVariant,
            contentColor = colors.onSurface,
            border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.5f)),
            shadowElevation = 12.dp,
            modifier = Modifier.width(ContextMenuWidth),
        ) {
            Column(Modifier.padding(vertical = ContextMenuPadding)) {
                ContextMenuItem(
                    title = "复制$suffix",
                    shortcut = copyHint,
                    onClick = onCopy,
                )
                ContextMenuItem(
                    title = if (multi) "逐条粘贴$suffix" else "粘贴",
                    shortcut = pasteHint,
                    onClick = onPaste,
                )
                ContextMenuDivider()
                // 文案跟着 `allPinned` 走，点下去发生的一定就是它写的那件事（见
                // `ClipboardViewModel.togglePinSelected`）。
                ContextMenuItem(
                    title = (if (allPinned) "取消置顶" else "置顶") + suffix,
                    shortcut = pinHint,
                    onClick = onTogglePin,
                )
                ContextMenuItem(
                    title = "删除$suffix",
                    shortcut = deleteHint,
                    onClick = onDelete,
                )
                if (multi) {
                    ContextMenuDivider()
                    ContextMenuItem(
                        title = "取消多选",
                        shortcut = "Esc",
                        onClick = onClearSelection,
                    )
                }
            }
        }
    }
}

/** 菜单里的分组线。 */
@Composable
private fun ContextMenuDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 菜单里的一项：动作在左、快捷键提示在右。整行可点。[shortcut] 为 `null` 时只显示动作。 */
@Composable
private fun ContextMenuItem(title: String, shortcut: String?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(ContextMenuItemHeight)
            .padding(horizontal = ContextMenuPadding)
            .clip(RoundedCornerShape(5.dp))
            .background(if (hovered) colors.primary.copy(alpha = 0.16f) else Color.Transparent)
            .hoverable(interaction)
            // 自带的水波纹 / 底色变化与这一行自绘的悬停态叠起来会很脏，因此不带 indication。
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            color = colors.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (shortcut != null) {
            Text(
                text = shortcut,
                fontSize = 11.sp,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 菜单的固定尺寸与行高：与 [SelectionContextMenu] 里那份高度估算必须是同一套数。 */
private val ContextMenuWidth = 190.dp
private val ContextMenuItemHeight = 28.dp
private val ContextMenuPadding = 4.dp
private val ContextMenuMargin = 6.dp

/** 一条分组线占的高度：线本身 1dp，加上两侧各 3dp 的间距。 */
private val ContextMenuSeparatorHeight = 7.dp
