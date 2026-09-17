package com.qcmian.clipper.core.data.local

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.SourceApplication
import com.qcmian.clipper.core.util.decodeJsonOrNull
import com.qcmian.clipper.core.util.encodeJson

/**
 * 剪贴板历史的一行。
 *
 * 一次复制的三种表示放在一起，与 [ClipItem] 完全一致。文件列表与来源应用都很小、也没有自己
 * 的身份，因此存为 JSON 列，而不必再拉进两张表。
 */
@Entity(tableName = "clip_history")
data class ClipItemEntity(
    @PrimaryKey val id: String,
    val text: String?,
    /** 原始（PNG/JPEG）图片字节，直接以 BLOB 存储——与领域模型里的表示完全一致。 */
    val image: ByteArray?,
    /** 文件路径的 JSON 数组。 */
    val files: String,
    val firstCopiedAt: Long,
    val lastCopiedAt: Long,
    val numberOfCopies: Int,
    val pin: String?,
    val title: String,
    /** [SourceApplication] 的 JSON 对象；平台无法判断时为 `null`。 */
    val application: String?,
)

internal fun ClipItemEntity.toModel(): ClipItem = ClipItem(
    id = id,
    text = text,
    image = image?.let(::ClipImage),
    files = decodeJsonOrNull<List<String>>(files).orEmpty(),
    firstCopiedAt = firstCopiedAt,
    lastCopiedAt = lastCopiedAt,
    numberOfCopies = numberOfCopies,
    pin = pin,
    title = title,
    application = decodeJsonOrNull<SourceApplication>(application.orEmpty()),
)

internal fun ClipItem.toEntity(): ClipItemEntity = ClipItemEntity(
    id = id,
    text = text,
    image = image?.toByteArray(),
    files = encodeJson(files),
    firstCopiedAt = firstCopiedAt,
    lastCopiedAt = lastCopiedAt,
    numberOfCopies = numberOfCopies,
    pin = pin,
    title = title,
    application = application?.let { encodeJson(it) },
)

/**
 * [ClipItem] 到 [ClipItemLite] 的映射。与 [toEntity] 除 image 外逐列一致——diff 时两边
 * 都从同一份领域模型导出，JSON 序列化对相同输入是确定性的，因此逐字段相等就等价于行未变化。
 */
internal fun ClipItem.toRowLite(): ClipItemLite = ClipItemLite(
    id = id,
    text = text,
    files = encodeJson(files),
    firstCopiedAt = firstCopiedAt,
    lastCopiedAt = lastCopiedAt,
    numberOfCopies = numberOfCopies,
    pin = pin,
    title = title,
    application = application?.let { encodeJson(it) },
)
