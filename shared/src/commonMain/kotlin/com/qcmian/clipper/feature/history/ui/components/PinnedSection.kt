package com.qcmian.clipper.feature.history.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.qcmian.clipper.core.domain.model.SearchResult
import com.qcmian.clipper.core.ui.Popup

/**
 * 固定在滚动区之外的置顶区块。
 *
 * 置顶在头部侧（[separatorFirst] 为 `false`）或页脚侧（为 `true`）时的排列顺序镜像：分隔线
 * 永远在滚动列表那一侧。
 *
 * 测出的高度由上层算进窗口高度。
 *
 * @param separator 是否画与滚动列表之间的分隔线（仅置顶与非置顶都存在时为真）。
 * @param row 单条目行，与可滚动列表共用（见 `HistoryScreen.entryRow`）。
 */
@Composable
internal fun PinnedSection(
    entries: List<IndexedValue<SearchResult>>,
    separator: Boolean,
    separatorFirst: Boolean,
    onHeightChange: (Dp) -> Unit,
    row: @Composable (IndexedValue<SearchResult>) -> Unit,
) {
    val density = LocalDensity.current
    Column(
        Modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightChange(with(density) { it.height.toDp() }) },
    ) {
        if (separator && separatorFirst) PinsSeparator()
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Popup.horizontalPadding),
        ) {
            entries.forEach { row(it) }
        }
        if (separator && !separatorFirst) PinsSeparator()
    }
}
