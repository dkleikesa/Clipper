package com.qcmian.clipper.core.ui.components

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
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * 菜单里的一行。
 *
 * 刻意不是 `data class`：里面装着 lambda，而 lambda 的 `equals` 是引用相等——哪天有人拿它当
 * `remember` 的键，就会每帧都判成「变了」。
 */
sealed interface AppContextMenuEntry

/** 一项动作：文字在左、键位提示在右。 */
class AppContextMenuAction(
    val title: String,
    /** 键位提示（例如 `⌘C`）。没有对应键位时给 `null`，这一行就只显示动作。 */
    val shortcut: String? = null,
    /** 为假时画淡、点不动。 */
    val enabled: Boolean = true,
    val onClick: () -> Unit,
) : AppContextMenuEntry

/** 两条动作之间的分组线。 */
object AppContextMenuDivider : AppContextMenuEntry

/**
 * 应用自己的右键菜单：左上角摆在 [at]，点外面收掉。
 *
 * **不用 `DropdownMenu`，也不用平台给 `BasicTextField` 那一套**：前者的偏移是「从锚点往下展开」的
 * 语义，要落在指针处得反过来推算；后者（`ContextMenuArea` 那一套）的配色与尺寸跟这套中性灰主题
 * 不是一家人。主面板里那份选中集菜单就是这个长相，两处因此共用同一份实现、同一副样子。
 *
 * 配色只取主题里**确实定义过**的 token：`ClipperTheme` 只填了 colorScheme 的一部分，
 * `surfaceContainer*` 那些没被指定的会退回 M3 基线色（带紫调），和这套中性灰不是一家人。
 *
 * 尺寸由常量算出来，所以打开前就能把它夹进窗口——`Popup` 不会自己躲开窗口边缘。[at] 是相对
 * [anchor] 的，两个一起换算到窗口坐标再夹，因此宿主不在窗口原点时（编辑器深在一页里）也落得对。
 *
 * @param at 指针位置，**相对 [anchor]**（也就是宿主那一块的左上角）。
 * @param entries 菜单里从上到下的行。
 * @param onDismiss 点菜单外面（或 `⎋`）收掉时回调。**点某一项不会回调**：要不要收由调用方在那一项
 *   的动作里决定——主面板那份要顺手清掉选中态，编辑器这份只是收起来。
 * @param anchor 宿主那一块在**窗口**里的原点。用 `Modifier.onGloballyPositioned` 量一次传进来；
 *   宿主就是窗口内容本身时不必传。
 */
@Composable
fun AppContextMenu(
    at: Offset,
    entries: List<AppContextMenuEntry>,
    onDismiss: () -> Unit,
    anchor: Offset = Offset.Zero,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    // 窗口尺寸只用来收边。量不到（0）时不夹：宁可让它探出窗口一点，也不要把菜单挤到别处去。
    val container = LocalWindowInfo.current.containerSize
    val offset = with(density) {
        val margin = ContextMenuMargin.toPx()
        val origin = anchor + at
        val right = (container.width - ContextMenuWidth.toPx() - margin).coerceAtLeast(margin)
        val bottom = (container.height - estimatedHeight(entries).toPx() - margin).coerceAtLeast(margin)
        val x = if (container.width > 0) origin.x.coerceIn(margin, right) else origin.x
        val y = if (container.height > 0) origin.y.coerceIn(margin, bottom) else origin.y
        IntOffset((x - anchor.x).roundToInt(), (y - anchor.y).roundToInt())
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
                entries.forEach { entry ->
                    when (entry) {
                        is AppContextMenuAction -> MenuRow(entry)
                        AppContextMenuDivider -> MenuDivider()
                    }
                }
            }
        }
    }
}

/** 行高按内容算出来，好在打开之前就能把菜单夹进窗口（与实际排版是同一套常量）。 */
private fun estimatedHeight(entries: List<AppContextMenuEntry>): Dp {
    val rows = entries.count { it is AppContextMenuAction }
    val dividers = entries.count { it === AppContextMenuDivider }
    return ContextMenuItemHeight * rows +
        ContextMenuSeparatorHeight * dividers +
        ContextMenuPadding * 2
}

/** 菜单里的一行：动作在左、键位提示在右，整行可点（`enabled` 为假时画淡、点不动）。 */
@Composable
private fun MenuRow(item: AppContextMenuAction) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // 够不着的那两档（只读框里的剪切 / 粘贴）统一压到 38%：与平台那套禁用态同一档透明度。
    val disabledAlpha = 0.38f

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(ContextMenuItemHeight)
            .padding(horizontal = ContextMenuPadding)
            .clip(RoundedCornerShape(5.dp))
            .background(
                if (hovered && item.enabled) colors.primary.copy(alpha = 0.16f) else Color.Transparent
            )
            .hoverable(interaction, enabled = item.enabled)
            // 自带的水波纹 / 底色变化与这一行自绘的悬停态叠起来会很脏，因此不带 indication。
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = item.enabled,
                onClick = item.onClick,
            )
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = item.title,
            fontSize = 12.sp,
            color = if (item.enabled) colors.onSurface else colors.onSurface.copy(alpha = disabledAlpha),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (item.shortcut != null) {
            Text(
                text = item.shortcut,
                fontSize = 11.sp,
                color = if (item.enabled) {
                    colors.onSurfaceVariant
                } else {
                    colors.onSurfaceVariant.copy(alpha = disabledAlpha)
                },
                maxLines = 1,
            )
        }
    }
}

/** 菜单里的分组线。 */
@Composable
private fun MenuDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 菜单的固定尺寸与行高：与 [estimatedHeight] 里那份估算必须是同一套数。 */
private val ContextMenuWidth = 190.dp
private val ContextMenuItemHeight = 28.dp
private val ContextMenuPadding = 4.dp
private val ContextMenuMargin = 6.dp

/** 一条分组线占的高度：线本身 1dp，加上两侧各 3dp 的间距。 */
private val ContextMenuSeparatorHeight = 7.dp
