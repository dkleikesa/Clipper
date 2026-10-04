package com.qcmian.clipper.core.ui.code

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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import kotlinx.coroutines.delay
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

/** 只读框里自绘的插入光标：宽度与 KodeMirror 那条（`SelectionDrawing` 画光标用 2f）一致。 */
private val CaretWidth = 2.dp

/** 光标明灭的半周期。与 KodeMirror 那个 `delay(500)` 同一个数，两套引擎看起来才是同一件事。 */
private const val CaretBlinkMillis = 500L

/**
 * 代码框的实现之一：**原生 `BasicTextField` + 叠在它上面的自绘装饰**。
 *
 * 三件事各走一条不侵入控件的路：
 *  - 高亮与折叠：交给 [CodeVisualTransformation]（只改显示，`value` 仍是真实文档）；
 *  - 行号：从 `onTextLayout` 拿到排版结果，在左侧装订线里按行画；
 *  - 折叠开关：装订线里的箭头 + 被折叠处的 `…` 占位符，都是叠在控件上的小点击区。
 *
 * 因此输入法、光标、选区、无障碍仍然全部由平台控件负责——这正是从自绘编辑器换回来换到的东西。
 *
 * 「不折行」是靠布局达成的，而不是某个开关：`softWrap` 只是 `TextDelegate` 的内部参数，公开的
 * `BasicTextField` 没有它。所以不折时正文那一层挂上横向滚动，子节点因此拿到无限宽约束，
 * 没有可折的宽度，自然按最长一行排版。
 *
 * 参数不逐个列在函数签名上，而是走 [CodeFieldSpec]——那是**两套实现共用的唯一一份契约**。
 * 所以这里既不新增参数、也不改调用面：业务层只认 `DevToolCodeField`（见 `CodeFieldEngine`）。
 */
