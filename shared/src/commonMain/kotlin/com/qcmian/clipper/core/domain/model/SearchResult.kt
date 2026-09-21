package com.qcmian.clipper.core.domain.model

import androidx.compose.runtime.Immutable

/**
 * 当前查询在条目**标题**中的一处匹配。
 * [ranges] 是需要高亮的字符区间。
 *
 * 携带的是 [ClipMeta] 而不是完整条目：列表要渲染的是标题与元信息，图片字节、正文与富文本
 * 都在载荷里，只有预览面板与「写回剪贴板」才需要，届时按 [ClipMeta.id] 单独取。
 */
@Immutable
data class SearchResult(
    val meta: ClipMeta,
    val ranges: List<IntRange> = emptyList(),
)
