package com.qcmian.clipper.core.domain.model

import androidx.compose.runtime.Immutable
import com.qcmian.clipper.core.settings.ClipFilterType

/**
 * 剪贴板历史中的单条记录。
 *
 * 保存系统剪贴板上找到的每一种表示（纯文本、图片和/或文件列表），
 * 以及用于去重和排序的记账字段。
 */
@Immutable
data class ClipItem(
    val id: String,
    val text: String? = null,
    /** 原始（PNG/JPEG）图片字节。 */
    val image: ClipImage? = null,
    val files: List<String> = emptyList(),
    /** 未被 [text] / [image] / [files] 单独建模的额外类型（HTML、RTF、PDF、URL 等）的原始字节。 */
    val contents: List<ClipboardContent> = emptyList(),
    val firstCopiedAt: Long = 0L,
    val lastCopiedAt: Long = 0L,
    val numberOfCopies: Int = 1,
    /**
     * 已置顶标记：**非空即表示这一条被置顶**，值本身没有含义。
     *
     * 字段名与数据库列名（`pin`）沿用历史版本的叫法——那时这里存的是「置顶项字母键」
     * （`b`、`p`…）。字母键废弃后它只剩「非空即置顶」的语义，于是写入固定值
     * [PINNED_MARKER]；旧数据里的字母照旧被认作已置顶，因此不必改表结构。
     */
    val pin: String? = null,
    val title: String = "",
    /**
     * [title] 是否来自图片文字识别。
     *
     * **显式存下来，不再靠字段组合推断。** 标题有四个来源——文件路径、正文、附加表示
     * （HTML / RTF）提取的文字、图片识别结果——而前三者在数据上与「识别成功」完全无法
     * 区分：都是「有图片、没有正文、标题非空」。猜错的后果是工具栏多出一个「复制图片
     * 文字」按钮，点下去复制的却只是条目本来的正文。
     *
     * 落库列 `clip_meta.hasRecognizedText`：新条目一律 `false`，只有识别成功时由
     * `updateTitle` 置位。
     */
    val hasRecognizedText: Boolean = false,
    /**
     * 图片文字识别的**完整原文**（取自载荷）；没有识别时为 `null`。
     *
     * [title] 只是它的前一段——标题按「列表里的一行」截断过，滚动长截图的识别结果装不下。
     * 因此「复制图片文字」与预览面板都读这一份，而不是标题。
     */
    val recognizedText: String? = null,
    /**
* 内容复制来源的应用。平台提供前台应用信息时填写；没有等价能力的
     * 平台保持 `null`，预览会隐藏「应用:」这一行。
     */
    val application: SourceApplication? = null,
) {
    val isPinned: Boolean get() = pin != null

    /**
     * 该条目占用的近似字节数：图片是精确值，文本与文件路径按 UTF-8 计。
     *
     * 仓库用它累计「这段时间删掉了多少内容」，以决定什么时候值得去回收数据库的空闲页
     * （见 `DefaultClipboardRepository` 的 `MIN_RECLAIM_BYTES`）；首次读取后缓存，避免反复编码。
     */
    val approximateSizeBytes: Long by lazy {
        (text?.utf8SizeBytes() ?: 0L) +
            (image?.size?.toLong() ?: 0L) +
            files.sumOf { it.utf8SizeBytes() } +
            contents.sumOf { it.size.toLong() }
    }

    /**
     * 附加表示里提取出的可读文字；没有可提取的表示时为空串。
     *
     * 算一次就缓存：解析要走 Ksoup 处理整个片段，而 [previewableText] 与 [hasReadableText]
     * 都读它、预览面板每次重组又在读 [previewableText]。
     */
    private val richText: String by lazy { extractReadableText(contents) }

    /**
     * 条目的**完整文本**：文件路径 → 正文 → 从附加表示（HTML / RTF）解析出的**全部**文字。
     *
     * 预览面板与「多条复制」读它，所以它必须是全文——刻意**不**复用 [deriveTitle]：那个函数
     * 产出的是落库用的**标题**（要短、会被 `toStoredTitle()` 截断到 1000 字符），而这里最后
     * 一档取的是未截断的 [richText]。
     *
     * 最后一档也是必要的：有些应用复制富文本时**只写 HTML、不写纯文本**，那种条目既没有
     * [text] 也没有 [files]，没有它就会是一片空白。
     *
     * 两条规则只在「既没有正文也没有文件」时才碰面，而那时 `title` 本来就由 [richText] 派生，
     * 因此 `toMeta()` 经 `previewableText.toStoredTitle()` 得到的标题不受这次改动影响。
     *
     * 图片识别出的原文不在这里：它在载荷里，由 [previewText] 另行取 `recognizedText`（那才是全文）。
     *
     * 它不在主构造器里，因此不参与 `equals` / `hashCode`。
     */
    val previewableText: String by lazy {
        when {
            files.isNotEmpty() -> files.joinToString(separator = "\n")
            !text.isNullOrBlank() -> text
            else -> richText.ifBlank { title }
        }
    }

    /**
     * 附加表示里能不能提取出可读文字。
     *
     * 用于「要不要对图片做文字识别」：条目自带可读文本时不该覆盖它的标题——与 [text] /
     * [files] 非空时的处理一致（见 `CaptureClipboardUseCase.shouldRecognize`）。
     */
    val hasReadableText: Boolean get() = richText.isNotEmpty()

    /**
     * 预览面板要显示的文本。
     *
     * OCR 条目优先给**完整识别原文**：标题是它截断后的版本，用它会把长截图的后半段藏起来。
     * 其余的条目没有这份数据，仍然走 [previewableText]。
     */
    val previewText: String
        get() = recognizedText?.takeIf { it.isNotBlank() } ?: previewableText

    /**
     * 条目在筛选栏里归属的类型。
     *
     * 一次复制可能同时携带多种表示（例如网页同时有纯文本与 HTML），这里取最具体的一种，
     * 优先级：文件 > 图片 > 富文本 > 文本。
     */
    val clipType: ClipFilterType
        get() = when {
            files.isNotEmpty() -> ClipFilterType.FILE
            image != null -> ClipFilterType.IMAGE
            contents.isNotEmpty() -> ClipFilterType.RICH_TEXT
            else -> ClipFilterType.TEXT
        }

    companion object {
        const val MAX_TITLE_LENGTH = 1_000

        /** 写入 [pin] 的固定值，只是「已置顶」的记号；见 [pin] 的说明。 */
        const val PINNED_MARKER = "pinned"
    }
}

