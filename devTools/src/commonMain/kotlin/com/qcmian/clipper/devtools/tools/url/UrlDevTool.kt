package com.qcmian.clipper.devtools.tools.url

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
import com.qcmian.clipper.devtools.api.DataTypes
import com.qcmian.clipper.devtools.api.DevTool
import com.qcmian.clipper.devtools.api.DevToolGroup
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.DevToolMetadata
import com.qcmian.clipper.devtools.api.devToolText
import com.qcmian.clipper.devtools.detect.UrlDataTypeDetector
import com.qcmian.clipper.devtools.ui.components.DevToolActionSpacer
import com.qcmian.clipper.devtools.ui.components.DevToolGroupDivider
import com.qcmian.clipper.devtools.ui.components.DevToolInputField
import com.qcmian.clipper.devtools.ui.components.DevToolReportSource
import com.qcmian.clipper.devtools.ui.components.DevToolResultActions
import com.qcmian.clipper.devtools.ui.components.DevToolSegmentedControl
import com.qcmian.clipper.devtools.ui.components.DevToolTypedSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 正文停下来多久才重算。与 JSON / Base64 / 数学工具取同一个值。 */
private const val EvaluateDebounceMillis = 150L

/** 输入区高度：与 Base64 工具一致，多行文本一屏能看个大概。 */
private val InputFieldHeight = 120.dp

/** 一次计算的结果。 */
private sealed interface UrlOutcome {
    class Encoded(val value: String) : UrlOutcome
    class Decoded(val value: String) : UrlOutcome
    class Failed(val message: String) : UrlOutcome
}

/**
 * URL 百分号编码 / 解码。
 *
 * 结构与 Base64 工具一致：**方向显式**（编码 / 解码分段控件），两个方向各留一份输入，输入框在上、
 * 结果在下，边打字边重算。差别在结果那一路多了一档「规则」——严格 / URL / 表单，见 [UrlRules]。
 *
 * 无状态的纯逻辑在 [UrlFormat] 里，这里只管交互与排版。
 */
internal object UrlDevTool : DevTool {

    override val metadata: DevToolMetadata = DevToolMetadata(
        id = "url",
        name = "URL 编解码",
        description = "百分号编码与还原：默认严格 RFC 3986，也可按 URL 或表单处理。",
        group = DevToolGroup.ENCODER,
        icon = ClipperIconKind.LINK,
    )

    // 剪贴板里是一整条 URL 时把本工具推荐到最前（探测器认的是带 scheme 的绝对 URL）。
    override val acceptedDataTypes: Set<String> = setOf(DataTypes.URL)

