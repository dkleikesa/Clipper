package com.qcmian.clipper.core.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * 系统剪贴板上一种类型的原始内容。
 *
 * [type] 是原生粘贴板类型标识（UTI，例如 `public.html`、`public.rtf`、`public.pdf`），
 * [value] 是该类型声明的原始字节。与 [ClipImage] 同理，[value] 是 [ByteArray]，
 * 因此这里覆盖 [equals] / [hashCode] 为内容语义——放进 `ClipItem` 这样的 data class 后，
 * 去重（`supersedes`）与列表 diff 才不会把内容相同、引用不同的两份数据误判为不同。
 *
 * [value] 为 `null` 表示「只有类型、没有载荷」的声明（某些应用会这样标记特殊类型）。
 */
@Immutable
@Serializable
class ClipboardContent(
    val type: String,
    val value: ByteArray? = null,
) {
    /** 原始字节数。 */
    val size: Int get() = value?.size ?: 0

    /** 底层的原始字节。调用方不得修改返回的数组。 */
    fun toByteArray(): ByteArray? = value

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClipboardContent) return false
        if (type != other.type) return false
        return if (value == null) other.value == null else value.contentEquals(other.value)
    }

    override fun hashCode(): Int = 31 * type.hashCode() + (value?.contentHashCode() ?: 0)

    override fun toString(): String = "ClipboardContent($type, ${size} bytes)"
}
