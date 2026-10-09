package com.qcmian.clipper.devtools.tools.cert

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import com.qcmian.clipper.devtools.ui.components.DevToolActionSpacer
import com.qcmian.clipper.devtools.ui.components.DevToolInputField
import com.qcmian.clipper.devtools.ui.components.DevToolResultActions
import com.qcmian.clipper.devtools.ui.components.DevToolSingleLineField
import com.qcmian.clipper.devtools.ui.components.DevToolSourceCard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 正文停下来多久才重算。与 JSON / Base64 / URL 工具取同一个值。 */
private const val ParseDebounceMillis = 150L

/** 顶部输入区的高度：够贴一张 PEM 或一段 Base64，又给下面的分栏留下大半屏。 */
private val InputFieldHeight = 140.dp

/** 下方左右两栏之间的间隔。 */
private val ColumnGap = 12.dp

/** 口令框的宽度。 */
private val PasswordFieldWidth = 260.dp

/**
 * 证书查看。
 *
 * 版面是**上一下二**：顶上一条输入（文本 / 文件两页，见 `DevToolInputField`），下面左右分栏——
 * 左边是解析出来的 **PEM 原文**（`证书原文`），右边是 **X.509 字段**（`证书信息`）。一眼对照
 * 「这句话对应证书里的哪一段」是这张版面存在的理由：原文与字段并排，不是上下叠着。
 *
 * 输入支持得广：文本这一路吃 PEM（可含多张、可混别的块）与裸 Base64（DER）；文件那一路再补上
 * **DER / PKCS#7 / PKCS#12 / JKS**——它们是二进制，粘不进来，只能走文件。密钥库要口令时才露出
 * 那个口令框（判据来自解析结果，不看扩展名，见 [CertParseResult.Failed.needsPassword]）。
 *
 * 解析与排版全在 [CertFormat]（纯逻辑）与 `CertParse.jvm.kt`（平台）里，这里只管交互与版面。
 */
internal object CertDevTool : DevTool {

    override val metadata: DevToolMetadata = DevToolMetadata(
        id = "cert",
        name = "证书查看",
        description = "解析证书、证书请求、吊销列表与密钥：PEM / DER / PKCS#7 / PKCS#12 / JKS，" +
            "左边看原文、右边看字段。",
        group = DevToolGroup.ENCODER,
        icon = ClipperIconKind.SHIELD,
    )

    // 剪贴板里是一张证书时把本工具推荐到最前。文本页什么都能收（用户可能粘的是 Base64 或别的东西），
    // 所以与 Base64 / URL 同一条口径：`acceptsAnyInput` 为真。
    override val acceptedDataTypes: Set<String> = setOf(DataTypes.CERT)
    override val acceptsAnyInput: Boolean = true

