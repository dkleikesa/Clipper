package com.qcmian.clipper.devtools.tools.hash

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
import com.qcmian.clipper.core.ui.code.scanPlain
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.devtools.api.DevTool
import com.qcmian.clipper.devtools.api.DevToolGroup
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.DevToolMetadata
import com.qcmian.clipper.devtools.api.devToolText
import com.qcmian.clipper.devtools.api.readBytesOrNull
import com.qcmian.clipper.devtools.ui.components.DevToolButton
import com.qcmian.clipper.devtools.ui.components.DevToolInputField
import com.qcmian.clipper.devtools.ui.components.DevToolInputOrigin
import com.qcmian.clipper.devtools.ui.components.DevToolResultList
import com.qcmian.clipper.devtools.ui.components.DevToolSectionDivider
import com.qcmian.clipper.devtools.ui.components.DevToolSegmentedControl
import com.qcmian.clipper.devtools.ui.components.DevToolSingleLineField
import com.qcmian.clipper.devtools.ui.components.DevToolSourceCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 正文停下来多久才重算。与 JSON / XML / 数学工具取同一个值。 */
private const val EvaluateDebounceMillis = 150L

/** 单次读入的文件上限；与 `readBytesOrNull` 的默认值一致，超过就提示而不是硬读。 */
private val MaxInputFileBytes = 16L * 1024 * 1024

/**
 * 输入区高度：装得下**两页里更高的那一页**（翻页时占的地方不动）——文件页 = 标题行 + 一条可敲的
 * 路径 + 来源卡片。文本页那边顺带比原来宽松一档。
 */
private val InputFieldHeight = 140.dp

/**
 * 载入的「非文本」来源：任意文件，或从浏览器一类应用粘贴 / 拖入的图片。
 *
 * 刻意用普通类而不是 `data class`：它的相等性按**引用**算，于是「换了份文件」一眼可辨——
 * `ByteArray` 放进数据类本来也是按引用比较，那样写只会让人误以为在比内容。
 *
 * [path] 是磁盘上的**绝对路径**，只在来源真的落在一个文件上时才有（剪贴板里的图片没有）；卡片
 * 与状态栏靠它说清「算的是哪个文件」——光有文件名，同名的两个文件分不出来。
 */
private class HashSource(
    val name: String,
    val bytes: ByteArray,
    val path: String? = null,
)

/**
 * Hash 摘要工具：对文本或任意文件一次算出常用的几种摘要（MD5、SHA-1、SHA-2 全家族、SHA-3 家族）
 * 与非密码学的 CRC32。
 *
 * 与 Base64 工具同一取舍：输入落在**字节**这一层——文本按 UTF-8 取字节，文件按原样取，于是
 * 「一段文字」与「一个二进制文件」在摘要面前是同一件事。这也是它必须复用 `DevToolInputField`
 * 的原因：那一个控件已经把「文本 / 文件 / 图片」三路输入收成一份契约，文件一路还认得二进制。
 */
internal object HashDevTool : DevTool {

    override val metadata: DevToolMetadata = DevToolMetadata(
        id = "hash",
        name = "Hash 摘要",
        description = "对文本或任意文件一次算出 MD5 / SHA-1 / SHA-224 / SHA-256 / SHA-384 / SHA-512 / SHA3 与 CRC32。",
        group = DevToolGroup.GENERATOR,
        icon = ClipperIconKind.FINGERPRINT,
    )

    // 与数学工具同一取舍：任何文本都可能是待摘要的内容，若声明 `text`，打开任意一段文字都会
    // 默认跳到本工具。这里留空，只在侧边栏手动进入。
    override val acceptedDataTypes: Set<String> = emptySet()

