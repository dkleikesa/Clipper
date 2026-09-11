package com.qcmian.clipper.domain.model

import com.qcmian.clipper.domain.model.ClipItem

/**
 * A match of the current query inside an item's title.
 * [ranges] are the character offsets that should be highlighted in the UI.
 */
data class SearchResult(
    val item: ClipItem,
    val ranges: List<IntRange> = emptyList(),
)
