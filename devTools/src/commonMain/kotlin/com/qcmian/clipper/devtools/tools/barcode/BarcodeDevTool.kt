package com.qcmian.clipper.devtools.tools.barcode

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
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.rememberTextMeasurer
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
import com.qcmian.clipper.devtools.ui.components.DevToolGroupDivider
import com.qcmian.clipper.devtools.ui.components.DevToolInputField
import com.qcmian.clipper.devtools.ui.components.DevToolMenuButton
import com.qcmian.clipper.devtools.ui.components.DevToolReportSource
import com.qcmian.clipper.devtools.ui.components.DevToolSegmentedControl
import com.qcmian.clipper.devtools.ui.components.DevToolSlider
import com.qcmian.clipper.devtools.ui.components.DevToolTypedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 正文停下来多久才重算。与 JSON / Base64 等工具取同一个值。 */
private const val EvaluateDebounceMillis = 150L

/** 输入区高度：与 Base64 工具一致，多行文本一屏能看个大概。 */
private val InputFieldHeight = 120.dp

/**
 * 导出 PNG 的**长边**像素档位；短边按码的形状算（见 `exportSizeOf`）。
 *
 * 给几档而不是一个输入框：码的用途就那么几种（聊天里贴、贴进文档、打印标签），用户不必去猜该填
 * 多少像素，也不必知道「长边」指哪条边。
 */
private val ExportLongSides = listOf(512, 1024, 2048, 4096)

/** 默认这一档。`internal` 是为了让测试跟着它走，而不是把 1024 抄一遍。 */
internal const val DefaultExportLongSide = 1024

/**
 * 一次编码请求：正文加上**全部**影响结果的参数。
 *
 * 结果区据此判断「眼前这份结果还算不算数」。只盯正文的话，切码制 / 改纠错那一瞬间旧结果仍被判成
 * 「就是当前的」，点保存就会存下上一档参数编出来的码。
 */
private data class EncodeRequest(
    val format: BarcodeFormat,
    val text: String,
    val level: QrErrorLevel,
    val aztecEcPercent: Int,
    val pdf417Ec: Pdf417ErrorLevel,
)

/**
 * 一次生成的结果：编得出就是 painter，编不出就是两句交代。
 *
 * 失败刻意分成**两行**而不是拼成一句：第一行是这个码制的**输入要求**（中文、稳定、照着改就行），
 * 第二行是编码器抛出的**原始报错**（英文、具体、常带「实际给了多少」这类细节）。两者各答一个
 * 问题——「该给什么」与「这次为什么不行」；混在一行里只会变成一句中英夹杂、读不完的话。
 *
 * @param requirement 该码制的输入要求，来自 [BarcodeFormat.inputHint]。
 * @param detail 编码器的原始报错；拿不到时为 `null`（那时只显示要求那一行）。
 */
private sealed interface CodeOutcome {
    class Ready(val format: BarcodeFormat, val code: EncodedCode) : CodeOutcome

    class Failed(val requirement: String, val detail: String?) : CodeOutcome
}

/**
 * 条码生成：把一段文本编成二维码或一维码——三种二维码制（QR / Aztec / PDF417）与九种一维码制
 * （Code 128 / 39 / 93、EAN-13 / 8、UPC-A / E、ITF、Codabar），支持导出 PNG 与复制到剪贴板。
 *
 * 与数学计算器同样**不吃某一种数据类型**（[acceptedDataTypes] 留空）：任何文本都可能是要编码的
 * 内容，声明 `text` 只会让打开任意一段文字都默认跳到本工具——那不是我们想要的，所以它只在
 * 侧边栏手动进入（进去后剪贴板内容照旧会被灌进输入框）。
 *
 * 编码与绘制都交给 qrose（见 `BarcodeFormat`）；这里只管交互与排版。编码在后台线程做——一段长
 * 文本的编码要几十毫秒，不该压在组合线程上，编不出来时也在那里接住异常。
 */
internal object BarcodeDevTool : DevTool {

    override val metadata: DevToolMetadata = DevToolMetadata(
        id = "barcode",
        name = "条码生成",
        description = "把文本编成二维码或一维码：QR、Aztec、PDF417 与 Code 128、EAN-13 等，可保存为 PNG。",
        group = DevToolGroup.ENCODER,
        icon = ClipperIconKind.QR_CODE,
    )

