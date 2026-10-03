package com.qcmian.clipper.devtools.ui.components.code

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.positionInRoot
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
import com.qcmian.clipper.core.ui.components.HorizontalScrollbar
import com.qcmian.clipper.core.ui.components.HorizontalScrollbarHeight
import com.qcmian.clipper.core.ui.components.VerticalScrollbar
import com.qcmian.clipper.core.ui.components.VerticalScrollbarWidth
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.devtools.ui.components.DevToolScrollbarGap
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
 *
 * [actions] 是标题行右端那块地方，用来放只属于**这个框**的动作（输入框的「打开文件 / 清空」、
 * 结果框的「保存文件 / 复制」）。做成插槽而不是几个布尔开关：控件不该认识「打开文件」是什么，
 * 那是调用方与宿主之间的事（见 `DevToolFieldActions`）。
 *
 * [softWrap] 决定长行是折到下一行还是横向滚出去。默认**不折**：这是代码，一行一条记录，
 * 折行会把「一行」这个结构本身弄没，对照两份 JSON 时尤其误导。目前写死默认值，等设置项齐了
 * 再由界面提供开关。
 *
 * 实现上没有现成的开关可拨——`softWrap` 只是 `TextDelegate` 的内部参数，公开的
 * `BasicTextField` 没有它。所以「不折」是靠布局达成的：正文那一层挂上横向滚动，子节点因此
 * 拿到无限宽约束，没有可折的宽度，自然按最长一行排版。
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
    softWrap: Boolean = false,
    actions: @Composable () -> Unit = {},
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
    // 不折行时正文那一层用得上；折行时它没有可滚的内容，留着不影响。
    val horizontalScroll = rememberScrollState()
    val interactionSource = remember { MutableInteractionSource() }
    val focusRequester = remember { FocusRequester() }
    // 点空白处落光标要用面板与正文各自的窗口坐标（见 FieldOrigins）。
    val origins = remember { FieldOrigins() }

    // 控件自己持有选区：折叠要知道光标在哪（落进被折叠的区间就得把它展开）。
    var fieldValue by remember { mutableStateOf(TextFieldValue(value)) }
    // 外部换了内容（换剪贴板条目、点了格式化）才整块替换；用户自己敲的字这里一定相等。
    LaunchedEffect(value) {
        if (fieldValue.text != value) {
            fieldValue = TextFieldValue(value, TextRange(value.length))
        }
    }

    // 当前行 / 括号配对两处高亮只在**聚焦时**画：没有光标的框（例如还没点进去、或右侧只读结果）
    // 谈「当前行」没有意义，画上去反而像一块脏背景。
    var focused by remember { mutableStateOf(false) }

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

    // 光标（选区活动端）所在的那一行，在正文坐标系里的上下缘。
    //
    // 走高亮变换会让**每次移动光标**都重跑一遍全量 filter（大文档上就是每次方向键几十毫秒），
    // 所以这里改用排版结果现算：它只依赖 layout，与正文内容无关，光标动一下代价几乎为零。
    // 折行时一条逻辑行会占多个可视行，但光标只在其中一行，取它自己那一行的范围正合适。
    val caret = fieldValue.selection.end
    val currentLineBand: Pair<Float, Float>? = remember(layout, mapping, caret, focused, readOnly) {
        // 右侧只读结果框不画：那里没有「正在编辑的行」，一条底色只会与左侧争注意力。
        if (readOnly || !focused) return@remember null
        val l = layout ?: return@remember null
        val transformed = mapping.originalToTransformed(caret)
        if (transformed !in 0..l.layoutInput.text.length) return@remember null
        val line = l.getLineForOffset(transformed)
        l.getLineTop(line) to l.getLineBottom(line)
    }

    // 光标紧挨着的那个括号，以及它的配对。
    val bracketPair: Pair<Int, Int>? = remember(structure, caret, focused) {
        if (focused) matchBracketPair(structure, caret) else null
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
        // 标签在左、动作在右：动作属于这个框，就该跟它的名字同处一行。
        // 字号跟着编辑区标题行整体提一档（11 → 13sp）：这一行是每个框的入口，原先小得像脚注。
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = hint)
            Spacer(Modifier.weight(1f))
            actions()
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(shape)
                .background(codeColors.editorBackground)
                .border(1.dp, colors.outline.copy(alpha = 0.6f), shape)
                // 整块面板都算正文区：内容右侧与下方的空白也要有编辑态光标，点一下也要能落下光标。
                // 那里没有 `BasicTextField`（它只有内容那么高），所以由面板接住点击，把面板坐标
                // 换算成正文坐标，再问排版结果「这里对应哪个字符」。
                .pointerHoverIcon(PointerIcon.Text)
                .onGloballyPositioned { origins.panel = it.positionInRoot() }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        focusRequester.requestFocus()
                        // 取不到排版结果（还没排过版）就退到文末，至少光标落在能继续输入的地方。
                        val caret = layout
                            ?.let { mapping.transformedToOriginal(it.getOffsetForPosition(origins.panel + tap - origins.text)) }
                            ?: fieldValue.text.length
                        fieldValue = fieldValue.copy(selection = TextRange(caret))
                    }
                }
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
                    // 四周内边距加在滚动**外面**（视口内缩，内边距本身不随内容滚），滚动条则留在
                    // 它外面贴着面板边框——内边距若加在面板上，滚动条会被推进来，与边框之间露出
                    // 一条底色缝。右端与下端分别留出滚动条的粗细、再加一点间隙：滑块既不压住正文，
                    // 也不贴着文字。无论滚动条此刻在不在都留着，免得它出现 / 消失时正文重排一次。
                    .padding(
                        start = 2.dp,
                        top = 4.dp,
                        end = VerticalScrollbarWidth + DevToolScrollbarGap,
                        bottom = if (softWrap) 4.dp
                        else 4.dp + DevToolScrollbarGap + HorizontalScrollbarHeight,
                    )
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
                        // 装订线要的是正文在 Row 里的纵向位置，横向滚动不影响它。
                        .onGloballyPositioned { fieldTopInRow = it.positionInParent().y }
                        // 当前行整行铺一条底纹。画在**横向滚动容器这一层**：它的宽度恒等于可视宽度，
                        // 所以底纹铺满一整行而不随内容长短伸缩；纵向坐标与正文同源，对得齐。
                        .drawBehind {
                            val band = currentLineBand ?: return@drawBehind
                            drawRect(
                                color = codeColors.currentLineBackground,
                                topLeft = Offset(0f, band.first),
                                size = Size(size.width, (band.second - band.first).coerceAtLeast(0f)),
                            )
                        }
                        // 不折行时这一层才是滚动容器：子节点拿到无限宽约束，于是按最长一行排版。
                        // 挂在这一层而不是外面含装订线的 Row 上——挂外面，行号会跟着正文一起横向滚走。
                        .then(if (softWrap) Modifier else Modifier.horizontalScroll(horizontalScroll))
                ) {
                    // 正文自己的原点。横向滚动时它会跟着移，所以点击换算必须读这里而不是外层的
                    // 位置——否则往右滚过之后，点在同一个像素上会落到更靠后的字符。
                    Box(
                        modifier = Modifier
                            // 括号配对底纹画在**正文这一层**（跟随横向滚动）：位置直接取自正文
                            // 坐标系的包围盒，不必再去抵消一次滚动量。
                            .drawBehind {
                                val pair = bracketPair ?: return@drawBehind
                                drawBracketHighlight(layout, mapping, pair.first, codeColors.bracketBackground)
                                drawBracketHighlight(layout, mapping, pair.second, codeColors.bracketBackground)
                            }
                            .onGloballyPositioned {
                                origins.text = it.positionInRoot()
                            }
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
                            modifier = Modifier
                                // 折行时才撑满：不折行时外层给的是无限宽，要求一个无限的宽度
                                // 没有意义。不撑满则按文字自然宽度排版，横向滚动才有内容可滚。
                                .then(if (softWrap) Modifier.fillMaxWidth() else Modifier)
                                .onFocusChanged { focused = it.isFocused }
                                .focusRequester(focusRequester),
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
            // 两条滚动条都画在正文之上（overlay），并且都贴着面板边框：正文那层内边距已经把
            // 它们的粗细让了出来，所以叠上去既不压住字、也不与边框之间留缝。
            VerticalScrollbar(
                scrollState = scrollState,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    // 止于横向滚动条之上，免得两条撞在右下角。
                    .padding(bottom = if (softWrap) 0.dp else HorizontalScrollbarHeight),
            )
            // 横向滚动条排在正文之下（只有不折行时才存在）。装订线那一段不归它管：滚动的是正文，
            // 滚动条也只该横跨正文，左端与正文对齐。
            if (!softWrap) {
                HorizontalScrollbar(
                    scrollState = horizontalScroll,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = 2.dp + gutterWidth + GutterTextGap),
                )
            }
        }
    }
}

