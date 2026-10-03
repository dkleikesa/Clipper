package com.qcmian.clipper.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.components.HorizontalScrollbar
import com.qcmian.clipper.core.ui.components.HorizontalScrollbarHeight
import com.qcmian.clipper.core.ui.components.VerticalScrollbar
import com.qcmian.clipper.core.ui.components.VerticalScrollbarWidth
import com.qcmian.clipper.core.ui.theme.hintColor

/**
 * 工具界面共用的一小组控件。
 *
 * 各插件只有一屏「输入框 + 一排按钮 + 输出框」，样式必须一致，所以不写在各工具里：一旦分叉，
 * JSON 工具与 XML 工具会长得不像同一套软件里的东西。
 */

/**
 * 正文与滚动条之间留的空隙。
 *
 * 只让出滚动条本身的宽度时，文字右缘（或下缘）会紧贴着滑块，看着像压在一起。两个编辑控件、
 * 横竖两个方向都读这一个值，免得四处的间距各走各的。
 */
internal val DevToolScrollbarGap = 5.dp

/**
 * 一节等宽编辑区（输入或输出）。
 *
 * [isError] 表示 [value] 本身是一条失败说明（而不是排好版的内容），正文改用错误色：调用方因此
 * 可以像普通结果一样把错误交给这个控件，不必在框外另开一行提示——那一行出现 / 消失会把编辑区
 * 顶得跳一下。与 `DevToolCodeField` 的同名参数同义。
 *
 * [softWrap] 与 `DevToolCodeField` 的同名参数一致：默认**不折**长行，改为横向滚出去。目前写死
 * 默认值，等设置项齐了再由界面提供开关。
 */
