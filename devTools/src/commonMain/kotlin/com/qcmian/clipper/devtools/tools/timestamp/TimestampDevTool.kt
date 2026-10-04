package com.qcmian.clipper.devtools.tools.timestamp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
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
import com.qcmian.clipper.devtools.api.DataTypes
import com.qcmian.clipper.devtools.api.DevTool
import com.qcmian.clipper.devtools.api.DevToolGroup
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.DevToolMetadata
import com.qcmian.clipper.devtools.api.devToolText
import com.qcmian.clipper.devtools.api.readTextFileOrNull
import com.qcmian.clipper.devtools.ui.components.DevToolButton
import com.qcmian.clipper.devtools.ui.components.DevToolFieldAction
import com.qcmian.clipper.devtools.ui.components.DevToolReportSource
import com.qcmian.clipper.devtools.ui.components.DevToolTypedSource
import com.qcmian.clipper.devtools.ui.components.DevToolMenuButton
import com.qcmian.clipper.devtools.ui.components.DevToolScrollbarGap
import com.qcmian.clipper.devtools.ui.components.DevToolSectionDivider
import com.qcmian.clipper.devtools.ui.components.code.DevToolCodeField
import com.qcmian.clipper.devtools.ui.components.code.rememberCodeColors
import com.qcmian.clipper.devtools.ui.components.code.scanPlain
import com.qcmian.clipper.devtools.ui.components.devToolFileDrop
import com.qcmian.clipper.devtools.ui.components.rememberFilePaste
import kotlin.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 输入区高度：大约四行，够显示一个时间戳或一条日期时间，也给结果区留出地方。 */
private val InputFieldHeight = 84.dp

/** 单行控件高度：与工具栏上的按钮 / 下拉同高，几行控件并排时基线才齐。 */
private val FieldHeight = 30.dp

/** 左侧标签列的宽度：最长的一项（「输入时区」）也放得下，各行内容因此左缘对齐。 */
private val FieldLabelWidth = 60.dp

/** 标签与内容之间的间隔。 */
private val FieldLabelGap = 8.dp

/** 结果里标签列的宽度：最长的一条（「毫秒级时间戳」六个字）也放得下。 */
private val LabelWidth = 92.dp

/**
 * 速查卡片的宽度：三列（含义 130 + 写法 78 + 示例，见 `TimestampSyntaxPanel`）加上左右内边距与
 * 滚动条，正好放得下那张表。
 *
 * 给固定值而不是按比例分：表的列宽本来就是定死的，窗口变宽时该长的是结果那一栏。
 */
private val SyntaxPanelWidth = 320.dp

/**
 * 几个预设模板，点一下填进格式框，用户接着改即可。
 *
 * 只列模板本身、不带名字——填进格式框的就是这串字符，菜单里显示别的反而要多看一眼才能对上。
 * 三个都是**同一套写法**，差别只在年月日之间用什么隔、要不要带偏移。
 */
private val PatternPresets = listOf(
    "yyyy-MM-dd HH:mm:ss.SSS",
    "yyyy/MM/dd HH:mm:ss",
    "yyyy-MM-dd'T'HH:mm:ssZZZZZ",
)

/**
 * 眼前这段内容从哪来——报告给状态栏的那句「来自…」（见 `DevToolReportSource`）。
 *
 * 标签为 `null` 表示「交回面板判断」：面板自己认得剪贴板记录与文件来源，这里只有工具内部才知道的
 * 两种来源需要点名。
 */
private enum class SourceOrigin(val label: String?) {
    /** 随剪贴板记录带进来的：交回面板，文件来源还要优先。 */
    Clipboard(null),

    /** 点了「当前时间」填进来的。 */
    Now("当前时间"),

    /** 用户直接编辑输入框，或从历史里点回来的。 */
    Typed(DevToolTypedSource),
}

/**
 * 速查页替的是哪个格式框——点中的写法接进它。
 *
 * 两个格式框各有一个「速查」按钮，而不是共用一个：模板只有一份，但填进去的那串是每一框自己的，
 * 共用一个的话点完还得猜「落到哪去了」。标签用在速查页顶部那句提示里。
 */
private enum class FormatField(val label: String) {
    Input("输入格式"),
    Output("输出格式"),
}

