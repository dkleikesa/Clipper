package com.qcmian.clipper.devtools.tools.json

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
import com.qcmian.clipper.devtools.ui.components.DevToolFormatBar
import com.qcmian.clipper.devtools.ui.components.DevToolInputField
import com.qcmian.clipper.devtools.ui.components.DevToolReportSource
import com.qcmian.clipper.devtools.ui.components.DevToolResultActions
import com.qcmian.clipper.devtools.ui.components.DevToolToggle
import com.qcmian.clipper.devtools.ui.components.DevToolTypedSource
import com.qcmian.clipper.devtools.ui.components.FormatMode
import com.qcmian.clipper.devtools.ui.components.rememberFormattedText
import com.qcmian.clipper.core.ui.code.DevToolCodeField
import com.qcmian.clipper.core.ui.code.scanJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        var mode by remember { mutableStateOf(FormatMode.Pretty) }
        // 缩进是**一个**取值：几个空格与制表符是同一件事的两面。原先拆成「空格数 + 是否制表符」
        // 两个状态，于是能拼出「用着制表符、同时还记着 4 空格」这种自相矛盾的状态，界面也只好
        // 在切到制表符时把空格数那一串藏起来——藏起来的那一份恰恰是用户刚挑过的。
        var indent by remember { mutableStateOf(JsonFormat.DefaultIndent) }
        // 排序与缩进、模式都正交：它既不决定合不合法，也不决定空白，只决定键的先后。
        var sortKeys by remember { mutableStateOf(false) }
        // 用户在编辑框里改过内容没有。状态栏据此把来源从「来自剪贴板 / 文件」改成「文本输入」。
        var typed by remember { mutableStateOf(false) }

        // 实时排版交给共用管线（防抖、后台计算、过期判断、状态栏上报，见 `rememberFormattedText`）。
        // 这里只说「怎么排」与「错了怎么说人话」，两个工具的调度口径因此一字不差。
        val formatted = rememberFormattedText(
            source = source,
            options = listOf(mode, indent, sortKeys),
            host = host,
            transform = { text ->
                when (mode) {
                    FormatMode.Pretty -> JsonFormat.format(text, indent, sortKeys)
                    FormatMode.Compact -> JsonFormat.minify(text, sortKeys)
                }
            },
            errorMessage = ::jsonErrorMessage,
        )

        // 来源报告给底部状态栏：改过编辑框就说「文本输入」，否则交回面板判断（剪贴板 / 文件）。
        DevToolReportSource(host, if (typed) DevToolTypedSource else null)

        // 每次主面板交进来一份新的剪贴板内容就整块替换：结果与提示都属于「上一份内容」，
        // 留着会让用户以为结果是对新内容算出来的。先灌正文再 `reset()`——它会按新正文重排一遍。
        LaunchedEffect(input) {
            val item = input ?: return@LaunchedEffect
            // 取文本可能要读文件、也可能要解析富文本——放到后台算，别让主线程在打开面板时先卡一下。
            val text = withContext(Dispatchers.Default) { item.devToolText() }
            source = text
            typed = false
            formatted.reset()
        }

        Column(Modifier.fillMaxSize()) {
            // 操作栏固定在最上方：输入与结果并排后，控件留在两列之间既挤窄结果框，
            // 也打断了「先动作、后对照」的阅读顺序。次序与分组由 `DevToolFormatBar` 统一保证，
            // 这里只补上本工具特有的那个开关。
            Row(verticalAlignment = Alignment.CenterVertically) {
                DevToolFormatBar(
                    mode = mode,
                    onModeChange = { mode = it },
                    indent = indent,
                    indentOptions = JsonFormat.IndentOptions,
                    indentLabel = ::indentLabel,
                    onIndentChange = { indent = it },
                    // 键排序跟「美化 / 压缩」不是一个维度——那两个互斥，这个只是叠在上面的一层修饰。
                    toggles = {
                        DevToolToggle(
                            title = "键排序",
                            checked = sortKeys,
                            onCheckedChange = { sortKeys = it },
                        )
                    },
                )

                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(10.dp))

            // 输入与结果左右等分，便于逐行对照格式化前后的差异。
            // 输入侧走 [DevToolInputField]：文本、拖入 / 打开 / 粘贴的文件都从这一个口子进来
            // （文件当文本读，读不出内容时按来路给提示或留路径）。结果侧仍是只读的代码框。
            // 两侧都用同一套代码框渲染：KodeMirror + 行号、高亮与折叠（高亮/折叠只改显示，
            // `value` 仍是真实文档）。
            // 曾经用过自绘的代码编辑器，它把输入法、光标、选区都变成了自研代码，不划算。
            Row(Modifier.weight(1f)) {
                DevToolInputField(
                    label = "输入",
                    value = source,
                    onValueChange = {
                        source = it
                        typed = true
                    },
                    host = host,
                    placeholder = "在此粘贴 JSON，从剪贴板条目打开，或把文件拖进来",
                    // JSON 的高亮与折叠要认括号，扫描器得跟着这个工具走。
                    scan = ::scanJson,
                    modifier = Modifier.weight(1f),
                )

                Spacer(Modifier.width(8.dp))

                DevToolCodeField(
                    label = "结果",
                    // 失败时错误就显示在结果框里（用错误色）：它是这次解析的产出，与结果同一个位置。
                    // 文案已经自带「第几行 第几列」，不必再前缀「解析失败」。
                    value = formatted.error ?: formatted.output,
                    isError = formatted.error != null,
                    editable = false,
                    onValueChange = {},
                    placeholder = "美化 / 压缩的结果会显示在这里",
                    modifier = Modifier.weight(1f),
                    // 「保存 / 复制」跟着结果走：产出的是这个框里的内容，动作就该在这儿。
                    // 过期时传空串，两个动作随之禁用——那时框里那份不属于眼前的输入，
                    // 存下来或拷出去都是错的。
                    actions = {
                        DevToolResultActions(
                            value = if (formatted.isStale) "" else formatted.output,
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
