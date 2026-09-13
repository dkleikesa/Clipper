package com.qcmian.clipper.feature.history.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.SearchResult
import com.qcmian.clipper.core.settings.PinPosition
import com.qcmian.clipper.core.ui.KeyShortcut
import com.qcmian.clipper.core.ui.ModifierFlags
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.core.ui.keyShortcuts
import com.qcmian.clipper.core.ui.visibleShortcut
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import com.qcmian.clipper.feature.history.ui.components.EmptyState
import com.qcmian.clipper.feature.history.ui.components.FooterRows
import com.qcmian.clipper.feature.history.ui.components.HistoryHeader
import com.qcmian.clipper.feature.history.ui.components.HistoryRow
import com.qcmian.clipper.feature.history.ui.components.PausedBanner
import com.qcmian.clipper.feature.history.ui.components.PinsSeparator
import com.qcmian.clipper.feature.history.ui.components.PreviewPane
import com.qcmian.clipper.feature.history.ui.components.PreviewSlideout
import com.qcmian.clipper.feature.history.ui.components.StatusToast
import com.qcmian.clipper.feature.history.ui.components.footerEntries
import com.qcmian.clipper.core.ui.components.rememberApplicationIcon
import com.qcmian.clipper.core.ui.components.ConfirmDialog
import com.qcmian.clipper.feature.preferences.ui.PreferencesActions
import com.qcmian.clipper.feature.preferences.ui.PreferencesDialog
import com.qcmian.clipper.feature.preferences.ui.PreferencesUiData
import com.qcmian.clipper.feature.history.state.ClipboardDialog
import com.qcmian.clipper.feature.history.state.ClipboardUiAction
import com.qcmian.clipper.feature.history.state.ClipboardUiState
import com.qcmian.clipper.feature.history.viewmodel.resolveKeyActions

/** 低于此宽度时，预览面板改为覆盖在列表上，而不是并排显示。 */
private val OverlayThreshold = 700.dp

/** 缩略图在 `imageMaxHeight` 之上额外增加的垂直内边距。 */
private val ImageRowPadding = 10.dp

/** 高度估算的安全余量，见 `preferredHeight` 的注释。 */
private val HeightSlack = 4.dp

