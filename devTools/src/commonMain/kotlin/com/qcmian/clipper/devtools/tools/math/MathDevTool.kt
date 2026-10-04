package com.qcmian.clipper.devtools.tools.math

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import com.qcmian.clipper.devtools.ui.components.DevToolFieldAction
import com.qcmian.clipper.devtools.ui.components.DevToolReportSource
import com.qcmian.clipper.devtools.ui.components.DevToolTypedSource
import com.qcmian.clipper.devtools.ui.components.rememberFilePaste
import com.qcmian.clipper.devtools.ui.components.DevToolScrollbarGap
import com.qcmian.clipper.devtools.ui.components.DevToolSectionDivider
import com.qcmian.clipper.devtools.ui.components.DevToolToggle
import com.qcmian.clipper.core.ui.code.DevToolCodeField
import com.qcmian.clipper.core.ui.code.scanPlain
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 正文停下来多久才重算。与 JSON / XML 工具取同一个值。 */
private const val EvaluateDebounceMillis = 150L

/** 表达式输入区的高度：大约五行，长表达式也看得全，同时给历史留出足够地方。 */
private val ExpressionFieldHeight = 96.dp

/** 语法帮助里「写法」那一列的宽度：最长的一条（`0x1F  0b1010  0o17`）也放得下。 */
private val HelpNameWidth = 148.dp

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
            // 完全一致。行号照显示；表达式没有块可折，只关掉折叠。
            DevToolCodeField(
                label = "表达式",
                value = source,
                onValueChange = {
                    source = it
                    typed = true
                },
                placeholder = "例如 2^10、sin(pi / 2)、sin(90deg)；支持的写法见「语法帮助」",
                filePaste = rememberFilePaste(host),
                // 表达式会长，折行比横向滚出去好读；这是少数用得上软折行的地方。
                softWrap = true,
                folding = false,
                scan = ::scanPlain,
                modifier = Modifier.fillMaxWidth().height(ExpressionFieldHeight),
                actions = {
                    DevToolFieldAction(
                        kind = ClipperIconKind.TRASH,
                        tooltip = "清空",
                        enabled = source.isNotEmpty(),
                        onClick = { source = "" },
                    )
                },
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
                HelpPanel(modifier = Modifier.weight(1f))
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
 */
@Composable
private fun HelpPanel(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val scroll = rememberScrollState()

    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(colors.onSurface.copy(alpha = 0.04f))
                .border(1.dp, colors.outline.copy(alpha = 0.6f), shape)
                .verticalScroll(scroll)
                // 右端多让一条滚动条的宽度再加一点间隙，免得最长的那一行被滑块压住。
                .padding(
                    start = 12.dp,
                    top = 8.dp,
                    bottom = 8.dp,
                    end = 12.dp + VerticalScrollbarWidth + DevToolScrollbarGap,
                ),
        ) {
            HelpSection("运算符")
            HelpRow("+  -  *  /  %  ^", "加减乘除、取余、乘方；^ 右结合，-2^2 算作 -(2^2) = -4")
            HelpRow("( )  ,", "分组，以及函数参数之间的分隔")
            HelpRow("0x1F  0b1010  0o17", "十六 / 二 / 八进制整数")
            HelpRow("1.5e-3", "科学计数法")

            HelpSection("常量")
            HelpRow("pi   e   tau", "圆周率、自然常数、2π")

            HelpSection("函数")
            HelpRow("sin  cos  tan", "三角函数，入参按所选角度单位")
            HelpRow("asin  acos  atan", "反三角函数，结果按所选角度单位")
            HelpRow("sinh  cosh  tanh", "双曲函数，与角度单位无关")
            HelpRow("sqrt  cbrt  hypot", "平方根、立方根、√(x²+y²)")
            HelpRow("exp  ln  log10  log2", "指数、自然对数、常用对数、以 2 为底的对数")
            HelpRow("log(x, 底)", "任意底的对数，例如 log(8, 2) = 3")
            HelpRow("pow(x, y)", "x 的 y 次方，等同 x^y")
            HelpRow("abs  sign  round", "绝对值、符号、四舍五入")
            HelpRow("floor  ceil  trunc", "向下取整、向上取整、截断小数")
            HelpRow("min  max  avg  sum", "可变参数：最小、最大、平均、求和")

            HelpSection("角度与弧度")
            HelpRow("sin(pi / 2)", "三角函数一律按弧度，与各类编程语言一致")
            HelpRow("sin(90deg)  sin(90°)", "要按角度算，在数字后加 deg 或 °")
            HelpRow("deg(x)", "把角度换成弧度，deg(180) ≈ 3.1416")
        }
        VerticalScrollbar(
            scrollState = scroll,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
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

/** 帮助里的一行：左边是等宽的写法，右边是一句话说明。 */
@Composable
private fun HelpRow(name: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = name,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(HelpNameWidth),
        )
        Text(
            text = description,
            fontSize = 12.sp,
            color = MaterialTheme.hintColor,
            modifier = Modifier.weight(1f),
        )
    }
}