@Composable
fun DevToolEditor(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    placeholder: String = "",
    isError: Boolean = false,
    softWrap: Boolean = false,
    /** 标题行右端那块地方，用来放只属于这个框的动作；与 `DevToolCodeField` 的同名参数一致。 */
    actions: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()

    Column(modifier.fillMaxWidth()) {
        // 标签在左、动作在右：动作属于这个框，就该跟它的名字同处一行。
        // 与 `DevToolCodeField` 的标题行同一档字号，两个编辑控件看起来才是同一套东西。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.hintColor)
            Spacer(Modifier.weight(1f))
            actions()
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 高度由调用方在自己的 Column 里用 `weight` 分配，这里只吃掉分到的那一份。
                .weight(1f)
                .clip(shape)
                .background(colors.onSurface.copy(alpha = 0.05f))
                .border(1.dp, colors.outline.copy(alpha = 0.6f), shape),
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // 内边距加在正文这一层、滚动条留在它外面，滚动条因此贴住面板边框；
                            // 加在面板上的话滚动条会被推进来，与边框之间留一条露出底色的缝。
                            // 右端与下端各留出一条滚动条的粗细再加上一点间隙，无论它此刻在不在
                            // 都留着，免得出现 / 消失时正文跟着重排一次。
                            .padding(
                                start = 8.dp,
                                end = 8.dp + VerticalScrollbarWidth + DevToolScrollbarGap,
                                top = 6.dp,
                                bottom = if (softWrap) 6.dp
                                else 6.dp + DevToolScrollbarGap + HorizontalScrollbarHeight,
                            )
                            // 滚动挂在内容这一层（边框与内边距之内），因此滚的是内容、框本身不动；
                            // 占位提示也是它的子节点，跟着一起滚，不会再「滚走了提示还停在原地」。
                            .verticalScroll(verticalScroll)
                            // 不折行时这一层才是横向滚动容器：子节点拿到无限宽约束，于是按最长
                            // 一行排版，没有可折的宽度。
                            .then(
                                if (softWrap) Modifier
                                else Modifier.horizontalScroll(horizontalScroll)
                            ),
                    ) {
                        if (value.isEmpty() && placeholder.isNotEmpty()) {
                            Text(
                                text = placeholder,
                                fontSize = 12.sp,
                                color = colors.onSurfaceVariant.copy(alpha = 0.6f),
                            )
                        }
                        BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            readOnly = readOnly,
                            textStyle = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                color = if (isError) colors.error else colors.onSurface,
                            ),
                            cursorBrush = SolidColor(colors.primary),
                            modifier = Modifier
                                // 折行时才撑满：不折行时外层给的是无限宽，要求一个无限的宽度没有意义。
                                .then(if (softWrap) Modifier.fillMaxWidth() else Modifier),
                        )
                    }
                    VerticalScrollbar(
                        scrollState = verticalScroll,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
                // 横向滚动条排在正文之下、占一行固定高度（只有不折行时才存在）。
                if (!softWrap) {
                    HorizontalScrollbar(
                        scrollState = horizontalScroll,
                        modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun DevToolButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    primary: Boolean = false,
    /** 文字后面接着画的东西（例如 [DevToolMenuButton] 的下拉箭头）；拿到的是与文字同色的前景色。 */
    trailing: (@Composable (Color) -> Unit)? = null,
) {
    DevToolButtonSurface(
        enabled = enabled,
        primary = primary,
        onClick = onClick,
        modifier = modifier,
    ) { contentColor ->
        Text(title, fontSize = 13.sp, color = contentColor, maxLines = 1)
        trailing?.invoke(contentColor)
    }
}

/**
 * [DevToolButton] 与 [DevToolMenuButton] 共用的外壳：同高、同圆角、同底色，悬停与禁用的表现也一致。
 * 两种按钮长得像同一套东西，靠的就是这一层——各自只决定里面放什么。
 *
 * [content] 拿到的是**内容该用的前景色**：它随 [enabled] / [primary] 变，所以在这里算好往里传，
 * 而不是让每个调用方自己再判一遍（判两遍早晚会不一致）。
 */
@Composable
private fun DevToolButtonSurface(
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Color) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val background = when {
        !enabled -> colors.onSurface.copy(alpha = 0.05f)
        primary -> colors.primary
        hovered -> colors.onSurface.copy(alpha = 0.16f)
        else -> colors.onSurface.copy(alpha = 0.08f)
    }
    val contentColor = when {
        !enabled -> colors.onSurfaceVariant.copy(alpha = 0.5f)
        primary -> colors.onPrimary
        else -> colors.onSurface
    }

    Row(
        modifier = modifier
            .height(30.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .hoverable(interaction, enabled = enabled)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        content(contentColor)
    }
}

/**
 * 单选下拉按钮：按钮上是**当前取值**，点开是一列互斥选项，当前项带对号。
 *
 * 用来表达「多选一」的取值域，替代「一组加减按钮 + 一个开关按钮」那种写法。加减按钮的毛病
 * 在取值域一长就明显：选到最远的一档要点很多下，中途还看不到都有哪些档位。开关按钮则常常
 * 要靠收缩或展开旁边的控件来表示状态，于是整排按钮跟着跳，而被收起来的那一份取值就看不见了。
 * 这里按钮上永远只有当前取值这一条文字，菜单展开也不改布局。
 *
 * 外壳与 [DevToolButton] 同一套，所以并排放在操作栏里不显得是外来控件。
 *
 * 菜单行自己排「对号列 + 文字」，与剪贴板筛选栏的单选下拉（`FilterDropdown`）一致：不用
 * `DropdownMenuItem`，免得工具窗口里出现 Material 默认的菜单留白。
 *
 * @param label 按钮上的文字，通常就是 [optionLabel] 作用在 [selected] 上的结果。
 */
@Composable
fun <T> DevToolMenuButton(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    // 让菜单水平居中于按钮：先量出两者宽度，宽度没量到之前不偏移。
    var anchorWidth by remember { mutableStateOf(0) }
    var menuWidth by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    val offsetX = if (menuWidth == 0) 0.dp else with(density) { ((anchorWidth - menuWidth) / 2).toDp() }

    Box(modifier) {
        DevToolButton(
            title = label,
            enabled = enabled,
            onClick = { expanded = true },
            modifier = Modifier.onSizeChanged { anchorWidth = it.width },
            trailing = { contentColor ->
                // 与剪贴板筛选栏的下拉（`FilterChip`）取同一档尺寸与间距：两处都是「点开一列选项」
                // 的按钮，箭头大小不一样会显得是两套控件。图标用按钮的前景色，好跟着 primary 走。
                Spacer(Modifier.width(3.dp))
                ClipperIcon(ClipperIconKind.CHEVRON_DOWN, size = 15.dp, tint = contentColor)
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(offsetX, 0.dp),
            modifier = Modifier.onSizeChanged { menuWidth = it.width },
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expanded = false
                            onSelect(option)
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 固定尺寸的勾选列：选中与未选中的行因此一样高，切换时菜单不会抖。
                    Box(Modifier.width(14.dp).height(14.dp), contentAlignment = Alignment.Center) {
                        if (isSelected) {
                            ClipperIcon(ClipperIconKind.CHECKMARK, size = 14.dp, tint = colors.primary)
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = optionLabel(option),
                        fontSize = 13.sp,
                        color = if (isSelected) colors.primary else colors.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 与 [DevToolButton] 的圆角一起构成操作栏的统一观感。 */
@Composable
fun DevToolActionSpacer() {
    Spacer(Modifier.width(10.dp))
}

/** 工具内的一条提示（成功 / 失败）。 */
@Composable
fun DevToolMessage(message: String, isError: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = message,
        fontSize = 11.sp,
        color = if (isError) colors.error else colors.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}
