package com.qcmian.clipper.feature.preferences.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.settings.ShortcutSpec
import com.qcmian.clipper.core.ui.components.rememberImageBitmap
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind

/**
 * 偏好设置对话框中可复用的各种行。
 *
 * 视觉约定（与全局主题一致）：
 * - 主色 `primary`（iOS 蓝 0A84FF）只用于选中态、录制态与分区标题；
 * - 输入控件统一 8dp 圆角、`surfaceVariant` 半透明底 + `outline` 半透明描边；
 * - 行高与间距走 4dp 的倍数。
 */

/** 设置页里的分区卡片：圆角容器 + 左上角主色分区标题，对应 macOS 系统设置的分组样式。 */
@Composable
internal fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        // animateContentSize：内部条件行（警告文字、附加控件）出现/消失时平滑过渡，
        // 而不是让整张卡片瞬间跳一下。
        Column(
            Modifier
                .fillMaxWidth()
                .animateContentSize()
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/**
 * 忽略应用列表中的一行：应用图标、解析出的应用名，
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
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
        IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
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
    val editable = item.text != null && item.image == null && item.files.isEmpty()
    var content by remember(item.id, item.text) { mutableStateOf(item.text.orEmpty()) }

    Column(
        Modifier
            .fillMaxWidth()
            // `PinsSettingsPane` 的表格选中：点击该行会让它成为 Delete 键的作用目标。
            .clickable(onClick = onSelect)
            .background(
                if (isSelected) colors.primary.copy(alpha = 0.12f) else Color.Transparent,
                RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
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
                        .width(46.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.surfaceVariant.copy(alpha = 0.35f))
                        .border(1.dp, colors.outline.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
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
                    .height(30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceVariant.copy(alpha = 0.35f))
                    .border(1.dp, colors.outline.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
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

            IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                ClipperIcon(ClipperIconKind.TRASH, size = 13.dp, tint = colors.onSurfaceVariant)
            }
        }

        if (editable) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceVariant.copy(alpha = 0.35f))
                    .border(1.dp, colors.outline.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
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

/** 行。 */
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
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
                .height(30.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (recording) colors.primary.copy(alpha = 0.10f)
                    else colors.surfaceVariant.copy(alpha = 0.35f),
                )
                .border(
                    width = 1.dp,
                    color = if (recording) colors.primary else colors.outline.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(6.dp),
                )
                .clickable(onClick = onRecord)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (recording) "按下新快捷键…" else spec.label,
                fontSize = 12.sp,
                color = if (recording) colors.primary else colors.onSurface,
            )
        }
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = onReset, modifier = Modifier.size(28.dp)) {
            ClipperIcon(ClipperIconKind.CLEAR, size = 12.dp, tint = colors.onSurfaceVariant)
        }
    }
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
        modifier = Modifier
            .fillMaxWidth()
            // 整行可点：开关本身很小，不必精确命中它。
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
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
        Spacer(Modifier.width(12.dp))
        // M3 Switch 默认 52×32，在设置行里偏大；等比缩到 ~38×24（点击整行即可切换）。
        Box(
            modifier = Modifier.size(width = 38.dp, height = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.scale(0.7f),
            )
        }
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
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        // 标题在左、当前值在右，同一行；滑杆独占下一整行——窄容器下也不会挤压换行。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
        }
        val interactionSource = remember { MutableInteractionSource() }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            enabled = enabled,
            interactionSource = interactionSource,
            // 默认轨道会在拇指位置挖一个「拇指宽 + 两侧 8dp」的矩形断口，视觉上滑块
            // 显得很大；这里关掉断口和首尾停止点，轨道连续贯穿，只有拇指浮在上面。
            track = {
                SliderDefaults.Track(
                    sliderState = it,
                    thumbTrackGapSize = 0.dp,
                    trackInsideCornerSize = 0.dp,
                    drawStopIndicator = null,
                )
            },
            // 默认拇指 20dp 相对 4dp 轨道过大，缩到 14dp。
            // 自绘拇指：默认 Thumb 按下时会有缩放动画且白边 2dp 太粗，
            // 这里按下态与普通态保持一致，白边减半为 1dp。
            thumb = {
                val sliderColors = SliderDefaults.colors()
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(
                            if (enabled) sliderColors.thumbColor
                            else sliderColors.disabledThumbColor,
                        )
                        .border(2.dp, Color.White, CircleShape),
                )
            },
            // M3 Slider 默认占高 48dp，压到 28dp 与紧凑行高一致。
            modifier = Modifier.fillMaxWidth().height(28.dp),
        )
    }
}

/**
 * 单选组：用一条分段控件（segmented control）表达"多选一"，
 * 比并排的普通按钮更能体现"互斥、共享同一轨道"的语义。
 *
 * 视觉：整组是一块圆角轨道，选中项是一枚主色拇指滑块（`primary` 底 + `onPrimary` 文字），
 * 未选中项透明底 + `onSurfaceVariant` 文字；各项等宽，切换时底色平滑过渡。
 */
@Composable
internal fun <T> SegmentedBlock(
    title: String,
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
        )
        // 轨道：整块作为背景，内部留 2dp 内边距容纳拇指滑块。
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = colors.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(2.dp)) {
                values.forEach { value ->
                    val isSelected = value == selected
                    val thumbColor by animateColorAsState(
                        targetValue = if (isSelected) colors.primary else Color.Transparent,
                    )
                    val textColor by animateColorAsState(
                        targetValue = when {
                            !enabled -> colors.onSurfaceVariant.copy(alpha = 0.4f)
                            isSelected -> colors.onPrimary
                            else -> colors.onSurfaceVariant
                        },
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(26.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(thumbColor)
                            .then(
                                if (enabled) {
                                    Modifier.clickable { onSelect(value) }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label(value),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 以逗号展开 `List<String>` 的单行输入框。
 *
 * 维护一份本地草稿：本框输入实时提交到 [onValuesChange]，同时用 [committed] 记账，
 * 因此设置被外部修改（如「恢复默认类型」按钮）时草稿会自动跟随刷新，
 * 而本框自己的提交不会触发重置，逗号分隔的连续输入不受影响。
 */
@Composable
internal fun DelimitedListField(
    values: List<String>,
    onValuesChange: (List<String>) -> Unit,
    label: String,
    supportingText: String,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf(values.joinToString(", ")) }
    var committed by remember { mutableStateOf(values) }

    LaunchedEffect(values) {
        if (values != committed) {
            draft = values.joinToString(", ")
            committed = values
        }
    }

    OutlinedTextField(
        value = draft,
        onValueChange = { text ->
            draft = text
            committed = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            onValuesChange(committed)
        },
        label = { Text(label) },
        supportingText = { Text(supportingText) },
        singleLine = true,
        modifier = modifier,
    )
}
