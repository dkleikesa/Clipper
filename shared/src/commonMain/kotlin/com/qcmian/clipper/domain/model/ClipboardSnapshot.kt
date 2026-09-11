package com.qcmian.clipper.domain.model

/**
 * 系统剪贴板的平台无关视图。
 *
 * `imageBase64` 保存编码后的原始图片字节（PNG 或 JPEG），这样该值无需依赖平台图片类型即可持久化。
 *
 * [types] 携带剪贴板声明的粘贴板类型标识，用于按 Maccy 的方式支持「忽略的剪贴板类型」偏好。
 */
data class ClipboardSnapshot(
    val text: String? = null,
    val imageBase64: String? = null,
    val files: List<String> = emptyList(),
    val types: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isNullOrEmpty() && imageBase64 == null && files.isEmpty()
}