    @Composable
    override fun Content(input: ClipItem?, host: DevToolHost) {
        var text by remember { mutableStateOf("") }
        // 文件 / 图片来源：非空时用卡片替掉文本框，摘要算的就是它的原始字节。
        var source by remember { mutableStateOf<HashSource?>(null) }
        var results by remember { mutableStateOf<List<HashResult>>(emptyList()) }
        // 结果写成哪种字符串（十六进制 / Base64 / Base64 URL）。点一下即重算，不等防抖。
        var encoding by remember { mutableStateOf(HashEncoding.Hex) }
        // 结果是对**哪一份输入、哪一种编码**算的。任一变化，旧结果就不作数了（见下面的 `ready`）。
        var resultKey by remember { mutableStateOf<Any?>(null) }
        var resultEncoding by remember { mutableStateOf(HashEncoding.Hex) }
        // 当前输入的字节数，供状态栏报告；放状态里是为了不在每次重组都重新编码一遍正文。
        var inputSize by remember { mutableStateOf(0) }
        // 待对拍的摘要：用户粘进来一段摘要，与上面算出的每一条比，看能不能对上、对上的是哪个算法。
        var compareText by remember { mutableStateOf("") }

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
            source = HashSource(path.substringAfterLast('/').ifBlank { path }, bytes, path)
        }

        // 从剪贴板条目打开时灌入内容：文件类条目按**原始字节**取（二进制正是这里要的），
        // 其余仍按文本取——于是打开一个 .json 或一张 .png 都能直接算。
        LaunchedEffect(input) {
            val item = input ?: return@LaunchedEffect
            val path = item.files.firstOrNull()
            if (path != null) {
                val bytes = withContext(Dispatchers.Default) { readBytesOrNull(path, MaxInputFileBytes) }
                if (bytes != null) {
                    text = ""
                    source = HashSource(path.substringAfterLast('/').ifBlank { path }, bytes, path)
                    return@LaunchedEffect
                }
            }
            // 取文本可能要读文件、也可能要解析富文本——放到后台算，别让主线程在打开面板时先卡一下。
            val value = withContext(Dispatchers.Default) { item.devToolText() }
            text = value
            source = null
        }

        // 实时计算：输入一变就重新计时，停下来才算一次。取消由 `LaunchedEffect` 负责——正在算的
        // 那一份即使算完也自然作废。
        LaunchedEffect(currentKey, encoding) {
            val bytes = source?.bytes ?: text.encodeToByteArray()
            inputSize = bytes.size
            if (bytes.isEmpty()) {
                results = emptyList()
                resultKey = currentKey
                resultEncoding = encoding
                return@LaunchedEffect
            }
            // 只有手敲正文才等防抖；换编码是「点一下就定」，立刻重算（与 Base64 工具同一条口径）。
            if (resultKey != currentKey) delay(EvaluateDebounceMillis)
            results = withContext(Dispatchers.Default) { HashFormat.digests(bytes, encoding) }
            resultKey = currentKey
            resultEncoding = encoding
        }

        // 状态栏：报「内容有多大」，顺带说清一次给几种算法，省得去数结果行。
        LaunchedEffect(inputSize, resultKey, currentKey, resultEncoding, encoding) {
            host.reportStatus(
                when {
                    inputSize == 0 -> null
                    resultKey != currentKey || resultEncoding != encoding -> "计算中…"
                    else -> "$inputSize 字节 · ${HashFormat.algorithms.size} 种算法"
                }
            )
        }

        // 结果对不对得上眼前这份输入与编码：不算完、或还在防抖的安静窗口里，就不是 fresh。
        val ready = inputSize > 0 && resultKey == currentKey &&
            resultEncoding == encoding && results.isNotEmpty()

        // 对拍：输入框里有东西才叫「在比」。结论只在结果作数（`ready`）时才下得——否则比的是上一份
        // 输入、或另一档编码的串，报出来的算法是错的。空输入是「还没对」，与「没对上」不是一回事。
        val comparing = compareText.isNotBlank()
        val match = if (ready && comparing) HashFormat.match(compareText, results, encoding) else null

