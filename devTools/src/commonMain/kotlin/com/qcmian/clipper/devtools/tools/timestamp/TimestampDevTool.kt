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
import com.qcmian.clipper.devtools.ui.components.DevToolToggle
import com.qcmian.clipper.devtools.ui.components.code.DevToolCodeField
import com.qcmian.clipper.devtools.ui.components.code.rememberCodeColors
import com.qcmian.clipper.devtools.ui.components.code.scanPlain
import com.qcmian.clipper.devtools.ui.components.devToolFileDrop
import kotlin.time.Instant

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
 * 两个预设模板：同一件事（年月日时分秒 + 毫秒）在两种风格里的写法。
 *
 * 只列模板本身、不带语言名——填进格式框的是模板，菜单里显示别的反而要多看一眼才能对上。点一下
 * 填进去，用户接着改即可。
 */
private val PatternPresets = listOf(
    "yyyy-MM-dd HH:mm:ss.SSS",
    "%Y-%m-%d %H:%M:%S.%f",
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
 * 时间戳转换工具。
 *
 * 界面自上而下每行的结构都一样——**左边一列标签、右边是内容**（输入框 / 时区 / 两个格式框），
 * 各行内容左缘因此对齐，读起来是一条竖线。
 *
 * 有**输入格式**与**输出格式**两个模板，都可以留空：
 *  - 输入格式留空 → 交给 [TimestampConvert] 自动识别（Unix 秒 / 毫秒时间戳、ISO 8601、本地日期
 *    时间等）；非空 → 交给 [TimestampPattern] 按模板解析（符号表见其注释）；
 *  - 输出格式留空 → 结果就是内置那组写法；非空 → 在它**之上多一行**「自定义格式」，内置那些照旧。
 *
 * 时区分**输入时区**与**输出时区**（默认都本机）：前者解释不带时区的输入，后者渲染结果。两者
 * 不同时就是一次时区换算——例如按上海时间读入 `2026-10-03 14:30:00`、输出成纽约时间。
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
        // 下面那一块现在显示什么：结果，还是占位符速查页。两者互斥（见底部那段说明）。
        var showSyntax by remember { mutableStateOf(false) }
        // 输入时区解释「不带时区的输入」，输出时区渲染结果；默认都为本机，两者不同时即为一次换算。
        var inputZoneId by remember { mutableStateOf(TimestampZones.systemId()) }
        var outputZoneId by remember { mutableStateOf(TimestampZones.systemId()) }

        val inputZone = remember(inputZoneId) { TimestampZones.of(inputZoneId) }
        val outputZone = remember(outputZoneId) { TimestampZones.of(outputZoneId) }
        // 输出用的模板：没单独指定输出格式就跟随输入格式——只填一个框时，进出一致。
        val output = outputPattern.ifBlank { inputPattern }

        // 从剪贴板条目打开时灌入正文——复制一个时间戳再按快捷键，是这里最顺手的用法。
        LaunchedEffect(input) {
            val text = input?.devToolText() ?: return@LaunchedEffect
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

        val fields = remember(parseResult, output, outputZone) {
            val instant = parseResult?.getOrNull() ?: return@remember emptyList()
            // 内置那组写法一直都在（秒级 / 毫秒级时间戳、本地与 UTC 时间、ISO 8601、星期、相对现在）；
            // 指定了输出格式时，只在它**上面加一行**按模板格式化的结果，而不是把整组换掉——换掉会让
            // 人以为「一加格式，原先那些就没了」。
            val builtIn = TimestampConvert.fields(instant, outputZone)
            if (output.isBlank()) {
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
                    // 时间戳与日期时间都是短文本，折行比横向滚出去好读；既没有行号可数、也没有块可折。
                    softWrap = true,
                    lineNumbers = false,
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
                        // 就按它出一份样例——与解析同一条规则，点一下就能验证模板对不对。
                        source = if (inputPattern.isBlank()) now.toEpochMilliseconds().toString()
                        else TimestampPattern.format(now, inputZone, inputPattern)
                        origin = SourceOrigin.Now
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
                // 两个格式框吃的是同一套占位符，入口放一个就够；挂在最后一行，读起来是整个格式块的
                // 尾巴。它管的是**下面那一块显示什么**（速查还是结果），与结果区互斥。
                DevToolToggle(
                    title = "占位符速查",
                    checked = showSyntax,
                    onCheckedChange = { showSyntax = it },
                )
            }

            Spacer(Modifier.height(18.dp))

            // 一条横线把上面那一叠设定（输入 / 时区 / 格式）与下面的结果分开——两者是「填什么」与
            // 「得到什么」，用线断开比只留一段空白清楚。
            DevToolSectionDivider()

            Spacer(Modifier.height(10.dp))

            Box(Modifier.weight(1f)) {
                // 速查页与结果占同一块地方（与数学工具的「语法帮助 ⇄ 历史」同一取舍）：两者并排会把
                // 结果那一列挤成一条缝，而速查是看几眼就收起来的东西。格式框留在上方看得见，改完
                // 收起来就能对着结果核。
                if (showSyntax) {
                    TimestampSyntaxPanel(Modifier.fillMaxSize())
                } else {
                    when {
                        source.isBlank() -> Placeholder(hintFor(output.isBlank()))
                        parseResult?.isFailure == true -> Text(
                            text = parseResult.exceptionOrNull()?.message ?: "无法识别输入",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                        )

                        else -> ResultList(fields, host, Modifier.fillMaxSize())
                    }
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
