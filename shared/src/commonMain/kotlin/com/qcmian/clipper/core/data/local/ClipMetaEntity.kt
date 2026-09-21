package com.qcmian.clipper.core.data.local

import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey

/**
 * 历史条目的**元数据行**（表 `clip_meta`）。
 *
 * 与载荷 [ClipPayloadEntity] 分表，是把「高频改写的窄字段」与「低频写入的大块数据」隔开的
 * 唯一手段：SQLite 的 `UPDATE` 会重写整条记录（含未修改的列），两者同行时，每次
 * 「复制次数 + 1」或置顶切换都会连带重写一遍图片 BLOB，写入量放大两三个数量级。
 *
 * 所有索引都带 `pinned` 前缀：「置顶区块」与「未置顶分页」各自按 4 种排序字段取数，
 * 联合索引让这些查询走索引扫描，而不是把 10 万行全排序后再 `LIMIT`。
 *
 * 这一层**不含任何编码过的结构**：文件路径是分隔符拼接的纯文本、来源应用是两个独立列。
 * 它们是每次启动都要全量读一遍的字段，任何一次多余的解析都会乘以条数。
 */
@Entity(
    tableName = "clip_meta",
    indices = [
        Index(value = ["pinned", "lastCopiedAt"]),
        Index(value = ["pinned", "firstCopiedAt"]),
        Index(value = ["pinned", "numberOfCopies"]),
        Index(value = ["pinned", "payloadBytes"]),
        Index(value = ["contentKey"]),
        // 将来做「按来源应用筛选 / 忽略」时用得上，现在的写入路径已经在维护它。
        Index(value = ["applicationBundleId"]),
    ],
)
data class ClipMetaEntity(
    @PrimaryKey val id: String,
    val title: String,
    /** [com.qcmian.clipper.core.settings.ClipFilterType] 的序号。 */
    val kind: Int,
    /**
     * 文件路径，用 NUL（`\u0000`）分隔；没有文件时为空串。
     *
     * 不用 JSON 数组：POSIX 路径不可能包含 NUL，分隔是安全的，而解析从「跑一趟 JSON 解析器」
     * 降成一次 `split`。
     */
    val files: String,
    /** [com.qcmian.clipper.core.domain.model.SourceApplication] 的应用名；没有来源时为 `null`。 */
    val applicationName: String?,
    /** 同上，Bundle 标识符。单独成列是为了能查询、能建索引——JSON 列做不到。 */
    val applicationBundleId: String?,
    val firstCopiedAt: Long,
    val lastCopiedAt: Long,
    val numberOfCopies: Int,
    /** `1` 表示已置顶。用整数而不是可空字符串，换来了索引里一个干净的等值前导列。 */
    val pinned: Int,
    /** 载荷的近似字节数，用于「内容大小」排序与存储上限。 */
    val payloadBytes: Long,
    /** 内容摘要，见 `contentKeyOf`。 */
    val contentKey: String,
    /** 标题是否来自图片文字识别。 */
    val hasRecognizedText: Boolean,
    /**
     * 条目是否带图片。
     *
     * 载荷里已经有图片字节了，这里单独存一列，是为了让**列表行高**不必去看载荷。
     * 行高决定窗口高度与滚动条长度，判据必须是元数据的一个字段，否则「列表按需加载图片」
     * 会让同一份内容在加载前后算出两个不同的总高。
     */
    val hasImage: Boolean,
)
