package com.qcmian.clipper.core.domain.model

/**
 * 剪贴板历史中的单条记录。
 *
 * 保存系统剪贴板上找到的每一种表示（纯文本、图片和/或文件列表），
 * 以及用于去重和排序的记账字段。
 */
data class ClipItem(
    val id: String,
    val text: String? = null,
    /** 原始（PNG/JPEG）图片字节。 */
    val image: ClipImage? = null,
    val files: List<String> = emptyList(),
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
 * 内容复制来源的应用。平台提供前台应用信息时填写；没有等价能力的
     * 平台保持 `null`，预览会隐藏「应用:」这一行。
     */
    val application: SourceApplication? = null,
) {
    val isPinned: Boolean get() = pin != null
    val isUnpinned: Boolean get() = pin == null

    /**
     * 该条目占用的近似字节数：图片是精确值，文本与文件路径按 UTF-8 计。
     *
     * 仓库用它累计「这段时间删掉了多少内容」，以决定什么时候值得去回收数据库的空闲页
     * （见 `DefaultClipboardRepository` 的 `MIN_RECLAIM_BYTES`）；首次读取后缓存，避免反复编码。
     */
    val approximateSizeBytes: Long by lazy {
        (text?.utf8SizeBytes() ?: 0L) +
            (image?.size?.toLong() ?: 0L) +
            files.sumOf { it.utf8SizeBytes() }
    }

    /**
     * 用于预览和搜索的文本。对应 `HistoryItem.previewableText`：图片没有文本表示，
     * 因此在文字识别填入之前，其标题保持为空。
     */
    val previewableText: String
        get() = when {
            files.isNotEmpty() -> files.joinToString("\n")
            !text.isNullOrBlank() -> text
            else -> title
        }

    /**
     * [title] 是否真的来自图片文字识别。
     *
     * 只有本身没有别的文本表示的纯图片条目，才会把识别结果写进 `title`（见 `CaptureClipboardUseCase`）。
     * 条目一旦带着 [text] 或 [files]，标题就是由它们的文本派生出来的（见 [previewableText]），
     * 此时 `title` 非空**不代表**图里有文字——用它当判据会让工具栏多出一个按钮。
     */
    val hasRecognizedText: Boolean
        get() = image != null && text.isNullOrBlank() && files.isEmpty() && title.isNotBlank()

    /** 当本条目已包含 [other] 提供的全部内容时返回 `true`。 */
    fun supersedes(other: ClipItem): Boolean {
        val hasContent = other.text != null || other.image != null || other.files.isNotEmpty()
        if (!hasContent) return false
        return (other.text == null || text == other.text) &&
            (other.image == null || image == other.image) &&
            (other.files.isEmpty() || files == other.files)
    }

    /**
     * 构建列表显示的单行标题。对应 `HistoryItem.generateTitle()`，包含 `showSpecialSymbols`
     * 偏好：开启时首尾空格显示为 `·`，换行与制表符显示为 `⏎`/`⇥`。
     */
    fun generateTitle(showSpecialSymbols: Boolean = true): String =
        previewableText.titleForDisplay(showSpecialSymbols)

    companion object {
        const val MAX_TITLE_LENGTH = 1_000

        /** 写入 [pin] 的固定值，只是「已置顶」的记号；见 [pin] 的说明。 */
        const val PINNED_MARKER = "pinned"
    }
}

/** UTF-8 编码后的字节数，用于估算条目的存储占用。 */
private fun String.utf8SizeBytes(): Long = encodeToByteArray().size.toLong()

/**
 * 会让 CoreText 在 macOS 26 上做单行截断时卡死的 Unicode 标量 #1520。
 *
 * U+FFFC（对象替换字符）是内联附件的占位符，因此带内嵌图片的富文本，其纯文本表示中每个附件
 * 都会带一个。两个及以上紧邻非拉丁文字时，会让排版器在「单行 + 中间截断」布局标题时陷入死循环。
 */
private val UNSAFE_TITLE_SCALARS = setOf('\uFFFC')

/** 对应 `String.removingScalarsUnsafeForTitleLayout()`，按标量逐个过滤。 */
fun String.removingUnsafeTitleScalars(): String =
    if (none { it in UNSAFE_TITLE_SCALARS }) this else filterNot { it in UNSAFE_TITLE_SCALARS }

/**
 * 把一段「代表条目自身的文本」格式化成列表显示用的单行标题。
 *
 * 图片文字识别的原文带着真换行存进 `title`（复制时要还原原文），由渲染方自行压平，
 * 因此不经过这里——本函数只服务 [ClipItem.generateTitle]。
 */
fun String.titleForDisplay(showSpecialSymbols: Boolean = true): String {
    val raw = take(ClipItem.MAX_TITLE_LENGTH).removingUnsafeTitleScalars()
    if (!showSpecialSymbols) return raw.trim()

    return raw
        .replace(Regex("^ +")) { "·".repeat(it.value.length) }
        .replace(Regex(" +$")) { "·".repeat(it.value.length) }
        .replace("\n", "\u23ce")
        .replace("\t", "\u21e5")
}

private val HEX_COLOR_PATTERN = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

fun isHexColor(value: String): Boolean = HEX_COLOR_PATTERN.matches(value.trim())
