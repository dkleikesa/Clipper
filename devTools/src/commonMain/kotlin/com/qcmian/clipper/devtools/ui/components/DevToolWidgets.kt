package com.qcmian.clipper.devtools.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.ui.code.rememberCodeColors
import com.qcmian.clipper.core.ui.components.HoverTooltip
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.theme.hintColor
import kotlin.math.roundToInt

/**
 * 工具界面共用的一小组控件。
 *
 * 各插件只有一屏「输入框 + 一排操作 + 输出框」，样式必须一致，所以不写在各工具里：一旦分叉，
 * JSON 工具与 XML 工具会长得不像同一套软件里的东西。
 *
 * 操作栏里的控件按**语义**分四类，各自一副长相。这条分工是刻意的：它们原先共用同一种按钮外壳，
 * 于是「选中」只能靠底色去猜，而底色又同时要兼任「主操作」的记号——一个视觉承担两件事，
 * 看的人自然分不清点下去会发生什么。
 *
 *  - 多选一 → [DevToolSegmentedControl]：一条轨道里几段，选中的那段实心主色；
 *  - 取一个值 → [DevToolMenuButton]：描边、透明底，像一格字段，点开才是一列选项；
 *  - 开关 → [DevToolToggle]：一枚勾选框，开没开直接画出来；
 *  - 一次性动作 → [DevToolButton]（[DevToolButton.primary] 表示「主操作」，与「选中」无关）。
 *
 * 不同类之间用 [DevToolGroupDivider] 断开；同类之间用 [DevToolActionSpacer]。
 *
 * 换**整页**（编码 / 解码）不在这一排里，用的是 [DevToolTabBar]：它换的是输入框与结果区本身，
 * 不是同一页里的一个取值，所以另画一副长相（见那个文件里的分工说明）。
 */

/**
 * 正文与滚动条之间留的空隙。
 *
 * 只让出滚动条本身的宽度时，文字右缘（或下缘）会紧贴着滑块，看着像压在一起。代码框里横竖两个
 * 方向、以及历史列表的竖向滚动条都读这一个值，免得四处的间距各走各的。
 */
internal val DevToolScrollbarGap = 5.dp

/**
 * 操作栏里所有控件共用的高度。
 *
 * 三种控件并排时高度必须一样，否则整排的基线会参差；写成一个常量而不是各写各的数字，
 * 将来调也只调这一处。
 */
private val DevToolControlHeight = 30.dp

@Composable
fun DevToolButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 主操作：实心主色。**这与「选中」不是一回事**——「多选一」的选中态归 [DevToolSegmentedControl]。 */
    primary: Boolean = false,
    /** 描边、透明底：长得像一格字段而不是一颗按钮；[DevToolMenuButton] 用它表示「这里是一个取值」。 */
    outlined: Boolean = false,
    /** 文字后面接着画的东西（例如 [DevToolMenuButton] 的下拉箭头）；拿到的是与文字同色的前景色。 */
    trailing: (@Composable (Color) -> Unit)? = null,
) {
    DevToolButtonSurface(
        enabled = enabled,
        primary = primary,
        outlined = outlined,
        onClick = onClick,
        modifier = modifier,
    ) { contentColor ->
        Text(title, fontSize = 13.sp, color = contentColor, maxLines = 1)
        trailing?.invoke(contentColor)
    }
}

/**
 * [DevToolButton] 与 [DevToolMenuButton] 共用的外壳：同高、同圆角，悬停与禁用的表现也一致。
 * 两种按钮长得像同一套东西，靠的就是这一层——各自只决定里面放什么。
 *
 * [content] 拿到的是**内容该用的前景色**：它随 [enabled] / [primary] 变，所以在这里算好往里传，
 * 而不是让每个调用方自己再判一遍（判两遍早晚会不一致）。
 *
 * [outlined] 是「取值格」与「按钮」的分界线：描边 + 透明底读起来是「这里放着一个值」，实心底
 * 读起来是「按下去会发生什么」。两者即使并排也不会被当成同一种控件。
 */
