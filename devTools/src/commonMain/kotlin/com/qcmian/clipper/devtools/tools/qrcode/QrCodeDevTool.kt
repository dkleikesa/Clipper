package com.qcmian.clipper.devtools.tools.qrcode

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.ui.code.rememberCodeColors
import com.qcmian.clipper.core.ui.code.scanPlain
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.devtools.api.DevTool
import com.qcmian.clipper.devtools.api.DevToolGroup
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.DevToolMetadata
import com.qcmian.clipper.devtools.api.devToolText
import com.qcmian.clipper.devtools.api.writeBytesFile
import com.qcmian.clipper.devtools.ui.components.DevToolActionSpacer
import com.qcmian.clipper.devtools.ui.components.DevToolFieldAction
import com.qcmian.clipper.devtools.ui.components.DevToolInputField
import com.qcmian.clipper.devtools.ui.components.DevToolReportSource
import com.qcmian.clipper.devtools.ui.components.DevToolSegmentedControl
import com.qcmian.clipper.devtools.ui.components.DevToolTypedSource
import io.github.alexzhirkevich.qrose.ImageFormat
import io.github.alexzhirkevich.qrose.QrCodePainter
import io.github.alexzhirkevich.qrose.options.QrBackground
import io.github.alexzhirkevich.qrose.options.QrBrush
import io.github.alexzhirkevich.qrose.options.QrColors
import io.github.alexzhirkevich.qrose.options.QrErrorCorrectionLevel
import io.github.alexzhirkevich.qrose.options.QrOptions
import io.github.alexzhirkevich.qrose.options.solid
import io.github.alexzhirkevich.qrose.toByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 正文停下来多久才重算。与 JSON / Base64 等工具取同一个值。 */
private const val EvaluateDebounceMillis = 150L

/** 输入区高度：与 Base64 工具一致，多行文本一屏能看个大概。 */
private val InputFieldHeight = 120.dp

/** 导出 PNG 的边长。1024 够清晰，文件也不至于过大。 */
private const val ExportSize = 1024

/**
 * 码元之外还要留出的静区比例。
 *
 * 二维码规范要求四周各留约 4 个码元的空白，不留扫描器可能认不出。这里用 `scale` 把码缩进
 * 画布中央（背景补白），而不是在外面加 padding——**导出的 PNG 也要带着这段白边**，而导出走的是
 * 同一个 painter，只有缩进画布内部才一起带走。
 */
private const val QuietZoneScale = 0.8f

/** 导出时的建议文件名。 */
private const val ExportFileName = "qrcode.png"

/**
 * 可选的四档纠错等级。
 *
 * 不暴露 qrose 的 `Auto`：它只在有 logo 时才升档，没 logo 时等价于 [Low]，摆出来只会让人以为
 * 「自动」比「低」强。四档直接对应规范里的 L / M / Q / H。
 */
private enum class QrLevel(
    val title: String,
    val hint: String,
    val level: QrErrorCorrectionLevel,
) {
    Low("低", "约 7% 面积可损坏，容量最大", QrErrorCorrectionLevel.Low),
    Medium("中", "约 15% 面积可损坏（默认）", QrErrorCorrectionLevel.Medium),
    MediumHigh("较高", "约 25% 面积可损坏", QrErrorCorrectionLevel.MediumHigh),
    High("高", "约 30% 面积可损坏，容量最小", QrErrorCorrectionLevel.High),
}

/** 一次生成的结果：画得出就是 Painter，画不出（文本超容量等）就是一句交代。 */
private sealed interface QrOutcome {
    class Ready(val painter: QrCodePainter) : QrOutcome
    class Failed(val message: String) : QrOutcome
}

/**
 * 二维码生成：把一段文本编成可扫描的二维码，可选纠错等级，支持导出 PNG。
 *
 * 与数学计算器同样**不吃某一种数据类型**（[acceptedDataTypes] 留空）：任何文本都可能是要编码的
 * 内容，声明 `text` 只会让打开任意一段文字都默认跳到二维码工具——那不是我们想要的，所以它只在
 * 侧边栏手动进入（进去后剪贴板内容照旧会被灌进输入框）。
 *
 * 编码与绘制都交给 qrose（见 `libs.versions.toml` 里的 `qrose`）：本工具只负责交互与排版。
 * 编码在后台线程做——一段长文本的编码要几十毫秒，不该压在组合线程上。
 */
internal object QrCodeDevTool : DevTool {

    override val metadata: DevToolMetadata = DevToolMetadata(
        id = "qrcode",
        name = "二维码",
        description = "把文本编成可扫描的二维码，可选纠错等级，支持保存为 PNG。",
        group = DevToolGroup.ENCODER,
        icon = ClipperIconKind.QR_CODE,
    )