    @Composable
    override fun Content(input: ClipItem?, host: DevToolHost) {
        var mode by remember { mutableStateOf(UrlMode.Encode) }
        // 默认落在最保守的**严格 RFC 3986**：编出来的结果在任何实现里都对得上，不会因为
        // `! * ' ( )` 这些「有的实现编、有的不编」的字符而与别人对不齐（见 [UrlRules]）。
        var rules by remember { mutableStateOf(UrlRules.Strict) }
        // 两个方向**各留一份输入**：编码框里装原文，解码框里装已编码串，它们不是一种东西。
        // 共用一个会让「解码框里的 %E4%B8%AD」被搬进编码框再编一次（变成 %25E4%25B8%25AD），
        // 而且换个方向回来，原来敲的东西已经没了。
        var encodeText by remember { mutableStateOf("") }
        var decodeText by remember { mutableStateOf("") }
        // 按方向各记一份「改过没有」：换到另一边时，状态栏该说的是那一边的情况。
        var encodeTyped by remember { mutableStateOf(false) }
        var decodeTyped by remember { mutableStateOf(false) }
        var outcome by remember { mutableStateOf<UrlOutcome?>(null) }
        // 正在算（防抖的安静窗口里，或后台还没回来）。它决定「复制 / 保存」能不能点：那时框里
        // 留着的是**上一份**结果，拷出去是错的。
        var computing by remember { mutableStateOf(false) }
        // 上一次真正算过的正文。只有它变了才值得等防抖；换方向 / 换规则都是点一下就定的事。
        var computedText by remember { mutableStateOf<String?>(null) }

        // 眼前这个方向正在用哪一份输入。
        val text = if (mode == UrlMode.Encode) encodeText else decodeText
        val typed = if (mode == UrlMode.Encode) encodeTyped else decodeTyped

        // 改**当前方向**那一份输入。`fromUser` 区分「手打的」与「从文件 / 剪贴板搬进来的」——
        // 状态栏只对前者说「文本输入」。
        fun updateText(value: String, fromUser: Boolean) {
            if (mode == UrlMode.Encode) {
                encodeText = value
                encodeTyped = fromUser
            } else {
                decodeText = value
                decodeTyped = fromUser
            }
        }

        DevToolReportSource(host, if (typed) DevToolTypedSource else null)

        // 从剪贴板打开时替用户选好方向：复制的是整条 URL，多半想**看懂**它（解开 %XX）；复制的是
        // 普通文本，多半想把它当参数**编**进去。判据与面板的探测共用同一个（`UrlDataTypeDetector`），
        // 免得「它推荐了、进来方向却相反」。方向仍可随时手切。
        LaunchedEffect(input) {
            val item = input ?: return@LaunchedEffect
            // 取文本可能要读文件、也可能要解析富文本——放到后台算，别让主线程在打开面板时先卡一下。
            val value = withContext(Dispatchers.Default) { item.devToolText() }
            val looksLikeUrl = withContext(Dispatchers.Default) { UrlDataTypeDetector.matches(value) }
            if (looksLikeUrl) {
                mode = UrlMode.Decode
                decodeText = value
                decodeTyped = false
            } else {
                mode = UrlMode.Encode
                encodeText = value
                encodeTyped = false
            }
        }

        // 实时计算：输入一变就重新计时，停下来才算一次。取消由 `LaunchedEffect` 负责——正在算的
        // 那一份即使算完也自然作废。
        LaunchedEffect(mode, rules, text) {
            if (text.isBlank()) {
                outcome = null
                computing = false
                computedText = null
                return@LaunchedEffect
            }
            computing = true
            // 只有手敲正文才等防抖；换方向 / 换规则都是「点一下就定」，立刻重算。
            if (text != computedText) delay(EvaluateDebounceMillis)
            val result = withContext(Dispatchers.Default) { computeOutcome(mode, rules, text) }
            computedText = text
            outcome = result
            computing = false
        }

        // 字符数变化报到窗口底部的状态栏，不占内容区那一行。
        LaunchedEffect(outcome, computing, text.length) {
            host.reportStatus(
                when {
                    computing -> "计算中…"
                    else -> when (val current = outcome) {
                        null -> null
                        is UrlOutcome.Failed -> "无法解码 · ${text.length} 字符"
                        is UrlOutcome.Encoded ->
                            "原文 ${text.length} 字符 → 编码 ${current.value.length} 字符"

                        is UrlOutcome.Decoded ->
                            "编码 ${text.length} 字符 → 原文 ${current.value.length} 字符"
                    }
                }
            )
        }

        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DevToolSegmentedControl(
                    options = UrlMode.entries,
                    selected = mode,
                    optionLabel = { it.title },
                    // 只换方向，不动两边的输入：编码框与解码框各存各的，切来切去都不丢。
                    onSelect = { mode = it },
                )

                DevToolGroupDivider()

                // 「规则」是三选一，与左边那个方向分段控件**同一副长相**（一条轨道里几段）——两处
                // 都是「多选一」，用同一种控件才不会让人以为第二种是别的性质的东西。「规则」这个
                // 标签留着，是为了跟左边那组 tab 区分开。
                Text("规则", fontSize = 12.sp, color = MaterialTheme.hintColor)
                DevToolActionSpacer()
                DevToolSegmentedControl(
                    options = UrlRules.entries,
                    selected = rules,
                    optionLabel = { it.title },
                    onSelect = { rules = it },
                    // 每档的出处与差别放在悬停提示里：tab 上只有短名，铺不下那几句话。
                    tooltip = { it.hint },
                )

                Spacer(Modifier.weight(1f))
            }

            Spacer(Modifier.height(10.dp))