@Composable
private fun DevToolButtonSurface(
    enabled: Boolean,
    primary: Boolean,
    outlined: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (Color) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(6.dp)

    val background = when {
        outlined -> if (hovered && enabled) colors.onSurface.copy(alpha = 0.08f) else Color.Transparent
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
    // 实心按钮不加边框，所以这里对它们画一条透明边——省得两种按钮的内缩各差 1dp。
    val borderColor = when {
        !outlined -> Color.Transparent
        !enabled -> colors.outline.copy(alpha = 0.4f)
        else -> colors.outline
    }

    Row(
        modifier = modifier
            .height(DevToolControlHeight)
            .clip(shape)
            .background(background)
            .border(1.dp, borderColor, shape)
            .hoverable(interaction, enabled = enabled)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = if (outlined) 12.dp else 14.dp),
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
 * 外壳与 [DevToolButton] 同一套，但走**描边**那一版：它读起来是「一格字段当前填着什么」，
 * 而不是「按下去会发生什么」——与旁边实心的动作按钮、分段控件里的选中段都不会混。
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
            outlined = true,
            onClick = { expanded = true },
            modifier = Modifier.onSizeChanged { anchorWidth = it.width },
            trailing = { contentColor ->
                // 与剪贴板筛选栏的下拉（`FilterChip`）取同一档尺寸与间距：两处都是「点开一列选项」
                // 的控件，箭头大小不一样会显得是两套东西。图标用控件的前景色。
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

/** 同一组控件之间的间隔。 */
@Composable
fun DevToolActionSpacer() {
    Spacer(Modifier.width(10.dp))
}

/**
 * 不同组之间的间隔：一条细分隔线。
 *
 * 只把间距拉大不足以表达分组——间距是均一的，看过去仍是一排。一条竖线把「选模式 / 调取值 /
 * 开修饰」切成几截，眼睛不必读文字就知道哪几个是一伙的。
 */
@Composable
fun DevToolGroupDivider() {
    Box(
        Modifier
            .padding(horizontal = 4.dp)
            .width(1.dp)
            .height(16.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    )
}

/**
 * 内容区里上下两块的间隔：一条横贯的细分隔线。
 *
 * 与 [DevToolGroupDivider] 相对——那条是竖的，切开操作栏里并排的几组控件；这条是横的，切开上下
 * 两块内容（例如「输入 / 结果 / 动作」与下面的「历史」）。整条铺满宽度而不是留出首尾缩进：它划的
 * 是两块区域，不是某个按钮。
 */
@Composable
fun DevToolSectionDivider(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    )
}

/**
 * 互斥选择：一条轨道里若干分段，选中的那一段实心主色，其余透明。
 *
 * 存在的理由是「多选一」这件事必须**看起来像一件事**。原先它由两个各自独立的按钮表示：
 * 互斥关系看不出来，选中态只能靠底色猜——而底色同时还要兼任「主操作」的记号，于是相邻两个
 * 按钮一实一虚，分不清哪个是「选中的」哪个是「更重要的」。合进一条轨道之后，「这几项里只能
 * 挑一个、现在挑的是它」自带说明。
 *
 * 与 [DevToolToggle] 不能互换外观：那个是「开 / 关」，这个是「甲 / 乙」。
 *
 * @param tooltip 每一段的悬停说明。段上只有短名，铺不下「RFC 3986」「encodeURI」这类出处，
 *   需要交代时就用它；为空（默认）时整段不挂提示，行为与从前一字不差。
 */
@Composable
fun <T> DevToolSegmentedControl(
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    tooltip: ((T) -> String)? = null,
) {
    val colors = MaterialTheme.colorScheme
    // 每段一份交互源：悬停高亮要各算各的。列表长度恒定，按长度记忆即可。
    val interactions = remember(options.size) { List(options.size) { MutableInteractionSource() } }
    val trackShape = RoundedCornerShape(7.dp)

    Row(
        modifier = modifier
            .height(DevToolControlHeight)
            .clip(trackShape)
            // 轨道比周围重一点点：它要声明「我这几个是一组的」，底色太淡就看不出来。
            .background(colors.onSurface.copy(alpha = 0.06f))
            // 内缩 2dp，选中段才是嵌在轨道里的一条，而不是与轨道等高的色块。
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            val hovered by interactions[index].collectIsHoveredAsState()
            // 提示为空时 `HoverTooltip` 直接渲染内容，不做任何包裹——所以这里的结构对
            // 「没传 tooltip」的调用方没有任何额外影响。
            HoverTooltip(text = tooltip?.invoke(option).orEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(5.dp))
                        .background(
                            when {
                                isSelected -> colors.primary
                                hovered -> colors.onSurface.copy(alpha = 0.08f)
                                else -> Color.Transparent
                            }
                        )
                        .hoverable(interactions[index])
                        .clickable(interactionSource = interactions[index], indication = null) {
                            onSelect(option)
                        }
                        .padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = optionLabel(option),
                        fontSize = 13.sp,
                        color = if (isSelected) colors.onPrimary else colors.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * 开关：一枚勾选框加标签，表示「这一层修饰开不开」。
 *
 * 与另外两类控件刻意拉开距离：分段控件是实心色块、下拉是描边格子，只有这个是勾选框。勾选框
 * 天生只说「有没有」——不必靠底色去推当前是开是关，也不会与「选一个」混淆。
 */
@Composable
fun DevToolToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val boxShape = RoundedCornerShape(4.dp)

    Row(
        modifier = modifier
            .height(DevToolControlHeight)
            .clip(RoundedCornerShape(6.dp))
            // 开关不给自己上底色：它是「叠在别的选择之上的一层修饰」，视觉重量该比那两类轻。
            .background(if (hovered) colors.onSurface.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) {
                onCheckedChange(!checked)
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(boxShape)
                .background(if (checked) colors.primary else Color.Transparent)
                .border(1.dp, if (checked) colors.primary else colors.outline, boxShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) {
                ClipperIcon(ClipperIconKind.CHECKMARK, size = 10.dp, tint = colors.onPrimary)
            }
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = title,
            fontSize = 13.sp,
            color = if (checked) colors.primary else colors.onSurface,
            maxLines = 1,
        )
    }
}

/**
 * 单行文本输入框：**项目里唯一的单行输入实现**，各变体只差参数。
 *
 * 与 `DevToolCodeField`（代码编辑框）刻意不同：那个要装多行、要行号与滚动条，天然是「一块区域」；
 * 这里的值只有一行（时间戳的格式模板、Hash 待对拍的摘要、时区搜索），用轻量输入框才贴切。
 *
 * 底色与边框是刻意选的：**实底 + 聚焦高亮**才读得出「这里能敲字」。若跟 [DevToolMenuButton] 那种
 * 下拉一样用透明底 + 一股描边，两者就长得一模一样，让人以为它也是只读的。
 *
 * 两处变体收敛在这里，各自不能另立一份实现（样式一分叉，同一个面板里就会出现两种输入框）：
 *  - [secret]：口令这类不能明文的输入，字打成点；
 *  - [bordered]：嵌在菜单 / 弹层里的搜索框，本身**不要壳**（无实底、无描边、不限高），外面那层
 *    容器负责外观。
 *
 * @param placeholder 空内容时的占位提示。
 * @param secret 打点显示（`PasswordVisualTransformation`）。口令框一律走这一档。
 * @param bordered 是否带「实底 + 描边 + 固定 30dp 高」那副壳。`false` 是无框输入，只要文字与光标。
 */
@Composable
internal fun DevToolSingleLineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    bordered: Boolean = true,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    // 底色与输入框（`DevToolCodeField`）取同一块：都是「能敲字的框」，一个样才读得出来。
    val codeColors = rememberCodeColors()
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(fontSize = 13.sp, color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        interactionSource = interaction,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier
            // 鼠标移进来要变**文本光标**：这框能敲字，而光标是「这里能敲」最直接的那句提示。
            // 摆在这一串的最前面：它的作用范围就是这一格整块（放到 `padding` 之后就只剩文字那一小块）。
            .pointerHoverIcon(PointerIcon.Text)
            .then(
                if (bordered) {
                    Modifier
                        .height(DevToolControlHeight)
                        .clip(shape)
                        .background(codeColors.editorBackground)
                        .border(
                            width = 1.dp,
                            color = if (focused) colors.primary else colors.outline.copy(alpha = 0.6f),
                            shape = shape,
                        )
                        .padding(horizontal = 12.dp)
                } else {
                    Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                }
            ),
        decorationBox = { innerTextField ->
            // 有壳时撑满整块高度再居中：`BasicTextField` 自己只占文字那一行，不这么做文字会贴在顶上。
            // 无壳时高度就是文字本身，铺满宽度即可。
            Box(
                modifier = if (bordered) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        fontSize = 13.sp,
                        color = MaterialTheme.hintColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                innerTextField()
            }
        },
    )
}

/**
 * 滑杆轨道宽度。太窄则一像素好几个百分点，太宽会把工具栏那一行挤满。
 */
private val DevToolSliderWidth = 132.dp

/**
 * 工具栏里的滑杆：一个**连续**取值，外加当前值读数。
 *
 * 与设置页那个 `SliderRow`（标题一行、滑杆独占下一行）不同，这里在**工具栏**里，要与旁边的分段
 * 控件、下拉同高同一行，所以只留「滑杆 + 读数」两件东西，标题交给调用方（见 `BarcodeDevTool` 的
 * `ToolbarOption`）。轨道与拇指的样式跟设置页保持一致（关掉断口与首尾停点、拇指 18dp 带白边），
 * 免得同一个应用里出现两种滑杆。
 *
 * 什么时候该用它：参数**真的有刻度**才配滑杆——Aztec 的纠错是百分比，连续量切成四档既够不到
 * 23% 这种中间值，也看不出它本来是连续的。离散档位仍用 [DevToolSegmentedControl] 或
 * [DevToolMenuButton]。
 *
 * @param valueLabel 当前值怎么显示（如「33%」）。拖动时它是唯一的读数。
 */
@Composable
fun DevToolSlider(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    valueLabel: (Int) -> String,
    modifier: Modifier = Modifier,
    tooltip: String? = null,
) {
    val content: @Composable () -> Unit = {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier.height(DevToolControlHeight),
        ) {
            Slider(
                value = value.toFloat(),
                onValueChange = { onValueChange(it.roundToInt()) },
                valueRange = range.first.toFloat()..range.last.toFloat(),
                // 逐格吸附：取值是整数量，拖动时不该出现 33.4% 这种读数。
                steps = (range.last - range.first - 1).coerceAtLeast(0),
                modifier = Modifier.width(DevToolSliderWidth),
                // 轨道自画成 4dp 细线：这一版 Material 的 `SliderDefaults.Track` 画的是 16dp 的粗胶囊，
                // 与设置页那条细线对不上，所以不借它，只取它的颜色；垂直居中由 Slider 自己的 Layout
                // 负责（`trackOffsetY`），这里只铺一条线。
                track = { sliderState ->
                    val sliderColors = SliderDefaults.colors()
                    val range = sliderState.valueRange
                    val fraction = if (range.endInclusive == range.start) 0f
                        else ((sliderState.value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(sliderColors.inactiveTrackColor),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(fraction)
                                .fillMaxHeight()
                                .background(sliderColors.activeTrackColor),
                        )
                    }
                },
                // 拇指缩到 18dp、去掉按下缩放：Material 默认那副动效与工具栏其余控件的静止感不合。
                thumb = {
                    Box(
                        Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(SliderDefaults.colors().thumbColor)
                            .border(2.dp, Color.White, CircleShape),
                    )
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(text = valueLabel(value), fontSize = 12.sp, color = MaterialTheme.hintColor)
        }
    }

    if (tooltip == null) {
        content()
    } else {
        HoverTooltip(text = tooltip, positioning = TooltipAnchorPosition.Above) { content() }
    }
}
