package com.qcmian.clipper.devtools.ui.components.code

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.ui.theme.hintColor
import kotlin.math.roundToInt

/** 折叠箭头所在列的宽度（够放 [ChevronLong] 的箭头，两侧还留得出余量）。 */
private val ChevronColumnWidth = 18.dp

/** 行号与折叠列之间的间距。 */
private val GutterNumbersGap = 10.dp

/** 折叠列与分隔线之间的间距。 */
private val GutterEndPadding = 6.dp

/** 分隔线与正文之间的间距：紧贴着看，正文首列会像是压在线上。 */
private val GutterTextGap = 5.dp

/** 折叠箭头尺寸：圆头 chevron。 */
private val ChevronLong = 8.dp
private val ChevronShort = 4.dp
private val ChevronStroke = 1.5.dp

/**
 * 带行号、语法高亮与折叠的代码输入区——**建立在原生 `BasicTextField` 之上**。
 *
 * 三件事各走一条不侵入控件的路：
 *  - 高亮与折叠：交给 [CodeVisualTransformation]（只改显示，`value` 仍是真实文档）；
 *  - 行号：从 `onTextLayout` 拿到排版结果，在左侧装订线里按行画；
 *  - 折叠开关：装订线里的箭头 + 被折叠处的 `…` 占位符，都是叠在控件上的小点击区。
 *
 * 因此输入法、光标、选区、无障碍仍然全部由平台控件负责——这正是从自绘编辑器换回来换到的东西。
 *
 * [isError] 表示 [value] 本身是一条失败说明（而不是排好版的内容），正文改用错误色：调用方因此
 * 可以像普通结果一样把错误交给这个控件，不必在框外另开一行提示。
 */
