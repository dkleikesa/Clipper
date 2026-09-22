package com.qcmian.clipper.core.data.local

import androidx.room3.Entity
import androidx.room3.PrimaryKey

/**
 * 历史条目的**载荷行**（表 `clip_payload`）。
 *
 * 这张表里的行只做「整条插入 / 整条删除」，永远不会被部分更新——重复复制只改元数据，
 * 内容相同时连写都不写。因此它不需要任何二级索引，也不会积累页分裂；磁盘回收只靠
 * `PRAGMA incremental_vacuum`，从不依赖 `VACUUM`（那要重写整库）。
 */
@Entity(tableName = "clip_payload")
data class ClipPayloadEntity(
    @PrimaryKey val id: String,
    val text: String?,
    /** 原始（PNG / JPEG）图片字节，直接以 BLOB 存储。 */
    val image: ByteArray?,
    /**
     * 附加表示（HTML / RTF / PDF 等）的 **CBOR** 编码；没有时为 `null`。
     *
     * 一条记录可能同时带好几份（一次复制携带多种格式是 macOS 粘贴板的固有行为），
     * 所以这里存的是 `List<ClipboardContent>` 整体。
     *
     * 为什么是 BLOB + CBOR 而不是 TEXT + JSON：`ClipboardContent.value` 是 `ByteArray`，
     * JSON 只能把它编成**数字数组**（实测约 3.6 倍膨胀，比 base64 还差），CBOR 是二进制格式，
     * 原生支持字节数组，落盘即原样。
     */
    val contents: ByteArray?,
    /**
     * 图片文字识别的**完整原文**；没有识别或识别失败时为 `null`。
     *
     * 元数据里的 `title` 只是它的前一段（按列表显示的粒度截断），「复制图片文字」要的是
     * 完整那一份，因此原文单独存这里。
     */
    val recognizedText: String?,
)