/**
 * 时间戳转换工具。
 *
 * 界面自上而下每行的结构都一样——**左边一列标签、右边是内容**（输入框 / 时区 / 两个格式框），
 * 各行内容左缘因此对齐，读起来是一条竖线。
 *
 * 有**输入格式**与**输出格式**两个模板，都可以留空：
 *  - 输入格式留空 → 交给 [TimestampConvert] 自动识别（Unix 秒 / 毫秒时间戳、ISO 8601、本地日期
 *    时间等）；非空 → 交给 [TimestampPattern] 按模板解析（写法与 kotlinx-datetime 一致，
 *    支持哪些符号见 [TimestampSyntax]）；
 *  - 输出格式留空 → 结果就是内置那组写法；非空 → 在它**之上多一行**「自定义格式」，内置那些照旧。
 *
 * 时区分**输入时区**与**输出时区**（默认都本机）：前者解释**不带时区**的输入（输入格式里写了
 * 偏移或时区 ID 时，以文本里那个为准），后者渲染结果。两者不同时就是一次时区换算——例如按上海
 * 时间读入 `2026-10-03 14:30:00`、输出成纽约时间。
 *
 * [acceptedDataTypes] 声明 `timestamp`：复制一个时间戳打开工具时会被推荐到最前；而普通文本
 * （凑巧是纯数字的除外）不会把用户引到这里。
 */
internal object TimestampDevTool : DevTool {

    override val metadata: DevToolMetadata = DevToolMetadata(
        id = "timestamp",
        name = "时间戳转换",
        description = "时间戳与日期时间双向转换，可自选时区、可分别指定输入与输出格式。",
        group = DevToolGroup.CONVERTER,
        icon = ClipperIconKind.CLOCK,
    )

    override val acceptedDataTypes: Set<String> = setOf(DataTypes.TIMESTAMP)

