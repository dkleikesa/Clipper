package com.qcmian.clipper.devtools.tools.base64

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
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
import com.qcmian.clipper.devtools.detect.Base64DataTypeDetector
import com.qcmian.clipper.devtools.api.readBytesOrNull
import com.qcmian.clipper.devtools.api.readTextFileOrNull
import com.qcmian.clipper.devtools.api.writeBytesFile
import com.qcmian.clipper.devtools.api.writeTextFile
import com.qcmian.clipper.devtools.ui.components.DevToolActionSpacer
import com.qcmian.clipper.devtools.ui.components.DevToolDirection
import com.qcmian.clipper.devtools.ui.components.DevToolFieldAction
import com.qcmian.clipper.devtools.ui.components.DevToolInputField
import com.qcmian.clipper.devtools.ui.components.DevToolInputOrigin
import com.qcmian.clipper.devtools.ui.components.imageInputName
import com.qcmian.clipper.devtools.ui.components.DevToolSourceCard
import com.qcmian.clipper.devtools.ui.components.DevToolTabBar
import com.qcmian.clipper.devtools.ui.components.DevToolToggle
import com.qcmian.clipper.core.ui.code.DevToolCodeField
import com.qcmian.clipper.core.ui.code.rememberCodeColors
import com.qcmian.clipper.core.ui.code.scanPlain
// 把 ImageBitmap 编成 PNG 只有这一处用到（复制 ICO 时的兜底，见 `ImageResult`）；模块里本来
// 就有它——条码工具导出码图走的是同一个编码器（见 `renderPng`）。
import io.github.alexzhirkevich.qrose.ImageFormat
import io.github.alexzhirkevich.qrose.toByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 正文停下来多久才重算。与 JSON / XML / 数学工具取同一个值。 */
private const val EvaluateDebounceMillis = 150L

/**
 * 输入区高度：比时间戳工具高一些，编码多行文本时一屏能看个大概。
 *
 * 这一份要装下**两页里更高的那一页**（翻页时占的地方不动）：文件页 = 标题行 + 一条可敲的路径
 * + 来源卡片，卡片里还得放得下缩略图与它那两行字。
 */
private val InputFieldHeight = 160.dp

/**
 * 编码时一次读入的文件上限；与 `readBytesOrNull` 的默认值一致，超过就提示而不是硬读。
 *
 * 结果**不设**长度上限：编出来的 Base64 一律照原样铺进结果框，多大都铺。代价是超大结果会让文本
 * 排版变慢——代码框走 `BasicTextField`，整段要一次性排版。真在实机上卡到不能用，再回来收。
 */
private val MaxInputFileBytes = 16L * 1024 * 1024

/**
 * 编码时从「非文本」来的输入：打开 / 拖入的文件、剪贴板里的图片。
 *
 * 刻意用普通类而不是 `data class`：它的相等性按**引用**算，重算的键一眼就能认出「换了份输入」
 * ——`ByteArray` 放进数据类本来也是按引用比较，那样写只是让人误以为在比值。
 *
 * [path] 是磁盘上的**绝对路径**，只在来源真的落在一个文件上时才有（剪贴板里的图没有）；
 * 卡片靠它说清「编的是哪个文件」——光有文件名，同名的两个文件分不出来。
 */
private class Base64Source(
    val name: String,
    val bytes: ByteArray,
    val path: String? = null,
) {
    val imageKind: ImageKind? = Base64Format.imageKindOf(bytes)
}

/** 一次计算的结果。 */
private sealed interface Base64Outcome {
    /** 编码成功。[sourceBytes] 是原文 / 原文件的字节数，用于体积对比。 */
    class Encoded(val base64: String, val sourceBytes: Int) : Base64Outcome

    /** [extension] 是「保存」时该用的后缀，见 [describe]。 */
    class DecodedText(
        val text: String,
        val bytes: Int,
        val sourceChars: Int,
        val extension: String,
    ) : Base64Outcome

    class DecodedImage(
        val kind: ImageKind,
        val bytes: ByteArray,
        val bitmap: ImageBitmap,
        val extension: String,
    ) : Base64Outcome

    class DecodedBinary(val bytes: ByteArray, val extension: String) : Base64Outcome

    class Failed(val message: String) : Base64Outcome
}