    @Composable
    override fun Content(input: ClipItem?, host: DevToolHost) {
        var sourceText by remember { mutableStateOf("") }
        var sourcePath by remember { mutableStateOf("") }
        var hasFileSource by remember { mutableStateOf(false) }
        var password by remember { mutableStateOf("") }
        var outcome by remember { mutableStateOf<CertParseResult?>(null) }
        // 正在解析（防抖的安静窗口里，或后台还没回来）。它决定「复制 / 保存」能不能点：那时框里
        // 留着的是**上一份**结果，拷出去是错的。
        var computing by remember { mutableStateOf(false) }
        // 上一次真正解析过的正文。只有它变了才值得等防抖；换文件 / 改口令都是点一下就定的事。
        var parsedText by remember { mutableStateOf<String?>(null) }

        fun takeFile(path: String) {
            sourcePath = path
            hasFileSource = true
        }

        // 从剪贴板打开时替用户灌好输入：**文件条目走文件页**（二进制粘不进来），其余按文本灌。
        LaunchedEffect(input) {
            val item = input ?: return@LaunchedEffect
            val files = item.files
            if (files.isNotEmpty()) {
                takeFile(files.first())
                return@LaunchedEffect
            }
            // 取文本可能要读文件、也可能要解析富文本——放到后台算，别让主线程在打开面板时先卡一下。
            val value = withContext(Dispatchers.Default) { item.devToolText() }
            if (value.isNotBlank()) sourceText = value
        }

        // 实时解析：输入一变就重新计时，停下来才算一次。取消由 `LaunchedEffect` 负责——正在算的
        // 那一份即使算完也自然作废。
        LaunchedEffect(hasFileSource, sourcePath, password, sourceText) {
            // 空串与「没填」是同一件事，统一成 `null` 传给解析器（密钥库与加密私钥都按「没给口令」处理）。
            val credential = password.ifEmpty { null }
            val request = when {
                // 文件优先：有文件来源时文本框里的东西不参与（两页各留各的，见 `DevToolInputField`）。
                hasFileSource && sourcePath.isNotBlank() -> CertInput.File(sourcePath, credential)
                // 文本那一路也要口令：加密的 PKCS#8 私钥可以直接粘进来，口令框一样得管用。
                sourceText.isNotBlank() -> CertInput.Text(sourceText, credential)
                else -> null
            }
            if (request == null) {
                outcome = null
                computing = false
                parsedText = null
                return@LaunchedEffect
            }
            computing = true
            // 只有手敲 / 粘贴正文才等防抖；换文件、改口令都是「点一下就定」，立刻重算。
            if (request is CertInput.Text && sourceText != parsedText) delay(ParseDebounceMillis)
            val result = withContext(Dispatchers.Default) { parseCertInput(request) }
            parsedText = sourceText
            outcome = result
            computing = false
        }

        // 解析概况报到窗口底部的状态栏，不占内容区那一行。
        LaunchedEffect(outcome, computing) {
            val current = outcome
            host.reportStatus(
                when {
                    computing -> "解析中…"
                    current is CertParseResult.Ok -> CertFormat.statusText(current.document)
                    current is CertParseResult.Failed -> "无法解析这段输入"
                    else -> null
                }
            )
        }

        val document = (outcome as? CertParseResult.Ok)?.document
        val failure = outcome as? CertParseResult.Failed
        val pemText = document?.let { CertFormat.pemText(it) }.orEmpty()
        val infoText = document?.let { CertFormat.infoText(it) } ?: failure?.message.orEmpty()
        // 口令框什么时候露：解析结果说「就差一个口令」（PKCS#12 或加密私钥），或文件名本身就像密钥库。
        // 不靠扩展名单独说了算——改名是常事——所以解析结果那一路是主判据。
        val showPassword = failure?.needsPassword == true || (hasFileSource && looksLikeKeyStoreName(sourcePath))
        // 框名跟着内容走：全是证书时才叫「证书原文 / 证书信息」，混了请求、吊销列表或密钥就该说「原文 / 信息」。
        val certificateOnly = document == null || CertFormat.hasOnlyCertificates(document)
        val sourceLabel = if (certificateOnly) "证书原文" else "原文"
        val infoLabel = if (certificateOnly) "证书信息" else "信息"

        Column(Modifier.fillMaxSize()) {
            DevToolInputField(
                label = "输入",
                value = sourceText,
                onValueChange = { sourceText = it },
                host = host,
                placeholder = "粘贴 PEM 或 Base64 证书文本；二进制证书请切到「文件」页打开",
                // 证书的正文是一长行一长行的 Base64，折行比横向拖出去好读。折行只改显示，`value` 仍是原样。
                softWrap = true,
                folding = false,
                scan = ::scanPlain,
                // 清空只清正文；来源与它的口令各留各的（两者分开，正是翻页能来回的前提）。
                onClear = { sourceText = "" },
                onClearSource = {
                    hasFileSource = false
                    sourcePath = ""
                    password = ""
                },
                hasSource = hasFileSource,
                sourcePath = sourcePath,
                pathPlaceholder = "粘贴或输入证书文件的绝对路径",
                onFiles = { paths, _ ->
                    // 把文件交给自己解析，因此返回空串——控件不会再往文本框里填任何东西（二进制也
                    // 填不进去），同时吞掉这次粘贴，免得系统把文件名贴进来。
                    paths.firstOrNull()?.let(::takeFile)
                    ""
                },
                sourceCard = { cardModifier ->
                    DevToolSourceCard(
                        name = if (hasFileSource) fileNameOf(sourcePath) else null,
                        detail = document?.let { CertFormat.statusText(it) },
                        emptyHint = "把 .pem / .der / .p7b / .p12 / .jks 拖到这里，或点右上角「打开文件」",
                        modifier = cardModifier,
                    )
                },
                modifier = Modifier.fillMaxWidth().height(InputFieldHeight),
            )

            if (showPassword) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("口令", fontSize = 12.sp, color = MaterialTheme.hintColor)
                    DevToolActionSpacer()
                    DevToolSingleLineField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = "密钥库口令（JKS 的证书是明文，可留空）",
                        secret = true,
                        modifier = Modifier.width(PasswordFieldWidth),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth().weight(1f)) {
                // 左：证书原文（PEM）。
                DevToolCodeField(
                    label = sourceLabel,
                    value = pemText,
                    onValueChange = {},
                    editable = false,
                    softWrap = true,
                    folding = false,
                    lineNumbers = false,
                    scan = ::scanPlain,
                    placeholder = if (computing) "解析中…" else "解析出的 PEM 原文会显示在这里",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    actions = {
                        // 正在算时把值传空：`DevToolResultActions` 据此禁用两个动作——那时框里那份不
                        // 属于眼前的输入，存下来或拷出去都是错的（与 JSON / Base64 工具同一条口径）。
                        DevToolResultActions(
                            value = if (computing) "" else pemText,
                            host = host,
                            suggestedFileName = "certificate.pem",
                        )
                    },
                )

                Spacer(Modifier.width(ColumnGap))

                // 右：证书信息（X.509 字段）。失败时错误就落在这里——它是这次解析的产出，与结果同位置。
                DevToolCodeField(
                    label = infoLabel,
                    value = infoText,
                    onValueChange = {},
                    editable = false,
                    isError = failure != null,
                    softWrap = true,
                    folding = false,
                    lineNumbers = false,
                    scan = ::scanPlain,
                    placeholder = if (computing) "解析中…" else "解析出的证书字段会显示在这里",
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    actions = {
                        DevToolResultActions(
                            value = if (computing) "" else infoText,
                            host = host,
                            suggestedFileName = "certificate-info.txt",
                        )
                    },
                )
            }
        }
    }
}

/** 绝对路径最后那一段；路径为空时给空串。 */
private fun fileNameOf(path: String): String = path.substringAfterLast('/').ifEmpty { path }

/** 文件名像不像密钥库——只用来**提前**露出那个口令框；真正的判据是解析结果。 */
private fun looksLikeKeyStoreName(path: String): Boolean {
    val name = path.substringAfterLast('/').lowercase()
    return name.endsWith(".p12") || name.endsWith(".pfx") ||
        name.endsWith(".jks") || name.endsWith(".keystore")
}
