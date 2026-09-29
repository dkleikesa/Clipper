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
    /**
     * 剪贴板上的**全部原始表示**（纯文本、图片、文件 URL、HTML / RTF / PDF…）。
     *
     * [text] / [image] / [files] 都是从这里派生出的便利视图，供领域层派生标题 / 类型 / 判空，
     * 不参与存储与写回；`contents` 才是唯一的原始数据（见 `MacClipboardDataSource.readSnapshot`）。
     */
    val contents: List<ClipboardContent> = emptyList(),
) {
    val isEmpty: Boolean
        get() = text.isNullOrEmpty() && image == null && files.isEmpty() && contents.isEmpty()
}