/** 读一个文件的结果。 */
private sealed interface Loaded {
    class Text(val value: String) : Loaded
    class Binary(val source: Base64Source) : Loaded
    class Failure(val message: String) : Loaded
}

/**
 * Base64 编解码：文本、图片与任意文件互转。
 *
 * 方向是**显式**的（顶上「编码 / 解码」两页页签），不做「自动猜」——`test`、`abcd` 这类普通词也是
 * 合法 Base64，猜错方向比多按一下更烦人（数学工具不声明数据类型，也是同一个取舍）。
 *
 * 输入输出都落在「字节」这一层：编码要的是字节（文本按 UTF-8 取，文件按原样取），解码给出的
 * 也是字节，再由「它是什么」决定怎么显示——图片给预览，能当 UTF-8 看的给文本，其余给一张
 * 「二进制文件」的摘要卡加下载。
 */
internal object Base64DevTool : DevTool {

    override val metadata: DevToolMetadata = DevToolMetadata(
        id = "base64",
        name = "Base64 编解码",
        description = "文本、图片与任意文件与 Base64 互转；解码结果自动分辨文本、图片与二进制。",
        group = DevToolGroup.ENCODER,
        icon = ClipperIconKind.BASE64,
    )

    override val acceptedDataTypes: Set<String> = setOf(DataTypes.BASE64)

