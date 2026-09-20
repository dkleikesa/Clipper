package com.qcmian.clipper.core.domain.model

import androidx.compose.runtime.Immutable

/**
 * 系统剪贴板的平台无关视图。
 *
 * [image] 保存原始的图片字节（PNG 或 JPEG），这样该值无需依赖平台图片类型即可持久化，
 * 也不必在内存中做一次 base64 编解码。
 *
 * [types] 携带剪贴板声明的粘贴板类型标识，用于支持「忽略的剪贴板类型」偏好。
 */
@Immutable
data class ClipboardSnapshot(
    val text: String? = null,
    val image: ClipImage? = null,
    val files: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    /** 未被 [text] / [image] / [files] 单独建模的额外类型（HTML、RTF、PDF、URL 等）的原始字节。 */
    val contents: List<ClipboardContent> = emptyList(),
) {
    val isEmpty: Boolean
        get() = text.isNullOrEmpty() && image == null && files.isEmpty() && contents.isEmpty()
}
