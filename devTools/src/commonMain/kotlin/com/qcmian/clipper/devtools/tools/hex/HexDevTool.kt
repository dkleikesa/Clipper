package com.qcmian.clipper.devtools.tools.hex

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.qcmian.clipper.core.ui.code.DevToolCodeField
import com.qcmian.clipper.core.ui.code.scanPlain
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.devtools.api.DevTool
import com.qcmian.clipper.devtools.api.DevToolGroup
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.DevToolMetadata
import com.qcmian.clipper.devtools.api.devToolText
import com.qcmian.clipper.devtools.api.readBytesOrNull
import com.qcmian.clipper.devtools.ui.components.DevToolActionSpacer
import com.qcmian.clipper.devtools.ui.components.DevToolGroupDivider
import com.qcmian.clipper.devtools.ui.components.DevToolInputField
import com.qcmian.clipper.devtools.ui.components.DevToolInputOrigin
import com.qcmian.clipper.devtools.ui.components.DevToolMenuButton
import com.qcmian.clipper.devtools.ui.components.DevToolResultActions
import com.qcmian.clipper.devtools.ui.components.DevToolSourceCard
import com.qcmian.clipper.devtools.ui.components.DevToolToggle
import com.qcmian.clipper.devtools.ui.components.imageInputName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 正文停下来多久才重排。与 JSON / XML / Base64 / Hash 取同一个值。 */
private const val EvaluateDebounceMillis = 150L

/** 单次读入的文件上限；与 `readBytesOrNull` 的默认值一致，超过就提示而不是硬读。 */
private val MaxInputFileBytes = 16L * 1024 * 1024

/**
 * 输入区高度：与 Base64 / Hash 一致，多行文本一屏能看个大概。
 *
 * 这一份要装下**两页里更高的那一页**（翻页时占的地方不动）：文件页 = 标题行 + 一条可敲的路径
 * + 来源卡片。
 */
private val InputFieldHeight = 160.dp

/**
 * 载入的「非文本」来源：任意文件，或从浏览器一类应用粘贴 / 拖入的图片。
 *
 * 刻意用普通类而不是 `data class`：它的相等性按**引用**算，于是「换了份文件 / 换了张图」一眼可辨
 * ——`ByteArray` 放进数据类本来也是按引用比较，那样写只会让人误以为在比值。
 *
 * [path] 是磁盘上的**绝对路径**，只在来源真的落在一个文件上时才有（剪贴板里的图片没有）；卡片
 * 与状态栏靠它说清「看的是哪个文件」——光有文件名，同名的两个文件分不出来。
 */
private class HexSource(
    val name: String,
    val bytes: ByteArray,
    val path: String? = null,
)

/**
 * 影响 dump 版面的三个选项，打成一个值。
 *
 * 做成 `data class` 是为了让「这一次排的是哪套版面」能按值比较（见 `computedOptions`）：换档之后
 * 旧结果立刻不算数，不必等后台那份算完。
 */
private data class HexOptions(
    val rowWidth: HexRowWidth,
    val uppercase: Boolean,
    val showCharColumn: Boolean,
)

/**
 * 二进制查看：把文本或任意文件的每个字节排成「偏移量 + 十六进制 + 字符」三列。
 *
 * 与 Base64 / Hash 工具同一取舍：输入落在**字节**这一层——文本按 UTF-8 取字节，文件按原样取，
 * 于是「一段文字」与「一个二进制文件」在这里是同一件事。这也是它复用 `DevToolInputField` 的原
 * 因：那一个控件已经把「文本 / 文件 / 图片」三路输入收成一份契约，文件一路还认得二进制。
 *
 * [acceptedDataTypes] 留空（与数学 / Hash / 正则工具同一取舍）：二进制文件的类型探测看不见
 * （探测器判的是文本），声明 `text` 又会让打开任意一段文字都跳到本工具。它只在侧边栏手动进入；
 * 带进来的内容照旧会被灌进输入框，图片会直接按字节当来源。
 */
internal object HexDevTool : DevTool {

    override val metadata: DevToolMetadata = DevToolMetadata(
        id = "hex",
        name = "二进制查看",
        description = "按偏移量把文本或任意文件的每个字节排成十六进制与字符两列，可调每行字节数。",
        group = DevToolGroup.CONVERTER,
        icon = ClipperIconKind.BINARY,
    )

    override val acceptedDataTypes: Set<String> = emptySet()