/** UTF-8 编码后的字节数，用于估算条目的存储占用。 */
private fun String.utf8SizeBytes(): Long = encodeToByteArray().size.toLong()

/**
 * 从一次复制的各个表示派生出**标题**。
 *
 * 依次尝试：文件路径 → 正文 → 图片识别结果 → 从附加表示（HTML / RTF）里提取的文字。
 *
 * 它只服务「落库的标题」这一件事，两个调用点都是标题派生：新条目（`ClipItem.toMeta()`，
 * 标题取自 [ClipItem.previewableText] 再截断）与**启动时的回填**
 * （见 `DefaultClipboardRepository.backfillEmptyTitles`）——`title` 是派生好落盘的，所以新增的
 * 标题来源只对新复制生效，历史遗留的空标题要靠同一个函数补一次。两处共用一套规则，
 * 才不会出现「新条目这样、旧条目那样」。
 *
 * 与「内容全文」是两回事：全文由 [ClipItem.previewableText] 直接给出（不截断、附加表示那一档
 * 优先取完整的 `richText`）。两者只在「既没有正文也没有文件」时才走到同一档，而那时 `title`
 * 本就为空或由 `richText` 派生，因此标题结果不会因为全文换了实现而漂移。
 *
 * [richText] 由调用方算好传入而不是就地解析：`ClipItem` 要把它缓存下来复用（见
 * [ClipItem.hasReadableText]），就地解析会算两遍。
 */
internal fun deriveTitle(
    text: String?,
    files: List<String>,
    title: String,
    richText: String,
): String = when {
    files.isNotEmpty() -> files.joinToString("\n")
    !text.isNullOrBlank() -> text
    // 最后一支是必要的：有些应用复制富文本时**只写 HTML、不写纯文本**，那种条目既没有
    // `text` 也没有 `files`，标题会一直是空的——在列表里是一行空白，搜什么都搜不到。
    else -> title.ifBlank { richText }
}