@Composable
internal fun DevToolCodeField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    placeholder: String = "",
    isError: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    val hint = MaterialTheme.hintColor
    val shape = RoundedCornerShape(6.dp)
    val codeColors = rememberCodeColors()
    val textStyle = remember(colors, isError) {
        TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = if (isError) colors.error else colors.onSurface,
        )
    }
    val textMeasurer = rememberTextMeasurer()
    val scrollState = rememberScrollState()
    val interactionSource = remember { MutableInteractionSource() }

    // 控件自己持有选区：折叠要知道光标在哪（落进被折叠的区间就得把它展开）。
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    // 外部换了内容（换剪贴板条目、点了格式化）才整块替换；用户自己敲的字这里一定相等。
    LaunchedEffect(value) {
        if (fieldValue.text != value) {
            fieldValue = TextFieldValue(value, TextRange(value.length))
        }
    }

    // 折叠状态只记「被折叠括号对的起点」。文档一变：先按 diff 平移，再让与新配对不符的失效。
    val foldedStarts = remember { mutableStateListOf<Int>() }
    val text = fieldValue.text
    val structure = remember(text) { scanJson(text) }
    val folded = remember(structure, foldedStarts.toList()) {
        structure.brackets.filter { it.isFoldable && it.foldStart in foldedStarts }
    }
    val transformation = remember(structure, folded, codeColors) {
        CodeVisualTransformation(structure.tokens, folded, codeColors)
    }
    LaunchedEffect(structure) {
        val valid = structure.brackets.filter { it.isFoldable }.map { it.foldStart }.toSet()
        foldedStarts.retainAll { it in valid }
    }

    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var fieldTopInRow by remember { mutableStateOf(0f) }
    val mapping = remember(text, transformation) {
        transformation.filter(AnnotatedString(text)).offsetMapping
    }
    val lines = remember(layout, mapping, structure) {
        layout?.let { visualLines(it, mapping, structure) }
    }

    val density = LocalDensity.current
    val digitWidthPx = remember(textMeasurer, textStyle) {
        textMeasurer.measure("0", textStyle).size.width.toFloat()
    }
    val digits = structure.lineCount.toString().length.coerceAtLeast(2)
    val gutterWidth = with(density) {
        (
            ChevronColumnWidth.toPx() + digitWidthPx * digits +
                GutterNumbersGap.toPx() + GutterEndPadding.toPx()
            ).toDp()
    }
    // 装订线不单独上色：与正文同一块底色，只靠一条竖分隔线分界（见两套配色里的 gutterDivider）。

    fun toggleFold(pair: BracketPair) {
        if (pair.foldStart in foldedStarts) {
            foldedStarts.remove(pair.foldStart)
            return
        }
        foldedStarts.add(pair.foldStart)
        // 光标若已经在被折叠的区间里，把它挪到区间起点：否则它就「消失」在藏起来的那段文本里。
        val selection = fieldValue.selection
        if (selection.min < pair.foldEnd && selection.max > pair.foldStart) {
            fieldValue = fieldValue.copy(selection = TextRange(pair.foldStart))
        }
    }

    Column(modifier.fillMaxWidth()) {
        Text(label, fontSize = 11.sp, color = hint)
        Spacer(Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(shape)
                .background(codeColors.editorBackground)
                .border(1.dp, colors.outline.copy(alpha = 0.6f), shape)
                .padding(horizontal = 2.dp, vertical = 4.dp),
        ) {
            // 分界线铺满整块面板（不随内容滚动）：只按内容高度画的话，内容短于面板时那一列会中途断掉。
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .fillMaxHeight()
                    .offset(x = gutterWidth)
                    .background(codeColors.gutterDivider)
            )
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                FoldGutter(
                    structure = structure,
                    text = text,
                    lines = lines,
                    fieldTopInRow = fieldTopInRow,
                    scroll = scrollState.value.toFloat(),
                    viewport = scrollState.viewportSize,
                    width = gutterWidth,
                    foldedStarts = foldedStarts,
                    textMeasurer = textMeasurer,
                    textStyle = textStyle,
                    lineNumberColor = hint,
                    chevronColor = hint,
                    interactionSource = interactionSource,
                    onToggle = ::toggleFold,
                )
                // 分隔线画在装订线的右缘（`x = gutterWidth`），正文从它右边再让出一点才开始。
                Spacer(Modifier.width(GutterTextGap))
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .onGloballyPositioned { fieldTopInRow = it.positionInParent().y }
                ) {
                    // 占位提示放在**文本框内部**、用与正文同一套度量：它的原点因此与正文完全
                    // 一致。放在外面当兄弟节点（旧 DevToolEditor 的写法）会因为它自己的字号/
                    // 行高与额外的 start 内边距，比正文偏右、比行号偏上。
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(
                            text = placeholder,
                            style = textStyle.copy(
                                color = colors.onSurfaceVariant.copy(alpha = 0.6f)
                            ),
                        )
                    }
                    BasicTextField(
                        value = fieldValue,
                        onValueChange = { new ->
                            val old = fieldValue
                            fieldValue = new
                            if (new.text != old.text) {
                                remapFolds(foldedStarts, old.text, new.text)
                                onValueChange(new.text)
                            } else {
                                unfoldAroundCaret(foldedStarts, new.selection, structure)
                            }
                        },
                        readOnly = readOnly,
                        textStyle = textStyle,
                        cursorBrush = SolidColor(colors.primary),
                        visualTransformation = transformation,
                        onTextLayout = { layout = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    // 折叠处的 `…`：叠在字形上，点它展开。
                    for (pair in folded) {
                        FoldChip(
                            layout = layout,
                            offset = mapping.originalToTransformed(pair.foldStart),
                            colors = codeColors,
                            interactionSource = interactionSource,
                            onClick = { toggleFold(pair) },
                        )
                    }
                }
            }
        }
    }
}

/** 一行可视文本行：它在排版结果里的位置，以及它属于第几逻辑行。 */
private data class VisualLine(
    val visual: Int,
    val docLine: Int,
    val top: Float,
    val bottom: Float
)

/**
 * 把排版结果里的每个可视行对回逻辑行。
 *
 * 折叠与软换行都会让「可视行 ≠ 逻辑行」：被折叠的区间只剩一个占位符，换行的续行还属于同一
 * 逻辑行。行号只标在每个逻辑行的第一可视行上，所以要把映射倒推回去问「这一可视行是哪一行」。
 */
private fun visualLines(
    layout: TextLayoutResult,
    mapping: OffsetMapping,
    structure: CodeStructure
): List<VisualLine> {
    val result = ArrayList<VisualLine>(layout.lineCount)
    for (index in 0 until layout.lineCount) {
        val original = mapping.transformedToOriginal(layout.getLineStart(index))
        result.add(
            VisualLine(
                visual = index,
                docLine = structure.lineNumberAt(original),
                top = layout.getLineTop(index),
                bottom = layout.getLineBottom(index),
            )
        )
    }
    return result
}

