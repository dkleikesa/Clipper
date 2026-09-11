package com.qcmian.clipper.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.focus.FocusRequester
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
import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.domain.model.SearchResult
import com.qcmian.clipper.domain.model.PinPosition
import com.qcmian.clipper.ui.components.EmptyState
import com.qcmian.clipper.ui.components.FooterRows
import com.qcmian.clipper.ui.components.HistoryHeader
import com.qcmian.clipper.ui.components.HistoryRow
import com.qcmian.clipper.ui.components.PausedBanner
import com.qcmian.clipper.ui.components.PinsSeparator
import com.qcmian.clipper.ui.components.PreviewPane
import com.qcmian.clipper.ui.components.PreviewSlideout
import com.qcmian.clipper.ui.components.StatusToast
import com.qcmian.clipper.ui.components.footerEntries
import com.qcmian.clipper.ui.components.rememberApplicationIcon
import com.qcmian.clipper.ui.dialogs.AboutDialog
import com.qcmian.clipper.ui.dialogs.ConfirmDialog
import com.qcmian.clipper.ui.dialogs.PreferencesDialog
import com.qcmian.clipper.ui.state.ClipboardDialog
import com.qcmian.clipper.ui.state.ClipboardUiAction
import com.qcmian.clipper.ui.state.ClipboardUiState

/** Width below which the preview pane overlays the list instead of sitting next to it. */
private val OverlayThreshold = 700.dp

/** Vertical padding a thumbnail adds on top of `imageMaxHeight`. */
private val ImageRowPadding = 10.dp

