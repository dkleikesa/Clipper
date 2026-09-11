package com.qcmian.clipper.domain.model

import com.qcmian.clipper.domain.model.ClipItem

/**
 * 当前查询在条目标题中的一处匹配。
 * [ranges] 是需要高亮的字符区间。
 */
data class SearchResult(
    val item: ClipItem,
    val ranges: List<IntRange> = emptyList(),
)