@Composable
fun NativeCodeField(spec: CodeFieldSpec) {
    // 把契约摊成局部名：下面这一整段实现与「契约化」之前逐字相同——重构只换了参数的来源，
    // 没有碰它的逻辑。新增参数时，这里按需取用即可（用不到就不取：那是一个有意的决定，
    // 而 `CodeFieldSpec` 保证两边看到的是同一个集合）。
    val label = spec.label
    val value = spec.value
    val onValueChange = spec.onValueChange
    val modifier = spec.modifier
    val editable = spec.editable
    val placeholder = spec.placeholder
    val isError = spec.isError
    val softWrap = spec.softWrap
    val actions = spec.actions
    val lineNumbers = spec.lineNumbers
    val folding = spec.folding
    val scan = spec.scan
    val showLabel = spec.showLabel
    val filePaste = spec.filePaste

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
    val structure = remember(text) { scan(text) }
    // 关掉折叠时列表恒空：下游（显示变换、`…` 占位符）因此不必各自再判一次开关。
    val folded = remember(structure, foldedStarts.toList(), folding) {
        if (!folding) emptyList()
        else structure.brackets.filter { it.isFoldable && it.foldStart in foldedStarts }
    }
    val transformation = remember(structure, folded, codeColors) {
        CodeVisualTransformation(structure.tokens, folded, codeColors)
    }
    LaunchedEffect(structure, folding) {
        if (!folding) return@LaunchedEffect
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
    val currentLineBand: Pair<Float, Float>? = remember(layout, mapping, caret, focused, editable) {
        // 右侧只读结果框不画：那里没有「正在编辑的行」，一条底色只会与左侧争注意力。
        if (!editable || !focused) return@remember null
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

    // <本仓库补丁> 只读框也要有插入光标。Compose 的 `BasicTextField` 在 `readOnly` 时**从根上**
    // 不画光标（`CoreTextField.kt`：`showCursor = enabled && !readOnly && …`），光标刷给什么颜色都
    // 没用——平台那一路因此补不回来，只能自己画一条。做法与 KodeMirror 自绘的那条对齐：位置取
    // 排版结果里的光标矩形（折行时它自带正确的那一可视行），明灭半周期也取同一个数。
    val caretRect: Rect? = remember(layout, mapping, caret, editable) {
        if (editable) return@remember null
        val l = layout ?: return@remember null
        val transformed = mapping.originalToTransformed(caret)
        if (transformed !in 0..l.layoutInput.text.length) return@remember null
        l.getCursorRect(transformed)
    }
    // 没聚焦就不画——平台文本框自己就是这条规矩（`showCursor` 里也看着焦点）。
    var caretOn by remember { mutableStateOf(true) }
    LaunchedEffect(editable, focused) {
        if (editable || !focused) return@LaunchedEffect
        caretOn = true
        while (true) {
            delay(CaretBlinkMillis)
            caretOn = !caretOn
        }
    }

    val density = LocalDensity.current
    val digitWidthPx = remember(textMeasurer, textStyle) {
        textMeasurer.measure("0", textStyle).size.width.toFloat()
    }
    val digits = structure.lineCount.toString().length.coerceAtLeast(2)
    val showGutter = lineNumbers || folding
    // 两个开关各自决定自己那一段占不占宽：都为假时整列宽度为 0，正文因此紧贴左边框。
    val gutterWidth = with(density) {
        var width = 0f
        if (folding) width += ChevronColumnWidth.toPx()
        if (lineNumbers) width += digitWidthPx * digits + GutterNumbersGap.toPx()
        if (showGutter) width += GutterEndPadding.toPx()
        width.toDp()
    }
    // 正文左缘相对面板内缩多少：装订线那一列，加上它与正文之间的间隙。
    val textStartInset = if (showGutter) gutterWidth + GutterTextGap else 0.dp
    // 装订线不单独上色：与正文同一块底色，只靠一条竖分隔线分界（见两套配色里的 gutterDivider）。

    /**
     * 接收一次新的编辑值：折叠位置按 diff 平移，工具那边同步拿到新正文。
     *
     * 文本框自己的 `onValueChange` 与「粘贴文件」（见 [filePasteModifier]）都走它——两条路各写一遍
     * 的话，早晚会出现「粘贴进来的内容工具收不到」这种只在一侧发生的毛病。
     */
    fun accept(new: TextFieldValue) {
        val old = fieldValue
        fieldValue = new
        if (new.text != old.text) {
            remapFolds(foldedStarts, old.text, new.text)
            onValueChange(new.text)
        } else {
            unfoldAroundCaret(foldedStarts, new.selection, structure)
        }
    }

    /**
     * 「粘贴文件」：`onPreviewKeyEvent` 跑在文本框自己的粘贴处理**之前**，吃掉这次按键，系统那条
     * 「粘成文件名」的路就不会走（见参数 [filePaste]）。
     */
    fun filePasteModifier(): Modifier {
        val paste = filePaste
        if (paste == null || !editable) return Modifier
        return Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown || event.key != Key.V) {
                return@onPreviewKeyEvent false
            }
            if (!event.isMetaPressed && !event.isCtrlPressed) return@onPreviewKeyEvent false
            val content = paste() ?: return@onPreviewKeyEvent false
            val selection = fieldValue.selection
            val text = fieldValue.text
            val updated = text.substring(0, selection.min) + content + text.substring(selection.max)
            accept(
                fieldValue.copy(
                    text = updated,
                    selection = TextRange(selection.min + content.length),
                )
            )
            true
        }
    }

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
        if (showLabel) {
            // 标签在左、动作在右：动作属于这个框，就该跟它的名字同处一行。
            // 字号跟着编辑区标题行整体提一档（11 → 13sp）：这一行是每个框的入口，原先小得像脚注。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 13.sp, color = hint)
                Spacer(Modifier.weight(1f))
                actions()
            }
            Spacer(Modifier.height(6.dp))
        }
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
            if (showGutter) {
                // 分界线铺满整块面板（不随内容滚动）：只按内容高度画的话，内容短于面板时那一列会中途断掉。
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .offset(x = gutterWidth)
                        .background(codeColors.gutterDivider)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    // 四周内边距加在滚动**外面**（视口内缩，内边距本身不随内容滚），滚动条则留在
                    // 它外面贴着面板边框——内边距若加在面板上，滚动条会被推进来，与边框之间露出
                    // 一条底色缝。右端与下端分别留出滚动条的粗细、再加一点间隙：滑块既不压住正文，
                    // 也不贴着文字。无论滚动条此刻在不在都留着，免得它出现 / 消失时正文重排一次。
                    .padding(
                        // 有装订线时它自己占着左缘，正文从它右边开始；没有装订线时直接给正文留出边距。
                        start = if (showGutter) 2.dp else 8.dp,
                        top = 4.dp,
                        end = VerticalScrollbarWidth + CodeScrollbarGap,
                        bottom = if (softWrap) 4.dp
                        else 4.dp + CodeScrollbarGap + HorizontalScrollbarHeight,
                    )
                    .verticalScroll(scrollState)
            ) {
                if (showGutter) {
                    FoldGutter(
                        structure = structure,
                        text = text,
                        lines = lines,
                        fieldTopInRow = fieldTopInRow,
                        scroll = scrollState.value.toFloat(),
                        viewport = scrollState.viewportSize,
                        width = gutterWidth,
                        showNumbers = lineNumbers,
                        showChevrons = folding,
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
                }
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
                        // 一致。放在外面当兄弟节点（更早的实现就是这么写的）会因为它自己的字号/
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
                            onValueChange = { new -> accept(new) },
                            readOnly = !editable,
                            textStyle = textStyle,
                            // 光标刷一直给：只读框照样有插入光标——`readOnly` 只挡改写，不挡插入点，
                            // 也不挡选择。只读框里点一下、拖一段、复制出去都是**要的**（见
                            // `CodeFieldSpec.editable`）。
                            cursorBrush = SolidColor(colors.primary),
                            visualTransformation = transformation,
                            onTextLayout = { layout = it },
                            modifier = Modifier
                                // 折行时才撑满：不折行时外层给的是无限宽，要求一个无限的宽度
                                // 没有意义。不撑满则按文字自然宽度排版，横向滚动才有内容可滚。
                                .then(if (softWrap) Modifier.fillMaxWidth() else Modifier)
                                .onFocusChanged { focused = it.isFocused }
                                .focusRequester(focusRequester)
                                .then(filePasteModifier())
                                // <本仓库补丁> 只读框的插入光标由我们自己画（见 `caretRect`）：
                                // 画在正文之上，坐标与文本排版同源，横向滚动时跟着走。
                                .drawWithContent {
                                    drawContent()
                                    val rect = caretRect
                                    if (!editable && focused && caretOn && rect != null) {
                                        drawRect(
                                            color = colors.primary,
                                            topLeft = Offset(rect.left, rect.top),
                                            size = Size(CaretWidth.toPx(), rect.height),
                                        )
                                    }
                                },
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
                        .padding(start = 2.dp + textStartInset),
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
 * 装订线高度能钉到的上限（像素）。
 *
 * Compose 把约束的宽高打包进一个 `Long`，单边最大只能表示 `0x3FFFF`（262143）像素；超出这个
 * 数的 `Modifier.height(...)` 会直接抛
 * `IllegalArgumentException: Can't represent a width of 0 and height of 514429 in Constraints`
 * （`SizeNode.targetConstraints` 就是这么构造约束的）。软折行的超长输入——几百 KB 的 Base64
 * 粘进来就是几万可视行——很容易顶穿这条线，所以装订线高度在这里封顶。
 *
 * 代价：文档比这更高时，超出那一段**不再绘制行号**（正文照常显示、照常滚动，不会崩）。
 * 262143 往下留两千出头的余量，免得 `Dp` → 像素取整刚好踩线。
 */
private const val MaxGutterHeightPx = 260_000f

/**
 * 正文与自绘滚动条之间留的间隙。
 *
 * 与 `:devTools` 别处用的是同一个视觉值 5dp，但**刻意各留一份**：代码框已经搬进 `:shared`，
 * 不能再反向依赖 `:devTools`；这个间隙是代码框自己的排版参数，不是工具箱的公共常量。
 */
private val CodeScrollbarGap = 5.dp

/**
 * 行号 + 折叠箭头那一列。
 *
 * 只处理**滚动窗口内**的行：控件是在一个滚动容器里一次性铺开整篇文档的，两万行的 JSON
 * 若每帧给每行量一次文字、挂一个箭头，滚动会直接卡死。
 *
 * 整列给回**箭头**光标：面板给整块区域设了 I 形（见 [NativeCodeField] 里那处
 * `pointerHoverIcon(PointerIcon.Text)`），而这一列既不能落光标也不能选中文本。折叠箭头那一格
 * 由它自己的 `pointerHoverIcon(PointerIcon.Hand)` 再改成手型（子节点优先）。
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
    showNumbers: Boolean,
    showChevrons: Boolean,
    foldedStarts: List<Int>,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    lineNumberColor: Color,
    chevronColor: Color,
    interactionSource: MutableInteractionSource,
    onToggle: (BracketPair) -> Unit,
) {
    val textHeight = lines?.lastOrNull()?.bottom ?: 0f
    val viewportHeight = if (viewport > 0) viewport.toFloat() else textHeight
    // 钉住的高度先封顶：超长文档的内容高超出 Compose 能表示的尺寸时会直接抛异常（见 [MaxGutterHeightPx]）。
    val height = with(LocalDensity.current) {
        (fieldTopInRow + textHeight).coerceAtMost(MaxGutterHeightPx).toDp()
    }
    val visible = lines.orEmpty().filter { it.bottom >= scroll && it.top <= scroll + viewportHeight }
    // 一个逻辑行可能有多个可视行（软换行的续行），行号与箭头都只挂在它的第一可视行上。
    val heads = visible.filterIndexed { index, line ->
        index == 0 || visible[index - 1].docLine != line.docLine
    }

    Box(modifier = Modifier.width(width).height(height).pointerHoverIcon(PointerIcon.Default)) {
        if (showNumbers) {
            Canvas(Modifier.fillMaxSize()) {
                // 行号靠右（紧挨着折叠列）；不显示折叠列时那一列与间距都不必让出来。
                val numbersRight = size.width - GutterEndPadding.toPx() -
                    (if (showChevrons) ChevronColumnWidth.toPx() + GutterNumbersGap.toPx() else 0f)
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
        }
        if (showChevrons) {
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
fun matchBracketPair(structure: CodeStructure, caret: Int): Pair<Int, Int>? {
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

// 配色（`CodeColors` / `rememberCodeColors`）搬去了 `CodeColors.kt`：它是两套实现共用的那一份，
// 挂在这一个实现名下会让另一套实现只能跨文件去取，正是「统一抽象」要消掉的那种耦合。
