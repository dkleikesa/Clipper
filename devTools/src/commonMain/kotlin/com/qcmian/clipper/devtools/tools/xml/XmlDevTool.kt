package com.qcmian.clipper.devtools.tools.xml

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.devtools.api.DataTypes
import com.qcmian.clipper.devtools.api.DevTool
import com.qcmian.clipper.devtools.api.DevToolGroup
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.DevToolMetadata
import com.qcmian.clipper.devtools.api.devToolText
import com.qcmian.clipper.devtools.api.readTextFileOrNull
import com.qcmian.clipper.devtools.ui.components.DevToolActionSpacer
import com.qcmian.clipper.devtools.ui.components.DevToolFormatBar
import com.qcmian.clipper.devtools.ui.components.DevToolInputActions
import com.qcmian.clipper.devtools.ui.components.DevToolReportSource
import com.qcmian.clipper.devtools.ui.components.DevToolResultActions
import com.qcmian.clipper.devtools.ui.components.DevToolToggle
import com.qcmian.clipper.devtools.ui.components.DevToolTypedSource
import com.qcmian.clipper.devtools.ui.components.FormatMode
import com.qcmian.clipper.devtools.ui.components.devToolFileDrop
import com.qcmian.clipper.devtools.ui.components.rememberFormattedText
import com.qcmian.clipper.devtools.ui.components.code.DevToolCodeField
import com.qcmian.clipper.devtools.ui.components.code.scanXml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        var mode by remember { mutableStateOf(FormatMode.Pretty) }
        // 缩进只影响「美化」（压缩根本没有换行），但这里同样不按模式禁用：它是用户的口味设置，
        // 想先设好再切回美化没道理拦着（与 JSON 工具同一取舍，见 `JsonDevTool`）。
        var indent by remember { mutableStateOf(XmlFormat.DefaultIndent) }
        // 属性排序与缩进、模式都正交：它既不决定合不合法，也不决定空白，只决定属性的先后。
        var sortAttributes by remember { mutableStateOf(false) }
        // 注释**默认保留**：它是原文里人特意写下的话，在「格式化」这一步顺手丢掉最不该。
        var keepComments by remember { mutableStateOf(true) }
        // 用户在编辑框里改过内容没有。状态栏据此把来源从「来自剪贴板 / 文件」改成「文本输入」。
        var typed by remember { mutableStateOf(false) }

        // 实时排版交给共用管线（与 JSON 工具同一份，见 `rememberFormattedText`）：这里只声明
        // 「怎么排」与「错了怎么说人话」，防抖、后台调度、过期判断、状态栏文案都由此统一。
        val formatted = rememberFormattedText(
            source = source,
            options = listOf(mode, indent, sortAttributes, keepComments),
            host = host,
            transform = { text ->
                when (mode) {
                    FormatMode.Pretty -> XmlFormat.format(text, indent, sortAttributes, keepComments)
                    FormatMode.Compact -> XmlFormat.minify(text, sortAttributes, keepComments)
                }
            },
            errorMessage = { _, error -> xmlErrorMessage(error) },
        )

        // 来源报告给底部状态栏：改过编辑框就说「文本输入」，否则交回面板判断（剪贴板 / 文件）。
        DevToolReportSource(host, if (typed) DevToolTypedSource else null)

        // 每次主面板交进来一份新的剪贴板内容就整块替换：结果与提示都属于「上一份内容」。
        // 先灌正文再 `reset()`——它会按新正文重排一遍。
        LaunchedEffect(input) {
            val item = input ?: return@LaunchedEffect
            // 取文本可能要读文件、也可能要解析富文本——放到后台算，别让主线程在打开面板时先卡一下。
            val text = withContext(Dispatchers.Default) { item.devToolText() }
            source = text
            typed = false
            formatted.reset()
        }

        Column(Modifier.fillMaxSize()) {
            // 操作栏固定在最上方，次序与分组由 `DevToolFormatBar` 统一保证，这里只补上本工具
            // 特有的两个开关。
            Row(verticalAlignment = Alignment.CenterVertically) {
                DevToolFormatBar(
                    mode = mode,
                    onModeChange = { mode = it },
                    indent = indent,
                    indentOptions = XmlFormat.IndentOptions,
                    indentLabel = ::indentLabel,
                    onIndentChange = { indent = it },
                    // 这两个跟「美化 / 压缩」不是一个维度——那两个互斥，它们只是叠在上面的一层修饰。
                    toggles = {
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
                    },
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
                    onValueChange = {
                        source = it
                        typed = true
                    },
                    placeholder = "在此粘贴 XML，从剪贴板条目打开，或把文件拖进来",
                    scan = ::scanXml,
                    // 拖进来的文件与「打开文件」走同一条读法，读不出内容才退回显示路径。
                    modifier = Modifier
                        .weight(1f)
                        .devToolFileDrop(host) { paths ->
                            source = paths.joinToString("\n") { readTextFileOrNull(it) ?: it }
                        },
                    actions = { DevToolInputActions(source, { source = it; typed = true }, host) },
                )

                Spacer(Modifier.width(8.dp))

                DevToolCodeField(
                    label = "结果",
                    // 失败时错误就显示在结果框里（用错误色）：它是这次解析的产出，与结果同一个位置。
                    value = formatted.error ?: formatted.output,
                    isError = formatted.error != null,
                    readOnly = true,
                    onValueChange = {},
                    placeholder = "美化 / 压缩的结果会显示在这里",
                    scan = ::scanXml,
                    modifier = Modifier.weight(1f),
                    // 「保存 / 复制」跟着结果走；过期时传空串，两个动作随之禁用。
                    actions = {
                        DevToolResultActions(
                            value = if (formatted.isStale) "" else formatted.output,
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