/**
 * 主界面：头部、历史列表、页脚，外加预览滑出面板。
 *
 * 界面是 [state] 的纯函数；每一次交互都通过 [onAction] 回传。所有行为都在
 * `ClipboardViewModel` 中。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HistoryScreen(
    state: ClipboardUiState,
    onAction: (ClipboardUiAction) -> Unit,
    onPreviewOpenChange: (Boolean) -> Unit,
    onPreferredHeightChange: (Dp) -> Unit,
    applicationIcon: (String?) -> String?,
    applicationName: (String) -> String?,
    availablePins: (ClipItem) -> List<String>,
    previewOnLeft: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val settings = state.settings
    val results = state.results

    val flags = remember { ModifierFlags() }
    val searchFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    /** 搜索框打开了输入法候选窗时为 `true`。 */
    var composing by remember { mutableStateOf(false) }

    /**
     * 最近一次指针按下期间按住的修饰键，这样 ⌥-点击会粘贴、⌘⇧-点击会不带格式粘贴，
     * 与 `HistoryItemView.performSelect` 完全一致。
     */
    var pointerShift by remember { mutableStateOf(false) }
    var pointerAlt by remember { mutableStateOf(false) }
    var pointerMeta by remember { mutableStateOf(false) }

    // `BoxWithConstraints` 会在布局阶段对内容做子组合，因此搜索框的焦点修饰符
    // 要等到第一帧之后才会挂上；没挂上时 `requestFocus()` 会抛
    // "FocusRequester is not initialized"，所以统一包一层 runCatching。
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { searchFocusRequester.requestFocus() }
    }

    // `Popup.handleFirstKeyDown`：宿主请求显示面板后，搜索框重新获得焦点。
    //
    // 同样必须先等一帧：面板每次显示都会重新测量，`BoxWithConstraints` 随之在布局阶段重新
    // 子组合，紧跟状态变化同步调用 `requestFocus()` 可能落在焦点修饰符挂上之前，
    // 于是这次聚焦静默失效——表现为「一次能输入、一次不能」。
    LaunchedEffect(state.focusRequestToken) {
        if (state.focusRequestToken <= 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { searchFocusRequester.requestFocus() }
    }

    LaunchedEffect(state.previewOpen) { onPreviewOpenChange(state.previewOpen) }

    val density = LocalDensity.current

    // 量出的各区块高度。它们从 0 开始，与 `Popup.height` 一致：
    // 面板先以最小高度打开，测量结果随后调整大小，使其贴合内容。
    var headerHeight by remember { mutableStateOf(0.dp) }
    var topPinsHeight by remember { mutableStateOf(0.dp) }
    var bottomPinsHeight by remember { mutableStateOf(0.dp) }
    var footerHeight by remember { mutableStateOf(0.dp) }

    val footerEntries = footerEntries(state.showQuit)

    // 下面这些派生值都是 [results] 的函数，而 `BoxWithConstraints` 在约束变化时会重新子组合
    // 整个界面——拖动窗口尺寸时每帧都会发生——所以在这里算一次并缓存，避免每帧重建列表与映射。
    val pinnedEntries = remember(results) { state.pinnedEntries }
    val unpinnedEntries = remember(results) { state.unpinnedEntries }
    val shortcuts = remember(results, settings.pasteByDefault) {
        shortcutMap(results, settings.pasteByDefault)
    }

    // 对应 `HistoryListView.scrollBottomPadding`。
    val pinsAtTop = settings.pinTo == PinPosition.TOP
    val pinsSeparator = pinnedEntries.isNotEmpty() && unpinnedEntries.isNotEmpty()
    val listBottomPadding = if (!pinsAtTop && pinsSeparator) {
        Popup.verticalSeparatorPadding
    } else {
        Popup.verticalSeparatorPadding - 1.dp
    }

    // 对应 `Popup.suitableHeight(for:)` + `Popup.preferredHeight(for:)`。
    //
    // `LazyColumn` 只布局可见行，因此无法直接报告总高度。行高先用 `Popup.itemHeight` /
    // `imageMaxHeight` 估算；当所有条目都进入布局（内容放得下）时，再用布局结果给出的
    // **实测**条目总高覆盖估算值——估算与真实渲染之间的偏差（字体度量、窗口尺寸取整等）
    // 正是「窗口没被撑满 / 残留一小段可滚动区间」的原因，实测修正让窗口精确贴合内容。
    val imageRowHeight = settings.imageMaxHeight.dp + ImageRowPadding
    val rowHeight: (SearchResult) -> Dp = { result ->
        if (result.item.image != null) imageRowHeight else Popup.itemHeight
    }

    // 滚动条的像素高度模型。与窗口高度估算共用同一个 [rowHeight]：条目高度是「每条一眼
    // 可算」的确定性值，因此滚动条不必再用「可见行平均高」这类随可见集合逐帧变化的估算。
    val scrollModel = remember(unpinnedEntries, settings.imageMaxHeight, density) {
        ListHeightModel(
            FloatArray(unpinnedEntries.size) { index ->
                with(density) { rowHeight(unpinnedEntries[index].value).toPx() }
            },
        )
    }
    val scrollPadding = with(density) {
        (Popup.verticalSeparatorPadding + listBottomPadding).toPx()
    }

    // 实测的未置顶条目总高（不含列表 contentPadding）；`null` 表示还没有实测值。
    var measuredItemsHeight by remember { mutableStateOf<Dp?>(null) }
    LaunchedEffect(listState, unpinnedEntries.size) {
        measuredItemsHeight = null
        snapshotFlow { listState.layoutInfo }.collect { info ->
            val visible = info.visibleItemsInfo
            if (info.totalItemsCount == 0) {
                measuredItemsHeight = 0.dp
            } else if (visible.size == info.totalItemsCount) {
                // 全部条目都在布局里：最后一条的底部就是条目总高（offset 以内容起点计）。
                val contentBottom = visible.maxOf { it.offset + it.size }
                measuredItemsHeight = with(density) { contentBottom.toDp() }
            }
        }
    }

    val itemsHeight = measuredItemsHeight
        ?: unpinnedEntries.fold(0.dp) { total, entry -> total + rowHeight(entry.value) }
    val listHeight = itemsHeight + Popup.verticalSeparatorPadding + listBottomPadding

    val chromeHeight = headerHeight + topPinsHeight + bottomPinsHeight + footerHeight
    val suitableHeight = listHeight + chromeHeight
    val threeItemHeight = chromeHeight + Popup.itemHeight * 3
    val minimumHeight = (
        if (state.previewOpen && state.selectedItem != null) {
            threeItemHeight.coerceAtLeast(Popup.minimumPreviewHeight)
        } else {
            threeItemHeight
        }
        ).coerceAtLeast(headerHeight + Popup.verticalPadding)
    // 估算高度与真实布局之间难免有 1–2px 的出入（AWT 窗口尺寸取整、字体度量等），
    // 出入会让列表「差一点装得下」——残留一小段可滚动区间和一截滚动条。留一点余量，
    // 内容本该放得下时窗口总是略高于内容，这个小滚动区间就消失了；内容真的超高时
    // 余量也会被吃掉，滚动行为不受影响。
    val preferredHeight = (suitableHeight + HeightSlack)
        .coerceAtLeast(minimumHeight)

    LaunchedEffect(preferredHeight) { onPreferredHeightChange(preferredHeight) }

    // `NavigationManager.scroll(to:)` 只会滚动未置顶列表；置顶区块始终可见。
    // 目标行已经完整可见时不再滚动：悬停也会更新选中项，若此时强行滚到视口顶部，
    // 列表会在鼠标下反复跳动（悬停 → 选中变化 → 滚动 → 悬停另一行），形成循环。
    LaunchedEffect(state.historySelection, unpinnedEntries.size) {
        val target = unpinnedEntries.indexOfFirst { it.index == state.historySelection }
        if (target < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == target }
        val fullyVisible = item != null &&
            item.offset >= info.viewportStartOffset &&
            item.offset + item.size <= info.viewportEndOffset
        if (!fullyVisible) listState.animateScrollToItem(target)
    }

    val keyHandler: (KeyEvent) -> Boolean = { event ->
        flags.update(event)
        val actions = resolveKeyActions(
            event = event,
            state = state,
            flags = flags,
            composing = composing,
            shortcuts = shortcuts,
            footerActions = footerEntries.map { it.action },
        )
        actions.forEach(onAction)
        actions.isNotEmpty()
    }

    /** 单条历史行，供固定的置顶区块与可滚动的未置顶列表共用。 */
    val entryRow: @Composable (IndexedValue<SearchResult>) -> Unit = { indexed ->
        val item = indexed.value.item
        HistoryRow(
            item = item,
            ranges = indexed.value.ranges,
            shortcut = visibleShortcut(shortcuts[item.id].orEmpty(), flags),
            isSelected = state.isHistoryHighlighted && indexed.index == state.historySelection,
            highlight = settings.highlightMatch,
            showColorSwatch = settings.showHexColorSwatch,
            maxImageHeight = settings.imageMaxHeight.dp,
            appIconBase64 = if (settings.showApplicationIcons) {
                rememberApplicationIcon(applicationIcon, item.application?.bundleId)
            } else {
                null
            },
            onClick = {
                onAction(
                    ClipboardUiAction.Activate(
                        index = indexed.index,
                        shift = pointerShift,
                        alt = pointerAlt,
                        meta = pointerMeta,
                    ),
                )
            },
            onHover = { onAction(ClipboardUiAction.HoverHistory(indexed.index)) },
        )
    }

    // 每个选中项只解析一次，且不在组合线程上。
    val previewAppIcon = rememberApplicationIcon(
        load = applicationIcon,
        bundleId = state.selectedItem?.application?.bundleId,
    )

    // 「当前宽度是否够并排显示预览面板」。刻意不用 `BoxWithConstraints`：它每次测量都会给
    // `SubcomposeLayout` 传一个新的 content lambda，而后者按引用比较 lambda、判定「内容变了」
    // 就重组整个槽位——拖动窗口尺寸时约束每帧都在变，等于每帧把整棵界面重组一遍。
    // 这里把宽度收敛成一个布尔值：宽度每帧都在变，但它只在跨过阈值的那一刻才改变，
    // 于是整段拖动通常一次重组都不会发生。
    var wideLayout by remember { mutableStateOf(false) }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                val wide = with(density) { size.width.toDp() } >= OverlayThreshold
                if (wide != wideLayout) wideLayout = wide
            }
            .clip(RoundedCornerShape(10.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .safeDrawingPadding()
                // 记录指针按下期间按住的修饰键，这样 ⌥-点击会粘贴、
                // ⌘⇧-点击会不带格式粘贴（`HistoryItemView.performSelect`）。
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            when (event.type) {
                                // `MouseMovedViewModifier`：鼠标移动会结束键盘导航，
                                // 于是悬停重新开始选择。
                                PointerEventType.Move -> onAction(ClipboardUiAction.PointerMoved)
                                PointerEventType.Press -> {
                                    pointerShift = event.keyboardModifiers.isShiftPressed
                                    pointerAlt = event.keyboardModifiers.isAltPressed
                                    pointerMeta = event.keyboardModifiers.isMetaPressed ||
                                        event.keyboardModifiers.isCtrlPressed
                                }

                                else -> Unit
                            }
                        }
                    }
                }
                .onPreviewKeyEvent(keyHandler),
        ) {
            Row(Modifier.fillMaxSize()) {
                if (previewOnLeft && wideLayout && state.previewOpen) {
                    PreviewSlideout(
                        item = state.selectedItem,
                        appIconBase64 = previewAppIcon,
                        previewWidth = settings.previewWidth,
                        onLeft = true,
                        onTogglePin = { onAction(ClipboardUiAction.TogglePinSelected) },
                        onDelete = { onAction(ClipboardUiAction.DeleteSelected) },
                        onCopyExtractedText = { onAction(ClipboardUiAction.CopyExtractedText) },
                        onWidthChange = { value -> onAction(ClipboardUiAction.SetPreviewWidth(value)) },
                    )
                }

                Column(Modifier.weight(1f).fillMaxHeight()) {
                    // 对应 `HeaderView.readHeight(appState, into: \.popup.headerHeight)`。
                    // 暂停横幅是复刻版新增的，因此折进同一个被测量的区块。
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
                    ) {
                        HistoryHeader(
                            visible = state.searchVisible,
                            query = state.query,
                            onQueryChange = { value -> onAction(ClipboardUiAction.UpdateQuery(value)) },
                            onCompositionChange = { composing = it },
                            focusRequester = searchFocusRequester,
                            previewOpen = state.previewOpen,
                            previewOnLeft = previewOnLeft,
                            onTogglePreview = { onAction(ClipboardUiAction.TogglePreview) },
                        )

                        if (settings.ignoreEvents) {
                            PausedBanner(
                                onlyNext = settings.ignoreOnlyNextEvent,
                                onResume = {
                                    onAction(
                                        ClipboardUiAction.UpdateSettings {
                                            it.copy(ignoreEvents = false, ignoreOnlyNextEvent = false)
                                        },
                                    )
                                },
                            )
                        }
                    }

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (results.isEmpty()) {
                            EmptyState(searching = state.query.isNotEmpty())
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                if (pinsAtTop && (pinnedEntries.isNotEmpty() || pinsSeparator)) {
                                    // 对应 `HistoryListView` 顶部区块的 `readHeight`
                                    // （`popup.extraTopHeight`）：固定的置顶项及其分隔线。
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .onSizeChanged { topPinsHeight = with(density) { it.height.toDp() } },
                                    ) {
                                        Column(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = Popup.horizontalPadding),
                                        ) {
                                            pinnedEntries.forEach { entryRow(it) }
                                        }
                                        if (pinsSeparator) PinsSeparator()
                                    }
                                }

                                Box(Modifier.weight(1f).fillMaxWidth()) {
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
                                        items(unpinnedEntries, key = { it.value.item.id }) { indexed ->
                                            entryRow(indexed)
                                        }
                                    }
                                    HistoryScrollbar(
                                        state = listState,
                                        model = scrollModel,
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

                                if (!pinsAtTop && (pinnedEntries.isNotEmpty() || pinsSeparator)) {
                                    // 对应 `HistoryListView` 底部区块的 `readHeight`
                                    // （`popup.extraBottomHeight`）。
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .onSizeChanged { bottomPinsHeight = with(density) { it.height.toDp() } },
                                    ) {
                                        if (pinsSeparator) PinsSeparator()
                                        Column(
                                            Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = Popup.horizontalPadding),
                                        ) {
                                            pinnedEntries.forEach { entryRow(it) }
                                        }
                                    }
                                }
                            }
                        }

                        if (state.previewOpen && !wideLayout) {
                            Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
                                PreviewPane(
                                    item = state.selectedItem,
                                    appIconBase64 = previewAppIcon,
                                    onTogglePin = { onAction(ClipboardUiAction.TogglePinSelected) },
                                    onDelete = { onAction(ClipboardUiAction.DeleteSelected) },
                                    onCopyExtractedText = { onAction(ClipboardUiAction.CopyExtractedText) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }

                    FooterRows(
                        selectedIndex = state.footerSelection,
                        showQuit = state.showQuit,
                        onAction = { action -> onAction(ClipboardUiAction.RunFooter(action)) },
                        onHover = { index ->
                            onAction(ClipboardUiAction.HoverFooter(index))
                            // `FooterItemView.onHover`：悬停页脚会关闭预览（离开时不必再触发）。
                            if (index >= 0 && state.previewOpen) onAction(ClipboardUiAction.TogglePreview)
                        },
                        // 对应 `FooterView.readHeight(appState, into: \.popup.footerHeight)`。
                        modifier = Modifier.onSizeChanged {
                            footerHeight = with(density) { it.height.toDp() }
                        },
                    )
                }

                if (!previewOnLeft && wideLayout && state.previewOpen) {
                    PreviewSlideout(
                        item = state.selectedItem,
                        appIconBase64 = previewAppIcon,
                        previewWidth = settings.previewWidth,
                        onLeft = false,
                        onTogglePin = { onAction(ClipboardUiAction.TogglePinSelected) },
                        onDelete = { onAction(ClipboardUiAction.DeleteSelected) },
                        onCopyExtractedText = { onAction(ClipboardUiAction.CopyExtractedText) },
                        onWidthChange = { value -> onAction(ClipboardUiAction.SetPreviewWidth(value)) },
                    )
                }
            }

            state.statusMessage?.let { message ->
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)) {
                    StatusToast(message)
                }
            }
        }
    }

    if (state.dialog == ClipboardDialog.PREFERENCES && state.confirmation == null) {
        PreferencesDialog(
            data = PreferencesUiData(
                settings = settings,
                pinnedItems = state.pinnedItems,
                storageSize = state.storageSize,
                historyBytes = state.historyBytes,
                screenCount = state.screenCount,
                supportsLaunchAtLogin = state.supportsLaunchAtLogin,
                supportsApplicationInfo = state.supportsApplicationInfo,
            ),
            actions = PreferencesActions(
                onSettingsChange = { transform -> onAction(ClipboardUiAction.UpdateSettings(transform)) },
                availablePins = availablePins,
                onPinChange = { item, pin -> onAction(ClipboardUiAction.UpdatePin(item, pin)) },
                onTitleChange = { item, title -> onAction(ClipboardUiAction.UpdateTitle(item, title)) },
                onContentChange = { item, text -> onAction(ClipboardUiAction.UpdateContent(item, text)) },
                onDeletePinned = { item -> onAction(ClipboardUiAction.DeleteItem(item)) },
                onClearUnpinned = { onAction(ClipboardUiAction.RequestClear(all = false, hidePanel = false)) },
                onClearAll = { onAction(ClipboardUiAction.RequestClear(all = true, hidePanel = false)) },
                onDismiss = { onAction(ClipboardUiAction.DismissPreferences) },
                applicationName = applicationName,
                applicationIcon = applicationIcon,
                onPickApplication = if (state.supportsApplicationInfo) {
                    { onAction(ClipboardUiAction.PickIgnoredApplication) }
                } else {
                    null
                },
            ),
        )
    }

    state.confirmation?.let { request ->
        ConfirmDialog(
            message = request.message,
            comment = request.comment,
            suppress = settings.suppressClearAlert,
            onSuppressChange = { value ->
                onAction(ClipboardUiAction.UpdateSettings { it.copy(suppressClearAlert = value) })
            },
            onConfirm = { onAction(ClipboardUiAction.ConfirmClear) },
            onDismiss = { onAction(ClipboardUiAction.DismissClear) },
        )
    }
}