/**
 * 把一段文字规范成「可以落库的标题」：过滤不安全标量，并按 [ClipItem.MAX_TITLE_LENGTH] 截断。
 *
 * 三条写入路径都必须走它：新条目（`toMeta`）、图片文字识别（`updateTitle`）、启动回填。
 * 走漏任何一条，同一个字段就会因为来源不同而带上不同的长度上限，**搜索范围也跟着来源
 * 浮动**——识别出来的标题能搜 5000 字符、正文只有 1000，用户完全无从预期。
 *
 * 过滤不安全标量（`\uFFFC` 是富文本内嵌附件的占位符）必须发生在**写入**时而不是渲染时：
 * 搜索返回的高亮区间是相对这一份文本算出来的，渲染若再删字符，后面所有区间都会错位。
 */
internal fun String.toStoredTitle(): String =
    removingUnsafeTitleScalars().take(ClipItem.MAX_TITLE_LENGTH)

/**
 * 会让 CoreText 在 macOS 26 上做单行截断时卡死的 Unicode 标量 #1520。
 *
 * U+FFFC（对象替换字符）是内联附件的占位符，因此带内嵌图片的富文本，其纯文本表示中每个附件
 * 都会带一个。两个及以上紧邻非拉丁文字时，会让排版器在「单行 + 中间截断」布局标题时陷入死循环。
 */
private val UNSAFE_TITLE_SCALARS = setOf('\uFFFC')

/** 按标量逐个过滤。**会改变长度**，只能用在写入路径上（见 [replacingUnsafeTitleScalars]）。 */
fun String.removingUnsafeTitleScalars(): String =
    if (none { it in UNSAFE_TITLE_SCALARS }) this else filterNot { it in UNSAFE_TITLE_SCALARS }

/**
 * 按标量逐个替换成空格。**长度不变**，用在渲染路径上。
 *
 * 搜索返回的高亮区间是相对 `ClipMeta.title` 原文算出来的，渲染时删掉任何一个字符，它后面的
 * 区间就会整体错位（已有历史里可能仍留着旧版本写进去的不安全标量，因此这里不能只是「信任写入
 * 路径已经过滤过」）。
 */
fun String.replacingUnsafeTitleScalars(): String =
    if (none { it in UNSAFE_TITLE_SCALARS }) this
    else CharArray(length) { if (this[it] in UNSAFE_TITLE_SCALARS) ' ' else this[it] }.concatToString()

/**
 * 把一段「代表条目自身的文本」格式化成列表显示用的单行标题。
 *
 * 图片文字识别的原文带着真换行存进 `title`（复制时要还原原文），由渲染方自行压平，
 * 因此不经过这里——它只服务「标题由正文 / 文件路径派生」的那些条目（见 `HistoryRow`）。
 *
 * **这里的每一步都必须等长**（`·` / `⏎` / `⇥` 都是逐字符替换）：调用方拿它算出来的字符串配上
 * 相对原文的高亮区间，任何一个字符被删掉（曾经的 `trim()` 就是如此），后面的高亮就会错位。
 * 关闭「特殊符号」时因此不裁剪首尾空白——它们在单行里本来也看不见。
 */
fun String.titleForDisplay(showSpecialSymbols: Boolean = true): String {
    val raw = take(ClipItem.MAX_TITLE_LENGTH).replacingUnsafeTitleScalars()
    if (!showSpecialSymbols) return raw

    return raw
        .replace(LEADING_SPACES) { "·".repeat(it.value.length) }
        .replace(TRAILING_SPACES) { "·".repeat(it.value.length) }
        .replace("\n", "\u23ce")
        .replace("\t", "\u21e5")
}

// 提为常量：`titleForDisplay` 在每一行的每次重组里都会被调用，就地构造正则相当于每行每帧都编译一次。
private val LEADING_SPACES = Regex("^ +")
private val TRAILING_SPACES = Regex(" +$")

private val HEX_COLOR_PATTERN = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

fun isHexColor(value: String): Boolean = HEX_COLOR_PATTERN.matches(value.trim())
