package com.qcmian.clipper.devtools.tools.math

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.ui.components.VerticalScrollbar
import com.qcmian.clipper.core.ui.components.VerticalScrollbarWidth
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.devtools.api.DevTool
import com.qcmian.clipper.devtools.api.DevToolGroup
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.DevToolMetadata
import com.qcmian.clipper.devtools.api.devToolText
import com.qcmian.clipper.devtools.ui.components.DevToolButton
import com.qcmian.clipper.devtools.ui.components.DevToolInputField
import com.qcmian.clipper.devtools.ui.components.DevToolReportSource
import com.qcmian.clipper.devtools.ui.components.DevToolTypedSource
import com.qcmian.clipper.devtools.ui.components.DevToolScrollbarGap
import com.qcmian.clipper.devtools.ui.components.DevToolSectionDivider
import com.qcmian.clipper.devtools.ui.components.DevToolToggle
import com.qcmian.clipper.core.ui.code.scanPlain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 正文停下来多久才重算。与 JSON / XML 工具取同一个值。 */
private const val EvaluateDebounceMillis = 150L

/** 表达式输入区的高度：大约五行，长表达式也看得全，同时给历史留出足够地方。 */
private val ExpressionFieldHeight = 96.dp

/**
 * 语法帮助里「写法」那一列的宽度。
 *
 * 每一格写法都是一个能点的按钮（见 `HelpSpellingCell`），所以除了文字本身，还要放得下它左右的
 * 留白与格与格之间的间隔。这一列是**定宽**的——各行说明因此左缘对齐——所以必须容下最宽的一行，
 * 否则那一行里最后一格会被挤到第二行去（`exp ln log10 log2` 原先就是这样断成两行的）。最宽的
 * 一行是两个长写法的 `sin(90deg)` `sin(90°)`，下面两个间距值配合它取，留出余量。
 */
private val HelpNameWidth = 172.dp

/**
 * 一格写法左右的留白（见 [HelpSpellingCell] 的内边距），以及相邻两格之间的间隔。
 *
 * 取小值不是为了省地方，而是为了把宽度让给写法本身：同一行的几个写法要排进上面那一列里，间距
 * 一大，四格的那种行（`exp ln log10 log2`）就排不下了。
 */
private val HelpSpellingGap = 8.dp

/** 一条历史记录：式子 + 它的结果。 */
private data class MathHistoryEntry(val expression: String, val value: Double)

/**
 * 数学表达式计算器。
 *
 * 与 JSON / XML 工具不同，它不吃某一种「数据类型」——任何文本都可能是表达式，所以
 * [acceptedDataTypes] 留空：它不会因为剪贴板里是一段文本就被自动选中，只能从侧边栏手动进去。
 * （若声明 `text`，打开任意一段文字都会默认跳到计算器，那不是我们想要的。）
 *
 * 交互是「实时算」而不是「按一下算」：正文停下 150ms 就重算，中间态不闪。结果卡里另给整数结果
 * 的其它进制——开发者要十六进制时，多半不想为它专门选一次进制。
 */
internal object MathDevTool : DevTool {

    override val metadata: DevToolMetadata = DevToolMetadata(
        id = "math",
        name = "数学计算器",
        description = "实时计算数学表达式，支持幂、常用函数与常量，报错会指出位置。",
        group = DevToolGroup.CALCULATION,
        icon = ClipperIconKind.CALCULATOR,
    )

