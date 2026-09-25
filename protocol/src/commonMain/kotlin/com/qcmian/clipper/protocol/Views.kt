package com.qcmian.clipper.protocol

import kotlinx.serialization.Serializable

/**
 * 输出给 agent / 脚本的条目视图。
 *
 * **它是独立的契约，不是 `ClipMeta` / `ClipItem` 的序列化形式。** 存储层的形态几乎全都
 * 不能直接给出去：`kind` 是枚举序号（`2`，没人看得懂）、`files` 是 NUL 分隔的一个字符串、
 * 时间戳是 epoch 毫秒、附加表示是 CBOR blob。这层映射放在 app 侧，于是数据库改编码、
 * 加列、换实现都不会影响 agent 看到的契约。
 *
 * **[list] 与 [get] 返回同一个类型**，只是 `get` 会把下半部分（`text` / `ocr` /
 * `imagePath`）填上。同一个形状意味着调用方只要写一套解析逻辑，不必按命令分支。
 */
@Serializable
data class CliView(
    val id: String,
    /** `text` / `image` / `file` / `richtext`。字符串而非整数，见类注释。 */
    val kind: String,

    /**
     * 可读的一行标识，**最多 [TITLE_CHAR_LIMIT] 个字符**。
     *
     * 就是 `ClipMeta.title` 截断后的样子——图片条目是识别出的文字，文件条目是路径，
     * 文本条目是正文开头。**不是全文**：要全文用 `get`，那里会回 [CliView.text]。
     *
     * 上限存在是因为列表一次可能回 20 条，而 `title` 在存储里最长可到 1000 字符
     * （见 `ClipItem.MAX_TITLE_LENGTH`）——不截断的话，一次 `list` 就能吃掉两万字符。
     */
    val title: String,

    /**
     * [title] 是否被截断（或该条目的主体根本不是文本，例如图片）。
     *
     * 名字里带 `title` 是为了**避免一个真实的误读**：它说的只是「这一行标识不完整」，
     * 不代表整个响应不完整。`get` 里它照样可能是 `true`（标题仍是一行），
     * 而那时 [CliView.text] 已经是全文了。
     */
    val titleTruncated: Boolean,

    /** 该条目的近似字节数（图片是精确值，文本按 UTF-8 计）。 */
    val bytes: Long = 0,
    val copies: Int = 1,
    val pinned: Boolean = false,

    /** ISO 8601 带时区，例如 `2026-09-25T14:30:00+08:00`。不是 epoch 毫秒。 */
    val firstCopiedAt: String,
    val lastCopiedAt: String,

    /** 复制来源的应用名；平台不支持或没有来源时省略。 */
    val sourceApp: String? = null,

    /** 文件类型条目携带的路径。其余类型为空。 */
    val files: List<CliFileView> = emptyList(),

    /**
     * 条目里有图片。
     *
     * 独立于 [kind] 是因为两者会同时为真：macOS 上「复制文件」常常连带一张缩略图，
     * 而那个条目的 [kind] 是 `file`（类型优先级：文件 > 图片 > 富文本 > 文本）。
     */
    val hasImage: Boolean = false,

    /** 有条目的图片文字识别结果；用 `get` 取全文。 */
    val hasOcr: Boolean = false,

    /**
     * 富文本条目携带的附加表示清单（只有类型与字节数，**没有内容**）。
     *
     * 只在 `get` 时填充。列表里为空不是「没有附加表示」，而是元数据层不含这份清单——
     * `kind == "richtext"` 才是「存在附加表示」的可靠信号。
     */
    val attachments: List<CliAttachmentView> = emptyList(),

    // ---------------------------------------------------------------------------------
    // 以下字段只在 `get` 时填充
    // ---------------------------------------------------------------------------------

    /**
     * 完整正文。
     *
     * `get` 默认返回它；`list` / `search` 里不存在。富文本条目的这一份是**从 HTML / RTF
     * 提取出的可读文字**，而不是标签源码——绝大多数场合 agent 要的是内容而不是排版。
     * 需要源码时用 `get --format html`。
     */
    val text: String? = null,

    /** 图片文字识别的完整原文（[title] 只是它前面一段）。 */
    val ocr: CliOcrView? = null,

    /**
     * 二进制内容**落盘后的路径**。
     *
     * 二进制从不走线上协议：base64 会带来 33% 膨胀且不可读，裸字节又没法塞进 JSON。
     * 服务端把字节写到 `$TMPDIR/clipper/<id>.<ext>` 并返回路径，需要原始字节时由 CLI
     * 自己读这个文件（`get --raw`）。
     *
     * 两种情况下会有值：图片条目（内容是图片本身），以及带了 `--format` 的富文本条目
     * （内容是该附加表示的原始字节）。哪种由 [kind] 与请求里的 `format` 决定。
     *
     * 路径以条目 id 命名，因此**重复调用拿到同一个文件**，不会越用越多。
     */
    val path: String? = null,
)

/** 文件类型条目里的一条路径。 */
@Serializable
data class CliFileView(
    val path: String,
    /**
     * 路径当前是否还存在。
     *
     * **可空**：列表不查磁盘（否则一次 list 就是几十次 stat），因此那里省略这个字段；
     * `get` 会查一次。省略 ≠ false。
     */
    val exists: Boolean? = null,
)

/** 富文本条目携带的一种附加表示。 */
@Serializable
data class CliAttachmentView(
    /** 原始粘贴板类型标识（UTI），例如 `public.html`。 */
    val type: String,
    val bytes: Int,
)

/** 图片文字识别结果。 */
@Serializable
data class CliOcrView(
    val text: String,
    val chars: Int,
    /** [text] 是否被截断。 */
    val truncated: Boolean,
)

/** `list` / `search` 的 `data`。 */
@Serializable
data class CliListView(
    val items: List<CliView>,
    /**
     * 当前筛选条件下的**总数**，而不是返回条数。
     *
     * Agent 靠它判断「还有更多」——`items.size < total` 就说明撞到 [CliRequest.limit] 了。
     */
    val total: Int,
    /** 本次是否因为 [CliRequest.limit] 而截断。 */
    val truncated: Boolean = false,
)

/**
 * `stats` 的 `data`。
 *
 * 刻意**没有**「各类型条数」：那需要一次全表分组查询，而现在的存储接口只能拿到
 * 已加载的元数据窗口（恰好是全部条目，但接口没承诺这一点）。宁可不给，
 * 也不给一个会被误解为精确值的数字。要按类型看，用 `list --kind x --limit …`。
 */
@Serializable
data class CliStatsView(
    /** 历史总条数（含置顶）。 */
    val total: Int,
    val pinned: Int,
    /** 数据库文件占用的字节数；平台测不到时省略。 */
    val storageBytes: Long? = null,
    /** 是否正在暂停记录新的复制。 */
    val paused: Boolean = false,
)

/** `copy` / `pin` / `unpin` / `delete` 的 `data`：实际生效的 id。 */
@Serializable
data class CliAffectedView(
    val ids: List<String>,
)

/** `ping` 的 `data`；用于探活与版本协商。 */
@Serializable
data class CliPingView(
    val appVersion: String,
    val protocolVersion: Int,
    /** 服务端已运行的毫秒数。 */
    val uptimeMillis: Long,
)