    @Composable
    override fun Content(input: ClipItem?, host: DevToolHost) {
        var level by remember { mutableStateOf(QrLevel.Medium) }
        var text by remember { mutableStateOf("") }
        // 用户在编辑框里改过内容没有。状态栏据此把来源从「来自剪贴板 / 文件」改成「文本输入」。
        var typed by remember { mutableStateOf(false) }
        // 防抖之后的正文：它才是拿去编码的那一份。编码放在后台，[outcomeSource] 记下结果对应的是
        // 哪一份正文——正文一变，旧结果虽然还画着，但已经不对应当前输入了（动作据此禁用）。
        var debounced by remember { mutableStateOf("") }
        var outcome by remember { mutableStateOf<QrOutcome?>(null) }
        var outcomeSource by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        // 从剪贴板条目打开时灌入正文——复制一段链接再按快捷键，是这里最顺手的用法。
        LaunchedEffect(input) {
            val item = input ?: return@LaunchedEffect
            // 取文本可能要读文件、也可能要解析富文本——放到后台算，别让主线程在打开面板时先卡一下。
            text = withContext(Dispatchers.Default) { item.devToolText() }
            typed = false
        }

        // 来源报告给底部状态栏：改过编辑框就说「文本输入」，否则交回面板判断（剪贴板 / 文件）。
        DevToolReportSource(host, if (typed) DevToolTypedSource else null)

        // 防抖：正文一变就重新计时，停下来才把这一份交给编码。取消由 `LaunchedEffect` 负责，
        // 因此打字期间不会有半截文本被编出来。
        LaunchedEffect(text) {
            if (text.isBlank()) {
                debounced = ""
                return@LaunchedEffect
            }
            delay(EvaluateDebounceMillis)
            debounced = text
        }

        // 生成：正文或等级一变就重来一次。整段编码放到后台线程，编码器抛错（文本超出容量）也接住，
        // 变成结果区里的一句交代，而不是让窗口崩掉。
        LaunchedEffect(debounced, level) {
            if (debounced.isBlank()) {
                outcome = null
                outcomeSource = null
                return@LaunchedEffect
            }
            val target = debounced
            val result = withContext(Dispatchers.Default) {
                runCatching { QrCodePainter(target, qrOptions(level)) }
                    .fold(
                        onSuccess = { QrOutcome.Ready(it) },
                        onFailure = { QrOutcome.Failed(qrFailureMessage(it)) },
                    )
            }
            outcome = result
            outcomeSource = target
        }

        // 字符数与等级报到窗口底部的状态栏，不占内容区那一行（与其它工具同一分工）。
        LaunchedEffect(outcome, outcomeSource, text, level) {
            host.reportStatus(
                when {
                    text.isBlank() -> null
                    outcomeSource != text -> "生成中…"
                    outcome is QrOutcome.Failed -> "无法生成 · ${text.length} 字符"
                    outcome is QrOutcome.Ready -> "纠错 ${level.title} · ${text.length} 字符"
                    else -> null
                }
            )
        }

        // 眼前这份结果还对得上当前输入吗。它决定「保存 / 复制」能不能点——不等防抖回来就动手，
        // 存下的（或拷走的）是上一份内容，与 JSON / Base64 工具是同一条口径。
        val fresh = text.isNotBlank() && outcomeSource == text
        val canAct = fresh && outcome is QrOutcome.Ready

        // 导出：把 painter 栅格化成图再编成 PNG。保存与复制都要它，收成一处；栅格化有十几毫秒
        // 到几十毫秒，放到后台线程，别卡住界面。
        suspend fun exportPng(painter: QrCodePainter): ByteArray? = withContext(Dispatchers.Default) {
            runCatching { painter.toByteArray(ExportSize, ExportSize, ImageFormat.PNG) }.getOrNull()
        }

        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 「等级」是四选一，与 URL 工具的「规则」同一副长相（一条轨道里几段）。档名只有
                // 一个字，差别写进悬停提示。
                Text("纠错", fontSize = 12.sp, color = MaterialTheme.hintColor)
                DevToolActionSpacer()
                DevToolSegmentedControl(
                    options = QrLevel.entries,
                    selected = level,
                    optionLabel = { it.title },
                    onSelect = { level = it },
                    tooltip = { it.hint },
                )
                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(10.dp))