    @Composable
    override fun Content(input: ClipItem?, host: DevToolHost) {
        var source by remember { mutableStateOf("") }
        // 结果连同「它是对哪一份正文算的」一起记：正文一变，旧结果还在，但它已经不对应当前输入了。
        var outcome by remember { mutableStateOf<Result<Double>?>(null) }
        var outcomeSource by remember { mutableStateOf<String?>(null) }
        // 支持的写法是**看一次就够**的参考材料，平时收起来，需要时摊在底部（替掉历史那一块）。
        var showHelp by remember { mutableStateOf(false) }
        val history = remember { mutableStateListOf<MathHistoryEntry>() }
        // 用户在编辑框里改过内容没有。状态栏据此把来源从「来自剪贴板 / 文件」改成「文本输入」。
        var typed by remember { mutableStateOf(false) }

        // 从剪贴板条目打开时灌入正文——复制一个式子再按快捷键，是这里最顺手的用法。
        LaunchedEffect(input) {
            val item = input ?: return@LaunchedEffect
            // 取文本可能要读文件、也可能要解析富文本——放到后台算，别让主线程在打开面板时先卡一下。
            val text = withContext(Dispatchers.Default) { item.devToolText() }
            source = text
            typed = false
        }

        // 来源报告给底部状态栏：改过编辑框就说「文本输入」，否则交回面板判断（剪贴板 / 文件）。
        DevToolReportSource(host, if (typed) DevToolTypedSource else null)

        // 实时求值：正文一变就重新计时，停下来才算一次。取消由 `LaunchedEffect` 负责——正在算的
        // 那一份即使算完也自然作废。
        LaunchedEffect(source) {
            val text = source
            if (text.isBlank()) {
                outcome = null
                outcomeSource = null
                return@LaunchedEffect
            }
            delay(EvaluateDebounceMillis)
            val result = withContext(Dispatchers.Default) { MathFormat.evaluate(text) }
            outcome = result
            outcomeSource = text
        }

        // 常驻一句提醒：角度单位已经写进表达式，界面上没有开关可看，这条状态就成了唯一的提示。
        LaunchedEffect(Unit) {
            host.reportStatus("三角函数按弧度；要按角度写 90deg 或 90°")
        }

        // 结果框里这一份还对得上当前输入吗。它只决定「能不能记进历史」——显示上留着上一份，
        // 免得每敲一个键结果就闪一下空白。
        val fresh = source.isNotBlank() && outcomeSource == source
        val value = outcome?.getOrNull()
        val canRecord = fresh && value != null

        Column(Modifier.fillMaxSize()) {
            // 与 JSON / XML 工具用**同一个**输入框：点击落光标、当前行底纹、滚动条这些交互因此
            // 完全一致，拖入 / 粘贴的文件也照同一条路读成文本。行号照显示；表达式没有块可折，
            // 只关掉折叠。
            DevToolInputField(
                label = "表达式",
                value = source,
                onValueChange = {
                    source = it
                    typed = true
                },
                host = host,
                placeholder = "例如 2^10、sin(pi / 2)、sin(90deg)；支持的写法见「语法帮助」",
                // 表达式会长，折行比横向滚出去好读；这是少数用得上软折行的地方。
                softWrap = true,
                folding = false,
                scan = ::scanPlain,
                // 这里没有「打开文件」：手敲一个式子是这个工具的主用法，摆一个文件按钮只会与
                // 旁边的清空抢位置（文件照样可以拖进来、粘进来）。
                showOpenAction = false,
                // 清空不算「手打」，与原先那个只清正文、不动来源标记的按钮一致。
                onClear = { source = "" },
                modifier = Modifier.fillMaxWidth().height(ExpressionFieldHeight),
            )

            Spacer(Modifier.height(10.dp))

            ResultCard(
                outcome = outcome,
                source = source,
                // 结果里的进制写法点了就复制它自己，宿主顺带弹一句「已复制到剪贴板」。
                onCopy = { host.copyToClipboard(it) },
            )

            Spacer(Modifier.height(10.dp))

            // 动作紧跟在结果下面：这两个都对着「刚算出来的这个结果」——一个把它记进历史，一个摊开写法说明。
            Row(verticalAlignment = Alignment.CenterVertically) {
                DevToolToggle(
                    title = "语法帮助",
                    checked = showHelp,
                    onCheckedChange = { showHelp = it },
                )

                Spacer(Modifier.weight(1f))

                DevToolButton(
                    title = "存入历史",
                    primary = true,
                    enabled = canRecord,
                    onClick = {
                        value?.let { history.add(0, MathHistoryEntry(source, it)) }
                    },
                )
            }

            Spacer(Modifier.height(10.dp))

            // 一条横线把上面那一叠（输入 / 结果 / 动作）与下面的历史分开。
            DevToolSectionDivider()

            Spacer(Modifier.height(10.dp))

            // 底部这一块要么是历史、要么是语法帮助：两者并排会把两边都挤成一条缝，不值当。
            if (showHelp) {
                HelpPanel(
                    // 点一个写法就把它接到表达式末尾：查到一半可以直接拿去算，不必照着敲。
                    onPick = {
                        source += it
                        typed = true
                    },
                    modifier = Modifier.weight(1f),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("历史", fontSize = 13.sp, color = MaterialTheme.hintColor)
                    Spacer(Modifier.weight(1f))
                    DevToolButton(
                        title = "清空",
                        enabled = history.isNotEmpty(),
                        onClick = { history.clear() },
                    )
                }

                Spacer(Modifier.height(6.dp))

                HistoryList(
                    entries = history,
                    // 从历史里点回来的是用户此前算过的式子，同样算「自己输入的内容」。
                    onPick = {
                        source = it.expression
                        typed = true
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 结果卡：主值是十进制结果，另附整数结果的其它进制；失败时这里显示带位置的报错。 */
@Composable
private fun ResultCard(outcome: Result<Double>?, source: String, onCopy: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.onSurface.copy(alpha = 0.05f))
            .border(1.dp, colors.outline.copy(alpha = 0.6f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text("结果", fontSize = 12.sp, color = MaterialTheme.hintColor)
        Spacer(Modifier.height(4.dp))

        when {
            source.isBlank() -> Text(
                text = "输入表达式后在这里显示结果",
                fontSize = 12.sp,
                color = colors.onSurfaceVariant.copy(alpha = 0.6f),
            )

            outcome == null -> Text("计算中…", fontSize = 12.sp, color = MaterialTheme.hintColor)

            outcome.isSuccess -> {
                val number = outcome.getOrThrow()
                Text(
                    text = "= ${MathFormat.format(number)}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 22.sp,
                    color = colors.onSurface,
                )
                MathFormat.integerBases(number)?.let { bases ->
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        bases.forEachIndexed { index, base ->
                            if (index > 0) {
                                Text(
                                    text = "·",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.hintColor,
                                    modifier = Modifier.padding(horizontal = 2.dp),
                                )
                            }
                            BaseChip(value = base, onCopy = onCopy)
                        }
                    }
                }
            }

            else -> Text(
                text = (outcome.exceptionOrNull() as? MathError)
                    ?.let { mathErrorMessage(source, it) }
                    ?: "表达式有误",
                fontSize = 13.sp,
                color = colors.error,
            )
        }
    }
}

/**
 * 一种进制写法。点了就把**它自己**复制走——三个进制并排显示，用户要的是其中的某一个，
 * 复制「三个连成一行」没有意义。悬停时给底纹并亮出复制图标，提示这里可以点。
 */
@Composable
private fun BaseChip(value: String, onCopy: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(3.dp)

    Row(
        modifier = Modifier
            .clip(shape)
            .background(if (hovered) colors.onSurface.copy(alpha = 0.08f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { onCopy(value) }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = value,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.hintColor,
        )
        if (hovered) {
            Spacer(Modifier.width(4.dp))
            ClipperIcon(ClipperIconKind.COPY, size = 12.dp, tint = colors.onSurfaceVariant)
        }
    }
}

/** 历史列表：点一条把它填回输入框；空的时候给一句说明，而不是一片空白。 */
@Composable
private fun HistoryList(
    entries: List<MathHistoryEntry>,
    onPick: (MathHistoryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.TopStart) {
            Text("算过的式子会记在这里，点一下可以填回输入框", fontSize = 12.sp, color = MaterialTheme.hintColor)
        }
        return
    }

    val scroll = rememberScrollState()
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                // 让出滚动条的位置，免得最后一行被滑块压住。
                .padding(end = VerticalScrollbarWidth + DevToolScrollbarGap),
        ) {
            entries.forEach { entry -> HistoryRow(entry, onPick) }
        }
        VerticalScrollbar(
            scrollState = scroll,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun HistoryRow(entry: MathHistoryEntry, onPick: (MathHistoryEntry) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(4.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (hovered) colors.onSurface.copy(alpha = 0.06f) else colors.onSurface.copy(alpha = 0f))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { onPick(entry) }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = entry.expression,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.hintColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "= ${MathFormat.format(entry.value)}",
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 语法帮助：支持的运算符、常量与函数，以及角度 / 弧度该怎么理解。
 *
 * 做成一张参考表而不是一句提示：函数名与常量名记不全很正常，摊开来看比让人「自己猜」有用。
 * 内容是**静态**的，与 `MathEvaluator` 里的函数表一一对应——那边加函数时这里要一起改。
 *
 * 写法那一格可以点，点一下接到「表达式」的末尾（与时间戳、正则两张速查同一套交互）：查到一半
 * 就能直接拿去算，不必照着它一个字符一个字符地敲。顶部那行提示固定在卡片里、不随内容滚——它说
 * 的是「点了会落到哪」，滚走之后用户就只能猜了。
 *
 * @param onPick 点中一个写法，由调用方决定接到哪儿。
 */
@Composable
private fun HelpPanel(onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val scroll = rememberScrollState()

    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.onSurface.copy(alpha = 0.04f))
            .border(1.dp, colors.outline.copy(alpha = 0.6f), shape),
    ) {
        Text(
            text = "点一个写法，接进「表达式」的末尾",
            fontSize = 11.sp,
            color = MaterialTheme.hintColor,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 6.dp),
        )
        DevToolSectionDivider()

        Box(Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    // 右端多让一条滚动条的宽度再加一点间隙，免得最长的那一行被滑块压住。
                    .padding(
                        start = 12.dp,
                        top = 2.dp,
                        bottom = 8.dp,
                        end = 12.dp + VerticalScrollbarWidth + DevToolScrollbarGap,
                    ),
            ) {
                HelpSection("运算符")
                HelpRow(listOf("+", "-", "*", "/", "%", "^"), "加减乘除、取余、乘方；^ 右结合，-2^2 算作 -(2^2) = -4", onPick)
                HelpRow(listOf("(", ")", ","), "分组，以及函数参数之间的分隔", onPick)
                HelpRow(listOf("0x1F", "0b1010", "0o17"), "十六 / 二 / 八进制整数", onPick)
                HelpRow(listOf("1.5e-3"), "科学计数法", onPick)

                HelpSection("常量")
                HelpRow(listOf("pi", "e", "tau"), "圆周率、自然常数、2π", onPick)

                HelpSection("函数")
                HelpRow(listOf("sin", "cos", "tan"), "三角函数，入参按所选角度单位", onPick)
                HelpRow(listOf("asin", "acos", "atan"), "反三角函数，结果按所选角度单位", onPick)
                HelpRow(listOf("sinh", "cosh", "tanh"), "双曲函数，与角度单位无关", onPick)
                HelpRow(listOf("sqrt", "cbrt", "hypot"), "平方根、立方根、√(x²+y²)", onPick)
                HelpRow(listOf("exp", "ln", "log10", "log2"), "指数、自然对数、常用对数、以 2 为底的对数", onPick)
                HelpRow(listOf("log(x, 底)"), "任意底的对数，例如 log(8, 2) = 3", onPick)
                HelpRow(listOf("pow(x, y)"), "x 的 y 次方，等同 x^y", onPick)
                HelpRow(listOf("abs", "sign", "round"), "绝对值、符号、四舍五入", onPick)
                HelpRow(listOf("floor", "ceil", "trunc"), "向下取整、向上取整、截断小数", onPick)
                HelpRow(listOf("min", "max", "avg", "sum"), "可变参数：最小、最大、平均、求和", onPick)

                HelpSection("角度与弧度")
                HelpRow(listOf("sin(pi / 2)"), "三角函数一律按弧度，与各类编程语言一致", onPick)
                HelpRow(listOf("sin(90deg)", "sin(90°)"), "要按角度算，在数字后加 deg 或 °", onPick)
                HelpRow(listOf("deg(x)"), "把角度换成弧度，deg(180) ≈ 3.1416", onPick)
            }
            VerticalScrollbar(
                scrollState = scroll,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

/** 帮助里的一节标题。 */
@Composable
private fun HelpSection(title: String) {
    Text(
        text = title,
        fontSize = 12.sp,
        color = MaterialTheme.hintColor,
        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

/**
 * 帮助里的一行：左边是一个或多个等宽的写法（每一格都能点，见 [HelpSpellingCell]），右边是一句
 * 话说明。
 *
 * 同一行的几个写法各自占一格、用间隔分开，而不是拿空格把它们拼成一个字符串：拼在一起会被当成
 * 一个整体，点下去不知道要接哪一个进去。
 */
@Composable
private fun HelpRow(
    spellings: List<String>,
    description: String,
    onPick: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Row(
            modifier = Modifier.width(HelpNameWidth),
            horizontalArrangement = Arrangement.spacedBy(HelpSpellingGap),
        ) {
            spellings.forEach { spelling -> HelpSpellingCell(spelling, onPick) }
        }
        Text(
            text = description,
            fontSize = 12.sp,
            color = MaterialTheme.hintColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 可点的写法格：点一下把这个写法接进「表达式」的末尾。
 *
 * 悬停浮出主色底、光标变手型：右边那一列说明不可点，只有在这里给出「能按」的信号，才分得清哪
 * 一格能按、哪一格只是文字。
 */
@Composable
private fun HelpSpellingCell(text: String, onPick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (hovered) colors.primary.copy(alpha = 0.12f) else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null) { onPick(text) }
            // 左右各 2dp：够让悬停底纹不贴着字，又不至于占掉写法本需要的宽度（见 `HelpNameWidth`）。
            .padding(horizontal = 2.dp, vertical = 1.dp),
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = if (hovered) colors.primary else colors.onSurface,
            // 一格写法是一整个可以点的词，断成两行既不好看、也让人以为它分成了两个写法。列宽够时
            // 用不上这一句；万一某个平台的等宽字体更宽而排不下，宁可截断得看得出来，也别悄悄换行。
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
