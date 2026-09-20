package com.qcmian.clipper.feature.history.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.settings.ClipFilterType
import com.qcmian.clipper.core.settings.SortBy
import com.qcmian.clipper.core.settings.SortOrder
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind

/**
 * 主面板内容区顶部的筛选栏：按类型过滤（多选）、排序方式、升降序。
 *
 * 三个控件均分一行（各占 1/3），直接读写设置（`filterTypes` / `sortBy` / `sortOrder`）。
 */
@Composable
fun HistoryFilterBar(
    filterTypes: Set<ClipFilterType>,
    sortBy: SortBy,
    sortOrder: SortOrder,
    onFilterTypesChange: (Set<ClipFilterType>) -> Unit,
    onSortByChange: (SortBy) -> Unit,
    onSortOrderChange: (SortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.padding(horizontal = Popup.horizontalPadding, vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        MultiSelectDropdown(
            options = ClipFilterType.entries,
            selected = filterTypes,
            title = filterTypeTitle(filterTypes),
            optionLabel = { it.label },
            onToggle = { type ->
                onFilterTypesChange(
                    if (type in filterTypes) filterTypes - type else filterTypes + type,
                )
            },
            modifier = Modifier.weight(1f),
        )
        FilterDropdown(
            options = SortBy.entries,
            selected = sortBy,
            label = { it.label },
            onSelect = onSortByChange,
            modifier = Modifier.weight(1f),
        )
        SortOrderToggle(
            sortOrder = sortOrder,
            onToggle = {
                onSortOrderChange(
                    if (sortOrder == SortOrder.DESCENDING) SortOrder.ASCENDING else SortOrder.DESCENDING,
                )
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** 类型筛选按钮的显示文字：空集 = 无，全选 = 全部，单个 = 该类型，多个 = 已选 N 类。 */
private fun filterTypeTitle(types: Set<ClipFilterType>): String = when {
    types.isEmpty() -> "无"
    types.size == ClipFilterType.entries.size -> "全部"
    types.size == 1 -> types.first().label
    else -> "已选 ${types.size} 类"
}

/** 菜单相对按钮锚点的水平偏移：让菜单水平居中于按钮。 */
@Composable
private fun centeredMenuOffset(anchorWidth: Int, menuWidth: Int): DpOffset {
    val density = LocalDensity.current
    val offsetX = if (menuWidth == 0) 0.dp else with(density) { ((anchorWidth - menuWidth) / 2).toDp() }
    return DpOffset(offsetX, 0.dp)
}

/** 多选下拉：点击条目切换选中、不关闭菜单；点击外部关闭。 */
@Composable
private fun MultiSelectDropdown(
    options: List<ClipFilterType>,
    selected: Set<ClipFilterType>,
    title: String,
    optionLabel: (ClipFilterType) -> String,
    onToggle: (ClipFilterType) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableStateOf(0) }
    var menuWidth by remember { mutableStateOf(0) }

    Box(modifier) {
        FilterChip(
            text = title,
            showArrow = true,
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().onSizeChanged { anchorWidth = it.width },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = centeredMenuOffset(anchorWidth, menuWidth),
            modifier = Modifier.onSizeChanged { menuWidth = it.width },
        ) {
            options.forEach { option ->
                val isSelected = option in selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onToggle(option) }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 固定尺寸的勾选列，保证选中 / 未选中行高一致；对号用矢量图标精确居中。
                    Box(Modifier.width(14.dp).height(14.dp), contentAlignment = Alignment.Center) {
                        if (isSelected) {
                            ClipperIcon(ClipperIconKind.CHECKMARK, size = 14.dp, tint = colors.primary)
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = optionLabel(option),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) colors.primary else colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 单选下拉：点击条目即选中并关闭。 */
@Composable
private fun <T> FilterDropdown(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    var anchorWidth by remember { mutableStateOf(0) }
    var menuWidth by remember { mutableStateOf(0) }

    Box(modifier) {
        FilterChip(
            text = label(selected),
            showArrow = true,
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().onSizeChanged { anchorWidth = it.width },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = centeredMenuOffset(anchorWidth, menuWidth),
            modifier = Modifier.onSizeChanged { menuWidth = it.width },
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            expanded = false
                            onSelect(option)
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSelected) colors.primary else colors.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** 升降序切换按钮：两个状态点击翻转，用箭头表示方向。 */
@Composable
private fun SortOrderToggle(
    sortOrder: SortOrder,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FilterChip(
        text = sortOrder.label,
        showArrow = false,
        leadingIcon = if (sortOrder == SortOrder.ASCENDING) {
            ClipperIconKind.ARROW_UP
        } else {
            ClipperIconKind.ARROW_DOWN
        },
        onClick = onToggle,
        modifier = modifier,
    )
}

/** 筛选栏按钮的统一外观：紧凑 chip，固定高度，文字居中，可选图标。 */
@Composable
private fun FilterChip(
    text: String,
    showArrow: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ClipperIconKind? = null,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = colors.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.4f)),
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (leadingIcon != null) {
                ClipperIcon(leadingIcon, size = 12.dp, tint = colors.onSurface)
                Spacer(Modifier.width(3.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showArrow) {
                Spacer(Modifier.width(3.dp))
                ClipperIcon(ClipperIconKind.CHEVRON_DOWN, size = 14.dp, tint = colors.onSurfaceVariant)
            }
        }
    }
}
