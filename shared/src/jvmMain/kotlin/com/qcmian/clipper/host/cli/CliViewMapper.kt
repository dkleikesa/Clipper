package com.qcmian.clipper.host.cli

import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.settings.ClipFilterType
import com.qcmian.clipper.protocol.CliAttachmentView
import com.qcmian.clipper.protocol.CliFileView
import com.qcmian.clipper.protocol.CliOcrView
import com.qcmian.clipper.protocol.CliView
import com.qcmian.clipper.protocol.TITLE_CHAR_LIMIT
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 领域模型 → 线上视图。
 *
 * 这一层之所以必须存在，是因为**存储层的形态几乎全都不能直接给出去**：
 * `kind` 是枚举序号、`files` 是一根 NUL 分隔的字符串、时间戳是 epoch 毫秒、
 * 附加表示是 CBOR blob。放在这里做，数据库改编码、加列、换实现都不会影响 agent 看到的契约。
 *
 * 纯函数，除了 [detail] 会读磁盘判断文件是否还在（见 [CliFileView.exists] 的说明）。
 */
internal object CliViewMapper {

    fun kindName(kind: ClipFilterType): String = when (kind) {
        ClipFilterType.TEXT -> "text"
        ClipFilterType.IMAGE -> "image"
        ClipFilterType.FILE -> "file"
        ClipFilterType.RICH_TEXT -> "richtext"
    }

    /** 认不出来时返回 `null`，由调用方回一条 `BAD_REQUEST`，而不是悄悄当成「不过滤」。 */
    fun kindFromName(name: String): ClipFilterType? = when (name.lowercase()) {
        "text" -> ClipFilterType.TEXT
        "image" -> ClipFilterType.IMAGE
        "file" -> ClipFilterType.FILE
        "richtext", "rich_text" -> ClipFilterType.RICH_TEXT
        else -> null
    }

    /**
     * epoch 毫秒 → ISO 8601 带时区。
     *
     * 不给 epoch：agent 拿到 `1758776000000` 之后算不出「这是多久以前」，还得自己推时区与闰秒。
     * 带偏移的 ISO 字符串可以直接读，同一时区下也能拿来做字符串比较与排序。
     */
    fun iso(millis: Long): String =
        Instant.ofEpochMilli(millis)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    /**
     * 列表条目：只吃元数据。
     *
     * 因此 `attachments` / `text` / `ocr` / `path` 都留空——它们的载荷不在内存里，
     * 而列表一次可能回 20 条，为每条都去读一次 BLOB 会把「列表很便宜」这件事毁掉。
     */
    fun summary(meta: ClipMeta): CliView = CliView(
        id = meta.id,
        kind = kindName(meta.kind),
        title = meta.title.take(TITLE_CHAR_LIMIT),
        titleTruncated = isTitleTruncated(meta.kind, meta.title),
        bytes = meta.payloadBytes,
        copies = meta.numberOfCopies,
        pinned = meta.isPinned,
        firstCopiedAt = iso(meta.firstCopiedAt),
        lastCopiedAt = iso(meta.lastCopiedAt),
        sourceApp = meta.application?.name,
        // 列表不查磁盘：一次 list 会变成几十次 stat。`exists` 省略表示「未检查」，不是 false。
        files = meta.files.map { CliFileView(path = it) },
        hasImage = meta.hasImage,
        hasOcr = meta.hasRecognizedText,
    )

    /** 详情条目：吃完整载荷。[exported] 是已经落盘好的二进制路径，没有则为 `null`。 */
    fun detail(item: ClipItem, exported: Path?): CliView {
        val kind = item.clipType
        // 标题为空时退回正文：`title` 在存储里可能还是空的（历史遗留，靠启动回填补齐）。
        val title = item.title.ifBlank { item.previewableText }
        val ocrText = item.recognizedText?.takeIf { it.isNotBlank() }

        return CliView(
            id = item.id,
            kind = kindName(kind),
            title = title.take(TITLE_CHAR_LIMIT),
            titleTruncated = isTitleTruncated(kind, title),
            bytes = item.approximateSizeBytes,
            copies = item.numberOfCopies,
            pinned = item.isPinned,
            firstCopiedAt = iso(item.firstCopiedAt),
            lastCopiedAt = iso(item.lastCopiedAt),
            sourceApp = item.application?.name,
            // 详情才查磁盘：调用方明确要了这一条，一次 stat 换掉它一次额外往返，划算。
            files = item.files.map { CliFileView(path = it, exists = File(it).exists()) },
            hasImage = item.image != null,
            hasOcr = ocrText != null,
            attachments = item.contents.map { CliAttachmentView(type = it.type, bytes = it.size) },
            text = textFor(item, kind),
            ocr = ocrText?.let { CliOcrView(text = it, chars = it.length, truncated = false) },
            path = exported?.toString(),
        )
    }

    /**
     * 什么时候回 `text`。
     *
     * 只有文本与富文本：这两类的「内容」就是一段文字。
     *
     * 图片这一支留空，是因为它的主体是像素，把标题当正文再给一遍只会让 agent 以为
     * 那才是内容——它的可读内容在 `ocr` 与 `path` 里。
     *
     * 文件这一支留空，是因为内容就是路径本身，而 `files` 已经**逐条**给了；照
     * `previewableText` 再回一份用 `\n` 拼起来的字符串，只会让同一个信息在响应里出现两次。
     */
    private fun textFor(item: ClipItem, kind: ClipFilterType): String? = when (kind) {
        ClipFilterType.IMAGE, ClipFilterType.FILE -> null
        else -> item.previewableText.ifBlank { null }
    }

    /**
     * [CliView.title] 是否不完整。
     *
     * 两种情况都为真：
     * - 标题本身超过 [TITLE_CHAR_LIMIT]（存储里最长 1000，见 `ClipItem.MAX_TITLE_LENGTH`）；
     * - 条目主体不是文本（图片）——那种情况下标题最多是识别结果，图本身永远不在标题里。
     *
     * 文件条目不必特判：路径列表已经被拼进标题，多文件时会自然超长。
     */
    private fun isTitleTruncated(kind: ClipFilterType, title: String): Boolean =
        kind == ClipFilterType.IMAGE || title.length > TITLE_CHAR_LIMIT
}
