package com.qcmian.clipper.devtools.tools.xml

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
import com.qcmian.clipper.devtools.ui.components.DevToolGroupDivider
import com.qcmian.clipper.devtools.ui.components.DevToolInputActions
import com.qcmian.clipper.devtools.ui.components.DevToolMenuButton
import com.qcmian.clipper.devtools.ui.components.DevToolResultActions
import com.qcmian.clipper.devtools.ui.components.DevToolSegmentedControl
import com.qcmian.clipper.devtools.ui.components.DevToolToggle
import com.qcmian.clipper.devtools.ui.components.devToolFileDrop
import com.qcmian.clipper.devtools.ui.components.code.DevToolCodeField
import com.qcmian.clipper.devtools.ui.components.code.scanXml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 正文停下来多久才重新排版。与 JSON 工具取同一个值（见 `JsonDevTool` 里那段说明）。
 */
private const val FormatDebounceMillis = 150L

/**
 * 结果的排版方式。
 *
 * 它只是一个状态：排版本身是**实时**的（输入停下就重算），分段控件用于切换结果面板按哪种方式排。
 */
private enum class XmlResultMode(val title: String) {
    Pretty("美化"),
    Compact("压缩"),
}

/**
 * XML 格式化 / 压缩工具。
 *
 * 与 [com.qcmian.clipper.devtools.tools.json.JsonDevTool] 结构完全一致——它演示的是插件接口的
 * 另一种来源：解析能力来自 kotlinx.serialization 的 XML 格式实现 xmlutil（见 [XmlFormat]），
 * 而不是 JDK 自带的 `javax.xml`。插件本身仍然是 commonMain 里的一段普通 Compose 代码。
 *
 * 与 JSON 工具的差异主要在操作栏的两个开关上：
 *  - XML 没有「键」，属性排序便是它的对应物——元素里属性的先后本就不承载语义（XML 规范如此），
 *    排一排既安全又便于对照；
 *  - 注释**默认保留**（可关）：它是原文里人特意写下的话，格式化这一步顺手丢掉最不该。
 */
internal object XmlDevTool : DevTool {

    override val metadata: DevToolMetadata = DevToolMetadata(
        id = "xml",
        name = "XML 格式化",
        description = "实时美化、压缩或排序属性 XML，非良构的输入会报错。",
        group = DevToolGroup.FORMATTER,
        icon = ClipperIconKind.TAG,
    )

    override val acceptedDataTypes: Set<String> = setOf(DataTypes.XML)

