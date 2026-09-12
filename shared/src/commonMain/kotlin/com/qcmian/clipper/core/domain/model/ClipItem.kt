package com.qcmian.clipper.core.domain.model

/**
 * 剪贴板历史中的单条记录。
 *
 * 保存系统剪贴板上找到的每一种表示（纯文本、编码后的图片和/或
 * 文件列表），以及用于去重和排序的记账字段。
 */
data class ClipItem(
    val id: String,
    val text: String? = null,
    /** 原始（PNG/JPEG）图片字节，以 base64 编码存储。 */
    val imageBase64: String? = null,
    val files: List<String> = emptyList(),
    val firstCopiedAt: Long = 0L,
    val lastCopiedAt: Long = 0L,
    val numberOfCopies: Int = 1,
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
     * 用于预览和搜索的文本。对应 `HistoryItem.previewableText`：图片没有文本表示，
     * 因此在文字识别填入之前，其标题保持为空。
     */
    val previewableText: String
        get() = when {
            files.isNotEmpty() -> files.joinToString("\n")
            !text.isNullOrBlank() -> text
            else -> title
        }

    val kind: ClipKind
        get() = when {
            imageBase64 != null && text.isNullOrBlank() && files.isEmpty() -> ClipKind.IMAGE
            files.isNotEmpty() -> ClipKind.FILE
            text != null && isHexColor(text) -> ClipKind.COLOR
            text != null && isLink(text) -> ClipKind.LINK
            else -> ClipKind.TEXT
        }

    /** 当本条目已包含 [other] 提供的全部内容时返回 `true`。 */
    fun supersedes(other: ClipItem): Boolean {
        val hasContent = other.text != null || other.imageBase64 != null || other.files.isNotEmpty()
        if (!hasContent) return false
        return (other.text == null || text == other.text) &&
            (other.imageBase64 == null || imageBase64 == other.imageBase64) &&
            (other.files.isEmpty() || files == other.files)
    }

    /**
     * 构建列表显示的单行标题。对应 `HistoryItem.generateTitle()`，包含 `showSpecialSymbols`
     * 偏好：开启时首尾空格显示为 `·`，换行与制表符显示为 `⏎`/`⇥`。
     */
    fun generateTitle(showSpecialSymbols: Boolean = true): String {
        val raw = previewableText.take(MAX_TITLE_LENGTH).removingUnsafeTitleScalars()
        if (!showSpecialSymbols) return raw.trim()

        return raw
            .replace(Regex("^ +")) { "·".repeat(it.value.length) }
            .replace(Regex(" +$")) { "·".repeat(it.value.length) }
            .replace("\n", "\u23ce")
            .replace("\t", "\u21e5")
    }

    companion object {
        const val MAX_TITLE_LENGTH = 1_000
    }
}

enum class ClipKind { TEXT, LINK, COLOR, IMAGE, FILE }

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

private val HEX_COLOR_PATTERN = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
private val LINK_PATTERN = Regex(
    "^(https?://|ftp://|mailto:|file://|www\\.)\\S+$",
    RegexOption.IGNORE_CASE,
)

fun isHexColor(value: String): Boolean = HEX_COLOR_PATTERN.matches(value.trim())

fun isLink(value: String): Boolean = LINK_PATTERN.matches(value.trim())
