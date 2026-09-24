package com.qcmian.clipper.feature.history.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.domain.model.SearchResult
import com.qcmian.clipper.core.settings.PinPosition
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.feature.history.state.ClipboardUiAction
import com.qcmian.clipper.feature.history.state.ClipboardUiState
import com.qcmian.clipper.feature.history.ui.components.DeepSearchFooter
import com.qcmian.clipper.feature.history.ui.components.EmptyState
import com.qcmian.clipper.feature.history.ui.components.FooterRows
import com.qcmian.clipper.feature.history.ui.components.HistoryFilterBar
import com.qcmian.clipper.feature.history.ui.components.HistoryHeader
import com.qcmian.clipper.feature.history.ui.components.HistoryScrollbar
import com.qcmian.clipper.feature.history.ui.components.PausedBanner
import com.qcmian.clipper.feature.history.ui.components.PinnedSection

/**
 * 各固定区块的实测高度：写入方是布局（[HistoryMainColumn] 里的 `onSizeChanged`），读取方是窗口
 * 高度的推导（[HistoryScreen]）。两者分处两个文件，因此用一个容器把「谁写谁读」显式化。
 *
 * 用 `mutableStateOf` 而不是普通字段：高度一变，读它的窗口高度推导就要重算并上报宿主。
 */
internal class HistoryChromeHeights {
    var header by mutableStateOf(0.dp)
    var filter by mutableStateOf(0.dp)
    var topPins by mutableStateOf(0.dp)
    var bottomPins by mutableStateOf(0.dp)
    var footer by mutableStateOf(0.dp)
}

/**
 * 面板的主窗口：头部、置顶区、筛选栏、内容区、页脚，自上而下。它盖在预览卡之上，因此**必须
 * 不透明**，否则卡片会透出来。
 *
 * 宽度是一个可推导的定值（见 [HistoryScreen] 的 `mainWidth`），不读窗口宽度：预览开着时窗口宽
 * 出来的那一段全归卡片，关着时两者本就相等。
 */
@Composable
internal fun BoxScope.HistoryMainColumn(
    state: ClipboardUiState,
    onAction: (ClipboardUiAction) -> Unit,
    mainWidth: Dp,
    previewHost: PreviewHostPolicy,
    searchFocusRequester: FocusRequester,
    onCompositionChange: (Boolean) -> Unit,
    listState: LazyListState,
    scrollHeights: FloatArray,
    scrollPadding: Float,
    /** 列表 `contentPadding` 里落在条目之下的部分，见 [HistoryHeightMetrics.listBottomPadding]。 */
    listBottomPadding: Dp,
    heights: HistoryChromeHeights,
    row: @Composable (IndexedValue<SearchResult>) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val settings = state.settings
    val pinnedEntries = state.pinnedEntries
    val unpinnedEntries = state.unpinnedEntries
    val pinsAtTop = settings.pinTo == PinPosition.TOP
    // 只要置顶区有内容就画分隔线。
    //
    // 从前还要求内容区非空，但内容区为空时那里并不是「什么都没有」——它是一个空状态区块
    // （「没有匹配的项目」）。两块内容之间没有分界，看起来像置顶区把下面的区域吞掉了。
    val pinsSeparator = pinnedEntries.isNotEmpty()
    // 全文搜索的入口只在有查询词时才出现：没有查询就没有要找的东西。
    val deepSearchVisible = state.appliedQuery.isNotEmpty()

    Column(
        Modifier
            // 宽度是设置算出来的一个定值，与窗口宽度、与预览开没开都无关。它同时是唯一的不透
            // 明底色：窗口还没收回去那几帧，它把卡片整块盖住。
            .width(mainWidth)
            .align(
                if (previewHost.onLeft) Alignment.CenterEnd else Alignment.CenterStart,
            )
            .fillMaxHeight()
            .background(colors.background),
    ) {
        // 暂停横幅折进同一个被测量的区块。
        Column(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { heights.header = with(density) { it.height.toDp() } },
        ) {
            HistoryHeader(
                visible = state.searchVisible,
                query = state.query,
                onQueryChange = { value -> onAction(ClipboardUiAction.UpdateQuery(value)) },
                onCompositionChange = onCompositionChange,
                focusRequester = searchFocusRequester,
                previewOpen = state.previewOpen,
                previewOnLeft = previewHost.onLeft,
                previewTooltip = "显示 / 隐藏预览（${settings.togglePreviewShortcut?.label ?: "未设置"}）",
                onTogglePreview = { onAction(ClipboardUiAction.TogglePreview) },
            )

            if (settings.ignoreEvents) {
                PausedBanner(
                    onResume = {
                        onAction(
                            ClipboardUiAction.UpdateSettings {
                                it.copy(ignoreEvents = false)
                            },
                        )
                    },
                )
            }

        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize()) {
                // 固定的置顶项及其分隔线。
                if (pinsAtTop && pinnedEntries.isNotEmpty()) {
                    PinnedSection(
                        entries = pinnedEntries,
                        separator = pinsSeparator,
                        separatorFirst = false,
                        onHeightChange = { heights.topPins = it },
                        row = row,
                    )
                }

                // 筛选栏紧贴内容区上方（也就是置顶区之下）：它只作用于内容区。
                //
                // 位置之所以稳定，不是因为把它挪到了别处，而是因为**置顶区不参与筛选与搜索**
                // （见 `ClipboardViewModel.matchResults`）——置顶项数量恒定，上面那块的高度
                // 就不会变，它也就不会跟着跳。
                //
                // 开关关闭时整块隐藏；外层 Box 始终存在，让 filterHeight 能跟着归零。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .onSizeChanged { heights.filter = with(density) { it.height.toDp() } },
                ) {
                    if (settings.showFilterBar) {
                        HistoryFilterBar(
                            filterTypes = settings.filterTypes,
                            sortBy = settings.sortBy,
                            sortOrder = settings.sortOrder,
                            onFilterTypesChange = { value ->
                                onAction(ClipboardUiAction.UpdateSettings { it.copy(filterTypes = value) })
                            },
                            onSortByChange = { value ->
                                onAction(ClipboardUiAction.UpdateSettings { it.copy(sortBy = value) })
                            },
                            onSortOrderChange = { value ->
                                onAction(ClipboardUiAction.UpdateSettings { it.copy(sortOrder = value) })
                            },
                        )
                    }
                }

                Box(Modifier.weight(1f).fillMaxWidth()) {
                    HistoryListArea(
                        state = state,
                        onAction = onAction,
                        listState = listState,
                        deepSearchVisible = deepSearchVisible,
                        scrollHeights = scrollHeights,
                        scrollPadding = scrollPadding,
                        listBottomPadding = listBottomPadding,
                        row = row,
                    )
                }

                if (!pinsAtTop && pinnedEntries.isNotEmpty()) {
                    PinnedSection(
                        entries = pinnedEntries,
                        separator = pinsSeparator,
                        separatorFirst = true,
                        onHeightChange = { heights.bottomPins = it },
                        row = row,
                    )
                }
            }
        }

        FooterRows(
            selectedIndex = state.footerSelection,
            showQuit = state.showQuit,
            onAction = { action -> onAction(ClipboardUiAction.RunFooter(action)) },
            // `FooterItemView.onHover` 原本会在悬停页脚时收起预览；预览开关现在是持久化的
            // 用户选择（见 `AppSettings.previewOpen`），只由按钮 / 快捷键切换，这里不再动它。
            onHover = { index -> onAction(ClipboardUiAction.HoverFooter(index)) },
            modifier = Modifier.onSizeChanged {
                heights.footer = with(density) { it.height.toDp() }
            },
        )
    }
}