        Column(Modifier.fillMaxSize()) {
            // 与 JSON / 数学工具用**同一个**输入框：点击落光标、行号、拖入 / 打开 / 粘贴的文件
            // 因此完全一致。文件侧按字节安置（见 `applyPath`），文本侧就是待摘要的原文。
            DevToolInputField(
                // 框名不必再说「文本 / 文件」：标题行里那两页签已经说着了。
                label = "输入",
                value = text,
                onValueChange = {
                    text = it
                    // 一打字就是在用文本那一档：文件来源随之撤掉（两种内容互斥，见 `DevToolInputField`）。
                    source = null
                },
                host = host,
                placeholder = "在此粘贴文本；或拖入 / 打开任意文件（文本、图片、二进制都行）",
                // 摘要可能对着长文本算，折行比横向滚出去好读；它没有块可折，关掉折叠。
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
                    source = HashSource(imageSourceName(origin), bytes)
                },
                // 两页各清各的：清文本不动文件、清文件不动文本——翻回去还能接着用。
                onClear = { text = "" },
                onClearSource = { source = null },
                hasSource = source != null,
                // 文件页那行路径：钉在来源自己身上，工具因此不必另存一份路径字符串。
                sourcePath = source?.path.orEmpty(),
                sourceCard = { cardModifier ->
                    HashSourceCard(source = source, modifier = cardModifier)
                },
                modifier = Modifier.fillMaxWidth().height(InputFieldHeight),
            )

            Spacer(Modifier.height(18.dp))

            // 一条横线把「填什么」与「得到什么」断开——与时间戳工具同一处收束，两个工具因此长得像
            // 同一套界面语言里的东西。
            DevToolSectionDivider()

            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 摘要是同一串字节，写成什么字符串取决于要喂给谁：十六进制 / Base64 / Base64 URL。
                // 点一下即重算，结果整列随之更换（见上面的计算管线）。
                DevToolSegmentedControl(
                    options = HashEncoding.entries,
                    selected = encoding,
                    optionLabel = { it.title },
                    onSelect = { encoding = it },
                )

                Spacer(Modifier.weight(1f))