/**
 * 面板与正文各自在窗口里的位置。
 *
 * 点击空白时要把面板坐标减去正文坐标，才能问排版结果「点的是哪个字符」。两端都取窗口坐标而不是
 * 各自的父坐标：中间隔着滚动容器与内边距，换算容易漏掉一项。
 *
 * 存成普通字段而不是 `mutableStateOf`：它只给点击处理读，随滚动变化时不值得触发一次重组。
 */
private class FieldOrigins {
    var panel: Offset = Offset.Zero
    var text: Offset = Offset.Zero
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
 * 光标紧挨着的那个括号，以及它的配对，返回这一对括号的两个文档偏移。
 *
 * 「紧挨着」有两层：光标右边的那个字符是括号（`caret == open/close`），或光标左边的那个字符
 * 是括号（`caret == open+1/close+1`）。两层可能同时成立——`]}` 之间就有两个括号一左一右——
 * 这时**优先右边**：光标刚落在一个括号前面时，想看的是它跟谁配对，而不是上一个刚说完的。
 *
 * 只认 [CodeStructure.brackets] 里已经配好对的那部分：孤立括号（例如打到一半的 `{`）没有配对，
 * 高亮它就等于什么都没说。扫描时开闭括号是同一个栈，所以这里不必再判类型。
 *
 * 一次线性扫过括号表。这在本项目的量级上可忽略——装订线每画一行就做一次同样的扫描
 * （见 `foldableOnLine`），它比这个热得多。
 */
internal fun matchBracketPair(structure: CodeStructure, caret: Int): Pair<Int, Int>? {
    // 先只记「左边的那个括号」，整表扫完还没有「右边」的才用它——右边优先级更高，因此不能
    // 边扫边采用：内层 `]` 先入表，若当场采用，`]}` 之间就会错认成内层那一对。
    var onLeft: Pair<Int, Int>? = null
    for (pair in structure.brackets) {
        if (caret == pair.open || caret == pair.close) return pair.open to pair.close
        if (onLeft == null && (caret == pair.open + 1 || caret == pair.close + 1)) {
            onLeft = pair.open to pair.close
        }
    }
    return onLeft
}

/**
 * 把一个括号字符的底纹画出来。
 *
 * 位置取自排版结果按**显示偏移**算的包围盒：显示层可能把制表符拉宽、把折叠压成一个占位符，
 * 直接拿文档偏移去问会错位，所以先过一遍 [mapping]。括号恒为一列、也不会被折叠，因此
 * `+1` 一定落在它自己身上。
 */
private fun DrawScope.drawBracketHighlight(
    layout: TextLayoutResult?,
    mapping: OffsetMapping,
    offset: Int,
    color: Color,
) {
    val result = layout ?: return
    val transformed = mapping.originalToTransformed(offset)
    if (transformed !in 0 until result.layoutInput.text.length) return
    val box = result.getBoundingBox(transformed)
    if (box.width <= 0f || box.height <= 0f) return
    drawRoundRect(
        color = color,
        topLeft = Offset(box.left, box.top),
        size = Size(box.width, box.height),
        cornerRadius = CornerRadius(2f, 2f),
    )
}

/**
 * IntelliJ Light 的语法配色。
 *
 * 用固定色值而不是从 Material 色板派生：这套配色的意义就在于「看起来像 IDEA」，
 * 派生出来的近似色反而会四不像。取值对应 IDEA 的 role——键 = field/property、
 * 常量 = keyword、标点 = 正文色。
 *
 * 浅色这一侧**保持原样**，因为它本来就与应用的色板处得来：编辑区底色取纯白，正是应用
 * `surface` 的值，而它比 `background`（`#F5F5F7`）亮一档——「纸比桌面亮」的关系成立。
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
    // 当前行用中性灰而不是浅蓝：白底上一块淡蓝会与选中态 / 主色按钮撞在一起，像第二层选区。
    currentLineBackground = Color(0x0F1B1B1F),
    // 配对括号用主色底纹：与界面里其他蓝色强调同族，一眼认得出「这两个是一对」。
    bracketBackground = Color(0x330A84FF),
)

/**
 * 深色下的语法配色，**从应用自己的色板派生**，而不是照搬 Darcula。
 *
 * 原先用的是 Darcula 原色，问题出在底色：`#2B2B2B` 是一块偏暖、偏绿的灰，而应用的面板底色是
 * `#1B1B1E`、窗口内的 chrome 是 `#26262A`——都是带一点蓝的中性灰。三种灰摆在一起，编辑区就成了
 * 整个窗口里唯一的外来物：它比周围亮，还偏绿。语法色同样打架，Darcula 的键紫 `#9876AA` 与字符串
 * 橄榄绿 `#6A8759` 跟强调色 `#0A84FF` 不是一个体系。
 *
 * 现在这套：
 *  - 底色 `#232328` 仍在应用的蓝灰族里，且**比 `background` 亮一档**——浅色下「纸比桌面亮」的
 *    关系在深色下同样成立，两套主题不会一个凹一个凸。
 *  - 键色取 `#7AA2F7`，与强调色 `#0A84FF` 同族，选中态、按钮与语法高亮因此像一套东西。
 *  - 字符串、数字、常量挑同族的低饱和色，既分得开又不与蓝色抢。
 *  - 标点直接用应用的 `onSurfaceVariant`（`#B4B4BD`），正文与界面文字同色。
 */
private val AppDarkCodeColors = CodeColors(
    editorBackground = Color(0xFF232328),
    key = Color(0xFF7AA2F7),
    string = Color(0xFF9ECE6A),
    number = Color(0xFFFF9E64),
    constant = Color(0xFFBB9AF7),
    punctuation = Color(0xFFB4B4BD),
    foldPlaceholder = Color(0xFF8E8E93),
    foldPlaceholderBackground = Color(0x33B4B4BD),
    gutterDivider = Color(0xFF35353B), // 应用的 surfaceVariant
    // 深底上抬一档白，比浅色那边更明显一点才看得出（深色下对比本来就弱）。
    currentLineBackground = Color(0x14FFFFFF),
    bracketBackground = Color(0x400A84FF),
)

/** 当前主题该用哪套语法配色。 */
@Composable
private fun rememberCodeColors(): CodeColors =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        AppDarkCodeColors
    } else {
        IdeaLightCodeColors
    }