/**
 * 未置顶列表的像素高度模型。
 *
 * 每条记录的高度只取决于它自身（文本行固定 `Popup.itemHeight`，图片行固定
 * `imageMaxHeight + ImageRowPadding`），因此可以对整份内容做精确的前缀和推算，而不必再
 * 依赖「可见行的平均高度」——那种估算会随可见集合逐帧变化，估算一变，滑块的长度与位置
 * 就跟着跳。
 *
 * 前缀和给出的「条目顶端偏移」与「滚动像素」严格互逆；滚动条的绘制与拖拽共用同一份数据，
 * 于是手指移动多少、滑块就移动多少。
 */
private class ListHeightModel(private val heights: FloatArray) {
    /** `starts[i]` 是第 i 条顶端相对内容起点的偏移，末项为全部条目的总高。 */
    private val starts = FloatArray(heights.size + 1).also { array ->
        heights.forEachIndexed { index, height -> array[index + 1] = array[index] + height }
    }

    val count: Int get() = heights.size

    /** 全部条目的总高，不含列表的 contentPadding。 */
    val contentHeight: Float get() = starts[starts.size - 1]

    /** 第 [index] 条顶端相对内容起点的偏移；越界时收敛到首尾。 */
    fun startOf(index: Int): Float = starts[index.coerceIn(0, count)]

