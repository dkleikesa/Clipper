package com.qcmian.clipper.core.domain.model

import androidx.compose.runtime.Immutable

/**
 * 条目的**载荷**：只有真正用到时才从存储里取出来的那部分。
 *
 * 与 [ClipMeta] 配对。列表只读元数据，预览面板与「写回剪贴板」才按 id 取一份 [ClipPayload]，
 * 因此历史里有多少张图片、多少兆正文，都不影响列表常驻的内存。
 *
 * **[contents] 是载荷的唯一真值源**：剪贴板上原有的每一种表示（纯文本、图片、文件 URL、
 * HTML / RTF…）连同它的原始字节都在里面，写回时逐类型原样搬回剪贴板。其余成员都是它的
 * 派生视图或索引，不构成第二份原始数据。
 *
 * 文件路径仍留在 [ClipMeta]：它很小、又要参与列表展示，不该为了显示一行字去解析载荷。
 */
@Immutable
class ClipPayload(
    /**
     * 条目的**全部原始表示**——载荷的唯一真值源。
     *
     * 早期版本的这一列只装「附加表示」（富文本等），文本与图片另存两列；现在它们也在这里，
     * 因此不会再出现「同一份内容只留住了其中一种编码」的坍缩。
     */
    val contents: List<ClipboardContent> = emptyList(),
    /**
     * 全文搜索用的**物化文本**。
     *
     * 它是从 [contents] 派生后落库的索引，**不是原始数据的第二份**——原始字节仍在 [contents]
     * 里。之所以要物化：全文搜索只投影 `id / text / recognizedText` 三个窄列，若改为每次
     * 解析 [contents] 的 CBOR，一次深搜就要解几十 MB 的 BLOB。
     */
    val text: String? = null,
    /**
     * 图片文字识别的**完整原文**；没有识别或识别失败时为 `null`。
     *
     * 它必须存在这里而不是 [ClipMeta.title]：标题是按「列表里的一行」截断过的搜索源，
     * 而识别原文可能有好几万字符（滚动长截图）。存放在载荷里的另一个好处是它不参与
     * 列表常驻内存——只有「复制图片文字」与预览面板会按 id 读它一次。
     */
    val recognizedText: String? = null,
    /**
     * 早期版本把图片单独存了一列（那一版没有保留全部原始类型）。读旧数据时从这里兜底；
     * 新数据的图片在 [contents] 里，这里恒为 `null`。
     */
    val legacyImage: ClipImage? = null,
) {
    /**
     * 图片：新数据从 [contents] 里挑，旧数据回退到 [legacyImage]。
     *
     * 缓存一次：预览面板每次重组都会读它，而挑图要遍历 [contents]。
     */
    val image: ClipImage? by lazy { legacyImage ?: contents.toClipImage() }

    /** 没有任何载荷（例如只携带文件路径的条目）。 */
    val isEmpty: Boolean
        get() = contents.isEmpty() && text == null && recognizedText == null && legacyImage == null
}
