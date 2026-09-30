package com.qcmian.clipper.core.domain.model

import androidx.compose.runtime.Immutable
import com.qcmian.clipper.core.settings.ClipFilterType
import com.qcmian.clipper.core.util.fnv1a64

/**
 * 剪贴板历史里**不含载荷**的那一半。
 *
 * 拆分的目的是内存预算：10 万条历史只把这一层算进来（每条约几百字节），图片字节、完整正文
 * 与富文本留在 [ClipItem] / 存储层，按 id 懒加载（见 [ClipPayload]）。
 *
 * 分工：
 * - [ClipMeta]：列表渲染、搜索、排序、导航、计数
 * - [ClipItem]：预览、写回剪贴板、图片文字识别——需要真正的载荷
 *
 * 字段口径与 [ClipItem] 保持一致，`toMeta()` / `toItem()` 之间只丢载荷，不丢语义。
 */
@Immutable
data class ClipMeta(
    val id: String,
    /** 列表标题；图片条目的标题来自文字识别，可能是多行原文。 */
    val title: String,
    /** 条目在筛选栏里的归属类型，与 `ClipItem.clipType` 同源。 */
    val kind: ClipFilterType,
    val files: List<String>,
    val application: SourceApplication?,
    val firstCopiedAt: Long,
    val lastCopiedAt: Long,
    val numberOfCopies: Int,
    /** 非空即表示已置顶，值本身没有含义（见 `ClipItem.pin`）。 */
    val pin: String?,
    /** 载荷占用的近似字节数，用于「内容大小」排序与存储上限。 */
    val payloadBytes: Long,
    /** 内容摘要，用于把「这条是否已存在」下推成一次等值查询（见 [contentKeyOf]）。 */
    val contentKey: String,
    val hasRecognizedText: Boolean,
    /**
     * 条目是否带图片。
     *
     * 图片字节在载荷里，但**行高**必须只看元数据：行高同时决定窗口高度与滚动条长度，
     * 若以「图片是否已加载」为判据，同一份内容在加载前后会算出两个总高。
     */
    val hasImage: Boolean,
) {
    val isPinned: Boolean get() = pin != null
    val isUnpinned: Boolean get() = pin == null
}

/**
 * 由一次复制的**全部原始表示**派生出内容摘要。
 *
 * 只吃 [ClipboardContent]：条目的文本 / 图片 / 文件 / 富文本现在都只在那一份里，不再有并行的
 * 字段需要一起哈希——这正是 `ClipPayload.contents` 成为唯一真值源之后的形态。
 *
 * 它把原来的 `ClipItem.supersedes`——两两逐字段比较、需要一整份历史驻留内存——换成一次
 * `WHERE contentKey = ?`，这是「历史不再全量加载」的前提。
 *
 * 语义上有一处刻意的收窄：`supersedes` 判断的是「包含」，这里判断的是「相同」。
 * 一次复制产生的是固定的表示集合，重复复制必然命中同一个摘要，实际行为一致。
 */
fun contentKeyOf(contents: List<ClipboardContent>): String =
    buildString(80) {
        // 按 (item 序号, 类型名) **稳定排序**后拼接：剪贴板给出的顺序不保证稳定，而摘要必须只
        // 取决于内容本身——否则同一份内容重复复制会算出不同的 key，去重直接失效。稳定排序保留
        // 了同类型内部的先后（多个文件路径的先后是有意义的）。
        for (content in contents.sortedWith(compareBy({ it.itemIndex }, { it.type }))) {
            append(content.itemIndex).append(':')
            append(fnv1a64(content.type)).append(':')
            append(content.value?.let(::fnv1a64) ?: 0L).append(',')
        }
    }

/**
 * 从完整条目派生出元数据。
 *
 * [title][ClipMeta.title] 存的是**原文**而不是列表显示用的单行串：`·` / `⏎` / `⇥` 的替换
 * 发生在渲染时（见 `titleForDisplay`）。这样「特殊符号」开关不再需要重写全部历史，
 * 而标题作为「复制图片文字」的内容时也不会把真换行一起换掉。
 */
fun ClipItem.toMeta(): ClipMeta = ClipMeta(
    id = id,
    title = previewableText.toStoredTitle(),
    kind = clipType,
    files = files,
    application = application,
    firstCopiedAt = firstCopiedAt,
    lastCopiedAt = lastCopiedAt,
    numberOfCopies = numberOfCopies,
    pin = pin,
    payloadBytes = approximateSizeBytes,
    contentKey = contentKeyOf(contents),
    hasRecognizedText = hasRecognizedText,
    hasImage = image != null,
)

/**
 * 从完整条目提取载荷。
 *
 * 只交回[原始表示][ClipPayload.contents]与搜索用的文本索引：图片不再单独存一份，它就在
 * `contents` 里，[ClipPayload.image] 读取时会派生出来。文件路径留在 [ClipMeta] 里，不算载荷。
 */
fun ClipItem.toPayload(): ClipPayload = ClipPayload(
    contents = contents,
    text = text,
)