    @Composable
    override fun Content(input: ClipItem?, host: DevToolHost) {
        var text by remember { mutableStateOf("") }
        // 文件 / 图片来源：非空时用卡片替掉文本框，dump 的就是它的原始字节。
        var source by remember { mutableStateOf<HexSource?>(null) }
        // 三个版面选项。点一下就重排，不等防抖。
        var uppercase by remember { mutableStateOf(false) }
        var showCharColumn by remember { mutableStateOf(true) }
        var rowWidth by remember { mutableStateOf(HexRowWidth.Sixteen) }

        var dump by remember { mutableStateOf<HexDump?>(null) }
        // 当前输入的字节数，供状态栏报告与空态判断；放状态里是为了不在每次重组都重新编码正文。
        var bytesSize by remember { mutableStateOf(0) }
        // 眼前这份 dump 是对**哪一份输入、哪一套版面**排的。任一变化，旧结果就不作数了（见 `ready`）。
        var computedKey by remember { mutableStateOf<Any?>(null) }
        var computedOptions by remember { mutableStateOf<HexOptions?>(null) }

        val options = HexOptions(rowWidth, uppercase, showCharColumn)
        // 当前这一份输入的标识：文件来源优先（卡片顶上时文本框是空的）。
        val currentKey: Any? = source ?: text

        // 把一条路径读成字节并顶上文件卡片。读不出（超限、不是普通文件）时只提示，不动现状。
        fun applyPath(path: String) {
            val bytes = readBytesOrNull(path, MaxInputFileBytes)
            if (bytes == null) {
                host.showStatus("读不了这个文件（超过上限，或不是普通文件）：$path")
                return
            }
            text = ""
            source = HexSource(path.substringAfterLast('/').ifBlank { path }, bytes, path)
        }

        // 从剪贴板条目打开时灌入内容：图片与文件类条目按**原始字节**取（二进制正是这里要的），
        // 其余仍按文本取——于是打开一张 .png 或一个 .json 都能直接看。
        LaunchedEffect(input) {
            val item = input ?: return@LaunchedEffect
            val image = item.image
            if (image != null && item.files.isEmpty()) {
                text = ""
                source = HexSource(imageInputName(DevToolInputOrigin.Paste), image.toByteArray())
                return@LaunchedEffect
            }
            val path = item.files.firstOrNull()
            if (path != null) {
                val bytes = withContext(Dispatchers.Default) { readBytesOrNull(path, MaxInputFileBytes) }
                if (bytes != null) {
                    text = ""
                    source = HexSource(path.substringAfterLast('/').ifBlank { path }, bytes, path)
                    return@LaunchedEffect
                }
            }
            // 取文本可能要读文件、也可能要解析富文本——放到后台算，别让主线程在打开面板时先卡一下。
            val value = withContext(Dispatchers.Default) { item.devToolText() }
            text = value
            source = null
        }

        // 实时排版：输入或版面一变就重排，取消由 `LaunchedEffect` 负责——正在排的那一份即使排完
        // 也自然作废。正文先落到局部再跨挂起：回来时读状态可能已经是新值了。
        LaunchedEffect(currentKey, options) {
            val loaded = source
            val body = text
            val bytes = withContext(Dispatchers.Default) { loaded?.bytes ?: body.encodeToByteArray() }
            bytesSize = bytes.size
            if (bytes.isEmpty()) {
                dump = null
                computedKey = currentKey
                computedOptions = options
                return@LaunchedEffect
            }
            // 只有手敲正文才等防抖；载入文件、换选项都是「点一下就定」，立刻重排。
            if (computedKey != currentKey) delay(EvaluateDebounceMillis)
            val value = withContext(Dispatchers.Default) {
                HexFormat.dump(
                    bytes = bytes,
                    bytesPerRow = options.rowWidth.bytesPerRow,
                    uppercase = options.uppercase,
                    showCharColumn = options.showCharColumn,
                )
            }
            dump = value
            computedKey = currentKey
            computedOptions = options
        }

        // dump 对不对得上眼前这份输入与版面：没排完、或还在防抖的安静窗口里，就不是 fresh。
        val ready = bytesSize > 0 && dump != null &&
            computedKey == currentKey && computedOptions == options

        // 状态栏：报「多大、排了多少行」，被截断时如实说明只排了前一段。
        LaunchedEffect(bytesSize, currentKey, options, computedKey, computedOptions, dump) {
            val current = dump
            host.reportStatus(
                when {
                    bytesSize == 0 -> null
                    !ready || current == null -> "排版中…"
                    current.truncated ->
                        "共 ${current.totalBytes} 字节 · 已排前 ${current.shownBytes} 字节 · ${current.rows} 行"

                    else -> "${current.totalBytes} 字节 · ${current.rows} 行"
                }
            )
        }

        Column(Modifier.fillMaxSize()) {
            // 与 JSON / Hash 工具用**同一个**输入框：点击落光标、行号、拖入 / 打开 / 粘贴的文件因此
            // 完全一致。文件侧按字节安置（见 `applyPath`），文本侧就是待查看的原文。
            DevToolInputField(
                // 框名不必再说「文本 / 文件」：标题行里那两页签已经说着了。
                label = "输入",
                value = text,
                onValueChange = {
                    text = it
                    // 一打字就是在用文本那一档：来源随之撤掉（两种内容互斥，见 `DevToolInputField`）。
                    source = null
                },
                host = host,
                placeholder = "在此粘贴文本；或拖入 / 打开任意文件（图片、可执行文件、任意二进制）",
                // 输入侧的文本会折行，看起来更顺；它本身没有块可折，关掉折叠。
                softWrap = true,
                folding = false,
                scan = ::scanPlain,
                // 文件一律按原始字节读：二进制正是这里要的，所以不走默认的「读成文本」。
                // 返回空串表示「已经安置好了」——输入框不必再往正文里填东西，也吞掉这次粘贴。
                onFiles = { paths, _ ->
                    paths.firstOrNull()?.let(::applyPath)
                    ""
                },
                // 从浏览器一类应用粘贴 / 拖入的图片没有磁盘路径，只有字节——同样直接当来源。
                onImage = { bytes, origin ->
                    text = ""
                    source = HexSource(imageInputName(origin), bytes)
                },
                // 两页各清各的：清文本不动文件、清文件不动文本——翻回去还能接着用。
                onClear = { text = "" },
                onClearSource = { source = null },
                hasSource = source != null,
                // 文件页那行路径：钉在来源自己身上，工具因此不必另存一份路径字符串。
                sourcePath = source?.path.orEmpty(),
                sourceCard = { cardModifier ->
                    HexSourceCard(source = source, modifier = cardModifier)
                },
                modifier = Modifier.fillMaxWidth().height(InputFieldHeight),
            )

            Spacer(Modifier.height(14.dp))

            // 版面选项：每行字节数（取值格，点开一列选项）、大小写与字符列（两个开关）。次序沿用
            // 工具栏那条规则——取值在左、开关在右，组间一条竖线断开（见 `DevToolWidgets`）。
            Row(verticalAlignment = Alignment.CenterVertically) {
                DevToolMenuButton(
                    label = rowWidth.title,
                    options = HexRowWidth.entries,
                    selected = rowWidth,
                    optionLabel = { it.title },
                    onSelect = { rowWidth = it },
                )

                DevToolGroupDivider()

                DevToolToggle(
                    title = "大写",
                    checked = uppercase,
                    onCheckedChange = { uppercase = it },
                )
                DevToolActionSpacer()
                DevToolToggle(
                    title = "字符列",
                    checked = showCharColumn,
                    onCheckedChange = { showCharColumn = it },
                )

                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(10.dp))

            Box(Modifier.fillMaxWidth().weight(1f)) {
                val current = dump
                when {
                    bytesSize == 0 -> Text(
                        text = "输入文本或载入文件后，这里按偏移列出每个字节的十六进制与字符",
                        fontSize = 12.sp,
                        color = MaterialTheme.hintColor,
                    )

                    current == null -> Text(
                        text = "排版中…",
                        fontSize = 12.sp,
                        color = MaterialTheme.hintColor,
                    )

                    else -> DevToolCodeField(
                        // 偏移量已经在正文里了，行号再来一列只会让人对不上「第几行」是哪个意思。
                        label = "结果 · 十六进制",
                        value = current.text,
                        onValueChange = {},
                        editable = false,
                        lineNumbers = false,
                        folding = false,
                        // 一行一条记录，折行会把「一行 16 字节」这个结构弄没——横向滚出去更好读。
                        softWrap = false,
                        scan = ::scanPlain,
                        modifier = Modifier.fillMaxSize(),
                        // 「保存 / 复制」跟着结果走；还没排完时传空串，两个动作随之禁用——那时框里
                        // 那份属于上一套版面 / 上一份输入，存下来或拷出去都是错的。
                        actions = {
                            DevToolResultActions(
                                value = if (ready) current.text else "",
                                host = host,
                                suggestedFileName = "dump.txt",
                            )
                        },
                    )
                }
            }
        }
    }
}

/**
 * Hex 的**文件页**：载入了文件 / 图片就把「这次看的是这个来源」摆出来，并给出**精确字节数**。
 * 长相见 [DevToolSourceCard]，绝对路径在它上方那条可敲的路径行里（归 `DevToolInputField`）。
 */
@Composable
private fun HexSourceCard(source: HexSource?, modifier: Modifier) {
    DevToolSourceCard(
        name = source?.name,
        // 按字节看，这里就给**精确**字节数，不用「KB / MB」那种约数——对不上时正是靠它查错。
        detail = source?.let { "${it.bytes.size} 字节" },
        emptyHint = "把文件拖进来，或打开 / 粘贴一个文件（图片、可执行文件、任意二进制）",
        modifier = modifier,
    )
}