    /** 距离内容起点 [offset] 像素处对应的条目下标。 */
    fun indexAt(offset: Float): Int {
        if (count == 0) return 0
        var low = 0
        var high = count - 1
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (starts[mid] <= offset) low = mid else high = mid - 1
        }
        return low
    }
}

/** 滑块的最小像素高度：内容极长时也要留出可抓取的长度。 */
private const val ThumbMinHeight = 36f

/** 滑块的像素几何：顶端 [top]、高度 [height]，均在轨道坐标系内。 */
private data class ThumbGeometry(val top: Float, val height: Float)

/**
 * 由当前滚动状态推出滑块几何；列表没有可滚动空间（或尚未布局）时返回 `null`。
 *
 * 绘制与拖拽都经由这里，因此「滑块位置 ↔ 滚动位置」两个方向的换算严格互逆。
 */
private fun thumbGeometry(
    state: LazyListState,
    model: ListHeightModel,
    contentPadding: Float,
    trackHeight: Float,
): ThumbGeometry? {
    if (model.count == 0 || trackHeight <= 0f || state.layoutInfo.visibleItemsInfo.isEmpty()) return null

    // 轨道高就是列表的可见高度；可滚动内容 = 条目总高 + 上下的 contentPadding。
    val viewport = trackHeight
    val contentHeight = model.contentHeight + contentPadding
    if (contentHeight <= viewport) return null

    val thumbHeight = (viewport * viewport / contentHeight)
        .coerceAtLeast(ThumbMinHeight)
        .coerceAtMost(trackHeight)
    val maxScroll = contentHeight - viewport
    val scrolled = model.startOf(state.firstVisibleItemIndex) + state.firstVisibleItemScrollOffset

    // 估算高度与真实布局之间可能有几像素出入，首尾改用 LazyListState 的精确判定兜住，
    // 这样滚到头时滑块一定贴住轨道两端。
    val fraction = when {
        !state.canScrollBackward -> 0f
        !state.canScrollForward -> 1f
        else -> (scrolled / maxScroll).coerceIn(0f, 1f)
    }
    return ThumbGeometry(top = fraction * (trackHeight - thumbHeight), height = thumbHeight)
}