/**
 * 行号 + 折叠箭头那一列。
 *
 * 只处理**滚动窗口内**的行：控件是在一个滚动容器里一次性铺开整篇文档的，两万行的 JSON
 * 若每帧给每行量一次文字、挂一个箭头，滚动会直接卡死。
 */
@Composable
private fun FoldGutter(
    structure: CodeStructure,
    text: String,
    lines: List<VisualLine>?,
    fieldTopInRow: Float,
    scroll: Float,
    viewport: Int,
    width: Dp,
    foldedStarts: List<Int>,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    lineNumberColor: Color,
    chevronColor: Color,
    interactionSource: MutableInteractionSource,
    onToggle: (BracketPair) -> Unit,
) {
    val textHeight = lines?.lastOrNull()?.bottom ?: 0f
    val height = with(LocalDensity.current) { (fieldTopInRow + textHeight).toDp() }
    val viewportHeight = if (viewport > 0) viewport.toFloat() else textHeight
    val visible = lines.orEmpty().filter { it.bottom >= scroll && it.top <= scroll + viewportHeight }
    // 一个逻辑行可能有多个可视行（软换行的续行），行号与箭头都只挂在它的第一可视行上。
    val heads = visible.filterIndexed { index, line ->
        index == 0 || visible[index - 1].docLine != line.docLine
    }

    Box(modifier = Modifier.width(width).height(height)) {
        Canvas(Modifier.fillMaxSize()) {
            // 行号靠左、箭头靠右（紧挨着正文），中间留出间距。
            val numbersRight = size.width - GutterEndPadding.toPx() -
                ChevronColumnWidth.toPx() - GutterNumbersGap.toPx()
            for (line in heads) {
                val measured = textMeasurer.measure(line.docLine.toString(), textStyle)
                drawText(
                    textLayoutResult = measured,
                    color = lineNumberColor,
                    topLeft = Offset(
                        x = numbersRight - measured.size.width,
                        y = fieldTopInRow + line.top
                    )
                )
            }
        }
        for (line in heads) {
            val pair = structure.foldableOnLine(text, line.docLine) ?: continue
            Box(
                modifier = Modifier
                    .atColumnLine(
                        top = fieldTopInRow + line.top,
                        height = line.bottom - line.top,
                        width = ChevronColumnWidth,
                        left = width - GutterEndPadding - ChevronColumnWidth
                    )
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable(interactionSource = interactionSource, indication = null) {
                        onToggle(pair)
                    },
                contentAlignment = Alignment.Center,
            ) {
                FoldChevron(folded = pair.foldStart in foldedStarts, color = chevronColor)
            }
        }
    }
}

/** 把子节点放到装订线列的某一行上（坐标为「行内容」坐标系）。 */
private fun Modifier.atColumnLine(top: Float, height: Float, width: Dp, left: Dp): Modifier =
    layout { measurable, _ ->
        val w = with(this) { width.roundToPx() }
        val x = with(this) { left.roundToPx() }
        val h = height.roundToInt().coerceAtLeast(1)
        val placeable = measurable.measure(Constraints.fixed(w, h))
        layout(w, h) { placeable.place(x, top.roundToInt()) }
    }

