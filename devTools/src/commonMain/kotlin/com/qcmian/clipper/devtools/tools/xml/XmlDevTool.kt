package com.qcmian.clipper.devtools.tools.xml

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
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
 * XML 格式化 / 压缩工具。
 *
 * 与 [com.qcmian.clipper.devtools.tools.json.JsonDevTool] 结构完全一致——它演示的是插件接口的
 * 另一种来源：解析能力来自 kotlinx.serialization 的 XML 格式实现 xmlutil（见 [formatXml]），
 * 而不是 JDK 自带的 `javax.xml`。插件本身仍然是 commonMain 里的一段普通 Compose 代码。
 */
internal object XmlDevTool : DevTool {

    override val metadata: DevToolMetadata = DevToolMetadata(
        id = "xml",
        name = "XML 格式化",
        description = "美化或压缩 XML，非良构的输入会报错。",
        group = DevToolGroup.FORMATTER,
        icon = ClipperIconKind.TAG,
        keywords = listOf("xml", "格式化", "美化", "压缩", "format", "pretty", "minify"),
    )

    override val acceptedDataTypes: Set<String> = setOf(DataTypes.XML)

    @Composable
    override fun Content(input: ClipItem?, host: DevToolHost) {
        var source by remember { mutableStateOf("") }
        var output by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        var note by remember { mutableStateOf<String?>(null) }

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
            DevToolEditor(
                label = "输入",
                value = source,
                onValueChange = { source = it },
                placeholder = "在此粘贴 XML，或从剪贴板条目打开",
                modifier = Modifier.weight(1f),
            )

            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                DevToolButton(
                    title = "格式化",
                    enabled = source.isNotBlank(),
                    primary = true,
                    onClick = { apply(formatXml(source), "已格式化") },
                )
                DevToolActionSpacer()
                DevToolButton(
                    title = "压缩",
                    enabled = source.isNotBlank(),
                    onClick = { apply(minifyXml(source), "已压缩") },
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