/**
 * 把滑块顶端放到轨道的 [thumbTop] 像素处。换算与 [thumbGeometry] 严格互逆，
 * 因此拖动时滑块与手指 1:1 跟随，滚动距离也与滑块位移一致。
 *
 * 本身不是挂起函数：指针手势运行在受限的挂起作用域里，只能在此完成纯计算，
 * 真正改动 [LazyListState] 的那一步交给 [scope]。
 *
 * 每次指针移动都新起一个协程，但不必自己合并：`scrollToItem` 内部用 `MutatorMutex` 串行化，
 * 后一次调用会取消前一次，最终生效的必然是最后一个目标。拖动期间产生的协程数跟着指针事件走、
 * 有界且很小，不值得为此再引入通道或额外的状态。
 */
private fun scrollToThumbTop(
    scope: CoroutineScope,
    state: LazyListState,
    model: ListHeightModel,
    contentPadding: Float,
    trackHeight: Float,
    thumbTop: Float,
) {
    val geometry = thumbGeometry(state, model, contentPadding, trackHeight) ?: return
    val travel = trackHeight - geometry.height
    val maxScroll = model.contentHeight + contentPadding - trackHeight
    if (travel <= 0f || maxScroll <= 0f) return

    val target = (thumbTop / travel).coerceIn(0f, 1f) * maxScroll
    val index = model.indexAt(target)
    val offset = (target - model.startOf(index)).roundToInt()
    scope.launch { state.scrollToItem(index, offset) }
}