    @Composable
    override fun Content(input: ClipItem?, host: DevToolHost) {
        var format by remember { mutableStateOf(BarcodeFormat.Qr) }
        var level by remember { mutableStateOf(QrErrorLevel.Medium) }
        // 另两种二维码的纠错各存一份：三者刻度不同（Aztec 是百分比、PDF417 是 0–8 级，见
        // `AztecEcPercentRange` / `Pdf417ErrorLevel`），来回切码制时各自的取值不该被对方顶掉。
        var aztecEcPercent by remember { mutableStateOf(DefaultAztecEcPercent) }
        var pdf417Ec by remember { mutableStateOf(Pdf417ErrorLevel.Auto) }
        // 一维码下方是否印出内容。默认印：一维标签的惯例就是带上那一行。
        var printText by remember { mutableStateOf(true) }
        var exportSide by remember { mutableStateOf(DefaultExportLongSide) }
        var text by remember { mutableStateOf("") }
        // 用户在编辑框里改过内容没有。状态栏据此把来源从「来自剪贴板 / 文件」改成「文本输入」。
        var typed by remember { mutableStateOf(false) }
        // 防抖之后的正文：它才是拿去编码的那一份。编码放在后台，[outcomeRequest] 记下结果对应的是
        // 哪一份请求——正文或参数一变，旧结果虽然还画着，但已经不对应当前设置了（动作据此禁用）。
        var debounced by remember { mutableStateOf("") }
        var outcome by remember { mutableStateOf<CodeOutcome?>(null) }
        var outcomeRequest by remember { mutableStateOf<EncodeRequest?>(null) }
        // 画「码下方那行文字」要它：预览与导出共用同一份度量，两边才不会两样。
        val measurer = rememberTextMeasurer()
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

        // 生成：正文或任一参数一变就重来一次。整段编码放到后台线程，编码器抛错（长度不对、
        // 字符集不合、内容超容量）也接住，变成结果区里的一句交代，而不是让窗口崩掉。
        LaunchedEffect(debounced, format, level, aztecEcPercent, pdf417Ec) {
            if (debounced.isBlank()) {
                outcome = null
                outcomeRequest = null
                return@LaunchedEffect
            }
            val request = EncodeRequest(format, debounced, level, aztecEcPercent, pdf417Ec)
            val result = withContext(Dispatchers.Default) {
                runCatching { encodeBarcode(format, debounced, level, aztecEcPercent, pdf417Ec) }
                    .fold(
                        onSuccess = { CodeOutcome.Ready(format, it) },
                        onFailure = { error ->
                            CodeOutcome.Failed(
                                requirement = format.inputHint,
                                detail = error.message?.takeIf { it.isNotBlank() }
                                    ?: error::class.simpleName.orEmpty(),
                            )
                        },
                    )
            }
            outcome = result
            outcomeRequest = request
        }

        // 眼前这一份请求（正文 + 参数）。它既决定状态栏说「生成中…」还是报尺寸，也决定「保存 /
        // 复制」能不能点：不等防抖 / 编码回来就动手，存下的（或拷走的）是上一份内容，与 JSON /
        // Base64 工具是同一条口径。
        val request = EncodeRequest(format, text, level, aztecEcPercent, pdf417Ec)
        val fresh = text.isNotBlank() && outcomeRequest == request

        // 码制与尺寸报到窗口底部的状态栏，不占内容区那一行（与其它工具同一分工）。
        LaunchedEffect(outcome, outcomeRequest, request) {
            // 取成局部值再判断：`outcome` 是 `mutableStateOf` 的委托属性，直接对它做类型判断拿不到
            // 智能转换（编译器不保证两次读取之间它没变）。
            val current = outcome
            host.reportStatus(
                when {
                    request.text.isBlank() -> null
                    !fresh -> "生成中…"
                    current is CodeOutcome.Failed -> "无法生成 · ${request.text.length} 字符"
                    current is CodeOutcome.Ready ->
                        "${current.format.title} · ${current.code.sizeLabel} · ${request.text.length} 字符"

                    else -> null
                }
            )
        }

        val canAct = fresh && outcome is CodeOutcome.Ready

        // 结果区与导出**共用**这一份：一维码要印字时它是带文字的那一版（见 `painterFor`），因此
        // 预览里看到的、存下来的、拷走的是同一张图。
        val readyPainter = remember(outcome, printText, measurer) {
            (outcome as? CodeOutcome.Ready)?.code?.painterFor(printText, measurer)
        }

        // 导出：把 painter 栅格化成图再编成 PNG。保存与复制都要它，收成一处；栅格化有十几毫秒
        // 到几十毫秒，放到后台线程，别卡住界面。
        suspend fun exportPng(painter: Painter, longSide: Int): ByteArray? = withContext(Dispatchers.Default) {
            runCatching {
                val size = exportSizeOf(painter, longSide)
                renderPng(painter, size.width, size.height)
            }.getOrNull()
        }

        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 「码制」用下拉而不是分段控件：十三个选项铺成一条轨道根本放不下（窗口收到最窄时
                // 尤其明显），下拉上永远只有当前这一档。
                Text("码制", fontSize = 12.sp, color = MaterialTheme.hintColor)
                DevToolActionSpacer()
                DevToolMenuButton(
                    label = format.title,
                    options = BarcodeFormat.entries,
                    selected = format,
                    optionLabel = { it.title },
                    onSelect = { format = it },
                )

                // 三种二维码都有纠错，但**刻度各不相同**（QR 四档、Aztec 百分比、PDF417 0–8 级），
                // 所以按当前码制摆出它自己那一组，而不是拼成一个统一数值——那会让人以为它们是同一
                // 种东西。一维码没有纠错，那个位置换成「下方是否印字」：两种码制各有一组附加控件，
                // 工具栏因此始终只有一组，不会因为切码制而变成两排。
                when (format) {
                    BarcodeFormat.Qr -> ToolbarOption("纠错") {
                        DevToolSegmentedControl(
                            options = QrErrorLevel.entries,
                            selected = level,
                            optionLabel = { it.title },
                            onSelect = { level = it },
                            // 档名只有一个字，差别写进悬停提示。
                            tooltip = { it.hint },
                        )
                    }

                    BarcodeFormat.Aztec -> ToolbarOption("纠错") {
                        // 百分比是**连续量**，所以给滑杆：切成四档既够不到 23% 这种中间值，也看不出
                        // 它本来是连续的。范围与默认值见 `AztecEcPercentRange`。
                        DevToolSlider(
                            value = aztecEcPercent,
                            range = AztecEcPercentRange,
                            onValueChange = { aztecEcPercent = it },
                            valueLabel = { "$it%" },
                            tooltip = "纠错占符号面积的比例；规范建议至少 23%，越高越抗污损、能装的内容越少",
                        )
                    }

                    BarcodeFormat.Pdf417 -> ToolbarOption("纠错") {
                        // 十档铺不进一条轨道（与「码制」同一个理由），用下拉。
                        DevToolMenuButton(
                            label = pdf417Ec.title,
                            options = Pdf417ErrorLevel.entries,
                            selected = pdf417Ec,
                            optionLabel = { it.menuLabel },
                            onSelect = { pdf417Ec = it },
                        )
                    }

                    else -> ToolbarOption("文本") {
                        DevToolSegmentedControl(
                            options = listOf(false, true),
                            selected = printText,
                            optionLabel = { if (it) "印字" else "不印" },
                            onSelect = { printText = it },
                            tooltip = {
                                if (it) {
                                    "在码下方印出内容——一维标签的惯例（EAN / UPC 的数字就是这么印的）"
                                } else {
                                    "只画条空，不印文字"
                                }
                            },
                        )
                    }
                }

                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(10.dp))

