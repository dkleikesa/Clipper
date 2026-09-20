package com.qcmian.clipper.core.domain.model

import androidx.compose.runtime.Immutable

/**
 * 当前查询在条目标题中的一处匹配。
 * [ranges] 是需要高亮的字符区间。
 */
@Immutable
data class SearchResult(
    val item: ClipItem,
    val ranges: List<IntRange> = emptyList(),
)