    @Composable
    override fun Content(input: ClipItem?, host: DevToolHost) {
        var mode by remember { mutableStateOf(DevToolDirection.Encode) }
        var urlSafe by remember { mutableStateOf(false) }
        // 编码结果前面要不要写 `data:<类型>;base64,`。只对**文件 / 图片**来源生效：手敲的一段文本
        // 没有「文件类型」可言，给它套个 `text/plain` 只是往结果前面塞噪音。
        var withPrefix by remember { mutableStateOf(true) }
        // 两个方向**各留一份输入**：编码框里装的是原文，解码框里装的是 Base64，它们不是一种东西。
        // 共用一个会让「解码框里的 Base64」被搬进编码框再编一次，出来的是双重编码——而且换个方向
        // 回来，原来敲的东西已经没了。
        var encodeText by remember { mutableStateOf("") }
        var decodeText by remember { mutableStateOf("") }
        // 编码侧还可能来自文件（打开 / 拖入 / 剪贴板图片）。它只对编码有意义，切到解码时留着不动，
        // 切回来还在。
        var source by remember { mutableStateOf<Base64Source?>(null) }
        var outcome by remember { mutableStateOf<Base64Outcome?>(null) }
        // 正在算（防抖的安静窗口里，或后台还没回来）。它决定「复制 / 保存」能不能点：那时候框里
        // 留着的是**上一份**结果，拷出去是错的。
        var computing by remember { mutableStateOf(false) }
        // 上一次真正算过的正文。只有它变了才值得等防抖；换方向 / 换选项都是点一下就定的事。
        var computedText by remember { mutableStateOf<String?>(null) }

        // 眼前这个方向正在用哪一份输入。
        val text = if (mode == DevToolDirection.Encode) encodeText else decodeText

        // 改**当前方向**那一份输入。
        fun updateText(value: String) {
            if (mode == DevToolDirection.Encode) encodeText = value else decodeText = value
        }

        // 读一个文件并按当前方向安置它：编码要字节（二进制正是内容），解码要文本（Base64 本身是文本）。
        fun applyPath(path: String) {
            when (val loaded = loadFile(path, mode)) {
                is Loaded.Text -> {
                    updateText(loaded.value)
                    source = null
                }

                is Loaded.Binary -> {
                    // 二进制只可能出现在编码方向（`loadFile` 就是这么分的）：让输入框空着、由文件卡片顶上。
                    updateText("")
                    source = loaded.source
                }

                is Loaded.Failure -> host.showStatus(loaded.message)
            }
        }

        // 从剪贴板条目打开：图片直接当图片来编码，文件按路径读，其余按文本灌进输入框——并且**替用户
        // 选好方向**：复制一段 Base64 再打开工具，十有八九是要解它，默认落在编码方向等于白点一次。
        LaunchedEffect(input) {
            val item = input ?: return@LaunchedEffect
            val image = item.image
            if (image != null && item.files.isEmpty()) {
                mode = DevToolDirection.Encode
                source = Base64Source(imageInputName(DevToolInputOrigin.Paste), image.toByteArray())
                encodeText = ""
                return@LaunchedEffect
            }
            if (item.files.isNotEmpty()) {
                applyPath(item.files.first())
                return@LaunchedEffect
            }
            val text = withContext(Dispatchers.Default) { item.devToolText() }
            // 判据与面板的探测**共用同一个**（`Base64DataTypeDetector`）：面板正是靠它把本工具推荐
            // 出来的，两边用同一条规则才不会「它推荐了、进来却不是解码」。
            val looksEncoded = withContext(Dispatchers.Default) { Base64DataTypeDetector.matches(text) }
            source = null
            mode = if (looksEncoded) DevToolDirection.Decode else DevToolDirection.Encode
            if (looksEncoded) {
                decodeText = text
            } else {
                encodeText = text
            }
        }

        // 实时求值：输入一变就重新计时，停下来才算一次。取消由 `LaunchedEffect` 负责——正在算的
        // 那一份即使算完也自然作废。
        LaunchedEffect(mode, urlSafe, withPrefix, text, source) {
            // 文件只喂编码方向；解码方向看的是它自己那个框里的 Base64。
            val file = if (mode == DevToolDirection.Encode) source else null
            if (file == null && text.isBlank()) {
                outcome = null
                computing = false
                computedText = null
                return@LaunchedEffect
            }
            computing = true
            // 只有手敲正文才等防抖；载入文件、换方向、换选项都是「点一下就定」，立刻重算。
            if (file == null && text != computedText) delay(EvaluateDebounceMillis)
            val result = withContext(Dispatchers.Default) {
                computeOutcome(mode, urlSafe, withPrefix, text, file)
            }
            computedText = if (file == null) text else null
            outcome = result
            computing = false
        }

        // 体积对比报到窗口底部的状态栏，不占内容区那一行（与其它工具同一分工）。
        LaunchedEffect(mode, outcome, computing, text.length, source) {
            val current = outcome
            host.reportStatus(
                when {
                    computing -> "计算中…"
                    current is Base64Outcome.Encoded ->
                        "输入 ${Base64Format.humanSize(current.sourceBytes.toLong())}" +
                            " → Base64 ${Base64Format.humanSize(current.base64.length.toLong())}"

                    current is Base64Outcome.DecodedText ->
                        "Base64 ${Base64Format.humanSize(current.sourceChars.toLong())}" +
                            " → 文本 ${Base64Format.humanSize(current.bytes.toLong())}"

                    current is Base64Outcome.DecodedImage ->
                        "${current.kind.label} · ${Base64Format.humanSize(current.bytes.size.toLong())}"

                    current is Base64Outcome.DecodedBinary ->
                        "二进制文件 · ${Base64Format.humanSize(current.bytes.size.toLong())}"

                    else -> null
                }
            )
        }

        Column(Modifier.fillMaxSize()) {
            // 方向是两**页**，不是工具栏里的一枚开关：编码与解码各带自己那一整套（选项 + 输入框 +
            // 结果区）。原先那枚分段控件与旁边的「URL 安全」长得一模一样，于是「换一整页」看上去
            // 跟「改一个选项」是同一件事。
            DevToolTabBar(
                options = DevToolDirection.entries,
                selected = mode,
                optionLabel = { it.title },
                // 只换页，不动两边的输入：编码框与解码框各存各的，切来切去都不丢。
                onSelect = { mode = it },
            )

            Spacer(Modifier.height(12.dp))

            // 载入的文件只属于编码那一页：解码读的是它自己框里的 Base64。
            val loadedFile = source

            when (mode) {
                // 编码页：文本、文件、剪贴板图片都能编。
                DevToolDirection.Encode -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DevToolToggle(
                            title = "URL 安全",
                            checked = urlSafe,
                            onCheckedChange = { urlSafe = it },
                        )

                        DevToolActionSpacer()

                        // 「类型前缀」只管文件 / 图片那一类来源：手敲的一段文本没有「文件类型」可写，
                        // 给它套个 `text/plain` 只是往结果前面塞噪音（见 `computeOutcome`）。
                        DevToolToggle(
                            title = "类型前缀",
                            checked = withPrefix,
                            onCheckedChange = { withPrefix = it },
                        )

                        Spacer(Modifier.weight(1f))
                    }

                    Spacer(Modifier.height(10.dp))

                    // 输入区整个交给 `DevToolInputField`：文本、文件（打开 / 拖入 / 粘贴）与
                    // **剪贴板里的图片**四路输入都从这一个口子进。载入文件 / 图片之后就换成那张
                    // 来源卡片。
                    DevToolInputField(
                        // 框名不必再说「文本 / 文件」：标题行里那道切换已经说着了。
                        label = "输入",
                        value = encodeText,
                        onValueChange = { encodeText = it },
                        host = host,
                        placeholder = "在此粘贴文本或图片；或拖入 / 打开一个文件（图片、任意二进制）",
                        // Base64 是长串，折行比横向滚出去好读——一行几百个字符要一直往右拖才看得完。
                        // 折行只改显示，`value` 仍是那一整行，复制 / 保存拿到的还是原样。
                        softWrap = true,
                        folding = false,
                        scan = ::scanPlain,
                        // 文件按**这一页**的方向安置：编码要的是字节（二进制正是内容）。返回空串
                        // 表示「已经安置好了」——输入框不必再往正文里填东西，也吞掉这次粘贴。
                        onFiles = { paths, _ ->
                            paths.firstOrNull()?.let(::applyPath)
                            ""
                        },
                        // 剪贴板里的图片没有磁盘路径：直接当编码来源——用户粘一张图进来，要看的
                        // 显然是它的 Base64。
                        onImage = { bytes, origin ->
                            source = Base64Source(imageInputName(origin), bytes)
                            encodeText = ""
                        },
                        // 两种形态各清各的：清文本不动文件、清文件不动文本——切回去还能接着用。
                        onClear = { encodeText = "" },
                        onClearSource = { source = null },
                        hasSource = loadedFile != null,
                        // 文件页那行路径：钉在来源自己身上，工具因此不必另存一份路径字符串。
                        sourcePath = loadedFile?.path.orEmpty(),
                        sourceCard = { cardModifier ->
                            FileSourceCard(source = loadedFile, modifier = cardModifier)
                        },
                        modifier = Modifier.fillMaxWidth().height(InputFieldHeight),
                    )
                }

                // 解码页：只吃一段 Base64（或一条 Data URL），结果可能是文本 / 图片 / 二进制文件。
                DevToolDirection.Decode -> {
                    DevToolInputField(
                        label = "输入 · Base64",
                        value = decodeText,
                        onValueChange = { decodeText = it },
                        host = host,
                        placeholder = "在此粘贴 Base64；也认 data:image/png;base64,… 这样的 Data URL",
                        softWrap = true,
                        folding = false,
                        scan = ::scanPlain,
                        // 这一页打开 / 拖入的文件按**文本**读：Base64 本身就是文本（见 `loadFile`）。
                        onFiles = { paths, _ ->
                            paths.firstOrNull()?.let(::applyPath)
                            ""
                        },
                        // 这一页没有图片可解：粘一张图进来只可能是想**编**它，所以替用户翻到编码页、
                        // 顺手把图挂上——跟从前那枚分段控件一样，不让这次粘贴石沉大海。
                        onImage = { bytes, origin ->
                            mode = DevToolDirection.Encode
                            source = Base64Source(imageInputName(origin), bytes)
                            encodeText = ""
                        },
                        onClear = { decodeText = "" },
                        modifier = Modifier.fillMaxWidth().height(InputFieldHeight),
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // 结果区两页共用一份：它按解出来的**字节是什么**决定画文本、图片还是文件卡，本来就不分
            // 方向；各画一份只会让两边慢慢长歪。
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

/** 在后台算出这一次的结果。编解码本身是纯函数，这里只负责接上与排版无关的字节。 */
private fun computeOutcome(
    mode: DevToolDirection,
    urlSafe: Boolean,
    withPrefix: Boolean,
    text: String,
    file: Base64Source?,
): Base64Outcome = when (mode) {
    DevToolDirection.Encode -> {
        val bytes = file?.bytes ?: text.encodeToByteArray()
        val encoded = Base64Format.encode(bytes, urlSafe)
        // 前缀只在**有类型可写**时才加：文件 / 图片有 MIME，一段手敲的文本没有。
        val output = if (withPrefix && file != null) {
            "data:${Base64Format.mimeTypeOf(file.name, file.imageKind)};base64,$encoded"
        } else {
            encoded
        }
        Base64Outcome.Encoded(output, bytes.size)
    }

    DevToolDirection.Decode -> {
        // 输入若是 Data URL，它声明的类型就是最可信的一手信息——`data:application/zip;base64,…`
        // 解出来当然该存成 `.zip`。先取下来，再连同字节一起交给 `describe`。
        val mime = Base64Format.dataUriMime(text)
        Base64Format.decode(text).fold(
            onSuccess = { describe(it, text.length, mime) },
            // 失败是 `Result` 里的 `Base64Error`；真出了别的异常也不该把窗口炸掉。
            onFailure = { error ->
                Base64Outcome.Failed(
                    (error as? Base64Error)?.let { base64ErrorMessage(text, it) } ?: "解码失败"
                )
            },
        )
    }
}

/**
 * 根据解出来的字节决定怎么显示：图片 → 预览，能当 UTF-8 看 → 文本，否则 → 二进制；同时定下
 * 「保存」该用什么扩展名。
 *
 * 扩展名按可靠程度取：**Data URL 声明的 MIME** → **文件头认出的格式** → 兜底的 `txt` / `bin`。
 * 光看内容认不出 `json`（它就是一段文本），所以 Data URL 那一手信息不能丢——丢了用户存下来就是
 * 个 `.txt`，还得自己改名。
 */
private fun describe(bytes: ByteArray, sourceChars: Int, mime: String?): Base64Outcome {
    val declared = Base64Format.extensionOf(mime)
    Base64Format.imageKindOf(bytes)?.let { kind ->
        val bitmap = runCatching { bytes.decodeToImageBitmap() }.getOrNull()
        if (bitmap != null) {
            return Base64Outcome.DecodedImage(kind, bytes, bitmap, declared ?: kind.extension)
        }
    }
    Base64Format.utf8OrNull(bytes)?.let {
        return Base64Outcome.DecodedText(it, bytes.size, sourceChars, declared ?: "txt")
    }
    val extension = declared
        ?: Base64Format.extensionOf(Base64Format.mimeBySignature(bytes))
        ?: "bin"
    return Base64Outcome.DecodedBinary(bytes, extension)
}

/**
 * 读一个文件并包成 [Loaded]。
 *
 * 两个方向要的东西不一样：**解码**读文本（Base64 就是文本），走 `readTextFileOrNull`；**编码**
 * 读原始字节，走 `readBytesOrNull`——后者才认二进制文件（`readTextFileOrNull` 会把图片当二进制
 * 拒掉，而图片正是这里的主要用法）。
 */
private fun loadFile(path: String, mode: DevToolDirection): Loaded {
    val name = path.substringAfterLast('/').ifBlank { path }
    if (mode == DevToolDirection.Decode) {
        val value = readTextFileOrNull(path)
            ?: return Loaded.Failure("读不了这个文件（不是文本，或超过大小上限）：$path")
        return Loaded.Text(value)
    }
    val bytes = readBytesOrNull(path, MaxInputFileBytes)
        ?: return Loaded.Failure(
            "读不了这个文件（超过 ${Base64Format.humanSize(MaxInputFileBytes)}，或不是普通文件）：$path"
        )
    return Loaded.Binary(Base64Source(name, bytes, path))
}

private fun saveText(host: DevToolHost, value: String, suggestedName: String) {
    val path = host.pickFileToSave(suggestedName) ?: return
    host.showStatus(if (writeTextFile(path, value)) "已保存到 $path" else "写不进这个位置：$path")
}

private fun saveBytes(host: DevToolHost, bytes: ByteArray, suggestedName: String) {
    val path = host.pickFileToSave(suggestedName) ?: return
    host.showStatus(if (writeBytesFile(path, bytes)) "已保存到 $path" else "写不进这个位置：$path")
}

/**
 * 把解出来的图写回系统剪贴板。
 *
 * **原样**交出去：解出来的字节就是用户要的那张图，重编一遍只会白改一次容器——体积还可能涨几倍，
 * 而这一份随后要进历史库（同一条理由见 `MacClipboardDataSource.write`，那里也是逐类型搬原始字节）。
 * 宿主按**魔数**认类型，PNG / JPEG / GIF / WebP / BMP / TIFF 都认（见 `imageFormatOf`）。
 *
 * ICO 是它唯一认不出的格式：交出去等于什么都没写，状态栏却会报「已复制到剪贴板」。那一下只能
 * 先从已解码好的位图编一张 PNG 出来——尺寸小（图标），这点开销可以忽略。
 */
private fun copyDecodedImage(host: DevToolHost, outcome: Base64Outcome.DecodedImage) {
    host.copyImageToClipboard(
        if (outcome.kind == ImageKind.Ico) {
            outcome.bitmap.toByteArray(ImageFormat.PNG)
        } else {
            outcome.bytes
        }
    )
}

@Composable
private fun ResultArea(
    outcome: Base64Outcome?,
    mode: DevToolDirection,
    computing: Boolean,
    host: DevToolHost,
    modifier: Modifier,
) {
    // 正在算的时候框里留着的是上一份结果：能看，但不能拷出去。
    val canAct = !computing
    when (outcome) {
        null -> PlaceholderResult(mode, computing, modifier)

        is Base64Outcome.Failed -> ResultTextField(
            label = "结果",
            value = outcome.message,
            isError = true,
            softWrap = true,
            onCopy = null,
            onSave = null,
            canAct = false,
            modifier = modifier,
        )

        is Base64Outcome.Encoded -> {
            val base64 = outcome.base64
            ResultTextField(
                label = "结果 · Base64",
                value = base64,
                isError = false,
                softWrap = true,
                onCopy = { host.copyToClipboard(base64) },
                onSave = { saveText(host, base64, "encoded.txt") },
                canAct = canAct,
                modifier = modifier,
            )
        }

        is Base64Outcome.DecodedText -> {
            val decoded = outcome.text
            ResultTextField(
                label = "结果 · 文本",
                value = decoded,
                isError = false,
                softWrap = true,
                onCopy = { host.copyToClipboard(decoded) },
                onSave = { saveText(host, decoded, "decoded.${outcome.extension}") },
                canAct = canAct,
                modifier = modifier,
            )
        }

        is Base64Outcome.DecodedImage -> ImageResult(
            outcome = outcome,
            onCopy = { copyDecodedImage(host, outcome) },
            onSave = { saveBytes(host, outcome.bytes, "decoded.${outcome.extension}") },
            canAct = canAct,
            modifier = modifier,
        )

        is Base64Outcome.DecodedBinary -> {
            val size = Base64Format.humanSize(outcome.bytes.size.toLong())
            // 认出类型就写在卡上：用户看到 `.zip` 才敢确认「存下来的东西是对的」。
            val detail = if (outcome.extension == "bin") "二进制文件 · $size"
            else "二进制文件 · $size · 建议存为 .${outcome.extension}"
            ResultSummaryCard(
                label = "结果 · 文件",
                message = "解出的是二进制内容，不能当文本显示",
                detail = detail,
                onCopy = null,
                onSave = { saveBytes(host, outcome.bytes, "decoded.${outcome.extension}") },
                canAct = canAct,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun ResultTextField(
    label: String,
    value: String,
    isError: Boolean,
    softWrap: Boolean,
    onCopy: (() -> Unit)?,
    onSave: (() -> Unit)?,
    canAct: Boolean,
    modifier: Modifier,
) {
    DevToolCodeField(
        label = label,
        value = value,
        onValueChange = {},
        editable = false,
        isError = isError,
        softWrap = softWrap,
        folding = false,
        scan = ::scanPlain,
        modifier = modifier,
        actions = {
            if (onSave != null) {
                DevToolFieldAction(
                    kind = ClipperIconKind.SAVE,
                    tooltip = "保存为文件",
                    enabled = canAct,
                    onClick = onSave,
                )
                Spacer(Modifier.width(4.dp))
            }
            if (onCopy != null) {
                DevToolFieldAction(
                    kind = ClipperIconKind.COPY,
                    tooltip = "复制",
                    enabled = canAct,
                    onClick = onCopy,
                )
            }
        },
    )
}

/** 不是文本的结果（解码出二进制）：一句说明 + 体积，动作照旧挂着。 */
@Composable
private fun ResultSummaryCard(
    label: String,
    message: String,
    detail: String,
    onCopy: (() -> Unit)?,
    onSave: (() -> Unit)?,
    canAct: Boolean,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.hintColor)
            Spacer(Modifier.weight(1f))
            if (onSave != null) {
                DevToolFieldAction(
                    kind = ClipperIconKind.SAVE,
                    tooltip = "保存为文件",
                    enabled = canAct,
                    onClick = onSave,
                )
                Spacer(Modifier.width(4.dp))
            }
            if (onCopy != null) {
                DevToolFieldAction(
                    kind = ClipperIconKind.COPY,
                    tooltip = "复制",
                    enabled = canAct,
                    onClick = onCopy,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(shape)
                .background(colors.onSurface.copy(alpha = 0.04f))
                .border(1.dp, colors.outline.copy(alpha = 0.6f), shape)
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message, fontSize = 13.sp, color = colors.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(detail, fontSize = 12.sp, color = MaterialTheme.hintColor)
            }
        }
    }
}

/**
 * 解码结果是图片：给预览，动作是「保存为图片」与「复制到剪贴板」。
 *
 * 复制这一下是必要的：解码出来的图多半是要**拿去用**的（贴进聊天、贴进文档），而这一页原先
 * 只有「保存」——想用还得先落盘再找回来。与码图那一页的复制同一个口子（[DevToolHost
 * .copyImageToClipboard]），提示语也一致。
 */
@Composable
private fun ImageResult(
    outcome: Base64Outcome.DecodedImage,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    canAct: Boolean,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val codeColors = rememberCodeColors()
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("结果 · 图片", fontSize = 13.sp, color = MaterialTheme.hintColor)
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${outcome.kind.label} · ${outcome.bitmap.width}×${outcome.bitmap.height}" +
                    " · ${Base64Format.humanSize(outcome.bytes.size.toLong())}",
                fontSize = 12.sp,
                color = MaterialTheme.hintColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            DevToolFieldAction(
                kind = ClipperIconKind.SAVE,
                tooltip = "保存为图片",
                enabled = canAct,
                onClick = onSave,
            )
            Spacer(Modifier.width(4.dp))
            DevToolFieldAction(
                kind = ClipperIconKind.COPY,
                // 与码图那一页同一句话：这个图标挨着「保存为图片」，光写「复制」会被读成
                // 「复制这张图的 Base64」。
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
                // 与编辑框同一块底色：图片贴上去像「印在纸上」，而不是浮在面板上。
                .background(codeColors.editorBackground)
                .border(1.dp, colors.outline.copy(alpha = 0.6f), shape)
                .padding(8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = outcome.bitmap,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * 编码侧的**文件页**：载入了文件就把「这次编的是这个文件」摆出来——缩略图 + 文件名 + 体积；
 * 空着则给一句落点提示。长相见 [DevToolSourceCard]，绝对路径在它上方那条可敲的路径行里
 * （归 `DevToolInputField`）。
 */
@Composable
private fun FileSourceCard(source: Base64Source?, modifier: Modifier) {
    DevToolSourceCard(
        name = source?.name,
        detail = source?.let {
            listOfNotNull(
                it.imageKind?.label,
                Base64Format.humanSize(it.bytes.size.toLong()),
            ).joinToString(" · ")
        },
        thumbnail = rememberThumbnail(source),
        emptyHint = "把文件拖进来，或打开 / 粘贴一个文件（图片、任意二进制）",
        modifier = modifier,
    )
}

/** 图片文件的缩略图。解码挪到后台：几兆的图在组合里同步解会让窗口顿一下。 */
@Composable
private fun rememberThumbnail(source: Base64Source?): ImageBitmap? {
    val bytes = source?.takeIf { it.imageKind != null }?.bytes ?: return null
    val bitmap by produceState<ImageBitmap?>(null, bytes) {
        value = withContext(Dispatchers.Default) {
            runCatching { bytes.decodeToImageBitmap() }.getOrNull()
        }
    }
    return bitmap
}

@Composable
private fun PlaceholderResult(mode: DevToolDirection, computing: Boolean, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            text = when {
                computing -> "计算中…"
                mode == DevToolDirection.Encode -> "输入文本或载入文件后，这里显示 Base64"
                else -> "粘贴 Base64 后，这里显示原文 / 图片 / 文件"
            },
            fontSize = 12.sp,
            color = MaterialTheme.hintColor,
        )
    }
}
