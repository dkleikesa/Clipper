package com.qcmian.clipper.ui.dialogs

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.settings.ShortcutSpec
import com.qcmian.clipper.ui.components.rememberImageBitmap
import com.qcmian.clipper.ui.icons.ClipperIcon
import com.qcmian.clipper.ui.icons.ClipperIconKind

/**
 * 偏好设置对话框中可复用的各种行。
 *
 * 它们原先放在 `PreferencesDialog.kt` 末尾，使那个文件长达 1 100 行；现在还是同样的行，
 * 只是单独成文件，好让对话框只需描述那六个分区。
 */

/**
 * Maccy `IgnoreApplicationsSettingsView` 中的一行：应用图标、解析出的应用名，
 * 以及把它从忽略列表中移除的按钮。
 */
@Composable
internal fun IgnoredApplicationRow(
    name: String,
    iconBase64: String?,
    onRemove: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val icon = rememberImageBitmap(iconBase64)

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Image(
                bitmap = icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
        } else {
            ClipperIcon(ClipperIconKind.APP, size = 18.dp, tint = colors.onSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            ClipperIcon(ClipperIconKind.CLEAR, size = 12.dp, tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
internal fun PinRow(
    item: ClipItem,
    availablePins: List<String>,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onPinChange: (String?) -> Unit,
    onTitleChange: (String) -> Unit,
    onContentChange: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    var title by remember(item.id, item.title) { mutableStateOf(item.title) }
    // `PinValueView`：只有纯文本条目才提供可编辑的内容字段。
    val editable = item.text != null && item.imageBase64 == null && item.files.isEmpty()
    var content by remember(item.id, item.text) { mutableStateOf(item.text.orEmpty()) }

    Column(
        Modifier
            .fillMaxWidth()
            // `PinsSettingsPane` 的表格选中：点击该行会让它成为 Delete 键的作用目标。
            .clickable(onClick = onSelect)
            .background(
                if (isSelected) colors.primary.copy(alpha = 0.10f) else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .padding(vertical = 3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 快捷键选择器
            Box {
                Box(
                    modifier = Modifier
                        .width(44.dp)
                        .height(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.dp, colors.outline, RoundedCornerShape(6.dp))
                        .clickable { expanded = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = (item.pin ?: "—").uppercase(),
                        fontSize = 12.sp,
                        color = colors.onSurface,
                    )
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    (listOfNotNull(item.pin) + availablePins).forEach { pin ->
                        DropdownMenuItem(
                            text = { Text(pin.uppercase(), fontSize = 12.sp) },
                            onClick = {
                                expanded = false
                                onPinChange(pin)
                            },
                        )
                    }
                }
            }

            // 别名
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, colors.outline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        onTitleChange(it)
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 12.sp,
                        color = colors.onSurface,
                    ),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onDelete),
                contentAlignment = Alignment.Center,
            ) {
                ClipperIcon(ClipperIconKind.TRASH, size = 13.dp, tint = colors.onSurfaceVariant)
            }
        }

        if (editable) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .border(1.dp, colors.outline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = content,
                    onValueChange = {
                        content = it
                        onContentChange(it)
                    },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontSize = 12.sp,
                        color = colors.onSurface,
                    ),
                    cursorBrush = SolidColor(colors.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            Text(
                text = "该置顶项不是纯文本，无法编辑内容。",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** 对应 Maccy 的 `KeyboardShortcuts.Recorder` 行。 */
@Composable
internal fun ShortcutRow(
    title: String,
    spec: ShortcutSpec,
    recording: Boolean,
    onRecord: () -> Unit,
    onReset: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(
                    width = 1.dp,
                    color = if (recording) colors.primary else colors.outline,
                    shape = RoundedCornerShape(6.dp),
                )
                .clickable(onClick = onRecord)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (recording) "按下新快捷键…" else spec.label,
                fontSize = 12.sp,
                color = if (recording) colors.primary else colors.onSurface,
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onReset),
            contentAlignment = Alignment.Center,
        ) {
            ClipperIcon(ClipperIconKind.CLEAR, size = 12.dp, tint = colors.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(
        text = text,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.primary,
    )
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}

@Composable
internal fun SwitchRow(
    title: String,
    checked: Boolean,
    description: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
internal fun SliderRow(
    title: String,
    valueLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            enabled = enabled,
            modifier = Modifier.width(200.dp),
        )
    }
}

@Composable
internal fun <T> ChipBlock(
    title: String,
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    enabled = enabled,
                    label = {
                        Text(label(value), style = MaterialTheme.typography.labelMedium)
                    },
                )
            }
        }
    }
}