                // 所有算法一起复制，省得逐个点：格式是「算法名对齐 + 摘要」，粘出去可直接对照。
                // 补齐到 9 列——最长的一档是「SHA3-256」（8 个字符），留一格才不会和摘要连成一片。
                DevToolButton(
                    title = "复制全部",
                    enabled = ready,
                    onClick = {
                        host.copyToClipboard(
                            results.joinToString("\n") {
                                it.algorithm.displayName.padEnd(9) + it.value
                            }
                        )
                    },
                )
            }

            Spacer(Modifier.height(10.dp))

            // 对拍那一行：粘一段外部摘要进来，核一下它对得上上面哪一条、是哪种算法。
            CompareRow(
                value = compareText,
                onValueChange = { compareText = it },
                comparing = comparing,
                hasInput = inputSize > 0,
                ready = ready,
                match = match,
            )

            Spacer(Modifier.height(6.dp))

            // 结果区与时间戳工具共用同一套列表：点一行复制那一行的摘要。空输入、首次计算各给一句
            // 提示；正在算时留着上一份、但点不动（见 `DevToolResultList` 的 `enabled`）。
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    inputSize == 0 -> Text(
                        text = "输入文本或载入文件后，这里给出各算法的摘要",
                        fontSize = 12.sp,
                        color = MaterialTheme.hintColor,
                    )

                    results.isEmpty() -> Text(
                        text = "计算中…",
                        fontSize = 12.sp,
                        color = MaterialTheme.hintColor,
                    )

                    else -> DevToolResultList(
                        items = results,
                        label = { it.algorithm.displayName },
                        value = { it.value },
                        onCopy = { host.copyToClipboard(it.value) },
                        // 对上的那一行上主色：结论那句话说「是哪种算法」，这里把那一行同时指出来，
                        // 眼睛不必在十一行里自己找（见 `CompareRow`）。
                        primary = { it == match },
                        // 摘要很长（SHA-512 有 128 个字符），折行显示，否则尾巴会被省略号吃掉。
                        wrapValues = true,
                        enabled = ready,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/** 图片没有文件名，卡片与状态栏总得有个称呼。按来路分开叫，用户才知道自己粘进来的是什么。 */
private fun imageSourceName(origin: DevToolInputOrigin): String = when (origin) {
    DevToolInputOrigin.Paste -> "剪贴板图片"
    DevToolInputOrigin.Drop -> "拖入的图片"
    DevToolInputOrigin.Open -> "图片"
}

/**
 * Hash 的**文件页**：载入了文件就把「这次摘的是这个文件」摆出来，并给出**精确字节数**。
 * 长相见 [DevToolSourceCard]，绝对路径在它上方那条可敲的路径行里（归 `DevToolInputField`）。
 */
@Composable
private fun HashSourceCard(source: HashSource?, modifier: Modifier) {
    DevToolSourceCard(
        name = source?.name,
        // 摘要按字节算，这里就给**精确**字节数，不用「KB / MB」那种约数——对不上时正是靠它查错。
        detail = source?.let { "${it.bytes.size} 字节" },
        emptyHint = "把文件拖进来，或打开 / 粘贴一个文件（文本、图片、二进制都行）",
        modifier = modifier,
    )
}

/** 结论那一格占的固定宽度：宽窄不随文案跳，右边的输入框因此不会在用户开始对拍时忽然缩一下。 */
private val CompareVerdictWidth = 124.dp

/**
 * 「对拍」那一行：左边一个单行输入框，粘一段摘要进来；右边是结论。
 *
 * 结论只在**既有输入、又有待对拍内容**时才出现——两者缺一都无从谈起：没有输入就没有可比的摘要，
 * 没有待对拍内容则根本没开始比。中间「结果还没算完」时给一句「对拍中…」，不拿上一份结果下结论。
 *
 * @param comparing 输入框里有没有东西要拿来比。
 * @param hasInput 上面那份待摘要的内容是不是空的（空则没有可对的摘要）。
 * @param ready 算出的结果是否作数（见 `HashDevTool.Content`）。
 * @param match 对上的那一条结果；没对上、或还没对时为 `null`。
 */
@Composable
private fun CompareRow(
    value: String,
    onValueChange: (String) -> Unit,
    comparing: Boolean,
    hasInput: Boolean,
    ready: Boolean,
    match: HashResult?,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("对比", fontSize = 12.sp, color = MaterialTheme.hintColor)
        Spacer(Modifier.width(8.dp))
        DevToolSingleLineField(
            value = value,
            onValueChange = onValueChange,
            placeholder = "粘贴一个摘要，核对它对得上下面哪一条",
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.width(CompareVerdictWidth), contentAlignment = Alignment.CenterStart) {
            CompareVerdict(comparing = comparing, hasInput = hasInput, ready = ready, match = match)
        }
    }
}

/** 对拍的结论：成功时报出**是哪种算法**，失败说「没对上」，还没算完说「对拍中」。 */
@Composable
private fun CompareVerdict(comparing: Boolean, hasInput: Boolean, ready: Boolean, match: HashResult?) {
    val colors = MaterialTheme.colorScheme
    when {
        // 还没开始比：右侧留白，不先摆一句「不匹配」出来（那是「比过但没对上」的意思）。
        !comparing || !hasInput -> Unit

        !ready -> Text("对拍中…", fontSize = 12.sp, color = MaterialTheme.hintColor)

        match != null -> Row(verticalAlignment = Alignment.CenterVertically) {
            ClipperIcon(ClipperIconKind.CHECKMARK, size = 14.dp, tint = colors.primary)
            Spacer(Modifier.width(5.dp))
            Text("匹配 · ${match.algorithm.displayName}", fontSize = 12.sp, color = colors.primary)
        }

        else -> Text("没有匹配的算法", fontSize = 12.sp, color = colors.error)
    }
}