            // 输入区就是普通的多行文本框：码的输入是文本，既没有「另一份输入」也没有方向之分，
            // 所以不像 Base64 那样需要分段控件与来源卡片。
            DevToolInputField(
                label = "输入 · 文本",
                value = text,
                onValueChange = {
                    text = it
                    typed = true
                },
                host = host,
                // 占位提示给的是**示例**（这个码制真收的一个值），不是要求：输入框回答的是「这里
                // 填什么」，「限多少」由结果区（输入为空时）与失败提示交代——EAN-13 那条要求
                // 二十多字，铺在这里既长又答非所问（见 `BarcodeFormat.inputExample`）。
                placeholder = "例如 ${format.inputExample}",
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
                currentFormat = format,
                painter = readyPainter,
                exportSide = exportSide,
                onExportSideChange = { exportSide = it },
                // 输入为空要单独说一声：下面那个 `fresh` 在文本为空时必然为假，若不先判空，
                // 空输入会落进「生成中…」——一句永远不会变的话，看起来像卡住了。
                empty = text.isBlank(),
                fresh = fresh,
                canAct = canAct,
                onSave = {
                    val ready = outcome as? CodeOutcome.Ready ?: return@ResultArea
                    // 导出的就是结果区里画的那一份（一维码可能已套上文字），别另外再取一次。
                    val target = readyPainter ?: return@ResultArea
                    scope.launch {
                        val path = host.pickFileToSave(ready.format.fileName) ?: return@launch
                        val bytes = exportPng(target, exportSide)
                        host.showStatus(
                            when {
                                bytes == null -> "导出失败"
                                writeBytesFile(path, bytes) -> "已保存到 $path"
                                else -> "写不进这个位置：$path"
                            }
                        )
                    }
                },
                onCopy = {
                    val target = readyPainter ?: return@ResultArea
                    scope.launch {
                        val bytes = exportPng(target, exportSide)
                        // 成功那句提示由宿主（面板）给，这里只在导出失败时补一句。
                        if (bytes != null) host.copyImageToClipboard(bytes) else host.showStatus("导出失败")
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

@Composable
private fun ResultArea(
    outcome: CodeOutcome?,
    /** 当前选中的码制；还没有结果时用它当标题。 */
    currentFormat: BarcodeFormat,
    /** 有结果时那份**成品 painter**（一维码印字时已套上下方那一行文字）；没有结果时为 `null`。 */
    painter: Painter?,
    /** 导出尺寸档位，以及改它的入口。 */
    exportSide: Int,
    onExportSideChange: (Int) -> Unit,
    /** 输入为空：没有可编的内容，也就没有「等待」可言——直接给该码制的要求。 */
    empty: Boolean,
    fresh: Boolean,
    canAct: Boolean,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val codeColors = rememberCodeColors()
    val ready = outcome as? CodeOutcome.Ready
    // 画得出码时用白底（见 `renderPng` 的说明），其余状态与别的工具一样用编辑区底色，
    // 免得深色主题下一句浅色的提示文字落在白底上看不见。
    val format = ready?.format ?: currentFormat

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("结果 · ${format.title}", fontSize = 13.sp, color = MaterialTheme.hintColor)
            if (ready != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = ready.code.sizeLabel,
                    fontSize = 12.sp,
                    color = MaterialTheme.hintColor,
                )
            }
            Spacer(Modifier.weight(1f))
            // 导出尺寸放在这一行（保存 / 复制旁边）而不是上面那排编码参数里：它管的是**输出**——
            // 存下来的图有多大。与那两个动作挨着，问「我要存的这张图」时一眼就看到。
            Text("尺寸", fontSize = 12.sp, color = MaterialTheme.hintColor)
            DevToolActionSpacer()
            DevToolMenuButton(
                label = "$exportSide px",
                options = ExportLongSides,
                selected = exportSide,
                optionLabel = { "$it px" },
                onSelect = onExportSideChange,
            )
            DevToolActionSpacer()
            DevToolFieldAction(
                kind = ClipperIconKind.SAVE,
                tooltip = "保存为 PNG（$exportSide px 长边）",
                enabled = canAct,
                onClick = onSave,
            )
            Spacer(Modifier.width(4.dp))
            DevToolFieldAction(
                kind = ClipperIconKind.COPY,
                tooltip = "复制到剪贴板",
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
                .background(if (painter != null) Color.White else codeColors.editorBackground)
                .border(1.dp, colors.outline.copy(alpha = 0.6f), shape)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                // 空态排在最前：`fresh` 判的是「结果对不对得上当前输入」，文本为空时它必然为假，
                // 排在它后面就会被「生成中…」抢走。这里给的这句要求与失败时那行**同一句**，
                // 只是不带错误色——那时还没有「错」，只是还没输入。
                empty -> Text(
                    text = "文本要求：${currentFormat.inputHint}",
                    fontSize = 12.sp,
                    color = MaterialTheme.hintColor,
                    textAlign = TextAlign.Center,
                )

                // `painter` 与 `ready` 同进同出（它就是从 `ready` 那份结果算出来的，见调用点）；
                // 画它而不是 `ready.code.painter`，一维码下方那行文字才在预览里。
                painter != null -> Image(
                    painter = painter,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )

                !fresh -> Text("生成中…", fontSize = 12.sp, color = MaterialTheme.hintColor)

                // 两行：上面是「该给什么」（错误色，是眼下要照做的），下面是「这次为什么不行」
                // （灰一档、小一号，属于细节）。
                outcome is CodeOutcome.Failed -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = "文本要求：${outcome.requirement}",
                        fontSize = 12.sp,
                        color = colors.error,
                        textAlign = TextAlign.Center,
                    )
                    outcome.detail?.takeIf { it.isNotEmpty() }?.let { detail ->
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = detail,
                            fontSize = 11.sp,
                            color = MaterialTheme.hintColor,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                else -> Text(
                    text = "输入文本后，这里显示 ${currentFormat.title}",
                    fontSize = 12.sp,
                    color = MaterialTheme.hintColor,
                )
            }
        }
    }
}

/**
 * 工具栏里「标签 + 一个控件」这一组。
 *
 * 收成一处的理由：分组分隔线、标签、间距三样必须一起出现，各写一遍早晚会漏掉某一样或间距不同
 * （原先只有「码制」一组，看不出这个问题；现在有了纠错 / 文本这一组）。
 */
@Composable
private fun ToolbarOption(label: String, content: @Composable () -> Unit) {
    DevToolGroupDivider()
    Text(label, fontSize = 12.sp, color = MaterialTheme.hintColor)
    DevToolActionSpacer()
    content()
}
