package com.qcmian.clipper.devtools.tools.hash

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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
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
import com.qcmian.clipper.devtools.api.readBytesOrNull
import com.qcmian.clipper.devtools.ui.components.DevToolButton
import com.qcmian.clipper.devtools.ui.components.DevToolFieldAction
import com.qcmian.clipper.devtools.ui.components.DevToolInputField
import com.qcmian.clipper.devtools.ui.components.DevToolInputOrigin
import com.qcmian.clipper.devtools.ui.components.DevToolReportSource
import com.qcmian.clipper.devtools.ui.components.DevToolResultList
import com.qcmian.clipper.devtools.ui.components.DevToolSectionDivider
import com.qcmian.clipper.devtools.ui.components.DevToolSegmentedControl
import com.qcmian.clipper.devtools.ui.components.DevToolTypedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 正文停下来多久才重算。与 JSON / XML / 数学工具取同一个值。 */
private const val EvaluateDebounceMillis = 150L

/** 单次读入的文件上限；与 `readBytesOrNull` 的默认值一致，超过就提示而不是硬读。 */
private val MaxInputFileBytes = 16L * 1024 * 1024

/** 输入区高度：大约四行，够看清短文本，也给下面的结果列表留出地方。 */
private val InputFieldHeight = 96.dp

/**
 * 载入的「非文本」来源：任意文件，或从浏览器一类应用粘贴 / 拖入的图片。
 *
 * 刻意用普通类而不是 `data class`：它的相等性按**引用**算，于是「换了份文件」一眼可辨——
 * `ByteArray` 放进数据类本来也是按引用比较，那样写只会让人误以为在比内容。
 */
private class HashSource(val name: String, val bytes: ByteArray)

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
        // 用户在编辑框里改过内容没有。状态栏据此把来源从「来自剪贴板 / 文件」改成「文本输入」。
        var typed by remember { mutableStateOf(false) }

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
            source = HashSource(path.substringAfterLast('/').ifBlank { path }, bytes)
            typed = false
        }

        // 来源报告给底部状态栏：改过编辑框 / 自己选过文件就说出来，否则交回面板判断。
        DevToolReportSource(host, if (typed) DevToolTypedSource else null)

        // 从剪贴板条目打开时灌入内容：文件类条目按**原始字节**取（二进制正是这里要的），
        // 其余仍按文本取——于是打开一个 .json 或一张 .png 都能直接算。
        LaunchedEffect(input) {
            val item = input ?: return@LaunchedEffect
            val path = item.files.firstOrNull()
            if (path != null) {
                val bytes = withContext(Dispatchers.Default) { readBytesOrNull(path, MaxInputFileBytes) }
                if (bytes != null) {
                    text = ""
                    source = HashSource(path.substringAfterLast('/').ifBlank { path }, bytes)
                    typed = false
                    return@LaunchedEffect
                }
            }
            // 取文本可能要读文件、也可能要解析富文本——放到后台算，别让主线程在打开面板时先卡一下。
            val value = withContext(Dispatchers.Default) { item.devToolText() }
            text = value
            source = null
            typed = false
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

        // 状态栏：右段「内容有多大」之外，顺带说清一次给几种算法，省得去数结果行。
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

        Column(Modifier.fillMaxSize()) {
            // 与 JSON / 数学工具用**同一个**输入框：点击落光标、行号、拖入 / 打开 / 粘贴的文件
            // 因此完全一致。文件侧按字节安置（见 `applyPath`），文本侧就是待摘要的原文。
            DevToolInputField(
                label = "输入",
                value = text,
                onValueChange = {
                    text = it
                    source = null
                    typed = true
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
                    typed = false
                },
                onClear = {
                    text = ""
                    source = null
                },
                sourceCard = source?.let { loaded ->
                    { cardModifier ->
                        HashSourceCard(
                            source = loaded,
                            onReplace = { host.pickFileToOpen()?.let(::applyPath) },
                            onClear = { source = null },
                            modifier = cardModifier,
                        )
                    }
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
 * 载入了文件：用一张卡片替掉输入框，说清楚「这次摘的是这个文件」，并给出**精确字节数**。
 *
 * 卡片上必须留着**换文件**与**回到文本**这两条路：它一旦替掉文本框，用户就没有别的入口了。
 * 与 Base64 的同名卡片一样，底色取编辑框那一块，贴上去像「印在纸上」而不是浮在面板上。
 */
@Composable
private fun HashSourceCard(
    source: HashSource,
    onReplace: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val codeColors = rememberCodeColors()

    Column(
        modifier = modifier
            .clip(shape)
            .background(codeColors.editorBackground)
            .border(1.dp, colors.outline.copy(alpha = 0.6f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("输入 · 文件", fontSize = 13.sp, color = MaterialTheme.hintColor)
            Spacer(Modifier.weight(1f))
            DevToolFieldAction(
                kind = ClipperIconKind.FOLDER,
                tooltip = "更换文件（也可以直接把文件拖进来）",
                onClick = onReplace,
            )
            Spacer(Modifier.width(4.dp))
            DevToolFieldAction(
                kind = ClipperIconKind.TRASH,
                tooltip = "清除文件，回到文本输入",
                onClick = onClear,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = source.name,
            fontSize = 13.sp,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            // 摘要按字节算，这里就给**精确**字节数，不用「KB / MB」那种约数——对不上时正是靠它查错。
            text = "${source.bytes.size} 字节",
            fontSize = 12.sp,
            color = MaterialTheme.hintColor,
        )
    }
}
