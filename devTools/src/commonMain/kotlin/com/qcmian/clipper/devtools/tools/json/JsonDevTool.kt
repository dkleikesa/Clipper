package com.qcmian.clipper.devtools.tools.json

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.devtools.api.DataTypes
import com.qcmian.clipper.devtools.api.DevTool
import com.qcmian.clipper.devtools.api.DevToolGroup
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.DevToolMetadata
import com.qcmian.clipper.devtools.api.devToolText
import com.qcmian.clipper.devtools.api.readTextFileOrNull
import com.qcmian.clipper.devtools.ui.components.DevToolActionSpacer
import com.qcmian.clipper.devtools.ui.components.DevToolButton
import com.qcmian.clipper.devtools.ui.components.DevToolInputActions
import com.qcmian.clipper.devtools.ui.components.DevToolMenuButton
import com.qcmian.clipper.devtools.ui.components.DevToolResultActions
import com.qcmian.clipper.devtools.ui.components.devToolFileDrop
import com.qcmian.clipper.devtools.ui.components.code.DevToolCodeField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 正文停下来多久才重新排版。
 *
 * 这个值只干一件事：让安静窗口比**连打时的击键间隔**长，中间那些多半非法的状态就来不及渲染出来。
 * 排版本身已经挪到后台调度器上（见 `Content` 里的说明），所以它不再是性能参数——拿它跟人打字的
 * 节奏比，而不是跟「算得多慢」比。
 *
 * 取 150ms 是往快的那头靠：本工具的正文多半是整块进来的（从剪贴板条目打开、或直接粘贴），
 * 那种情况没有中间态要压，延迟纯粹在拖后腿，而这正是主路径。手敲是次要路径，其击键间隔一般也
 * 在 150ms 以上，连续输入仍然连不起来。再往下调（100ms 上下）会开始落进快速连打的间隔里，
 * 于是每停一下就闪一次报错——那恰恰是这个窗口要压掉的东西。
 */
private const val FormatDebounceMillis = 150L

/**
 * 结果的排版方式。
 *
 * 它只是一个状态：排版本身是**实时**的（输入停下就重算），按钮用于切换结果面板按哪种方式排。
 */
private enum class JsonResultMode(val title: String) {
    Pretty("美化"),
    Compact("压缩"),
}

/**
 * JSON 格式化 / 压缩工具，也是插件接口的参考实现。
 *
 * 真正的解析在 [JsonFormat] 里，这里只管交互与排版。
 */
internal object JsonDevTool : DevTool {
    override val metadata: DevToolMetadata = DevToolMetadata(
        id = "json",
        name = "JSON 格式化",
        description = "实时美化、压缩或按键排序 JSON，非法输入会报出出错位置。",
        group = DevToolGroup.FORMATTER,
        icon = ClipperIconKind.BRACES,
    )

    override val acceptedDataTypes: Set<String> = setOf(DataTypes.JSON)

