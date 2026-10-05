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
import com.qcmian.clipper.devtools.ui.components.DevToolTypedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 正文停下来多久才重算。与 JSON / Base64 等工具取同一个值。 */
private const val EvaluateDebounceMillis = 150L

/** 输入区高度：与 Base64 工具一致，多行文本一屏能看个大概。 */
private val InputFieldHeight = 120.dp

/** 导出 PNG 的**长边**像素数；短边按码的形状算（见 `exportSizeOf`）。 */
private const val ExportLongSide = 1024

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
        var text by remember { mutableStateOf("") }
        // 用户在编辑框里改过内容没有。状态栏据此把来源从「来自剪贴板 / 文件」改成「文本输入」。
        var typed by remember { mutableStateOf(false) }
        // 防抖之后的正文：它才是拿去编码的那一份。编码放在后台，[outcomeSource] 记下结果对应的是
        // 哪一份正文——正文一变，旧结果虽然还画着，但已经不对应当前输入了（动作据此禁用）。
        var debounced by remember { mutableStateOf("") }
        var outcome by remember { mutableStateOf<CodeOutcome?>(null) }
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

        // 生成：正文或码制 / 等级一变就重来一次。整段编码放到后台线程，编码器抛错（长度不对、
        // 字符集不合、内容超容量）也接住，变成结果区里的一句交代，而不是让窗口崩掉。
        LaunchedEffect(debounced, format, level) {
            if (debounced.isBlank()) {
                outcome = null
                outcomeSource = null
                return@LaunchedEffect
            }
            val target = debounced
            val result = withContext(Dispatchers.Default) {
                runCatching { encodeBarcode(format, target, level) }
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
            outcomeSource = target
        }

        // 码制与尺寸报到窗口底部的状态栏，不占内容区那一行（与其它工具同一分工）。
        LaunchedEffect(outcome, outcomeSource, text) {
            // 取成局部值再判断：`outcome` 是 `mutableStateOf` 的委托属性，直接对它做类型判断拿不到
            // 智能转换（编译器不保证两次读取之间它没变）。
            val current = outcome
            host.reportStatus(
                when {
                    text.isBlank() -> null
                    outcomeSource != text -> "生成中…"
                    current is CodeOutcome.Failed -> "无法生成 · ${text.length} 字符"
                    current is CodeOutcome.Ready ->
                        "${current.format.title} · ${current.code.sizeLabel} · ${text.length} 字符"

                    else -> null
                }
            )
        }

        // 眼前这份结果还对得上当前输入吗。它决定「保存 / 复制」能不能点——不等防抖回来就动手，
        // 存下的（或拷走的）是上一份内容，与 JSON / Base64 工具是同一条口径。
        val fresh = text.isNotBlank() && outcomeSource == text
        val canAct = fresh && outcome is CodeOutcome.Ready

        // 导出：把 painter 栅格化成图再编成 PNG。保存与复制都要它，收成一处；栅格化有十几毫秒
        // 到几十毫秒，放到后台线程，别卡住界面。
        suspend fun exportPng(painter: Painter): ByteArray? = withContext(Dispatchers.Default) {
            runCatching {
                val size = exportSizeOf(painter, ExportLongSide)
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

                // 纠错只有 QR 有：另外两种二维码的参数形态各不相同（见 `QrErrorLevel`），一律用
                // 编码器的默认值，不在这里凑一个控件。
                if (format == BarcodeFormat.Qr) {
                    DevToolGroupDivider()
                    Text("纠错", fontSize = 12.sp, color = MaterialTheme.hintColor)
                    DevToolActionSpacer()
                    DevToolSegmentedControl(
                        options = QrErrorLevel.entries,
                        selected = level,
                        optionLabel = { it.title },
                        onSelect = { level = it },
                        // 档名只有一个字，差别写进悬停提示。
                        tooltip = { it.hint },
                    )
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
                // 占位提示按码制给：一维码对长度与字符集的要求各不相同，写清楚才不用靠报错去猜。
                placeholder = format.inputHint,
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
                fresh = fresh,
                canAct = canAct,
                onSave = {
                    val ready = outcome as? CodeOutcome.Ready ?: return@ResultArea
                    scope.launch {
                        val path = host.pickFileToSave(ready.format.fileName) ?: return@launch
                        val bytes = exportPng(ready.code.painter)
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
                    val ready = outcome as? CodeOutcome.Ready ?: return@ResultArea
                    scope.launch {
                        val bytes = exportPng(ready.code.painter)
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
            DevToolFieldAction(
                kind = ClipperIconKind.SAVE,
                tooltip = "保存为 PNG",
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
                .background(if (ready != null) Color.White else codeColors.editorBackground)
                .border(1.dp, colors.outline.copy(alpha = 0.6f), shape)
                .padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            when {
                ready != null -> Image(
                    painter = ready.code.painter,
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
