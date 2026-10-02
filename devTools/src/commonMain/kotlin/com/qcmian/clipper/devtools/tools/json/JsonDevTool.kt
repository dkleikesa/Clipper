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
import com.qcmian.clipper.devtools.ui.components.DevToolMessage
import com.qcmian.clipper.devtools.ui.components.code.DevToolCodeField
import kotlinx.coroutines.delay

/** 输入停下来多久才重新排版。 */
private const val FormatDebounceMillis = 250L

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
        description = "实时美化或压缩 JSON，非法输入会报出出错位置。",
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
        var mode by remember { mutableStateOf(JsonResultMode.Pretty) }

        // 每次主面板交进来一份新的剪贴板内容就整块替换：结果与提示都属于「上一份内容」，
        // 留着会让用户以为结果是对新内容算出来的。
        LaunchedEffect(input) {
            val text = input?.devToolText() ?: return@LaunchedEffect
            source = text
            output = ""
            error = null
        }

        // 实时排版：每次输入变化都重新计时，停下来才真正跑一次（打字过程中不排版）。
        // 失败时**保留上一次的结果**——边打边清空会让结果面板一直闪，而「哪错了」由错误提示回答。
        LaunchedEffect(source, mode) {
            if (source.isBlank()) {
                output = ""
                error = null
                return@LaunchedEffect
            }
            delay(FormatDebounceMillis)
            val result = when (mode) {
                JsonResultMode.Pretty -> JsonFormat.format(source)
                JsonResultMode.Compact -> JsonFormat.minify(source)
            }
            result.fold(
                onSuccess = {
                    output = it
                    error = null
                },
                onFailure = {
                    error = it.message ?: it::class.simpleName ?: "解析失败"
                },
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

            Spacer(Modifier.height(8.dp))

            // 输入与结果左右等分，便于逐行对照格式化前后的差异。
            // 两侧都用 [DevToolCodeField]：**原生 `BasicTextField`** + 叠在它上面的行号、高亮与
            // 折叠（高亮/折叠走 `VisualTransformation`，只改显示，`value` 仍是真实文档）。
            // 曾经用过自绘的代码编辑器，它把输入法、光标、选区都变成了自研代码，不划算。
            Row(Modifier.weight(1f)) {
                DevToolCodeField(
                    label = "输入",
                    value = source,
                    onValueChange = { source = it },
                    placeholder = "在此粘贴 JSON，或从剪贴板条目打开",
                    modifier = Modifier.weight(1f),
                )

                Spacer(Modifier.width(8.dp))

                DevToolCodeField(
                    label = "结果",
                    value = output,
                    readOnly = true,
                    onValueChange = {},
                    placeholder = "美化 / 压缩的结果会显示在这里",
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