    @Composable
    override fun Content(input: ClipItem?, host: DevToolHost) {
        var source by remember { mutableStateOf("") }
        var output by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var mode by remember { mutableStateOf(JsonResultMode.Pretty) }
        // 缩进是**一个**取值：几个空格与制表符是同一件事的两面。原先拆成「空格数 + 是否制表符」
        // 两个状态，于是能拼出「用着制表符、同时还记着 4 空格」这种自相矛盾的状态，界面也只好
        // 在切到制表符时把空格数那一串藏起来——藏起来的那一份恰恰是用户刚挑过的。
        var indent by remember { mutableStateOf(JsonFormat.DefaultIndent) }
        // 排序与缩进、模式都正交：它既不决定合不合法，也不决定空白，只决定键的先后。
        var sortKeys by remember { mutableStateOf(false) }
        // 上一次真正排版用的正文。只有它变了才值得等防抖：换缩进、换模式都是点一下就定的事。
        var laidOutSource by remember { mutableStateOf<String?>(null) }

        // 每次主面板交进来一份新的剪贴板内容就整块替换：结果与提示都属于「上一份内容」，
        // 留着会让用户以为结果是对新内容算出来的。
        LaunchedEffect(input) {
            val text = input?.devToolText() ?: return@LaunchedEffect
            source = text
            output = ""
            error = null
            laidOutSource = null
        }

        // 实时排版：正文变化时重新计时，停下来才真正跑一次（打字过程中不排版）。
        //
        // 防抖只对**正文**生效：缩进与模式是点一下就该出结果的操作，让它们也等这 250ms，按钮就会
        // 显得发木——连点几下加号，每一下都要等，中间还互相把计时器清零。所以正文没变就直接算。
        //
        // 排版本身放到默认调度器上跑：这项工作随文档长度线性涨（1MB 实测解析约 10ms、结果侧高亮
        // 扫描约 25ms），留在组合线程上就是按一次键掉几帧。挪出去之后按键与滚动都不再被它卡住，
        // 防抖的职责也回到它该管的那一件事——打字过程中别让中间态（多半是非法 JSON）闪出来。
        // 换了输入就把这个协程取消掉，正在算的那一份即使算完也自然作废。
        //
        // 失败时把结果清空、改成显示错误本身：两者占的是结果框的同一块地方，留着上一次的结果
        // 也看不见，却会让「复制结果」还能拷出一份与眼前内容不符的东西。
        LaunchedEffect(source, mode, indent, sortKeys) {
            // 本次要排的正文先落到局部：下面要跨一次挂起，回来之后再读状态可能已经是新值了。
            val text = source
            if (text.isBlank()) {
                output = ""
                error = null
                laidOutSource = null
                return@LaunchedEffect
            }
            if (text != laidOutSource) delay(FormatDebounceMillis)
            val result = withContext(Dispatchers.Default) {
                when (mode) {
                    JsonResultMode.Pretty -> JsonFormat.format(text, indent, sortKeys)
                    JsonResultMode.Compact -> JsonFormat.minify(text, sortKeys)
                }
            }
            // 先记下「这次排的是哪份正文」，再落结果：记住的是已经算过的正文，不是刚拿到的那份。
            laidOutSource = text
            result.fold(
                onSuccess = {
                    output = it
                    error = null
                },
                onFailure = {
                    output = ""
                    error = jsonErrorMessage(text, it)
                },
            )
        }

        // 结果框里这一份还对得上当前输入吗。为真有两段：防抖的安静窗口里，以及后台正在算的时候。
        //
        // 它不只是一句提示——「复制结果」必须跟着它一起禁用。否则那 250ms 里按钮是亮的，点下去
        // 拷到的是**上一份**内容的排版结果，正是上面那段注释想在失败分支上避免的事。
        // 输入为空时不算过期：那时框里本来就该是空的，没有什么「旧结果」可言。
        val resultIsStale = source.isNotBlank() && source != laidOutSource

        // 行数 / 字符数与「排版中…」报到窗口底部的状态栏，不再占编辑区上方那一行。
        //
        // 放在 `LaunchedEffect` 里而不是直接调：`reportStatus` 写的是面板的状态，在组合期间写
        // 等于边读边写。键里带上 `output` 与 `error`，结果一落地就把数字换成结果那一侧的量。
        LaunchedEffect(source, output, error, resultIsStale) {
            host.reportStatus(
                when {
                    source.isBlank() -> null
                    resultIsStale -> "排版中…"
                    output.isNotEmpty() -> "结果 ${output.lineCountOf()} 行 · ${output.length} 字符"
                    error != null -> "输入 ${source.lineCountOf()} 行 · ${source.length} 字符"
                    else -> null
                }
            )
        }

        Column(Modifier.fillMaxSize()) {
            // 操作栏固定在最上方：输入与结果并排后，按钮留在两列之间既挤窄结果框，
            // 也打断了「先动作、后对照」的阅读顺序。
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 这两个不是「动作」而是「状态」：点一下切换结果面板的排版方式，当前生效的实心。
                DevToolButton(
                    title = JsonResultMode.Pretty.title,
                    primary = mode == JsonResultMode.Pretty,
                    onClick = { mode = JsonResultMode.Pretty },
                )
                DevToolActionSpacer()
                DevToolButton(
                    title = JsonResultMode.Compact.title,
                    primary = mode == JsonResultMode.Compact,
                    onClick = { mode = JsonResultMode.Compact },
                )
                // 「复制结果」搬去了结果框的标题行：产出的是那个框里的内容，按钮留在工具栏上
                // 会让人先找按钮、再对框（见 `DevToolResultActions`）。这里因此只剩一个更宽的
                // 间隔，把「选模式」和右边那组「决定输出长什么样」的控件分开。
                Spacer(Modifier.width(14.dp))

                // 缩进只影响「美化」（压缩根本没有换行），但这里**不**按模式禁用：缩进是用户的口味
                // 设置，想先设好再切回美化，没道理拦着；菜单按钮上一直显示着当前取值，点了也不会
                // 「没反应」。原先按模式整组变灰，反而逼着用户先切模式、再调缩进、再切回来。
                Text("缩进", fontSize = 12.sp, color = MaterialTheme.hintColor)
                DevToolActionSpacer()
                DevToolMenuButton(
                    label = indentLabel(indent),
                    options = JsonFormat.IndentOptions,
                    selected = indent,
                    optionLabel = ::indentLabel,
                    onSelect = { indent = it },
                )

                Spacer(Modifier.width(10.dp))

                // 开关而不是模式：它跟「美化 / 压缩」不是一个维度——那两个互斥，这个只是叠在上面
                // 的一层修饰，所以摆在缩进旁边、和缩进一起算「输出长什么样」的那组。
                DevToolButton(
                    title = "键排序",
                    primary = sortKeys,
                    onClick = { sortKeys = !sortKeys },
                )

                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(10.dp))

            // 输入与结果左右等分，便于逐行对照格式化前后的差异。
            // 两侧都用 [DevToolCodeField]：**原生 `BasicTextField`** + 叠在它上面的行号、高亮与
            // 折叠（高亮/折叠走 `VisualTransformation`，只改显示，`value` 仍是真实文档）。
            // 曾经用过自绘的代码编辑器，它把输入法、光标、选区都变成了自研代码，不划算。
            Row(Modifier.weight(1f)) {
                DevToolCodeField(
                    label = "输入",
                    value = source,
                    onValueChange = { source = it },
                    placeholder = "在此粘贴 JSON，从剪贴板条目打开，或把文件拖进来",
                    // 拖进来的文件与「打开文件」走同一条读法，读不出内容才退回显示路径——
                    // 与剪贴板里的文件条目完全一致（见 `readTextFileOrNull`）。
                    modifier = Modifier
                        .weight(1f)
                        .devToolFileDrop(host) { paths ->
                            source = paths.joinToString("\n") { readTextFileOrNull(it) ?: it }
                        },
                    actions = { DevToolInputActions(source, { source = it }, host) },
                )

                Spacer(Modifier.width(8.dp))

                DevToolCodeField(
                    label = "结果",
                    // 失败时错误就显示在结果框里（用错误色）：它是这次解析的产出，与结果同一个位置。
                    // 文案已经自带「第几行 第几列」，不必再前缀「解析失败」。
                    value = error ?: output,
                    isError = error != null,
                    readOnly = true,
                    onValueChange = {},
                    placeholder = "美化 / 压缩的结果会显示在这里",
                    modifier = Modifier.weight(1f),
                    // 「保存 / 复制」跟着结果走：产出的是这个框里的内容，动作就该在这儿。
                    // 过期时传空串，两个动作随之禁用——那时框里那份不属于眼前的输入，
                    // 存下来或拷出去都是错的（与工具栏上「复制结果」原先的判据一致）。
                    actions = {
                        DevToolResultActions(
                            value = if (resultIsStale) "" else output,
                            host = host,
                            suggestedFileName = "formatted.json",
                        )
                    },
                )
            }
        }
    }
}

/**
 * 缩进在界面上的写法：按钮与菜单项共用一份，所以菜单里选中的那一项与按钮上的文字永远一致。
 *
 * 制表符写全「制表符」而不是缩写 `Tab`：按钮宽度跟着标签走，而「制表符」与「4 空格」几乎一样
 * 宽，从空格切到制表符时，右边那串字符数就不会左右跳（缩写会跳 17dp 左右）。宽度也够——换成
 * 菜单按钮后整组缩进控件比原来的「加减 + Tab」窄了 50dp 上下，而最小窗口宽下正是这里最紧。
 */
private fun indentLabel(indent: JsonIndent): String = when (indent) {
    is JsonIndent.Spaces -> "${indent.count} 空格"
    JsonIndent.Tab -> "制表符"
}

/**
 * 这段文字占几行。
 *
 * 用 `count { it == '\n' } + 1` 而不是 `lines().size`：后者会为一份 1MB 的文档切出一整个字符串列表，
 * 而状态栏每敲一个键就要问一次。空串算 0 行——「0 行」比「1 行」诚实。
 */
private fun String.lineCountOf(): Int = if (isEmpty()) 0 else count { it == '\n' } + 1