            // 输入区就是普通的多行文本框：二维码的输入是文本，既没有「另一份输入」也没有方向之分，
            // 所以不像 Base64 那样需要分段控件与来源卡片。
            DevToolInputField(
                label = "输入 · 文本",
                value = text,
                onValueChange = {
                    text = it
                    typed = true
                },
                host = host,
                placeholder = "在此粘贴或输入要生成二维码的文本，如链接、一段文字、Wi-Fi 信息",
                // 长文本折行比横向滚出去好读；折行只改显示，`value` 仍是原样。
                softWrap = true,
                folding = false,
                scan = ::scanPlain,
                // 清空不算「手打」，但也别留着上一档的手打标记。
                onClear = {
                    text = ""
                    typed = false
                },
                modifier = Modifier.fillMaxWidth().height(InputFieldHeight),
            )

            Spacer(Modifier.height(10.dp))

            ResultArea(
                outcome = outcome,
                fresh = fresh,
                level = level,
                canAct = canAct,
                onSave = {
                    val ready = outcome as? QrOutcome.Ready ?: return@ResultArea
                    scope.launch {
                        val path = host.pickFileToSave(ExportFileName) ?: return@launch
                        val bytes = exportPng(ready.painter)
                        host.showStatus(
                            when {
                                bytes == null -> "二维码导出失败"
                                writeBytesFile(path, bytes) -> "已保存到 $path"
                                else -> "写不进这个位置：$path"
                            }
                        )
                    }
                },
                onCopy = {
                    val ready = outcome as? QrOutcome.Ready ?: return@ResultArea
                    scope.launch {
                        val bytes = exportPng(ready.painter)
                        // 成功那句提示由宿主（面板）给，这里只在导出失败时补一句。
                        if (bytes != null) host.copyImageToClipboard(bytes) else host.showStatus("二维码导出失败")
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

/**
 * 二维码的绘制选项。
 *
 * **颜色固定为黑码白底**，不跟主题走：二维码要能被别的设备扫，浅色主题下白底没问题，深色主题下
 * 若把底色改成深色、码改成浅色，不少扫描器会认不出。所以无论深浅，结果区里始终是一张白底黑码的
 * 图——这也是结果卡固定用白底的原因。
 */
private fun qrOptions(level: QrLevel): QrOptions = QrOptions(
    colors = QrColors(
        dark = QrBrush.solid(Color.Black),
        light = QrBrush.solid(Color.White),
    ),
    // 背景补白：配合 `scale` 一起，给码元四周留出静区（见 [QuietZoneScale]）。
    background = QrBackground(fill = SolidColor(Color.White)),
    errorCorrectionLevel = level.level,
    scale = QuietZoneScale,
)

/** 编码失败时给用户的一句交代。绝大多数情况是文本超出这个等级的容量。 */
private fun qrFailureMessage(error: Throwable): String {
    val detail = error.message?.takeIf { it.isNotBlank() } ?: error::class.simpleName.orEmpty()
    return "这条文本放不下二维码：$detail\n试试缩短内容，或把纠错等级调低。"
}

@Composable
private fun ResultArea(
    outcome: QrOutcome?,
    fresh: Boolean,
    level: QrLevel,
    canAct: Boolean,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val codeColors = rememberCodeColors()
    // 画得出二维码时用白底（见 `qrOptions` 的说明），其余状态与别的工具一样用编辑区底色，
    // 免得深色主题下一句浅色的提示文字落在白底上看不见。
    val ready = outcome as? QrOutcome.Ready

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("结果 · 二维码", fontSize = 13.sp, color = MaterialTheme.hintColor)
            if (ready != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "纠错 ${level.title}",
                    fontSize = 12.sp,
                    color = MaterialTheme.hintColor,
                )
            }
            Spacer(Modifier.weight(1f))
            DevToolFieldAction(
                kind = ClipperIconKind.SAVE,
                tooltip = "保存为 PNG",
                enabled = canAct,
                onClick = onSave,
            )
            Spacer(Modifier.width(4.dp))
            DevToolFieldAction(
                kind = ClipperIconKind.COPY,
                tooltip = "复制二维码到剪贴板",
                enabled = canAct,
                onClick = onCopy,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(shape)
                .background(if (ready != null) Color.White else codeColors.editorBackground)
                .border(1.dp, colors.outline.copy(alpha = 0.6f), shape)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                ready != null -> Image(
                    painter = ready.painter,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                !fresh -> Text("生成中…", fontSize = 12.sp, color = MaterialTheme.hintColor)

                outcome is QrOutcome.Failed -> Text(
                    text = outcome.message,
                    fontSize = 12.sp,
                    color = colors.error,
                    textAlign = TextAlign.Center,
                )

                else -> Text(
                    text = "输入文本后，这里显示二维码",
                    fontSize = 12.sp,
                    color = MaterialTheme.hintColor,
                )
            }
        }
    }
}
