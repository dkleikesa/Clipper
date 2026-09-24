package com.qcmian.clipper.feature.preferences.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.ShortcutSpec
import com.qcmian.clipper.core.ui.components.HoverTooltip
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.theme.hintColor

/**
 * 偏好设置对话框中可复用的各种行。
 *
 * 视觉约定（与全局主题一致）：
 * - 主色 `primary`（iOS 蓝 0A84FF）只用于选中态、录制态与分区标题；
 * - 输入控件统一 8dp 圆角、`surfaceVariant` 半透明底 + `outline` 半透明描边；
 * - 行高与间距走 4dp 的倍数。
 */

/**
 * 设置页里的一组设置：圆角容器 + 描边，**不带标题**。
 *
 * 标题不在这里，是因为「当前是哪个分区」已经由侧边栏的选中项与页面大标题说了两遍；
 * 一张卡片再抄一遍就是同一句话出现三次。页内的次级分组用 [GroupLabel]。
 */
@Composable
internal fun SettingsGroup(
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            content()
        }
    }
}

/**
 * 一组的标题（如「外观」页里的窗口 / 列表 / 搜索，或「存储与数据」里的容量 / 清除）。
 *
 * 必须比分组里的行**明显**更抢眼：行标题是 `bodyMedium`（14sp），所以这里用大一档的
 * `titleMedium`（16sp）+ 加粗，颜色取正文色 `onSurface`。用 labelMedium(12sp) /
 * labelSmall(11sp) 会让标题比正文行还小，就看不出这是分组了。
 */
@Composable
internal fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
    )
}

/**
 * 键位胶囊的固定宽度。
 *
 * 按最长的那条设计：录制中的「按下新快捷键」是 6 个 12sp 的字形（约 72dp），加左右各 12dp
 * 内边距约 96dp；实际绑定的键位标签最长是「⌃⌥⇧⌘」加一个字符，也用不满，取 110dp 留余量。
 */
private val ShortcutPillWidth = 110.dp

/**
 * 键位胶囊：可录制行的当前绑定。**定宽**，内容居中。
 *
 * 定宽是刻意的：绑定长短不一（`⏎` 到 `⌃⌥⇧⌘⎋`），若各自包住文字，整列右边缘会参差不齐，
 * 扫一眼看不出「这一列都是键位」。定宽之后每行的胶囊与右侧垃圾桶都落在同一条竖线上。
 *
 * 文字用 hintColor 表示「未绑定」（[dimmed]），与「有绑定」区分。
 *
 * @param onClick `null` 表示这一行不可交互（[dimmed] 与它无关：未绑定的行仍可点击去录制）。
 */
@Composable
private fun ShortcutPill(
    text: String,
    recording: Boolean = false,
    dimmed: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .width(ShortcutPillWidth)
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
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = when {
                recording -> colors.primary
                dimmed -> MaterialTheme.hintColor
                else -> colors.onSurface
            },
            // 定宽之后文本要能截断：用户录进特别长的组合时宁可省略，也不要撑破胶囊。
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 可录制快捷键的一行：标题（含 [hint] 小字）、当前绑定（点一下重新录制）、清除按钮。
 *
 * 清除写的是「未绑定」（[spec] 为 `null`），而不是「恢复默认」：默认值只是出厂时的一次赋值，
 * 用户按 ✕ 的意图是「这个功能不要快捷键」。想回到默认值有设置页底部的「恢复默认设置」。
 *
 * @param hint 标题下的灰色小字，用来说明派生的交互（`⇧` 连选、`⏎` 的修饰键映射……）。
 * @param label 覆盖胶囊里的文字。默认用 [spec] 的 `label`（`⌥⌘⌫`）；「快速粘贴」那种一带多的
 *   绑定要写成 `⌘1…9`，一条 spec 表达不出来。
 */
@Composable
internal fun ShortcutRow(
    title: String,
    spec: ShortcutSpec?,
    recording: Boolean,
    onRecord: () -> Unit,
    onClear: () -> Unit,
    hint: String? = null,
    label: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurface,
            )
            if (!hint.isNullOrBlank()) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.hintColor,
                )
            }
        }
        ShortcutPill(
            text = if (recording) "按下新快捷键" else label ?: spec?.label ?: "未设置",
            recording = recording,
            dimmed = spec == null,
            onClick = onRecord,
        )
        Spacer(Modifier.width(8.dp))
        HoverTooltip("清除快捷键") {
            IconButton(onClick = onClear, enabled = spec != null, modifier = Modifier.size(28.dp)) {
                // 垃圾桶而不是叉号：这一列的动作是「删掉这条绑定」，
                // 叉号在设置页里还兼着「关闭窗口」的意思，两者容易看岔。
                ClipperIcon(ClipperIconKind.TRASH, size = 15.dp, tint = colors.onSurfaceVariant)
            }
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
                    color = MaterialTheme.hintColor,
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

/**
 * 一条布尔偏好：标题、读值、写回。
 *
 * 设置页里的开关占了绝大多数，逐项手写 `SwitchRow(...) { onSettingsChange { it.copy(...) } }`
 * 会把同一段样板抄二十来遍，字段名还要在「读」与「写」两处各出现一次。把这两件事收进一个值，
 * 分区里就只剩一张表（见 [SwitchSettings]），新增开关只多一行声明。
 *
 * 前三个参数刻意保持 `(标题, 读, 写)` 的顺序：最常见的那几项一行就写得下。
 *
 * @param read 取当前值。表只是声明，值必须现取——偏好随时可能被外部改掉（如「恢复默认设置」）。
 * @param write 写入新值；写成 [AppSettings] 的扩展，调用点因此只写 `copy(field = value)`。
 * @param description 标题下的灰色小字说明。
 * @param enabled 平台不支持时置灰（仍占一行，可解释「为什么用不了」）。
 * @param visible 整行都不出现（如宿主不支持开机自启时，那一项干脆不显示）。
 */
internal class BooleanSetting(
    val title: String,
    val read: (AppSettings) -> Boolean,
    val write: AppSettings.(Boolean) -> AppSettings,
    val description: String? = null,
    val enabled: Boolean = true,
    val visible: Boolean = true,
)

/**
 * 按表渲染一组布尔开关，顺序即表中的顺序。
 *
 * 表是「有哪些开关、各自读写哪个字段」的唯一声明处，渲染与改动路径都只有这一条。
 */
@Composable
internal fun SwitchSettings(
    settings: AppSettings,
    items: List<BooleanSetting>,
    onChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    items.forEach { setting ->
        if (!setting.visible) return@forEach
        SwitchRow(
            title = setting.title,
            checked = setting.read(settings),
            description = setting.description,
            enabled = setting.enabled,
        ) { value -> onChange { setting.write(it, value) } }
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
        // 标题与滑杆之间留 5dp：滑杆自身的高度大半是给拇指的留白，紧贴标题会显得挤在一起。
        Spacer(Modifier.height(5.dp))
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