/** 折叠箭头：圆头 chevron，展开指下、折叠指右。 */
@Composable
private fun FoldChevron(folded: Boolean, color: Color) {
    val width = if (folded) ChevronShort else ChevronLong
    val height = if (folded) ChevronLong else ChevronShort
    Canvas(modifier = Modifier.width(width).height(height)) {
        val stroke = ChevronStroke.toPx()
        val inset = stroke / 2f
        val left = inset
        val right = size.width - inset
        val top = inset
        val bottom = size.height - inset
        val path = Path().apply {
            if (folded) {
                moveTo(left, top)
                lineTo(right, (top + bottom) / 2f)
                lineTo(left, bottom)
            } else {
                moveTo(left, top)
                lineTo((left + right) / 2f, bottom)
                lineTo(right, top)
            }
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/** 被折叠区间的 `…` 占位符：叠在字形上，可点、显手型。 */
@Composable
private fun FoldChip(
    layout: TextLayoutResult?,
    offset: Int,
    colors: CodeColors,
    interactionSource: MutableInteractionSource,
    onClick: () -> Unit,
) {
    val density = LocalDensity.current
    val padX = with(density) { 1.dp.toPx() }
    Box(
        modifier = Modifier
            .layout { measurable, _ ->
                val box = layout?.takeIf { offset in 0 until it.layoutInput.text.length }
                    ?.getBoundingBox(offset)
                if (box == null) {
                    measurable.measure(Constraints.fixed(0, 0))
                    layout(0, 0) { }
                } else {
                    val chip = measurable.measure(
                        Constraints.fixed(
                            (box.width + padX * 2).roundToInt(),
                            box.height.roundToInt()
                        )
                    )
                    layout(chip.width, chip.height) {
                        chip.place((box.left - padX).roundToInt(), box.top.roundToInt())
                    }
                }
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
    )
}

/**
 * 折叠状态跟着文档走：把坐标按这次编辑平移，落在被替换区间里的直接丢掉。
 *
 * `BasicTextField` 只给新文本，没有变更集，所以用「公共前/后缀」求这一次编辑的三元组——
 * 打字、粘贴、删除都是一次替换。
 */
private fun remapFolds(starts: MutableList<Int>, old: String, new: String) {
    if (starts.isEmpty()) return
    var prefix = 0
    while (prefix < old.length && prefix < new.length && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    while (suffix < old.length - prefix && suffix < new.length - prefix &&
        old[old.length - 1 - suffix] == new[new.length - 1 - suffix]
    ) {
        suffix++
    }
    val removedEnd = old.length - suffix
    val insertedEnd = new.length - suffix
    val delta = insertedEnd - removedEnd

    val remapped = ArrayList<Int>(starts.size)
    for (start in starts) {
        when {
            start >= removedEnd -> remapped.add(start + delta)
            start <= prefix -> remapped.add(start)
            // 落在被替换的区间里：它引用的括号已经没了，交给随后的失效清理。
            else -> Unit
        }
    }
    starts.clear()
    starts.addAll(remapped)
}

/** 光标（或选区）落进被折叠的区间时，把那一处展开。 */
private fun unfoldAroundCaret(
    starts: MutableList<Int>,
    selection: TextRange,
    structure: CodeStructure
) {
    if (starts.isEmpty()) return
    val covering = structure.brackets.filter {
        it.isFoldable && it.foldStart in starts &&
            selection.min < it.foldEnd && selection.max > it.foldStart
    }
    if (covering.isEmpty()) return
    starts.removeAll(covering.map { it.foldStart }.toSet())
}

/**
 * IntelliJ Light 的语法配色。
 *
 * 用固定色值而不是从 Material 色板派生：这套配色的意义就在于「看起来像 IDEA」，
 * 派生出来的近似色反而会四不像。取值对应 IDEA 的 role——键 = field/property、
 * 常量 = keyword、标点 = 正文色。
 */
private val IdeaLightCodeColors = CodeColors(
    editorBackground = Color(0xFFFFFFFF), // IDEA 的代码区就是纯白
    key = Color(0xFF7A3E9D), // field / property（截图里 parser、pretty、compact 那个紫）
    string = Color(0xFF067D17),
    number = Color(0xFF1750EB),
    constant = Color(0xFF0033B3), // true / false / null，走 keyword 蓝
    punctuation = Color(0xFF000000),
    foldPlaceholder = Color(0xFF8C8C8C),
    foldPlaceholderBackground = Color(0x14000000),
    gutterDivider = Color(0xFFE0E0E0),
)

/** Darcula 的语法配色，对应 IDEA 深色方案。 */
private val DarculaCodeColors = CodeColors(
    editorBackground = Color(0xFF2B2B2B),
    key = Color(0xFF9876AA),
    string = Color(0xFF6A8759),
    number = Color(0xFF6897BB),
    constant = Color(0xFFCC7832),
    punctuation = Color(0xFFA9B7C6),
    foldPlaceholder = Color(0xFFA9B7C6),
    foldPlaceholderBackground = Color(0x33A9B7C6),
    gutterDivider = Color(0xFF3C3F41),
)

/** 当前主题该用哪套语法配色。 */
@Composable
private fun rememberCodeColors(): CodeColors =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        DarculaCodeColors
    } else {
        IdeaLightCodeColors
    }
