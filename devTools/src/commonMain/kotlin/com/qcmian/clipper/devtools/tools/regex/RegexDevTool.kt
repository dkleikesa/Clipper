package com.qcmian.clipper.devtools.tools.regex

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.ui.code.DevToolCodeField
import com.qcmian.clipper.core.ui.code.scanPlain
import com.qcmian.clipper.core.ui.components.HoverTooltip
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.devtools.api.DevTool
import com.qcmian.clipper.devtools.api.DevToolGroup
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.DevToolMetadata
import com.qcmian.clipper.devtools.api.devToolText
import com.qcmian.clipper.devtools.ui.components.DevToolActionSpacer
import com.qcmian.clipper.devtools.ui.components.DevToolButton
import com.qcmian.clipper.devtools.ui.components.DevToolFieldAction
import com.qcmian.clipper.devtools.ui.components.DevToolInputField
import com.qcmian.clipper.devtools.ui.components.DevToolReportSource
import com.qcmian.clipper.devtools.ui.components.DevToolResultActions
import com.qcmian.clipper.devtools.ui.components.DevToolSectionDivider
import com.qcmian.clipper.devtools.ui.components.DevToolSingleLineField
import com.qcmian.clipper.devtools.ui.components.DevToolTabBar
import com.qcmian.clipper.devtools.ui.components.DevToolToggle
import com.qcmian.clipper.devtools.ui.components.DevToolTypedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 正文停下来多久才重算。与 JSON / URL / Hash 取同一个值。 */
private const val EvaluateDebounceMillis = 150L

/** 左侧标签列的宽度：「替换为」也放得下，各行内容因此左缘对齐。 */
private val LabelWidth = 48.dp

/** 标签与内容之间的间隔。 */
private val LabelGap = 8.dp

/**
 * 速查面板的宽度。
 *
 * 三列（含义 96 + 写法 100 + 示例）加上内边距与滚动条，320dp 正好放得下；与时间戳工具那张速查
 * 同宽，两个工具并排看是一回事。
 */
private val SyntaxPanelWidth = 320.dp

/**
 * 匹配 / 替换：换的是**整页**（右边那一栏从「命中列表」变成「替换结果」），所以用页签而不是工具栏
 * 里的分段控件——两者在工具栏上的长相刻意不同（见 `DevToolTabBar`）。
 */
private enum class RegexMode(val title: String) {
    Match("匹配"),
    Replace("替换"),
}

/**
 * 算一次的全部输入。
 *
 * 打包成一个值是为了当 `LaunchedEffect` 的键，也是「眼前这份结果算的是哪一份输入」的凭据：
 * 与它不等就说明结果还停在上一份输入上（见 `RegexDevTool.Content` 里的 `stale`）。
 */
private data class RegexQuery(
    val pattern: String,
    val flags: Set<RegexFlag>,
    val mode: RegexMode,
    val template: String,
    val subject: String,
)

/** 一次计算的产出。三选一，正好对应结果框里那三种样子。 */
private sealed interface RegexOutcome {
    /** 命中列表；[report] 是已经排好版的那段文字（排版放在后台算，见 `compute`）。 */
    class Matched(val matches: List<RegexMatch>, val report: String, val truncated: Boolean) : RegexOutcome

    /** 替换后的整段文本。 */
    class Replaced(val text: String, val count: Int) : RegexOutcome

    /** 模式或替换模板写错：[message] 就是要显示在结果框里的那句话。 */
    class Failed(val message: String) : RegexOutcome
}

/**
 * 正则表达式工具：左边一段待匹配文本，右边边写边看结果。
 *
 * 两页（见 [RegexMode]）：**匹配**列出每一处命中的位置与捕获组，**替换**按模板替换后给出整段结果。
 * 模式与标志位两页共用——换页不是换模式，只是换「拿这份匹配做什么」。
 *
 * 想不起来的写法（元字符、四个开关各是什么意思、替换模板的 `$1` 那一套）摊在右边的**速查**里：点
 * 一行就把写法接进对应框的末尾，与时间戳工具那张速查同一交互。面板只列**当前看得见的那几个框**
 * 能用的写法——在匹配页把「替换为」的写法也列出来，点了看不出发生了什么。
 *
 * 结果那一栏是**只读代码框**而不是逐行可点的列表：一处命中既有位置、匹配内容，也可能带着好几个
 * 捕获组，行数与行宽都不固定（见 [matchReport]）；放进代码框还能顺手选中一段、或整体存成文件。
 * 「只把匹配内容抽出来」另有一个动作，挂在结果框的标题行上（见 [ResultField]）。
 *
 * **命中不做文本内高亮**，这是刻意的：代码框的着色走 `CodeStructure`，而它是按**正文**缓存的
 * （KodeMirror 的 `StateField` 只在 `docChanged` 时重扫），模式改了而正文没变时那份高亮不会刷新
 * ——要做就得给 `DevToolCodeField` 加一等参数、并动扫描器与装饰器，不该混在这个工具里做。谁在哪一行
 * 由右边那栏给全，先这样够用。
 *
 * [acceptedDataTypes] 留空：与 Hash / 数学工具同一取舍——任何一段文本都可能是待匹配的内容，声明
 * `text` 会让普通文本打开工具窗口时默认跳到本工具。
 *
 * **入口暂时隐藏**：`DevToolsRegistry.builtIn()` 里那一行是注释掉的（原因与放开步骤都写在那边），
 * 侧边栏因此看不到它、也不会被自动选中。藏的只是入口——工具本身、纯逻辑与测试都照常编译与运行，
 * 本文件里没有一处为此改动。
 */
