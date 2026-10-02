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
import com.qcmian.clipper.devtools.ui.components.DevToolActionSpacer
import com.qcmian.clipper.devtools.ui.components.DevToolButton
import com.qcmian.clipper.devtools.ui.components.DevToolEditor
import com.qcmian.clipper.devtools.ui.components.DevToolMessage

/**
 * JSON 格式化 / 压缩工具，也是插件接口的参考实现。
 *
 * 真正的解析在 [JsonFormat] 里，这里只管交互与排版。
 */
internal object JsonDevTool : DevTool {
    override val metadata: DevToolMetadata = DevToolMetadata(
        id = "json",
        name = "JSON 格式化",
        description = "美化或压缩 JSON，非法输入会报出出错位置。",
        group = DevToolGroup.FORMATTER,
        icon = ClipperIconKind.BRACES,
        keywords = listOf("json", "格式化", "美化", "压缩", "format", "pretty", "minify"),
    )

    override val acceptedDataTypes: Set<String> = setOf(DataTypes.JSON)

    @Composable
    override fun Content(input: ClipItem?, host: DevToolHost) {
        var source by remember { mutableStateOf("") }
        var output by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var note by remember { mutableStateOf<String?>(null) }

        // 每次主面板交进来一份新的剪贴板内容就整块替换：输入、结果与提示都属于「上一份内容」，
        // 留着会让用户以为结果是对新内容算出来的。
        LaunchedEffect(input) {
            val text = input?.devToolText() ?: return@LaunchedEffect
            source = text
            output = ""
            error = null
            note = null
        }

        fun apply(result: Result<String>, success: String) {
            result.fold(
                onSuccess = {
                    output = it
                    error = null
                    note = success
                },
                onFailure = {
                    output = ""
                    error = it.message ?: it::class.simpleName ?: "解析失败"
                    note = null
                },
            )
        }

        Column(Modifier.fillMaxSize()) {
            // 操作栏固定在最上方：输入与结果并排后，按钮留在两列之间既挤窄结果框，
            // 也打断了「先动作、后对照」的阅读顺序。
            Row(verticalAlignment = Alignment.CenterVertically) {
                DevToolButton(
                    title = "格式化",
                    enabled = source.isNotBlank(),
                    primary = true,
                    onClick = { apply(JsonFormat.format(source), "已格式化") },
                )
                DevToolActionSpacer()
                DevToolButton(
                    title = "压缩",
                    enabled = source.isNotBlank(),
                    onClick = { apply(JsonFormat.minify(source), "已压缩") },
                )
                DevToolActionSpacer()
                DevToolButton(
                    title = "复制结果",
                    enabled = output.isNotEmpty(),
                    onClick = { host.copyToClipboard(output) },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${source.length} 字符",
                    fontSize = 11.sp,
                    color = MaterialTheme.hintColor,
                )
            }

            error?.let { DevToolMessage("解析失败：$it", isError = true) }
            note?.let { DevToolMessage(it) }

            Spacer(Modifier.height(8.dp))

            // 输入与结果左右等分，便于逐行对照格式化前后的差异。
            // 两侧都用 [DevToolEditor]（原生 `BasicTextField`）：与 XML 工具、与项目里其它
            // 文本输入同一套控件。曾经换过自绘的代码编辑器（为了折叠），但它把输入法、光标、
            // 选区这些平台能力都变成了自研代码，代价高于折叠带来的收益，因此退回原生控件。
            Row(Modifier.weight(1f)) {
                DevToolEditor(
                    label = "输入",
                    value = source,
                    onValueChange = { source = it },
                    placeholder = "在此粘贴 JSON，或从剪贴板条目打开",
                    modifier = Modifier.weight(1f),
                )

                Spacer(Modifier.width(8.dp))

                DevToolEditor(
                    label = "结果",
                    value = output,
                    readOnly = true,
                    onValueChange = {},
                    placeholder = "格式化 / 压缩的结果会显示在这里",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
