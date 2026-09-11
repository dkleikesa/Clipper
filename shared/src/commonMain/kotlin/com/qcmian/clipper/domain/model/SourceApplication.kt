package com.qcmian.clipper.domain.model

import kotlinx.serialization.Serializable

/**
 * 剪贴板条目来源的应用，对应 Maccy 的 `HistoryItem.application` + `NSWorkspace.frontmostApplication`。
 *
 * 图标不做持久化：它按需从应用包中查找并缓存，方式与 Maccy 的 `ApplicationImageCache` 相同。
 */
@Serializable
data class SourceApplication(
    /** 本地化的应用名，显示在预览中。 */
    val name: String,
    /** Bundle 标识符，用于忽略列表以及查找图标。 */
    val bundleId: String? = null,
)
