package com.qcmian.clipper.core.domain.model

import androidx.compose.runtime.Immutable

/**
 * 条目的**载荷**：只有真正用到时才从存储里取出来的那部分。
 *
 * 与 [ClipMeta] 配对。列表只读元数据，预览面板与「写回剪贴板」才按 id 取一份 [ClipPayload]，
 * 因此历史里有多少张图片、多少兆正文，都不影响列表常驻的内存。
 *
 * 文件路径不在其中：它很小、又要参与列表展示，属于 [ClipMeta]。
 */
@Immutable
class ClipPayload(
    val text: String? = null,
    val image: ClipImage? = null,
    val contents: List<ClipboardContent> = emptyList(),
) {
    /** 没有任何载荷（例如只携带文件路径的条目）。 */
    val isEmpty: Boolean get() = text == null && image == null && contents.isEmpty()
}
