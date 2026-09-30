package com.qcmian.clipper.core.domain.model

import androidx.compose.runtime.Immutable
import com.qcmian.clipper.core.util.fnv1a64

/**
 * 剪贴板图片的**原始字节**。
 *
 * 格式就是复制时剪贴板给出的那一种（macOS 上通常是 TIFF，也可能是 PNG / JPEG…）。
 * 捕获层**不做任何转码**——「复制什么格式、粘贴还是什么格式」由它保证：显示层遇到
 * Compose 解码器不认的格式会自行转码（见 `ImageCache`），与这里存的是什么无关。
 *
 * 刻意单独成一个类型，而不是直接用裸 `ByteArray`：`ClipPayload` 是 data class，而数组的
 * 相等性按引用比较——直接把数组放进数据类，列表的变更检测会静默失效。这里用内容语义的
 * [equals] / [hashCode] 把它变成值对象。
 *
 * 与落库使用的表示一致（Room 里就是一个 BLOB），因此内存与磁盘之间不再需要 base64
 * 编解码，也不再有 33% 的编码膨胀。
 */
@Immutable
class ClipImage(bytes: ByteArray) {
    private val bytes: ByteArray = bytes

    val size: Int get() = bytes.size

    /** 底层的原始字节。调用方不得修改返回的数组。 */
    fun toByteArray(): ByteArray = bytes

    /**
     * 由内容派生的 64 位 FNV-1a 哈希，供 UI 位图缓存做键，也用于 `remember` 的快速比较——
     * 这样每次重组都不必逐字节比较整幅图片。图片不可变，该值只计算一次。
     */
    val cacheKey: Long by lazy { fnv1a64(bytes) xor bytes.size.toLong() }

    override fun equals(other: Any?): Boolean =
        this === other || (other is ClipImage && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ClipImage(${bytes.size} bytes)"
}