    @Composable
    override fun Content(input: ClipItem?, host: DevToolHost) {
        var source by remember { mutableStateOf("") }
        var output by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var mode by remember { mutableStateOf(XmlResultMode.Pretty) }
        // 缩进只影响「美化」（压缩根本没有换行），但这里同样不按模式禁用：它是用户的口味设置，
        // 想先设好再切回美化没道理拦着（与 JSON 工具同一取舍，见 `JsonDevTool`）。
        var indent by remember { mutableStateOf(XmlFormat.DefaultIndent) }
        // 属性排序与缩进、模式都正交：它既不决定合不合法，也不决定空白，只决定属性的先后。
        var sortAttributes by remember { mutableStateOf(false) }
        // 注释**默认保留**：它是原文里人特意写下的话，在「格式化」这一步顺手丢掉最不该。
        var keepComments by remember { mutableStateOf(true) }
        // 上一次真正排版用的正文。只有它变了才值得等防抖：换缩进、换模式都是点一下就定的事。
        var laidOutSource by remember { mutableStateOf<String?>(null) }

        // 每次主面板交进来一份新的剪贴板内容就整块替换：结果与提示都属于「上一份内容」。
        LaunchedEffect(input) {
            val text = input?.devToolText() ?: return@LaunchedEffect
            source = text
            output = ""
            error = null
            laidOutSource = null
        }

        // 实时排版：正文变化时重新计时，停下来才真正跑一次；缩进与模式是点一下就该出结果的操作，
        // 正文没变就不等（与 JSON 工具逐条对应，理由见 `JsonDevTool` 里那段长注释）。
        //
        // 排版挪到默认调度器上跑：它随文档长度线性涨，留在组合线程上就是按一次键掉几帧。
        LaunchedEffect(source, mode, indent, sortAttributes, keepComments) {
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
                    XmlResultMode.Pretty -> XmlFormat.format(text, indent, sortAttributes, keepComments)
                    XmlResultMode.Compact -> XmlFormat.minify(text, sortAttributes, keepComments)
                }
            }
            // 先记下「这次排的是哪份正文」，再落结果：记住的是已经算过的正文。
            laidOutSource = text
            result.fold(
                onSuccess = {
                    output = it
                    error = null
                },
                onFailure = {
                    output = ""
                    error = xmlErrorMessage(it)
                },
            )
        }

        // 结果框里这一份还对得上当前输入吗。为真有两段：防抖的安静窗口里，以及后台正在算的时候。
        // 失败时把结果清空、改成显示错误本身：两者占的是结果框的同一块地方。
        val resultIsStale = source.isNotBlank() && source != laidOutSource

        // 行数 / 字符数与「排版中…」报到窗口底部的状态栏（与 JSON 工具同一口径）。
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
            // 操作栏固定在最上方，从左到右三组，各是一种语义、各一副长相（见 `DevToolWidgets` 顶部）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 一、多选一：结果面板按哪种方式排。
                DevToolSegmentedControl(
                    options = XmlResultMode.entries,
                    selected = mode,
                    optionLabel = { it.title },
                    onSelect = { mode = it },
                )

                DevToolGroupDivider()

                // 二、取一个值：缩进。描边格子而不是实心按钮——它是「一个字段当前的取值」。
                Text("缩进", fontSize = 12.sp, color = MaterialTheme.hintColor)
                DevToolActionSpacer()
                DevToolMenuButton(
                    label = indentLabel(indent),
                    options = XmlFormat.IndentOptions,
                    selected = indent,
                    optionLabel = ::indentLabel,
                    onSelect = { indent = it },
                )

                DevToolGroupDivider()

                // 三、开关：这两个跟「美化 / 压缩」不是一个维度——那两个互斥，它们只是叠在上面的一层修饰。
                DevToolToggle(
                    title = "属性排序",
                    checked = sortAttributes,
                    onCheckedChange = { sortAttributes = it },
                )
                DevToolActionSpacer()
                DevToolToggle(
                    title = "保留注释",
                    checked = keepComments,
                    onCheckedChange = { keepComments = it },
                )

                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(10.dp))

            // 输入与结果左右等分，便于逐行对照格式化前后的差异。两侧都用 [DevToolCodeField]，
            // 只是扫描器换成 [scanXml]：高亮与折叠因此认得 `<tag>`。
            Row(Modifier.weight(1f)) {
                DevToolCodeField(
                    label = "输入",
                    value = source,
                    onValueChange = { source = it },
                    placeholder = "在此粘贴 XML，从剪贴板条目打开，或把文件拖进来",
                    scan = ::scanXml,
                    // 拖进来的文件与「打开文件」走同一条读法，读不出内容才退回显示路径。
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
                    value = error ?: output,
                    isError = error != null,
                    readOnly = true,
                    onValueChange = {},
                    placeholder = "美化 / 压缩的结果会显示在这里",
                    scan = ::scanXml,
                    modifier = Modifier.weight(1f),
                    // 「保存 / 复制」跟着结果走；过期时传空串，两个动作随之禁用。
                    actions = {
                        DevToolResultActions(
                            value = if (resultIsStale) "" else output,
                            host = host,
                            suggestedFileName = "formatted.xml",
                        )
                    },
                )
            }
        }
    }
}

/**
 * 缩进在界面上的写法：按钮与菜单项共用一份，所以菜单里选中的那一项与按钮上的文字永远一致
 * （与 JSON 工具的 `indentLabel` 同构）。
 */
private fun indentLabel(indent: XmlIndent): String = when (indent) {
    is XmlIndent.Spaces -> "${indent.count} 空格"
    XmlIndent.Tab -> "制表符"
}

/**
 * 把 xmlutil 的报错说成人话。
 *
 * 它一般已经在消息里带上了出错位置（行列或偏移），无需像 JSON 那样自己换算；拿不到消息时退回
 * 异常类名，至少说明「确实失败了」。
 */
private fun xmlErrorMessage(error: Throwable): String =
    error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName ?: "解析失败"

/**
 * 这段文字占几行。
 *
 * 用 `count { it == '\n' } + 1` 而不是 `lines().size`：后者会为一份大文档切出一整个字符串列表，
 * 而状态栏每敲一个键就要问一次。空串算 0 行——「0 行」比「1 行」诚实。
 */
private fun String.lineCountOf(): Int = if (isEmpty()) 0 else count { it == '\n' } + 1