/**
 * 历史列表右侧的细滚动条（自绘 overlay）：列表不可滚动时整条隐藏（连点击热区一起消失）。
 * 滑块的位置与长度来自 [model] 的确定性高度，绘制与拖拽共用同一套换算。
 *
 * 交互：按住滑块拖动时保持按下瞬间的相对位置（1:1 跟手，不会把滑块顶端吸到手指上）；
 * 按住轨道空白处则把滑块中心移过去，等同于点击跳转。
 */
@Composable
private fun HistoryScrollbar(
    state: LazyListState,
    model: ListHeightModel,
    contentPadding: Float,
    modifier: Modifier = Modifier,
) {
    // 精确的「可滚动」判定（LazyListState 内部算好，不用估算），只在越过边界时变化，
    // 不会因为普通滚动逐帧重组。
    val scrollable by remember(state) {
        derivedStateOf { state.canScrollForward || state.canScrollBackward }
    }
    if (!scrollable) return

    val thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    val scope = rememberCoroutineScope()
    Canvas(
        modifier
            .width(10.dp)
            .pointerInput(state, model, contentPadding) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()

                    val trackHeight = size.height.toFloat()
                    val geometry = thumbGeometry(state, model, contentPadding, trackHeight)
                    val grab = when {
                        // 抓住滑块：记住手指相对滑块顶端的位置，拖动全程保持这个关系才会跟手。
                        geometry != null &&
                            down.position.y in geometry.top..(geometry.top + geometry.height) ->
                            down.position.y - geometry.top
                        // 按在轨道空白处：把滑块中心对到手指。
                        geometry != null -> geometry.height / 2f
                        else -> 0f
                    }
                    scrollToThumbTop(scope, state, model, contentPadding, trackHeight, down.position.y - grab)

                    var lastY = down.position.y
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        change.consume()
                        if (!change.pressed) break
                        if (change.position.y != lastY) {
                            lastY = change.position.y
                            scrollToThumbTop(scope, state, model, contentPadding, trackHeight, lastY - grab)
                        }
                    }
                }
            },
    ) {
        val geometry = thumbGeometry(state, model, contentPadding, size.height) ?: return@Canvas
        // 滑块画满整个画布宽度（10dp），不再是细 3dp 居中。
        val barWidth = size.width
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset((size.width - barWidth) / 2f, geometry.top),
            size = Size(barWidth, geometry.height),
            cornerRadius = CornerRadius(barWidth / 2f),
        )
    }
}

/**
 * 置顶项使用分配到的字母，前九个未置顶项使用 `1`…`9`。
 * 每个条目携带 `KeyShortcut.create(character:)` 产生的三个变体。
 */
private fun shortcutMap(
    results: List<SearchResult>,
    pasteByDefault: Boolean,
): Map<String, List<KeyShortcut>> {
    val map = mutableMapOf<String, List<KeyShortcut>>()
    var counter = 1
    results.forEach { result ->
        val item = result.item
        val character = when {
            item.isPinned -> item.pin
            counter <= 9 -> (counter++).toString()
            else -> null
        } ?: return@forEach
        map[item.id] = keyShortcuts(character.uppercase(), pasteByDefault)
    }
    return map
}
