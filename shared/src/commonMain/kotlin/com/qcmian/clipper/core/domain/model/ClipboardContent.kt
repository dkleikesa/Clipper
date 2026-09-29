package com.qcmian.clipper.core.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * 系统剪贴板上一种类型的原始内容。
 *
 * [type] 是原生粘贴板类型标识（UTI，例如 `public.html`、`public.rtf`、`public.pdf`），
 * [value] 是该类型声明的原始字节。与 [ClipImage] 同理，[value] 是 [ByteArray]，
 * 因此这里覆盖 [equals] / [hashCode] 为内容语义——放进 `ClipPayload` / `ClipItem` 这样的
 * data class 后，列表 diff 与内容摘要（`contentKeyOf`）才不会把内容相同、引用不同的两份
 * 数据误判为不同。
 *
 * [value] 为 `null` 表示「只有类型、没有载荷」的声明（某些应用会这样标记特殊类型）。
 */
@Immutable
@Serializable
class ClipboardContent(
    val type: String,
    val value: ByteArray? = null,
    /**
     * 这份表示属于粘贴板的第几个 item。
     *
     * 粘贴板是「items × types」的二维结构：**同一个类型可以出现多次**——复制多个文件、多张
     * 图片时，每个 item 各带一份 `public.file-url` / `public.tiff`。它们之间的从属关系必须在
     * 写回时还原，否则同类型会互相覆盖（见 `MacPasteboard.write`）。读取时按下标填入。
     *
     * 默认值让早期落库的数据（没有这一维）解码回「单个 item」。
     */
    val itemIndex: Int = 0,
) {
    /** 原始字节数。 */
    val size: Int get() = value?.size ?: 0

    /** 底层的原始字节。调用方不得修改返回的数组。 */
    fun toByteArray(): ByteArray? = value

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClipboardContent) return false
        if (type != other.type) return false
        if (itemIndex != other.itemIndex) return false
        return if (value == null) other.value == null else value.contentEquals(other.value)
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + (value?.contentHashCode() ?: 0)
        result = 31 * result + itemIndex
        return result
    }

    override fun toString(): String = "ClipboardContent($type, ${size} bytes, item=$itemIndex)"
}

/** PNG 类型；同一张图有多种编码时优先取它——无损、体积小，解码器直接认得。 */
const val PNG_CONTENT_TYPE: String = "public.png"

/**
 * 剪贴板上的图片类型（macOS UTI）。
 *
 * 放在领域层而不是数据源里：[ClipPayload.image] 需要从载荷持有的原始表示中挑出图片那一份
 * ——这是「载荷只留一份原始数据」的必然要求，而它并不是 macOS 独有的知识。
 *
 * **刻意不含 `com.apple.icns`**：那是 Finder 一类应用给文件附带的**图标**，不是用户复制的
 * 内容图片。实测复制一个普通文件时，粘贴板上会有一条 800 KB 上下的图标；把它当图片收下，
 * 会让「复制文件」变成「存了一张大图」（`hasImage` 为真、存储凭空膨胀）。图标在数据源层被
 * 直接过滤掉——目标应用会自己生成它，留着没有保真价值。
 */
val IMAGE_CONTENT_TYPES: Set<String> = setOf(
    PNG_CONTENT_TYPE,
    "public.tiff",
    "public.jpeg",
    "public.heic",
)

/**
 * 从一批原始表示里挑出图片的原始字节；没有图片时为 `null`。
 *
 * 优先 PNG（见 [PNG_CONTENT_TYPE]），否则取最先出现的图片类型——macOS 上通常是 TIFF。
 * 挑出来的字节**不做任何转码**，显示层遇到解码器不认的格式会自行处理。
 */
fun List<ClipboardContent>.toClipImage(): ClipImage? {
    val picked = firstOrNull { it.type == PNG_CONTENT_TYPE }
        ?: firstOrNull { it.type in IMAGE_CONTENT_TYPES }
    return picked?.value?.takeIf { it.isNotEmpty() }?.let(::ClipImage)
}