/**
 * Port of Maccy's `ContentView`: header, history list, footer, plus the preview slideout.
 *
 * The screen is a pure function of [state]; every interaction is sent back through
 * [onAction]. All the behaviour lives in `ClipboardViewModel`.
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

    /** `true` while the search field has an open input-method candidate window. */
    var composing by remember { mutableStateOf(false) }

    /**
     * Modifiers held during the most recent pointer press, so a `⌥`-click pastes and a
     * `⌘⇧`-click pastes without formatting, exactly like `HistoryItemView.performSelect`.
     */
    var pointerShift by remember { mutableStateOf(false) }
    var pointerAlt by remember { mutableStateOf(false) }
    var pointerMeta by remember { mutableStateOf(false) }

    // `BoxWithConstraints` subcomposes its content during the layout pass, so the search
    // field's focus modifier is only attached after the first frame.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        searchFocusRequester.requestFocus()
    }

    // `Popup.handleFirstKeyDown`: the host asked for the panel and the search takes focus back.
    LaunchedEffect(state.focusRequestToken) {
        if (state.focusRequestToken > 0) searchFocusRequester.requestFocus()
    }

    LaunchedEffect(state.previewOpen) { onPreviewOpenChange(state.previewOpen) }

    val density = LocalDensity.current

    // Measured block heights, the counterpart of Maccy's `readHeight(appState, into: \.popup.*)`
    // in `HeaderView`, `FooterView` and `HistoryListView`. They start at zero, exactly like
    // `Popup.height`: the panel first opens at its minimum height and the measurements then
    // resize it so it hugs the content.
    var headerHeight by remember { mutableStateOf(0.dp) }
    var topPinsHeight by remember { mutableStateOf(0.dp) }
    var bottomPinsHeight by remember { mutableStateOf(0.dp) }
    var footerHeight by remember { mutableStateOf(0.dp) }

    val footerEntries = footerEntries(flags, state.showQuit)
    val shortcuts = shortcutMap(results, settings.pasteByDefault)

    // Port of `HistoryListView.scrollBottomPadding`.
    val pinsAtTop = settings.pinTo == PinPosition.TOP
    val pinsSeparator = state.pinnedEntries.isNotEmpty() && state.unpinnedEntries.isNotEmpty()
    val listBottomPadding = if ((!pinsAtTop && pinsSeparator) || settings.showFooter) {
        Popup.verticalSeparatorPadding
    } else {
        Popup.verticalSeparatorPadding - 1.dp
    }

    // Port of `Popup.suitableHeight(for:)` + `Popup.preferredHeight(for:)`.
    //
    // Maccy measures the scrolling list too, because its `ScrollView` lays out every row. A
    // `LazyColumn` only lays out the rows that are visible and therefore cannot report a total
    // height, so the unscrolled rows are estimated from `Popup.itemHeight` and the image rows
    // from `imageMaxHeight`. Everything else is measured, which is what removes the drift the
    // old fully static estimate had.
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

    // `NavigationManager.scroll(to:)` only ever scrolls the unpinned list; the pinned block is
    // always on screen.
    LaunchedEffect(state.historySelection, state.unpinnedEntries.size) {
        val target = state.unpinnedEntries.indexOfFirst { it.index == state.historySelection }
        if (target >= 0) listState.animateScrollToItem(target)
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

    /** One history row, shared by the fixed pins block and the scrolling unpinned list. */
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

    // Resolved once per selected item, off the composition thread.
    val previewAppIcon = rememberApplicationIcon(
        load = applicationIcon,
        bundleId = state.selectedItem?.application?.bundleId,
    )

    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= OverlayThreshold

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .safeDrawingPadding()
                // Capture the modifiers held during a pointer press, so a ⌥-click pastes
                // and a ⌘⇧-click pastes without formatting (`HistoryItemView.performSelect`).
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            when (event.type) {
                                // `MouseMovedViewModifier`: mouse movement ends keyboard
                                // navigation, so hovering starts selecting again.
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
                    // Port of `HeaderView.readHeight(appState, into: \.popup.headerHeight)`.
                    // The paused banner is a replica addition, so it is folded into the same
                    // measured block.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
                    ) {
                        HistoryHeader(
                            title = "Clipper",
                            showTitle = settings.showTitle,
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
                                    // Port of `HistoryListView`'s top block `readHeight`
                                    // (`popup.extraTopHeight`): the fixed pins and their divider.
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

                                LazyColumn(
                                    state = listState,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
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

                                if (!pinsAtTop && (state.pinnedEntries.isNotEmpty() || pinsSeparator)) {
                                    // Port of `HistoryListView`'s bottom block `readHeight`
                                    // (`popup.extraBottomHeight`).
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

                    if (settings.showFooter) {
                        FooterRows(
                            flags = flags,
                            selectedIndex = state.footerSelection,
                            showQuit = state.showQuit,
                            onAction = { action -> onAction(ClipboardUiAction.RunFooter(action)) },
                            onHover = { index ->
                                onAction(ClipboardUiAction.HoverFooter(index))
                                // `FooterItemView.onHover`: hovering the footer closes the preview.
                                if (state.previewOpen) onAction(ClipboardUiAction.TogglePreview)
                            },
                            // Port of `FooterView.readHeight(appState, into: \.popup.footerHeight)`.
                            modifier = Modifier.onSizeChanged {
                                footerHeight = with(density) { it.height.toDp() }
                            },
                        )
                    }
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
            settings = settings,
            pinnedItems = state.pinnedItems,
            storageSize = state.storageSize,
            availablePins = availablePins,
            onSettingsChange = { transform -> onAction(ClipboardUiAction.UpdateSettings(transform)) },
            onPinChange = { item, pin -> onAction(ClipboardUiAction.UpdatePin(item, pin)) },
            onTitleChange = { item, title -> onAction(ClipboardUiAction.UpdateTitle(item, title)) },
            onContentChange = { item, text -> onAction(ClipboardUiAction.UpdateContent(item, text)) },
            onDeletePinned = { item -> onAction(ClipboardUiAction.DeleteItem(item)) },
            onClearUnpinned = { onAction(ClipboardUiAction.RequestClear(all = false, hidePanel = false)) },
            onClearAll = { onAction(ClipboardUiAction.RequestClear(all = true, hidePanel = false)) },
            onDismiss = { onAction(ClipboardUiAction.DismissPreferences) },
            screenCount = state.screenCount,
            supportsLaunchAtLogin = state.supportsLaunchAtLogin,
            onResetPosition = onResetPosition,
            supportsApplicationInfo = state.supportsApplicationInfo,
            applicationName = applicationName,
            applicationIcon = applicationIcon,
            onPickApplication = if (state.supportsApplicationInfo) {
                { onAction(ClipboardUiAction.PickIgnoredApplication) }
            } else {
                null
            },
        )
    }

    if (state.dialog == ClipboardDialog.ABOUT) {
        AboutDialog(
            onDismiss = { onAction(ClipboardUiAction.DismissAbout) },
            onOpenUrl = { url -> onAction(ClipboardUiAction.OpenUrl(url)) },
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
 * Pinned items use their assigned letter, the first nine unpinned items use `1`…`9`.
 * Each entry carries the three variants `KeyShortcut.create(character:)` produces.
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
