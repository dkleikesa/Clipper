package com.qcmian.clipper.core.data.local

import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.model.ClipPayload
import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.domain.model.SourceApplication
import com.qcmian.clipper.core.settings.ClipFilterType
import com.qcmian.clipper.core.util.decodeCborOrNull
import com.qcmian.clipper.core.util.encodeCbor

/** `pinned` 列的取值：非零即置顶；用整数是为了让联合索引有一个干净的等值前导列。 */
internal const val PINNED = 1
internal const val UNPINNED = 0

/**
 * `pinnedAt` 在「未置顶」时的取值。
 *
 * 这一列只在置顶期间有意义，取消置顶就清零：留着旧值会让「先取消、再重新置顶」拿到一个
 * 过期的时间戳，从而排在它该在的位置之外（见 `ClipMetaEntity.pinnedAt`）。
 */
internal const val NOT_PINNED_AT = 0L

/**
 * 文件路径之间的分隔符。
 *
 * 用 NUL 而不是 JSON：POSIX 路径**不可能**包含 NUL，拼接是安全的；而解析成本从
 * 「跑一趟 JSON 解析器」降成一次 `split`——这个字段每次启动要对全部条目解一遍。
 */
private const val FILE_SEPARATOR = '\u0000'

// ---------------------------------------------------------------------------------------
// 领域模型 → 实体
// ---------------------------------------------------------------------------------------

// `ClipItem.toMeta()` / `ClipItem.toPayload()` 是领域层的扩展函数（见 `ClipMeta.kt`），
// 因为它们只依赖领域模型本身，数据层不必再重复一份。

internal fun ClipMeta.toEntity(): ClipMetaEntity = ClipMetaEntity(
    id = id,
    title = title,
    kind = kind.ordinal,
    files = encodeFiles(files),
    applicationName = application?.name,
    applicationBundleId = application?.bundleId,
    firstCopiedAt = firstCopiedAt,
    lastCopiedAt = lastCopiedAt,
    numberOfCopies = numberOfCopies,
    pinned = if (isPinned) PINNED else UNPINNED,
    // 新条目不可能一进来就是置顶的；置顶时间由 `setPinned` 现发（见 `NOT_PINNED_AT`）。
    pinnedAt = NOT_PINNED_AT,
    payloadBytes = payloadBytes,
    contentKey = contentKey,
    hasRecognizedText = hasRecognizedText,
    hasImage = hasImage,
)

internal fun ClipPayload.toEntity(id: String): ClipPayloadEntity = ClipPayloadEntity(
    id = id,
    text = text,
    image = image?.toByteArray(),
    // 没有附加表示的条目（绝大多数）连 BLOB 都不写。
    contents = if (contents.isEmpty()) null else encodeCbor(contents),
    recognizedText = recognizedText,
)

// ---------------------------------------------------------------------------------------
// 实体 → 领域模型
// ---------------------------------------------------------------------------------------

internal fun ClipMetaEntity.toModel(): ClipMeta = ClipMeta(
    id = id,
    title = title,
    // 枚举序号可能来自更早的版本（枚举成员被增删），越界时退回最宽泛的「文本」。
    kind = ClipFilterType.entries.getOrElse(kind) { ClipFilterType.TEXT },
    files = decodeFiles(files),
    // 应用名与 bundleId 同行同生共死：name 为 null 即表示「没有来源应用」。
    application = applicationName?.let { SourceApplication(it, applicationBundleId) },
    firstCopiedAt = firstCopiedAt,
    lastCopiedAt = lastCopiedAt,
    numberOfCopies = numberOfCopies,
    pin = if (pinned == PINNED) ClipItem.PINNED_MARKER else null,
    payloadBytes = payloadBytes,
    contentKey = contentKey,
    hasRecognizedText = hasRecognizedText,
    hasImage = hasImage,
)

internal fun ClipPayloadEntity.toModel(): ClipPayload = ClipPayload(
    text = text,
    image = image?.let(::ClipImage),
    contents = decodeCborOrNull<List<ClipboardContent>>(contents).orEmpty(),
    recognizedText = recognizedText,
)

// ---------------------------------------------------------------------------------------
// 拼装
// ---------------------------------------------------------------------------------------

/** 元数据 +（可空的）载荷还原成完整条目。载荷尚未加载时那些字段保持为空。 */
internal fun ClipMeta.toItem(payload: ClipPayload?): ClipItem = ClipItem(
    id = id,
    text = payload?.text,
    image = payload?.image,
    files = files,
    contents = payload?.contents.orEmpty(),
    firstCopiedAt = firstCopiedAt,
    lastCopiedAt = lastCopiedAt,
    numberOfCopies = numberOfCopies,
    pin = pin,
    title = title,
    // 用存储里的真实标记，而不是让 `ClipItem` 按字段组合去猜：标题有四个来源，前三个
    // （正文 / 文件路径 / 附加表示提取的文字）与「图片识别成功」在数据上长得一样。
    hasRecognizedText = hasRecognizedText,
    recognizedText = payload?.recognizedText,
    application = application,
)

// ---------------------------------------------------------------------------------------
// 文件路径
// ---------------------------------------------------------------------------------------

private fun encodeFiles(files: List<String>): String =
    if (files.isEmpty()) "" else files.joinToString(FILE_SEPARATOR.toString())

private fun decodeFiles(encoded: String): List<String> =
    if (encoded.isEmpty()) emptyList() else encoded.split(FILE_SEPARATOR)