    @Composable
    override fun Content(input: ClipItem?, host: DevToolHost) {
        var source by remember { mutableStateOf("") }
        // 这段内容从哪来：随剪贴板带进来的、点「当前时间」的、还是自己敲的。报告给状态栏。
        var origin by remember { mutableStateOf(SourceOrigin.Clipboard) }
        var inputPattern by remember { mutableStateOf("") }
        var outputPattern by remember { mutableStateOf("") }
        // 速查页正开着的话，是替哪个格式框开的（点中的写法接进它）；`null` 就是没开。
        var syntaxTarget by remember { mutableStateOf<FormatField?>(null) }
        // 输入时区解释「不带时区的输入」，输出时区渲染结果；默认都为本机，两者不同时即为一次换算。
        var inputZoneId by remember { mutableStateOf(TimestampZones.systemId()) }
        var outputZoneId by remember { mutableStateOf(TimestampZones.systemId()) }

        // 点中的写法接进对应格式框的末尾：让用户按「先年月日、再时分秒」的顺序一路点下来，
        // 中间的分隔符自己敲（各有独立按键，比记符号快）。
        fun pick(target: FormatField, spelling: String) {
            when (target) {
                FormatField.Input -> inputPattern += spelling
                FormatField.Output -> outputPattern += spelling
            }
        }

        fun toggleSyntax(target: FormatField) {
            syntaxTarget = if (syntaxTarget == target) null else target
        }

        val inputZone = remember(inputZoneId) { TimestampZones.of(inputZoneId) }
        val outputZone = remember(outputZoneId) { TimestampZones.of(outputZoneId) }
        // 输出用的模板：没单独指定输出格式就跟随输入格式——只填一个框时，进出一致。
        val output = outputPattern.ifBlank { inputPattern }
        // 输入格式里带了时区的话，解析只认文本里那个，下面的「输入时区」就用不上了（见 `TimestampPattern`）。
        val inputCarriesZone = remember(inputPattern) { TimestampPattern.carriesZone(inputPattern) }
        // 模板本身写错了（不认识的字母、位数不对）就先说模板：这类错误看着像「输入不对」，不说清
        // 楚会把用户引到输入上去找。先问输入格式，因为输出格式留空时会跟随它。
        val patternProblem = remember(inputPattern, output) {
            TimestampPattern.problemOf(inputPattern)?.let { "输入格式：$it" }
                ?: TimestampPattern.problemOf(output)?.let { "输出格式：$it" }
        }

        // 从剪贴板条目打开时灌入正文——复制一个时间戳再按快捷键，是这里最顺手的用法。
        LaunchedEffect(input) {
            val item = input ?: return@LaunchedEffect
            // 取文本可能要读文件、也可能要解析富文本——放到后台算，别让主线程在打开面板时先卡一下。
            val text = withContext(Dispatchers.Default) { item.devToolText() }
            source = text
            origin = SourceOrigin.Clipboard
        }

        // 来源同步给面板：它自己只认得「打开时带了什么」，不知道用户随后点了按钮还是敲了字。
        DevToolReportSource(host, origin.label)

        // 同步解析：输入 / 输入格式 / 输入时区任一变化就重算。解析很轻（几次字符串判断），摆在
        // 组合里算一目了然，也不会留下「上一份结果」的中间态。
        val parseResult: Result<Instant>? = remember(source, inputPattern, inputZone) {
            if (source.isBlank()) {
                null
            } else if (inputPattern.isBlank()) {
                when (val parsed = TimestampConvert.parse(source, inputZone)) {
                    is TimestampParse.Success -> Result.success(parsed.instant)
                    is TimestampParse.Failure -> Result.failure(IllegalArgumentException(parsed.reason))
                }
            } else {
                TimestampPattern.parse(source, inputZone, inputPattern)
            }
        }

        val fields = remember(parseResult, output, outputZone, patternProblem) {
            val instant = parseResult?.getOrNull() ?: return@remember emptyList()
            // 内置那组写法一直都在（秒级 / 毫秒级时间戳、本地与 UTC 时间、ISO 8601、星期、相对现在）；
            // 指定了输出格式时，只在它**上面加一行**按模板格式化的结果，而不是把整组换掉——换掉会让
            // 人以为「一加格式，原先那些就没了」。
            val builtIn = TimestampConvert.fields(instant, outputZone)
            // 模板写错时不排「自定义格式」那一行：错误由结果区那行红字说，不混进结果列表里。
            if (output.isBlank() || patternProblem != null) {
                builtIn
            } else {
                listOf(
                    TimestampField("自定义格式", TimestampPattern.format(instant, outputZone, output), primary = true),
                ) + builtIn
            }
        }

        // 状态栏这一行只说「眼前这份输入处于什么状态」。
        LaunchedEffect(source, parseResult) {
            host.reportStatus(
                when {
                    source.isBlank() -> null
                    parseResult?.isFailure == true -> "无法识别输入 · ${source.length} 字符"
                    parseResult != null -> "已识别 · ${source.length} 字符"
                    else -> null
                }
            )
        }

        Column(Modifier.fillMaxSize()) {
            // 输入：框名交给左边的标签列，编辑框因此不再自带标题行；「清空 / 当前时间」两个动作
            // 跟着框，排在它右边。
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("输入")
                Spacer(Modifier.width(FieldLabelGap))
                DevToolCodeField(
                    label = "输入",
                    showLabel = false,
                    value = source,
                    // 用户直接编辑输入框——内容就不再来自剪贴板或「当前时间」了。
                    onValueChange = {
                        source = it
                        origin = SourceOrigin.Typed
                    },
                    placeholder = if (inputPattern.isBlank()) {
                        "例如 1759468800、1759468800000 或 2026-10-03 14:30:00"
                    } else {
                        "按「输入格式」填，例如 2026-10-03 14:30:00"
                    },
                    filePaste = rememberFilePaste(host),
                    // 时间戳与日期时间都是短文本，折行比横向滚出去好读；没有块可折，行号照显示。
                    softWrap = true,
                    folding = false,
                    scan = ::scanPlain,
                    modifier = Modifier
                        .weight(1f)
                        .height(InputFieldHeight)
                        // 拖进来的文件与剪贴板里的文件条目走同一条读法（见 `readTextFileOrNull`）。
                        .devToolFileDrop(host) { paths ->
                            source = paths.joinToString("\n") { readTextFileOrNull(it) ?: it }
                            origin = SourceOrigin.Typed
                        },
                )

                Spacer(Modifier.width(8.dp))

                DevToolFieldAction(
                    kind = ClipperIconKind.TRASH,
                    tooltip = "清空",
                    enabled = source.isNotEmpty(),
                    onClick = { source = "" },
                )

                Spacer(Modifier.width(4.dp))

                DevToolButton(
                    title = "当前时间",
                    onClick = {
                        val now = TimestampConvert.now()
                        // 填的是「输入侧」的内容：输入格式留空就填毫秒时间戳（精度到毫秒），有格式
                        // 就按它出一份样例——与解析同一条规则，点一下就能验证模板对不对。模板写错
                        // 了就不填（错误已经显示在结果区），免得填一串半成品出来反倒看不出错在哪。
                        val sample = if (inputPattern.isBlank()) {
                            now.toEpochMilliseconds().toString()
                        } else {
                            runCatching { TimestampPattern.format(now, inputZone, inputPattern) }.getOrNull()
                        }
                        if (sample != null) {
                            source = sample
                            origin = SourceOrigin.Now
                        }
                    },
                )
            }

            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("输入时区")
                Spacer(Modifier.width(FieldLabelGap))
                TimeZonePicker(
                    selectedId = inputZoneId,
                    onSelect = { inputZoneId = it },
                )

                // 箭头读作「输入时区 → 输出时区」：左边解释无时区的输入，右边渲染结果。
                Text(
                    text = "→",
                    fontSize = 12.sp,
                    color = MaterialTheme.hintColor,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )

                TimeZonePicker(
                    selectedId = outputZoneId,
                    onSelect = { outputZoneId = it },
                )

                // 输入格式自带时区时，左边那个下拉其实用不上了——不说明的话，改了它毫无变化，看着
                // 就像工具坏了。提示跟着输入格式走：模板里没了时区，它自己就消失。
                if (inputCarriesZone) {
                    Text(
                        text = "输入格式里带了时区，以文本为准",
                        fontSize = 11.sp,
                        color = MaterialTheme.hintColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 10.dp).weight(1f),
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("输入格式")
                Spacer(Modifier.width(FieldLabelGap))
                SingleLineField(
                    value = inputPattern,
                    onValueChange = { inputPattern = it },
                    placeholder = "按此格式解析输入，如 yyyy-MM-dd HH:mm:ss",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                PresetMenu(selected = inputPattern, onSelect = { inputPattern = it })
                Spacer(Modifier.width(8.dp))
                SyntaxButton(opened = syntaxTarget == FormatField.Input) {
                    toggleSyntax(FormatField.Input)
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("输出格式")
                Spacer(Modifier.width(FieldLabelGap))
                SingleLineField(
                    value = outputPattern,
                    onValueChange = { outputPattern = it },
                    placeholder = "按此格式输出结果，如 yyyy-MM-dd HH:mm:ss",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                PresetMenu(selected = outputPattern, onSelect = { outputPattern = it })
                Spacer(Modifier.width(8.dp))
                SyntaxButton(opened = syntaxTarget == FormatField.Output) {
                    toggleSyntax(FormatField.Output)
                }
            }

            Spacer(Modifier.height(18.dp))

            // 一条横线把上面那一叠设定（输入 / 时区 / 格式）与下面的结果分开——两者是「填什么」与
            // 「得到什么」，用线断开比只留一段空白清楚。
            DevToolSectionDivider()

            Spacer(Modifier.height(10.dp))

            // 结果在左、速查在右，并排——速查不再把结果盖住。
            //
            // 速查放右边：这块面板的行标签一路靠左成一条竖线（见类注释），结果留在左边才与上面的
            // 格式行对齐；速查自己是一张列宽定死的三列表，占住右边正合适。
            Row(Modifier.weight(1f)) {
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    when {
                        source.isBlank() -> Placeholder(hintFor(output.isBlank()))
                        // 模板写错时先报模板：输入可能压根没错，先把人引到输入上会白找一轮。
                        patternProblem != null -> Text(
                            text = patternProblem,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                        )

                        parseResult?.isFailure == true -> Text(
                            text = parseResult.exceptionOrNull()?.message ?: "无法识别输入",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                        )

                        else -> ResultList(fields, host, Modifier.fillMaxSize())
                    }
                }

                val target = syntaxTarget
                if (target != null) {
                    Spacer(Modifier.width(12.dp))
                    TimestampSyntaxPanel(
                        target = target.label,
                        onPick = { pick(target, it) },
                        modifier = Modifier.width(SyntaxPanelWidth).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/** 左侧一列的标签：等宽，各行内容因此左缘对齐。 */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.hintColor,
        modifier = Modifier.width(FieldLabelWidth),
    )
}

/** 「预设」下拉：把几个预设模板填进格式框，用户接着改。 */
@Composable
private fun PresetMenu(selected: String, onSelect: (String) -> Unit) {
    DevToolMenuButton(
        label = "预设",
        options = PatternPresets,
        // 当前模板不在预设里时，`selected` 传它自己，菜单便没有一项被高亮。
        selected = selected,
        optionLabel = { it },
        onSelect = onSelect,
    )
}

/**
 * 格式行尾的「速查」按钮：点开把下面那块换成速查表，再点收起。
 *
 * 文案跟着状态翻，说的是「按下去会怎样」（与标题栏那个侧边栏开关同一口径）——两个格式框各有一个
 * 按钮，不翻的话点开之后分不清开的是哪一个。
 */
@Composable
private fun SyntaxButton(opened: Boolean, onClick: () -> Unit) {
    DevToolButton(title = if (opened) "收起" else "速查", onClick = onClick)
}

/**
 * 单行文本输入框：高度与按钮一致、有实底、聚焦时描主色边，文字垂直居中。
 *
 * 与 `DevToolCodeField`（代码编辑框）刻意不同：那个要装多行、要行号与滚动条，天然是「一块区域」；
 * 这里的模板只有一行，用轻量输入框才贴切，文字也居得中。
 *
 * 底色与边框是刻意选的：**实底 + 聚焦高亮**才读得出「这里能敲字」。若跟「预设」那种下拉一样用
 * 透明底 + 一股描边，两者就长得一模一样，让人以为它也是只读的。
 */
@Composable
private fun SingleLineField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
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
        modifier = modifier
            .height(FieldHeight)
            .clip(shape)
            .background(codeColors.editorBackground)
            .border(
                width = 1.dp,
                color = if (focused) colors.primary else colors.outline.copy(alpha = 0.6f),
                shape = shape,
            )
            .padding(horizontal = 12.dp),
        decorationBox = { innerTextField ->
            // 撑满整块高度再居中：`BasicTextField` 自己只占文字那一行，不这么做文字会贴在顶上。
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
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

private fun hintFor(auto: Boolean): String = if (auto) {
    "输入时间戳或日期时间后，这里会列出它的各种写法"
} else {
    "输入按「输入格式」解析，结果按「输出格式」输出"
}

/** 结果列表：逐行「标签 + 值」，点一行复制那一行的值。 */
@Composable
private fun ResultList(
    fields: List<TimestampField>,
    host: DevToolHost,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                // 让出滚动条的位置，免得最后一行被滑块压住。
                .padding(end = VerticalScrollbarWidth + DevToolScrollbarGap),
        ) {
            fields.forEach { field ->
                ResultRow(field, onCopy = { host.copyToClipboard(field.value) })
            }
        }
        VerticalScrollbar(
            scrollState = scroll,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/**
 * 结果里的一行。
 *
 * 整行可点即复制：值有长有短，让人精确拖选一段数字很容易选歪，点整行则没有落点要求。
 * 复制图标只在悬停时出现——平时不占视觉重量，「这一行能点」靠底色变化表达。
 */
@Composable
private fun ResultRow(field: TimestampField, onCopy: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(4.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (hovered) colors.onSurface.copy(alpha = 0.06f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null) { onCopy() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = field.label,
            fontSize = 12.sp,
            color = MaterialTheme.hintColor,
            modifier = Modifier.width(LabelWidth),
        )
        Text(
            text = field.value,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            // 两个时间戳是这次转换真正要拿走的数，用主色把它们从日期、星期里挑出来。
            color = if (field.primary) colors.primary else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        if (hovered) {
            ClipperIcon(ClipperIconKind.COPY, size = 14.dp, tint = colors.onSurfaceVariant)
        }
    }
}

/** 空输入时的占位说明。 */
@Composable
private fun Placeholder(text: String) {
    Text(text = text, fontSize = 12.sp, color = MaterialTheme.hintColor)
}