internal object RegexDevTool : DevTool {

    override val metadata: DevToolMetadata = DevToolMetadata(
        id = "regex",
        name = "正则表达式",
        description = "边写边看命中：列出每处匹配的位置与捕获组，或按模板替换。",
        group = DevToolGroup.TEXT,
        icon = ClipperIconKind.REGEX,
    )

    override val acceptedDataTypes: Set<String> = emptySet()

    @Composable
    override fun Content(input: ClipItem?, host: DevToolHost) {
        var mode by remember { mutableStateOf(RegexMode.Match) }
        var patternText by remember { mutableStateOf("") }
        var flags by remember { mutableStateOf(emptySet<RegexFlag>()) }
        var template by remember { mutableStateOf("") }
        var subject by remember { mutableStateOf("") }
        // 用户在编辑框里改过内容没有。状态栏据此把来源从「来自剪贴板 / 文件」改成「文本输入」。
        var typed by remember { mutableStateOf(false) }
        var outcome by remember { mutableStateOf<RegexOutcome?>(null) }
        // 上一次真正算过的输入。与眼前这份不等，就说明结果框里那份还对不上眼前的内容：能看，
        // 但不能拷出去（与 JSON / URL / Hash 同一条口径）。
        var computed by remember { mutableStateOf<RegexQuery?>(null) }
        // 速查面板开没开。两个「速查」按钮开的是同一张表。
        var syntaxOpen by remember { mutableStateOf(false) }

        val query = RegexQuery(patternText, flags, mode, template, subject)
        val stale = query != computed
        // 速查只列**当前看得见的那几个框**能用的写法：在匹配页点一行「替换模板」，那个框还没画出来，
        // 点了也看不出发生了什么。
        val syntaxGroups = RegexSyntax.groups.filter {
            it.target != RegexSyntaxField.Template || mode == RegexMode.Replace
        }

        // 点一行速查：把写法接到对应框的末尾（与时间戳工具同一套交互——「写法」是一格能点的东西）。
        fun pick(target: RegexSyntaxField, spelling: String) {
            when (target) {
                RegexSyntaxField.Pattern -> patternText += spelling
                RegexSyntaxField.Template -> template += spelling
            }
        }

        DevToolReportSource(host, if (typed) DevToolTypedSource else null)

        // 从剪贴板条目打开时把正文灌进「待匹配文本」：顺手的用法是「我刚复制了一段文本，想拿正则
        // 在里面找点什么」——正文长、多半是现成复制的，而模式短、多半要现敲。模式不覆盖用户的输入。
        LaunchedEffect(input) {
            val item = input ?: return@LaunchedEffect
            // 取文本可能要读文件、也可能要解析富文本——放到后台算，别让主线程在打开面板时先卡一下。
            val text = withContext(Dispatchers.Default) { item.devToolText() }
            subject = text
            typed = false
        }

        // 实时计算：输入一变就重新计时，停下来才算一次。取消由 `LaunchedEffect` 负责——正在算的
        // 那一份即使算完也自然作废。
        LaunchedEffect(query) {
            if (patternText.isEmpty()) {
                outcome = null
                computed = query
                return@LaunchedEffect
            }
            // 只有手敲（模式或正文）才等防抖；换页、拨标志都是「点一下就定」，立刻重算。
            // 先落到局部变量：`computed` 是委托属性，跨挂起点之后不能再当稳定值读。
            val previous = computed
            val handTyped = previous == null ||
                previous.pattern != query.pattern ||
                previous.subject != query.subject
            if (handTyped) delay(EvaluateDebounceMillis)
            val result = withContext(Dispatchers.Default) { compute(query) }
            outcome = result
            computed = query
        }

        // 行数 / 字符数这类「眼前这份内容是什么状态」的读数报到窗口底部的状态栏，不占内容区。
        LaunchedEffect(query, computed, outcome) {
            host.reportStatus(
                when {
                    patternText.isEmpty() -> null
                    stale -> "计算中…"
                    else -> when (val current = outcome) {
                        null -> null
                        is RegexOutcome.Failed -> "模式有问题 · 文本 ${subject.length} 字符"
                        is RegexOutcome.Matched -> buildString {
                            append("命中 ").append(current.matches.size).append(" 处")
                            if (current.truncated) append("（另有更多，未列出）")
                            append(" · 文本 ").append(subject.length).append(" 字符")
                        }

                        is RegexOutcome.Replaced ->
                            "替换 ${current.count} 处 · 结果 ${current.text.length} 字符"
                    }
                }
            )
        }

        Column(Modifier.fillMaxSize()) {
            DevToolTabBar(
                options = RegexMode.entries,
                selected = mode,
                optionLabel = { it.title },
                // 只换页，不动两边的输入：模式、标志、正文都是两页共用的。
                onSelect = { mode = it },
            )

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("模式")
                Spacer(Modifier.width(LabelGap))
                DevToolSingleLineField(
                    value = patternText,
                    onValueChange = { patternText = it },
                    placeholder = "正则模式，例如 (\\w+)@(\\w+)\\.com",
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                SyntaxButton(opened = syntaxOpen) { syntaxOpen = !syntaxOpen }
            }

            Spacer(Modifier.height(8.dp))

            // 标志位单独一行：四枚开关并排就有 390dp 上下，跟模式框挤在同一行，窄窗口里最后
            // 一枚会被截掉半截。
            Row(verticalAlignment = Alignment.CenterVertically) {
                RegexFlag.entries.forEachIndexed { index, flag ->
                    if (index > 0) DevToolActionSpacer()
                    // 悬停提示只给一句短的；完整的那几句（含义 / 字母 / 效果）在速查表里摊着。
                    HoverTooltip(text = flag.tooltip) {
                        DevToolToggle(
                            title = flag.title,
                            checked = flag in flags,
                            onCheckedChange = { on -> flags = if (on) flags + flag else flags - flag },
                        )
                    }
                }
            }

            if (mode == RegexMode.Replace) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FieldLabel("替换为")
                    Spacer(Modifier.width(LabelGap))
                    DevToolSingleLineField(
                        value = template,
                        onValueChange = { template = it },
                        placeholder = "用 \$1、\${名} 引用捕获组（\$0 是整段匹配）；字面量「\$」写成「\\\$」",
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    // 与模式行那枚开的是同一张表：在替换页想查模板写法，不必先回匹配页去点。
                    SyntaxButton(opened = syntaxOpen) { syntaxOpen = !syntaxOpen }
                }
            }

            Spacer(Modifier.height(14.dp))

            // 一条横线把上面那几行设定（模式 / 标志 / 模板）与下面的正文分开——两者是「按什么找」
            // 与「在什么里找」，用线断开比只留一段空白清楚。
            DevToolSectionDivider()

            Spacer(Modifier.height(10.dp))

            // 左输入、右结果并排：两边都在讲同一件事（这份模式在这段文本上做了什么），逐处对着看
            // 才方便——这也正是 JSON 工具输入 / 结果两栏的摆法。
            Row(Modifier.weight(1f)) {
                DevToolInputField(
                    label = "待匹配文本",
                    value = subject,
                    onValueChange = {
                        subject = it
                        typed = true
                    },
                    host = host,
                    placeholder = "在这里粘一段文本；从剪贴板条目打开时它已经填好了",
                    // 待匹配的可以是日志、代码、长段落，折行比横向滚出去好读；没有块可折，关掉折叠。
                    softWrap = true,
                    folding = false,
                    scan = ::scanPlain,
                    onClear = {
                        subject = ""
                        typed = false
                    },
                    modifier = Modifier.weight(1f),
                )

                Spacer(Modifier.width(8.dp))

                ResultField(
                    outcome = outcome,
                    mode = mode,
                    stale = stale,
                    host = host,
                    modifier = Modifier.weight(1f),
                )

                // 速查挂在最右边：两块正文（待匹配文本 / 结果）才是主体，它是一旁摊开的参考，
                // 关掉之后正文把地方收回去（与时间戳工具同一摆法）。
                if (syntaxOpen) {
                    Spacer(Modifier.width(12.dp))
                    RegexSyntaxPanel(
                        groups = syntaxGroups,
                        onPick = ::pick,
                        modifier = Modifier.width(SyntaxPanelWidth).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

/**
 * 速查入口：点开把右边那块换成速查表，再点收起。
 *
 * 文案跟着状态翻，说的是「按下去会怎样」（与时间戳工具、标题栏那个侧边栏开关同一口径）——两个
 * 入口共用一份状态，不翻的话点开之后分不清开的是哪一个。
 */
@Composable
private fun SyntaxButton(opened: Boolean, onClick: () -> Unit) {
    DevToolButton(title = if (opened) "收起" else "速查", onClick = onClick)
}

/** 左侧一列的标签：等宽，各行内容因此左缘对齐。 */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = MaterialTheme.hintColor,
        modifier = Modifier.width(LabelWidth),
    )
}

/**
 * 结果那一栏。
 *
 * 三种产出共用这一个框：**算出来了**显示结果，**算错了**显示那句错（用错误色），**还没算**空着
 * 并给一句说明。错误与结果占同一块地方是有意的——它俩本就是同一次计算的两种产出，分开放会让人
 * 以为「结果还在，只是旁边多了句话」。
 *
 * @param stale 结果框里那份还对不对得上眼前的输入。对不上时「保存 / 复制 / 只复制匹配内容」全禁掉：
 *   那时拷出去的是**上一份**输入的结果。
 */
@Composable
private fun ResultField(
    outcome: RegexOutcome?,
    mode: RegexMode,
    stale: Boolean,
    host: DevToolHost,
    modifier: Modifier,
) {
    val failed = outcome is RegexOutcome.Failed
    val body = when (val current = outcome) {
        null -> ""
        is RegexOutcome.Failed -> current.message
        is RegexOutcome.Matched -> current.report
        is RegexOutcome.Replaced -> current.text
    }
    val matched = outcome as? RegexOutcome.Matched

    DevToolCodeField(
        label = if (mode == RegexMode.Match) "命中" else "替换结果",
        value = body,
        onValueChange = {},
        editable = false,
        isError = failed,
        // 框空着有两种意思，得分开说：**还没写模式**是「它会给出什么」，**写好了却没有命中**
        // 是「这次的结果就是空」。混用一句会让人以为模式没生效。
        placeholder = when {
            outcome != null && mode == RegexMode.Match -> "没有匹配"
            outcome != null -> "替换结果为空"
            mode == RegexMode.Match -> "写好模式后，这里列出每一处命中的位置与捕获组"
            else -> "写好模式与替换模板后，这里给出替换结果"
        },
        softWrap = true,
        folding = false,
        scan = ::scanPlain,
        modifier = modifier,
        actions = {
            // 「只复制匹配内容」：把命中抽出来是这个工具最常用的用法（抽邮箱、抽 id），而上面那份
            // 报告每一处都带着位置与捕获组，抽出来还得再洗一遍。空命中时不给——那时它拷出来是空。
            if (matched != null && !stale && matched.matches.isNotEmpty()) {
                DevToolFieldAction(
                    kind = ClipperIconKind.TEXT_LINES,
                    tooltip = "只复制匹配内容（每行一条）",
                    onClick = { host.copyToClipboard(matched.matches.joinToString("\n") { it.value }) },
                )
                Spacer(Modifier.width(4.dp))
            }
            // 过期或出错时传空串，两个动作随之禁用（见 `DevToolResultActions`）。
            DevToolResultActions(
                value = if (stale || failed) "" else body,
                host = host,
                suggestedFileName = if (mode == RegexMode.Match) "matches.txt" else "replaced.txt",
            )
        },
    )
}

/**
 * 在后台算出这一次的结果。
 *
 * 全是纯函数，这里只负责把「哪一页、要什么」接上：匹配页排一份报告，替换页先校验模板再替换。
 * 模板先校验是因为那句话能说清是**哪一号组不存在**，而引擎只会抛一句英文的 `Illegal group
 * reference`；既然能先说清，就没必要让引擎去撞一次。
 *
 * 报错文案不在这里拼：`RegexFormat` 抛出来的异常里已经是一句人话（它才知道引擎说了什么、以及
 * 位置该按哪份模式算），这里只管显示。
 */
private fun compute(query: RegexQuery): RegexOutcome {
    val pattern = RegexPattern(query.pattern, query.flags)
    if (query.mode == RegexMode.Match) {
        return RegexFormat.scan(pattern, query.subject).fold(
            onSuccess = {
                RegexOutcome.Matched(
                    matches = it.matches,
                    // 排版也放在后台：两千处命中的位置换算是一遍实打实的扫描，摆进组合里会卡。
                    report = matchReport(query.subject, it.matches, it.truncated),
                    truncated = it.truncated,
                )
            },
            onFailure = { RegexOutcome.Failed(it.message ?: "正则模式无法编译") },
        )
    }
    RegexFormat.templateProblem(pattern, query.template)?.let { return RegexOutcome.Failed(it) }
    return RegexFormat.replace(pattern, query.subject, query.template).fold(
        onSuccess = { RegexOutcome.Replaced(it.text, it.count) },
        onFailure = { RegexOutcome.Failed(it.message ?: "正则模式无法编译") },
    )
}
