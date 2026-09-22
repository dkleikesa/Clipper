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
    /**
     * 图片文字识别的**完整原文**；没有识别或识别失败时为 `null`。
     *
     * 它必须存在这里而不是 [ClipMeta.title]：标题是按「列表里的一行」截断过的搜索源，
     * 而识别原文可能有好几万字符（滚动长截图）。存放在载荷里的另一个好处是它不参与
     * 列表常驻内存——只有「复制图片文字」与预览面板会按 id 读它一次。
     */
    val recognizedText: String? = null,
) {
    /** 没有任何载荷（例如只携带文件路径的条目）。 */
    val isEmpty: Boolean
        get() = text == null && image == null && contents.isEmpty() && recognizedText == null
}