            DevToolInputField(
                label = if (mode == UrlMode.Encode) "输入 · 原文" else "输入 · 已编码",
                value = text,
                onValueChange = { updateText(it, fromUser = true) },
                host = host,
                placeholder = if (mode == UrlMode.Encode) {
                    "在此粘贴要编码的文本，例如查询参数、路径片段"
                } else {
                    "在此粘贴含 %XX 的文本，例如 %E4%B8%AD%E6%96%87"
                },
                // 百分号编码是长串，折行比横向滚出去好读——一行几百个字符要一直往右拖才看得完。
                // 折行只改显示，`value` 仍是那一整行，复制 / 保存拿到的还是原样。
                softWrap = true,
                folding = false,
                scan = ::scanPlain,
                // 清空不算「手打」，但也别留着上一档的手打标记。
                onClear = { updateText("", fromUser = false) },
                modifier = Modifier.fillMaxWidth().height(InputFieldHeight),
            )

            Spacer(Modifier.height(10.dp))

            ResultArea(
                outcome = outcome,
                mode = mode,
                computing = computing,
                host = host,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

/** 在后台算出这一次的结果。编解码本身是纯函数，这里只负责把方向与规则接上。 */
private fun computeOutcome(mode: UrlMode, rules: UrlRules, text: String): UrlOutcome = when (mode) {
    UrlMode.Encode -> UrlOutcome.Encoded(UrlFormat.encode(text, rules))

    UrlMode.Decode -> UrlFormat.decode(text, rules).fold(
        onSuccess = { UrlOutcome.Decoded(it) },
        // 解码只会以 `UrlError` 失败；真出了别的异常，也不该把窗口炸掉。
        onFailure = { error ->
            val urlError = error as? UrlError ?: UrlError(error.message ?: "解码失败", null)
            UrlOutcome.Failed(urlErrorMessage(text, urlError))
        },
    )
}

@Composable
private fun ResultArea(
    outcome: UrlOutcome?,
    mode: UrlMode,
    computing: Boolean,
    host: DevToolHost,
    modifier: Modifier,
) {
    // 正在算的时候框里留着的是上一份结果：能看，但不能拷出去（见 `ResultField`）。
    when (outcome) {
        null -> PlaceholderResult(mode, computing, modifier)

        // 失败时错误就显示在结果框里（用错误色）：它是这次解码的产出，与结果同一个位置。
        // 文案已经自带「第几列」，不必再前缀「解码失败」。
        is UrlOutcome.Failed -> DevToolCodeField(
            label = "结果",
            value = outcome.message,
            onValueChange = {},
            editable = false,
            isError = true,
            softWrap = true,
            folding = false,
            scan = ::scanPlain,
            modifier = modifier,
        )

        is UrlOutcome.Encoded -> ResultField(
            label = "结果 · 编码",
            value = outcome.value,
            suggestedFileName = "url-encoded.txt",
            computing = computing,
            host = host,
            modifier = modifier,
        )

        is UrlOutcome.Decoded -> ResultField(
            label = "结果 · 解码",
            value = outcome.value,
            suggestedFileName = "url-decoded.txt",
            computing = computing,
            host = host,
            modifier = modifier,
        )
    }
}

/** 结果框：只读代码框 + 「保存 / 复制」两个跟着它走的动作。 */
@Composable
private fun ResultField(
    label: String,
    value: String,
    suggestedFileName: String,
    computing: Boolean,
    host: DevToolHost,
    modifier: Modifier,
) {
    DevToolCodeField(
        label = label,
        value = value,
        onValueChange = {},
        editable = false,
        softWrap = true,
        folding = false,
        scan = ::scanPlain,
        modifier = modifier,
        actions = {
            // 正在算时把值传空：`DevToolResultActions` 据此禁用两个动作——那时框里那份不属于眼前
            // 的输入，存下来或拷出去都是错的（与 JSON / Base64 工具同一条口径）。
            DevToolResultActions(
                value = if (computing) "" else value,
                host = host,
                suggestedFileName = suggestedFileName,
            )
        },
    )
}

@Composable
private fun PlaceholderResult(mode: UrlMode, computing: Boolean, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text = when {
                computing -> "计算中…"
                mode == UrlMode.Encode -> "输入文本后，这里显示它的百分号编码"
                else -> "粘贴已编码的文本后，这里显示还原结果"
            },
            fontSize = 12.sp,
            color = MaterialTheme.hintColor,
        )
    }
}