/**
 * 内容区：空状态 / 列表 / 滚动条。
 *
 * 判据是**内容区**是否为空，而不是整个 `results`：置顶区不参与筛选与搜索，它会一直有内容，
 * 按 `results` 判断就永远显示不出空状态。
 */
@Composable
private fun BoxScope.HistoryListArea(
    state: ClipboardUiState,
    onAction: (ClipboardUiAction) -> Unit,
    listState: LazyListState,
    deepSearchVisible: Boolean,
    scrollHeights: FloatArray,
    scrollPadding: Float,
    listBottomPadding: Dp,
    row: @Composable (IndexedValue<SearchResult>) -> Unit,
) {
    val entries = state.unpinnedEntries
    if (entries.isEmpty()) {
        EmptyState(searching = state.query.isNotEmpty())
        // 空状态时入口照样出现，而且最该出现：标题一条都没命中，正是「去正文里找找」最可能
        // 有用的时刻。它浮在空状态之上，不改变那里原有的居中布局。
        if (deepSearchVisible) {
            DeepSearchFooter(
                state = state.deepSearch,
                hits = state.deepSearchHits,
                onRun = { onAction(ClipboardUiAction.RunDeepSearch) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Popup.horizontalPadding,
                end = Popup.horizontalPadding,
                top = Popup.verticalSeparatorPadding,
                bottom = listBottomPadding,
            ),
        ) {
            items(entries, key = { it.value.meta.id }) { indexed ->
                row(indexed)
            }
            // 入口固定在内容末尾：用户滚到这儿本身就是「这些还不够」的表达，因此它不需要额外
            // 的提示，也不该悬在界面上方随时可见。
            if (deepSearchVisible) {
                item {
                    DeepSearchFooter(
                        state = state.deepSearch,
                        hits = state.deepSearchHits,
                        onRun = { onAction(ClipboardUiAction.RunDeepSearch) },
                    )
                }
            }
        }
        HistoryScrollbar(
            state = listState,
            heights = scrollHeights,
            contentPadding = scrollPadding,
            // 无标题栏窗口默认沿边缘 8dp 内是缩放热区
            // （WindowDecorationDefaults.ResizerThickness），
            // 热区会吞掉点击去调整窗口大小；内移 8dp 让滚动条
            // 完全落在可点击区域内。
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .padding(end = 8.dp),
        )
    }
}
