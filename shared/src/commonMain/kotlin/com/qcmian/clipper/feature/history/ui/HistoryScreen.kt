package com.qcmian.clipper.feature.history.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    onResetPosition: () -> Unit = {},
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
    val shortcuts = shortcutMap(results, settings.pasteByDefault)

    // 对应 `HistoryListView.scrollBottomPadding`。
    val pinsAtTop = settings.pinTo == PinPosition.TOP
    val pinsSeparator = state.pinnedEntries.isNotEmpty() && state.unpinnedEntries.isNotEmpty()
    val listBottomPadding = if (!pinsAtTop && pinsSeparator) {
        Popup.verticalSeparatorPadding
    } else {
        Popup.verticalSeparatorPadding - 1.dp
    }

    // 对应 `Popup.suitableHeight(for:)` + `Popup.preferredHeight(for:)`。
    //
    // `LazyColumn` 只布局可见行，
    // 因此无法报告总高度；这里改为用 `Popup.itemHeight` 估算未滚动的行、用 `imageMaxHeight`
    // 估算图片行。其余部分都是实测的，这正是消除旧版完全静态估算偏差的原因。
    val imageRowHeight = settings.imageMaxHeight.dp + ImageRowPadding
    val rowHeight: (SearchResult) -> Dp = { result ->
        if (result.item.imageBase64 != null) imageRowHeight else Popup.itemHeight
    }
    val listHeight = state.unpinnedEntries.fold(0.dp) { total, entry -> total + rowHeight(entry.value) } +
        Popup.verticalSeparatorPadding + listBottomPadding

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
    val preferredHeight = suitableHeight
        .coerceAtLeast(minimumHeight)
        .coerceAtMost(settings.windowHeight.dp)

    LaunchedEffect(preferredHeight) { onPreferredHeightChange(preferredHeight) }

    // `NavigationManager.scroll(to:)` 只会滚动未置顶列表；置顶区块始终可见。
    // 目标行已经完整可见时不再滚动：悬停也会更新选中项，若此时强行滚到视口顶部，
    // 列表会在鼠标下反复跳动（悬停 → 选中变化 → 滚动 → 悬停另一行），形成循环。
    LaunchedEffect(state.historySelection, state.unpinnedEntries.size) {
        val target = state.unpinnedEntries.indexOfFirst { it.index == state.historySelection }
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

    BoxWithConstraints(modifier.fillMaxSize().clip(RoundedCornerShape(10.dp))) {
        val wide = maxWidth >= OverlayThreshold

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
                if (previewOnLeft && wide && state.previewOpen) {
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
                                if (pinsAtTop && (state.pinnedEntries.isNotEmpty() || pinsSeparator)) {
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
                                            state.pinnedEntries.forEach { entryRow(it) }
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
                                        items(state.unpinnedEntries, key = { it.value.item.id }) { indexed ->
                                            entryRow(indexed)
                                        }
                                    }
                                    HistoryScrollbar(
                                        state = listState,
                                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                                    )
                                }

                                if (!pinsAtTop && (state.pinnedEntries.isNotEmpty() || pinsSeparator)) {
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
                                            state.pinnedEntries.forEach { entryRow(it) }
                                        }
                                    }
                                }
                            }
                        }

                        if (state.previewOpen && !wide) {
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

                if (!previewOnLeft && wide && state.previewOpen) {
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
                onResetPosition = onResetPosition,
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
 * 历史列表右侧的细滚动条（自绘 overlay）：内容不超出视口时不绘制。
 * 全部数据取自 [LazyListState]，绘制阶段读取 snapshot 状态，滚动时自动重画。
 */
@Composable
private fun HistoryScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val thumbColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.28f)
    Canvas(modifier.width(8.dp)) {
        val info = state.layoutInfo
        if (info.totalItemsCount == 0 || info.visibleItemsInfo.isEmpty()) return@Canvas
        val viewport = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
        if (viewport <= 0f) return@Canvas

        // 用可见行的平均高度估算总内容高度（LazyColumn 不报告总高度）。
        val avgItemHeight = info.visibleItemsInfo.map { it.size }.average().toFloat()
        val estimatedTotal = avgItemHeight * info.totalItemsCount
        if (estimatedTotal <= viewport) return@Canvas

        val thumbHeight = (viewport * viewport / estimatedTotal).coerceAtLeast(36f)
        val scrolled = state.firstVisibleItemIndex * avgItemHeight + state.firstVisibleItemScrollOffset
        val fraction = (scrolled / (estimatedTotal - viewport)).coerceIn(0f, 1f)

        val barWidth = 3.dp.toPx()
        drawRoundRect(
            color = thumbColor,
            topLeft = Offset((size.width - barWidth) / 2f, fraction * (size.height - thumbHeight)),
            size = Size(barWidth, thumbHeight),
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
