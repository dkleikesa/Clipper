package com.qcmian.clipper.core.util

/**
 * 由字节魔数识别的图片格式。
 *
 * 单独成一个类型，而不是让两处各写一遍判断：**写回剪贴板**要它的粘贴板类型（UTI），
 * **导出文件**要它的扩展名，两者必须来自同一次判断，否则会出现「按 PNG 导出的文件、
 * 写回时却按 TIFF 声明」这种自相矛盾。
 */
enum class ImageFormat(
    /** 落盘时的扩展名，例如 `png`。 */
    val extension: String,
    /** 写回剪贴板时使用的 UTI；识别不出来时为 `null`，此时不写回图片。 */
    val uti: String?,
) {
    PNG("png", "public.png"),
    JPEG("jpg", "public.jpeg"),
    TIFF("tiff", "public.tiff"),
    GIF("gif", "com.compuserve.gif"),
    BMP("bmp", "com.microsoft.bmp"),
    WEBP("webp", "org.webmproject.webp"),
    HEIC("heic", "public.heic"),
    UNKNOWN("bin", null),
}

/**
 * 按魔数判断 [bytes] 的图片格式。
 *
 * 只看开头几个字节：图片魔数稳定且相互区分，而这份字节可能是几 MB 的图片——判断本身
 * 在写回剪贴板与导出时都会走，不该把它整份读一遍。
 */
fun imageFormatOf(bytes: ByteArray): ImageFormat = when {
    bytes.startsWith(0x89, 0x50, 0x4E, 0x47) -> ImageFormat.PNG
    bytes.startsWith(0xFF, 0xD8, 0xFF) -> ImageFormat.JPEG
    // TIFF 有两种字节序：little-endian `II*\0`、big-endian `MM\0*`。
    bytes.startsWith(0x49, 0x49, 0x2A, 0x00) -> ImageFormat.TIFF
    bytes.startsWith(0x4D, 0x4D, 0x00, 0x2A) -> ImageFormat.TIFF
    bytes.startsWith(0x47, 0x49, 0x46, 0x38) -> ImageFormat.GIF
    bytes.startsWith(0x42, 0x4D) -> ImageFormat.BMP
    // RIFF 容器还装着 WAV / AVI，这里沿用「RIFF 即 WebP」的粗判——剪贴板上的 RIFF
    // 实际只有 WebP 一种可能，多写四个字节的精确判定没有收益。
    bytes.startsWith(0x52, 0x49, 0x46, 0x46) -> ImageFormat.WEBP
    // ISO-BMFF：偏移 4 处是 `ftyp`。剪贴板上的这类容器实际只可能是 HEIC
    // （MP4 / AVIF 不会以「图片」的身份出现在粘贴板上），因此不再细看 brand。
    bytes.matchesAt(4, 0x66, 0x74, 0x79, 0x70) -> ImageFormat.HEIC
    else -> ImageFormat.UNKNOWN
}

/** 逐字节比对前缀；不足 [signature] 长度时返回 `false`。 */
private fun ByteArray.startsWith(vararg signature: Int): Boolean = matchesAt(0, *signature)

/** 从 [offset] 开始逐字节比对；越界时返回 `false`。 */
private fun ByteArray.matchesAt(offset: Int, vararg signature: Int): Boolean {
    if (size < offset + signature.size) return false
    return signature.withIndex().all { (index, byte) -> this[offset + index].toInt() and 0xFF == byte }
}
