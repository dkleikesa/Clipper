package com.qcmian.clipper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.qcmian.clipper.core.ClipAction
import com.qcmian.clipper.core.ClipboardRepository
import com.qcmian.clipper.core.SearchResult
import com.qcmian.clipper.core.defaultAction
import com.qcmian.clipper.model.ClipItem
import com.qcmian.clipper.settings.PinPosition
import com.qcmian.clipper.settings.SearchVisibility
import com.qcmian.clipper.ui.components.FooterAction
import com.qcmian.clipper.ui.components.FooterRows
import com.qcmian.clipper.ui.components.footerEntries
import com.qcmian.clipper.ui.components.HistoryRow
import com.qcmian.clipper.ui.components.PreviewPane
import com.qcmian.clipper.ui.components.SearchField
import com.qcmian.clipper.ui.dialogs.AboutDialog
import com.qcmian.clipper.ui.dialogs.ConfirmDialog
import com.qcmian.clipper.ui.dialogs.PreferencesDialog
import com.qcmian.clipper.ui.icons.ClipperIcon
import com.qcmian.clipper.ui.icons.ClipperIconKind
import kotlinx.coroutines.delay

/** Width below which the preview pane overlays the list instead of sitting next to it. */
private val OverlayThreshold = 700.dp

/** Search box height, `SearchFieldView`'s fixed 23pt. */
private val SearchFieldHeight = 23.dp

/** Vertical padding a thumbnail adds on top of `imageMaxHeight`. */
private val ImageRowPadding = 10.dp

/** Roughly the height of the paused banner. */
private val BannerHeight = 34.dp

/** Upper bound for the draggable preview width, mirroring Maccy's practical limit. */
private const val PreviewMaxWidth = 900

/**
 * Port of Maccy's `ContentView`: header, history list, footer, plus the preview slideout.
 * Keyboard handling follows `KeyChord` one-to-one.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HistoryPanel(
    repository: ClipboardRepository,
    onQuit: (() -> Unit)?,
    onPreviewOpenChange: (Boolean) -> Unit,
    onRequestHideWindow: () -> Unit,
    autoPreview: Boolean,
    previewOnLeft: Boolean = false,
    controller: ClipperController? = null,
    onPreferredHeightChange: (Dp) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val settings = repository.settings
    val results = repository.searchResults

    val flags = remember { ModifierFlags() }
    val searchFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    var historySelection by remember { mutableStateOf(0) }
    var footerSelection by remember { mutableStateOf(-1) }
    var previewOpen by remember { mutableStateOf(false) }
    var autoOpenSuppressed by remember { mutableStateOf(false) }
    var showPreferences by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<ConfirmRequest?>(null) }
    /** `true` while the search field has an open input-method candidate window. */
    var composing by remember { mutableStateOf(false) }
    /**
     * Modifiers held during the most recent pointer press, so a `⌥`-click pastes and a
     * `⌘⇧`-click pastes without formatting, exactly like `HistoryItemView.performSelect`.
     */
    var pointerShift by remember { mutableStateOf(false) }
    var pointerAlt by remember { mutableStateOf(false) }
    var pointerMeta by remember { mutableStateOf(false) }
    /**
     * Port of `NavigationManager.isKeyboardNavigating`: while the keyboard drives the
     * selection, hovering a row is remembered instead of stealing the highlight.
     */
    var keyboardNavigating by remember { mutableStateOf(true) }
    var pendingHoverSelection by remember { mutableStateOf(-1) }

    // Port of `HistoryListView`: the pinned block lives *outside* the scroll view, so pinned
    // items stay visible while the unpinned history scrolls underneath them.
    val pinnedEntries = results.withIndex().filter { it.value.item.isPinned }
    val unpinnedEntries = results.withIndex().filterNot { it.value.item.isPinned }
    val pinsAtTop = settings.pinTo == PinPosition.TOP
    val pinsSeparator = pinnedEntries.isNotEmpty() && unpinnedEntries.isNotEmpty()
    // `HistoryListView.scrollBottomPadding`: the scroll view keeps a full separator gap while
    // the pins block follows it, and a 1pt tighter one when the footer is hidden.
    val listBottomPadding = if ((!pinsAtTop && pinsSeparator) || settings.showFooter) {
        Popup.verticalSeparatorPadding
    } else {
        Popup.verticalSeparatorPadding - 1.dp
    }

    val shortcuts = shortcutMap(results, settings.pasteByDefault)
    val footerEntries = footerEntries(flags, onQuit != null)
    val selectedResult = results.getOrNull(historySelection)
    val selectedItem = selectedResult?.item

    // Port of `AppState.searchVisible`: the whole header collapses when the search box is
    // hidden, or when it should only appear while a query is being typed.
    // `AppState.searchVisible` reads the throttled `searchQuery`, not the raw input.
    val searchVisible = settings.showSearch &&
        (settings.searchVisibility == SearchVisibility.ALWAYS || repository.searchQuery.isNotEmpty())

    // `BoxWithConstraints` subcomposes its content during the layout pass, so the search
    // field's focus modifier is only attached after the first frame.
    LaunchedEffect(Unit) {
        withFrameNanos { }
        searchFocusRequester.requestFocus()
    }

    LaunchedEffect(previewOpen) {
        onPreviewOpenChange(previewOpen)
        controller?.isPreviewOpen = previewOpen
    }

    // Keep an external host (the desktop tray) in sync with the panel state.
    LaunchedEffect(controller, repository) {
        controller?.let { host ->
            host.togglePauseAction = { onlyNext ->
                repository.updateSettings {
                    // `AppDelegate.performStatusItemClick`: ⌥ pauses, ⇧⌥ pauses for one copy.
                    val paused = !it.ignoreEvents
                    it.copy(
                        ignoreEvents = paused,
                        ignoreOnlyNextEvent = if (onlyNext) paused else false,
                    )
                }
            }
            host.togglePreviewAction = { previewOpen = !previewOpen }
            host.clearSearchAction = { repository.clearSearch() }
        }
    }

    // The host reads this to size and position its window, and to register the hot key.
    LaunchedEffect(controller, settings) {
        controller?.settings = settings
    }

    // Port of `FloatingPanel.resignKey`: don't auto-hide while a dialog is on screen.
    LaunchedEffect(controller, showPreferences, showAbout, confirmation) {
        controller?.isModalOpen = showPreferences || showAbout || confirmation != null
    }

    LaunchedEffect(controller, settings.ignoreEvents) {
        controller?.isPaused = settings.ignoreEvents
    }

    // `AppDelegate.isStatusItemDisabled`: paused or nothing is being recorded at all.
    LaunchedEffect(controller, settings.ignoreEvents, settings.saveText, settings.saveImages, settings.saveFiles) {
        controller?.isStatusItemDisabled = settings.ignoreEvents ||
            (!settings.saveText && !settings.saveImages && !settings.saveFiles)
    }

    LaunchedEffect(controller, settings.menuIcon) {
        controller?.menuIcon = settings.menuIcon
    }

    // `AppState.menuIconText`: the most recent unpinned copy, shortened like Maccy does.
    val menuIconText = results.firstOrNull { it.item.isUnpinned }?.item?.previewableText
        ?.take(100)
        ?.trim()
        ?.replace("\n", "")
        ?.replace("\r", "")
        ?.take(20)
        .orEmpty()
    LaunchedEffect(controller, menuIconText, settings.showRecentCopyInMenuBar) {
        controller?.recentCopyText = if (settings.showRecentCopyInMenuBar) menuIconText else ""
    }

    // Maccy's popup hugs its content: `Popup.preferredHeight` grows with the list up to the
    // configured window height and never shrinks below three items.
    val pinnedCount = results.count { it.item.isPinned }
    val showSeparator = pinnedCount in 1 until results.size
    val imageRowHeight = settings.imageMaxHeight.dp + ImageRowPadding
    val rowsHeight = results.fold(0.dp) { total, result ->
        total + if (result.item.imageBase64 != null) imageRowHeight else Popup.itemHeight
    }
    val separatorHeight = if (showSeparator) Popup.verticalSeparatorPadding * 2 + 1.dp else 0.dp
    val headerHeight = Popup.verticalPadding +
        (if (searchVisible) SearchFieldHeight else 0.dp) +
        (if (settings.ignoreEvents) BannerHeight else 0.dp)
    val footerHeight = if (settings.showFooter) {
        1.dp + Popup.verticalSeparatorPadding + Popup.itemHeight * footerEntries.size + Popup.verticalPadding
    } else {
        0.dp
    }
    // `Popup.preferredHeight`: with the preview open the window must be at least
    // `minimumPreviewHeight` tall for the slideout to be usable.
    val baseMinimumHeight = headerHeight + footerHeight + Popup.itemHeight * 3
    val minimumHeight = if (previewOpen && selectedItem != null) {
        baseMinimumHeight.coerceAtLeast(Popup.minimumPreviewHeight)
    } else {
        baseMinimumHeight
    }
    val preferredHeight = (headerHeight + footerHeight + Popup.verticalSeparatorPadding * 2 + rowsHeight + separatorHeight)
        .coerceAtLeast(minimumHeight)
        .coerceAtMost(settings.windowHeight.dp)

    LaunchedEffect(preferredHeight) { onPreferredHeightChange(preferredHeight) }

    LaunchedEffect(results.size) {
        if (historySelection > results.lastIndex) historySelection = results.lastIndex.coerceAtLeast(0)
    }

    // Port of `History.searchQuery.didSet`: a new query highlights the first match, while an
    // empty one falls back to the first unpinned item.
    LaunchedEffect(repository.searchQuery) {
        keyboardNavigating = true
        footerSelection = -1
        historySelection = if (repository.searchQuery.isEmpty()) {
            repository.searchResults.indexOfFirst { it.item.isUnpinned }.coerceAtLeast(0)
        } else {
            0
        }
    }

    // `NavigationManager.scroll(to:)` only ever scrolls the unpinned list; the pinned block is
    // always on screen.
    LaunchedEffect(historySelection, unpinnedEntries.size) {
        val target = unpinnedEntries.indexOfFirst { it.index == historySelection }
        if (target >= 0) listState.animateScrollToItem(target)
    }

    // Maccy's `SlideoutController.startAutoOpen`: slide the preview out on its own once the
    // selection settles, unless the user closed it manually. Only hosts with room for a
    // side-by-side pane opt in.
    LaunchedEffect(selectedItem?.id, autoPreview) {
        if (!autoPreview || selectedItem == null) return@LaunchedEffect
        if (!settings.openPreviewAutomatically || autoOpenSuppressed || previewOpen) return@LaunchedEffect
        delay(settings.previewDelay.toLong())
        if (!previewOpen && !autoOpenSuppressed) previewOpen = true
    }

    LaunchedEffect(repository.statusMessage) {
        if (repository.statusMessage == null) return@LaunchedEffect
        delay(1_600)
        repository.dismissStatus()
    }

    // Port of `NavigationManager.isKeyboardNavigating.didSet`: the hover remembered while
    // navigating is applied as soon as the mouse takes over.
    LaunchedEffect(keyboardNavigating) {
        if (!keyboardNavigating && pendingHoverSelection >= 0) {
            val index = pendingHoverSelection
            pendingHoverSelection = -1
            if (footerSelection < 0 && historySelection != index) {
                historySelection = index.coerceIn(0, results.lastIndex.coerceAtLeast(0))
            }
        }
    }

    fun selectHistory(index: Int) {
        keyboardNavigating = true
        historySelection = index.coerceIn(0, results.lastIndex.coerceAtLeast(0))
        footerSelection = -1
    }

    /**
     * Port of `HoverSelectionModifier`: hovering selects right away unless the keyboard is
     * currently navigating, in which case the hover is applied once the mouse takes over.
     */
    fun hoverHistory(index: Int) {
        if (!keyboardNavigating) {
            if (footerSelection >= 0 || historySelection != index) {
                historySelection = index.coerceIn(0, results.lastIndex.coerceAtLeast(0))
                footerSelection = -1
            }
        } else {
            pendingHoverSelection = index
        }
    }

    fun selectFooter(index: Int) {
        footerSelection = index.coerceIn(0, footerEntries.lastIndex.coerceAtLeast(0))
    }

    /** Port of `NavigationManager.isFirstItemHighlighted`. */
    fun isFirstItemHighlighted(): Boolean = footerSelection < 0 && historySelection == 0

    /** Port of `NavigationManager.highlightNext(allowCycle:)`. */
    fun moveNext(allowCycle: Boolean = false) {
        if (footerSelection >= 0) {
            if (footerSelection < footerEntries.lastIndex) {
                selectFooter(footerSelection + 1)
            } else if (footerEntries.isNotEmpty()) {
                // Maccy wraps around inside the footer.
                selectFooter(0)
            }
        } else if (historySelection < results.lastIndex) {
            selectHistory(historySelection + 1)
        } else if (footerEntries.isNotEmpty()) {
            selectFooter(0)
        } else if (allowCycle && results.isNotEmpty()) {
            selectHistory(0)
        }
    }

    /** Port of `NavigationManager.highlightPrevious`. */
    fun movePrevious() {
        if (footerSelection >= 0) {
            if (footerSelection > 0) selectFooter(footerSelection - 1) else selectHistory(results.lastIndex.coerceAtLeast(0))
        } else if (historySelection > 0) {
            selectHistory(historySelection - 1)
        }
    }

    /** Port of `NavigationManager.highlightLast`: the last history item hands over to the footer. */
    fun moveToLast() {
        if (footerSelection >= 0) {
            selectFooter(footerEntries.lastIndex.coerceAtLeast(0))
            return
        }

        val lastIndex = results.lastIndex
        if (lastIndex >= 0 && historySelection == lastIndex && footerEntries.isNotEmpty()) {
            selectFooter(0)
        } else {
            selectHistory(lastIndex.coerceAtLeast(0))
        }
    }

    fun activate(index: Int, action: ClipAction) {
        results.getOrNull(index)?.let { repository.select(it.item, action) }
    }

    fun requestClear(all: Boolean, hidePanel: Boolean = true) {
        // Port of `AppState.select` + `ConfirmationView`: honour "don't ask again".
        if (settings.suppressClearAlert) {
            if (all) repository.clearAll() else repository.clear()
            // `History.clear` closes the popup once the history is gone.
            if (hidePanel) onRequestHideWindow()
            return
        }
        confirmation = ConfirmRequest(
            message = "确定要清除历史记录吗？",
            comment = "无法撤消此操作。",
        ) {
            if (all) repository.clearAll() else repository.clear()
            if (hidePanel) onRequestHideWindow()
        }
    }

    fun togglePreview() {
        val willOpen = !previewOpen
        previewOpen = willOpen
        // Port of `SlideoutController.togglePreview(trigger: .manual)`: opening clears the
        // auto-open suppression, closing keeps it so the pane does not spring back.
        autoOpenSuppressed = !willOpen
    }

    // Port of `Popup.handleFirstKeyDown` / `Popup.handleKeyDown` in `.cycle` mode: the host
    // increments these when the global hot key opens the panel or asks for the next item.
    LaunchedEffect(controller?.openRequests) {
        if ((controller?.openRequests ?: 0) > 0) {
            keyboardNavigating = true
            historySelection = 0
            footerSelection = -1
            autoOpenSuppressed = false
            searchFocusRequester.requestFocus()
        }
    }

    LaunchedEffect(controller?.cycleRequests) {
        if ((controller?.cycleRequests ?: 0) > 0) {
            // `Popup.handleKeyDown` in `.cycle` state: `highlightNext(allowCycle: true)`.
            moveNext(allowCycle = true)
        }
    }

    // Port of `Popup.handleFlagsChanged`: releasing the modifiers in cycle mode accepts
    // the highlighted item (plain activation) and closes the panel.
    LaunchedEffect(controller?.acceptRequests) {
        if ((controller?.acceptRequests ?: 0) > 0 && footerSelection < 0 && results.isNotEmpty()) {
            activate(historySelection, ClipAction.DEFAULT)
            onRequestHideWindow()
        }
    }

    // Port of `FloatingPanel.close()`: the preview slideout is closed with the popup.
    LaunchedEffect(controller?.hideRequests) {
        if ((controller?.hideRequests ?: 0) > 0) previewOpen = false
    }

    val keyHandler: (KeyEvent) -> Boolean = { event ->
        flags.update(event)
        val modifierOnly = flags.isModifierKey(event)

        // Port of `KeyHandlingView`: ignore input while an IME candidate window is open.
        if (composing) {
            false
        } else if (event.type != KeyEventType.KeyDown || modifierOnly) {
            false
        } else {
            val meta = event.isMetaPressed || event.isCtrlPressed
            val alt = event.isAltPressed
            val control = event.isCtrlPressed

            when {
                // moveToLast: ⌃⌥N, Maccy's (.n, [.control, .option])
                control && alt && event.key == Key.N -> {
                    selectHistory(results.lastIndex.coerceAtLeast(0))
                    true
                }

                // moveToFirst: ⌃⌥P, Maccy's (.p, [.control, .option])
                control && alt && event.key == Key.P -> {
                    selectHistory(0)
                    true
                }

                // moveToNext: ↓ / ⇧↓ / ⌃N / ⌃⇧N / ⌃J
                (event.key == Key.DirectionDown && !alt && !meta) ||
                    (control && (event.key == Key.N || event.key == Key.J)) -> {
                    moveNext()
                    true
                }

                // moveToPrevious: ↑ / ⇧↑ / ⌃P / ⌃⇧P
                (event.key == Key.DirectionUp && !alt && !meta) ||
                    (control && event.key == Key.P) -> {
                    movePrevious()
                    true
                }

                // moveToPrevious: ⌃K, but only while the first item is not highlighted;
                // otherwise the key falls through and types into the search field (#1055).
                control && event.key == Key.K && !isFirstItemHighlighted() -> {
                    movePrevious()
                    true
                }

                // moveToLast: ⌘↓ / ⌥↓ / PageDown
                (event.key == Key.DirectionDown && (meta || alt)) || event.key == Key.PageDown -> {
                    moveToLast()
                    true
                }

                // moveToFirst: ⌘↑ / ⌥↑ / PageUp
                (event.key == Key.DirectionUp && (meta || alt)) || event.key == Key.PageUp -> {
                    selectHistory(0)
                    true
                }

                // selectCurrentItem
                event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                    if (footerSelection >= 0) {
                        footerEntries.getOrNull(footerSelection)?.let { entry ->
                            runFooter(
                                action = entry.action,
                                requestClear = { all -> requestClear(all) },
                                togglePreview = ::togglePreview,
                                showPreferences = { showPreferences = true },
                                showAbout = { showAbout = true },
                                onQuit = onQuit,
                            )
                        }
                    } else if (results.isNotEmpty()) {
                        activate(
                            historySelection,
                            defaultAction(settings, event.isShiftPressed, alt, meta),
                        )
                    } else {
                        // Port of `AppState.select`: nothing is selected, so the query itself
                        // is copied onto the clipboard.
                        repository.copySearchQuery()
                    }
                    true
                }

                // close: `KeyChord.close` closes the popup, and `ListHeaderView` clears the
                // search whenever the panel stops being the key window.
                event.key == Key.Escape -> {
                    repository.clearSearch()
                    onRequestHideWindow()
                    true
                }

                // pinOrUnpin: the recordable `KeyboardShortcuts.Name.pin`, `⌥P` by default
                matchesShortcut(event, settings.pinShortcut) -> {
                    selectedItem?.let { repository.togglePin(it) }
                    true
                }

                // togglePreview: the recordable `togglePreview`, `⌃Space` by default
                matchesShortcut(event, settings.togglePreviewShortcut) -> {
                    togglePreview()
                    true
                }

                // openPreferences: ⌘,
                event.key == Key.Comma && meta -> {
                    showPreferences = true
                    true
                }

                // clearSearch: ⌃U
                control && event.key == Key.U -> {
                    repository.clearSearch()
                    true
                }

                // deleteOneCharFromSearch: ⌃H
                control && event.key == Key.H -> {
                    repository.deleteOneCharFromSearch()
                    searchFocusRequester.requestFocus()
                    true
                }

                // deleteLastWordFromSearch: ⌃W
                control && event.key == Key.W -> {
                    repository.deleteLastWordFromSearch()
                    searchFocusRequester.requestFocus()
                    true
                }

                // clearHistory / clearHistoryAll: ⌥⌘⌫ and ⌥⇧⌘⌫
                (event.key == Key.Delete || event.key == Key.Backspace) && alt && meta -> {
                    requestClear(all = event.isShiftPressed)
                    true
                }

                // deleteCurrentItem: the recordable `delete`, `⌥⌫` by default
                matchesShortcut(event, settings.deleteShortcut) -> {
                    selectedItem?.let { repository.delete(it) }
                    true
                }

                else -> {
                    // `History.pressedShortcutItem` + `HistoryItemAction`: the item shortcut
                    // is matched by key alone and the modifiers pick copy / paste /
                    // paste-without-formatting, so ⌥1, ⌘⇧1 and ⌥⇧1 work too.
                    val character = event.utf16CodePoint
                        .takeIf { it > 0 }
                        ?.toChar()
                        ?.uppercaseChar()
                        ?.toString()
                    val action = if (character == null) {
                        ClipAction.UNKNOWN
                    } else {
                        defaultAction(settings, event.isShiftPressed, alt, meta)
                    }
                    // A plain key press (`default`) must keep typing into the search field.
                    val selectable = action != ClipAction.UNKNOWN && action != ClipAction.DEFAULT
                    val index = if (character != null && selectable) {
                        results.indexOfFirst { result ->
                            shortcuts[result.item.id]?.any { it.character == character } == true
                        }
                    } else {
                        -1
                    }
                    if (index >= 0) {
                        activate(index, action)
                        true
                    } else {
                        false
                    }
                }
            }
        }
    }

    /** One history row, shared by the fixed pins block and the scrolling unpinned list. */
    val entryRow: @Composable (IndexedValue<SearchResult>) -> Unit = { indexed ->
        val item = indexed.value.item
        HistoryRow(
            item = item,
            ranges = indexed.value.ranges,
            shortcut = visibleShortcut(shortcuts[item.id].orEmpty(), flags),
            isSelected = footerSelection < 0 && indexed.index == historySelection,
            highlight = settings.highlightMatch,
            showColorSwatch = settings.showHexColorSwatch,
            maxImageHeight = settings.imageMaxHeight.dp,
            appIconBase64 = if (settings.showApplicationIcons) {
                repository.applicationIcon(item.application?.bundleId)
            } else {
                null
            },
            onClick = {
                repository.select(
                    item,
                    defaultAction(settings, pointerShift, pointerAlt, pointerMeta),
                )
            },
            onHover = { hoverHistory(indexed.index) },
        )
    }

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
                                PointerEventType.Move -> keyboardNavigating = false
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
                if (previewOnLeft && wide && previewOpen) {
                    PreviewSlideout(
                        item = selectedItem,
                        appIconBase64 = selectedItem?.application?.let {
                            repository.applicationIcon(it.bundleId)
                        },
                        previewWidth = settings.previewWidth,
                        onLeft = true,
                        onTogglePin = { selectedItem?.let { repository.togglePin(it) } },
                        onDelete = { selectedItem?.let { repository.delete(it) } },
                        onCopyExtractedText = { selectedItem?.let { repository.copyExtractedText(it) } },
                        onWidthChange = { value ->
                            repository.updateSettings { it.copy(previewWidth = value) }
                        },
                    )
                }

                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Header(
                        title = "Clipper",
                        showTitle = settings.showTitle,
                        visible = searchVisible,
                        query = repository.queryInput,
                        onQueryChange = repository::updateSearchQuery,
                        onCompositionChange = { composing = it },
                        focusRequester = searchFocusRequester,
                        previewOpen = previewOpen,
                        previewOnLeft = previewOnLeft,
                        onTogglePreview = ::togglePreview,
                    )

                    if (settings.ignoreEvents) {
                        PausedBanner(
                            onlyNext = settings.ignoreOnlyNextEvent,
                            onResume = {
                                repository.updateSettings {
                                    it.copy(ignoreEvents = false, ignoreOnlyNextEvent = false)
                                }
                            },
                        )
                    }

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (results.isEmpty()) {
                            EmptyState(searching = repository.queryInput.isNotEmpty())
                        } else {
                            Column(Modifier.fillMaxSize()) {
                                if (pinsAtTop && pinnedEntries.isNotEmpty()) {
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = Popup.horizontalPadding),
                                    ) {
                                        pinnedEntries.forEach { entryRow(it) }
                                    }
                                }
                                if (pinsAtTop && pinsSeparator) PinsSeparator()

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
                                    items(unpinnedEntries, key = { it.value.item.id }) { indexed ->
                                        entryRow(indexed)
                                    }
                                }

                                if (!pinsAtTop && pinnedEntries.isNotEmpty()) {
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

                        if (previewOpen && !wide) {
                            Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
                                PreviewPane(
                                    item = selectedItem,
                                    appIconBase64 = selectedItem?.application?.let {
                                        repository.applicationIcon(it.bundleId)
                                    },
                                    onTogglePin = { selectedItem?.let { repository.togglePin(it) } },
                                    onDelete = { selectedItem?.let { repository.delete(it) } },
                                    onCopyExtractedText = {
                                        selectedItem?.let { repository.copyExtractedText(it) }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }

                    if (settings.showFooter) {
                        FooterRows(
                            flags = flags,
                            selectedIndex = footerSelection,
                            showQuit = onQuit != null,
                            onAction = { action ->
                                runFooter(
                                    action = action,
                                    requestClear = { all -> requestClear(all) },
                                    togglePreview = ::togglePreview,
                                    showPreferences = { showPreferences = true },
                                    showAbout = { showAbout = true },
                                    onQuit = onQuit,
                                )
                            },
                            onHover = { index ->
                                selectFooter(index)
                                // `FooterItemView.onHover`: hovering the footer closes the preview.
                                if (previewOpen) togglePreview()
                            },
                        )
                    }
                }

                if (!previewOnLeft && wide && previewOpen) {
                    PreviewSlideout(
                        item = selectedItem,
                        appIconBase64 = selectedItem?.application?.let {
                            repository.applicationIcon(it.bundleId)
                        },
                        previewWidth = settings.previewWidth,
                        onLeft = false,
                        onTogglePin = { selectedItem?.let { repository.togglePin(it) } },
                        onDelete = { selectedItem?.let { repository.delete(it) } },
                        onCopyExtractedText = { selectedItem?.let { repository.copyExtractedText(it) } },
                        onWidthChange = { value ->
                            repository.updateSettings { it.copy(previewWidth = value) }
                        },
                    )
                }
            }

            repository.statusMessage?.let { message ->
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.onSurface,
                        contentColor = colors.surface,
                        tonalElevation = 4.dp,
                    ) {
                        Text(
                            text = message,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }

    if (showPreferences && confirmation == null) {
        PreferencesDialog(
            settings = settings,
            pinnedItems = repository.items.filter { it.isPinned },
            storageSize = repository.storageSize,
            availablePins = { repository.availablePins(it) },
            onSettingsChange = repository::updateSettings,
            onPinChange = { item, pin -> repository.updatePin(item, pin) },
            onTitleChange = { item, title -> repository.updateTitle(item, title) },
            onContentChange = { item, text -> repository.updateContent(item, text) },
            onDeletePinned = { item -> repository.delete(item) },
            onClearUnpinned = { requestClear(all = false, hidePanel = false) },
            onClearAll = { requestClear(all = true, hidePanel = false) },
            onDismiss = { showPreferences = false },
            screenCount = repository.screenCount,
            supportsLaunchAtLogin = repository.supportsLaunchAtLogin,
            onResetPosition = { controller?.resetPosition() },
            supportsApplicationInfo = repository.supportsApplicationInfo,
            applicationName = repository::applicationName,
            applicationIcon = repository::applicationIcon,
            onPickApplication = if (repository.supportsApplicationInfo) {
                { repository.pickApplication() }
            } else {
                null
            },
        )
    }

    if (showAbout) {
        AboutDialog(
            onDismiss = { showAbout = false },
            onOpenUrl = { url -> repository.openUrl(url) },
        )
    }

    confirmation?.let { request ->
        ConfirmDialog(
            message = request.message,
            comment = request.comment,
            suppress = settings.suppressClearAlert,
            onSuppressChange = { value ->
                repository.updateSettings { it.copy(suppressClearAlert = value) }
            },
            onConfirm = {
                request.onConfirm()
                confirmation = null
            },
            onDismiss = { confirmation = null },
        )
    }
}

private fun runFooter(
    action: FooterAction,
    requestClear: (Boolean) -> Unit,
    togglePreview: () -> Unit,
    showPreferences: () -> Unit,
    showAbout: () -> Unit,
    onQuit: (() -> Unit)?,
) {
    when (action) {
        FooterAction.CLEAR -> requestClear(false)
        FooterAction.CLEAR_ALL -> requestClear(true)
        FooterAction.PREFERENCES -> showPreferences()
        FooterAction.ABOUT -> showAbout()
        FooterAction.QUIT -> onQuit?.invoke()
    }
}

@Composable
private fun Header(
    title: String,
    showTitle: Boolean,
    visible: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onCompositionChange: (Boolean) -> Unit,
    focusRequester: FocusRequester,
    previewOpen: Boolean,
    previewOnLeft: Boolean,
    onTogglePreview: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 15.dp, end = 10.dp)
            .padding(top = Popup.verticalPadding)
            // Maccy collapses the header to zero height instead of removing it, so the search
            // field keeps focus and typing brings it back into view on its own.
            .then(if (visible) Modifier else Modifier.height(0.dp).clipToBounds()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showTitle) {
            Text(
                text = title,
                fontSize = 13.sp,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(5.dp))
        }

        SearchField(
            query = query,
            onQueryChange = onQueryChange,
            onCompositionChange = onCompositionChange,
            focusRequester = focusRequester,
            modifier = Modifier.weight(1f),
        )

        Spacer(Modifier.width(5.dp))
        Box(
            modifier = Modifier
                .height(23.dp)
                .clip(RoundedCornerShape(Popup.cornerRadius))
                .clickable(onClick = onTogglePreview)
                .padding(horizontal = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            // `HeaderView` mirrors the slideout placement: `sidebar.left` when the preview
            // sits on the right, `sidebar.right` when it is docked on the left.
            ClipperIcon(
                if (previewOnLeft) ClipperIconKind.SIDEBAR_RIGHT else ClipperIconKind.SIDEBAR_LEFT,
                size = 15.dp,
                tint = if (previewOpen) colors.primary else colors.onSurface,
            )
        }
        Spacer(Modifier.width(5.dp))
    }
}

@Composable
private fun PausedBanner(onlyNext: Boolean, onResume: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(Popup.cornerRadius))
            .background(colors.primary.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClipperIcon(ClipperIconKind.PAUSE, size = 12.dp, tint = colors.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (onlyNext) "已暂停 — 下一次复制将被忽略" else "已暂停 — 不再记录新的复制内容",
            fontSize = 12.sp,
            color = colors.primary,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(Popup.cornerRadius))
                .clickable(onClick = onResume)
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text("恢复", fontSize = 12.sp, color = colors.primary)
        }
    }
}

@Composable
private fun EmptyState(searching: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ClipperIcon(
            if (searching) ClipperIconKind.SEARCH else ClipperIconKind.COPY,
            size = 28.dp,
            tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (searching) "没有匹配的项目" else "剪贴板历史为空",
            fontSize = 13.sp,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (searching) "换个关键词，或在偏好设置中切换搜索模式。" else "在任意位置复制内容，就会出现在这里。",
            fontSize = 11.sp,
            color = colors.onSurfaceVariant.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )
    }
}

private data class ConfirmRequest(
    val message: String,
    val comment: String,
    val onConfirm: () -> Unit,
)

/** The divider Maccy draws between the pinned block and the scrolling history. */
@Composable
private fun PinsSeparator() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        modifier = Modifier.padding(
            horizontal = Popup.horizontalSeparatorPadding,
            vertical = Popup.verticalSeparatorPadding,
        ),
    )
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

/**
 * Port of `SlideoutView` + `SlideoutController.startResize(.slideout)`: the preview pane with
 * its draggable divider, docked on the right by default and on the left when there is no room
 * for it next to the popup.
 */
@Composable
private fun PreviewSlideout(
    item: ClipItem?,
    appIconBase64: String?,
    previewWidth: Int,
    onLeft: Boolean,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onCopyExtractedText: () -> Unit,
    onWidthChange: (Int) -> Unit,
) {
    if (!onLeft) PreviewDivider(previewWidth, onLeft, onWidthChange)

    PreviewPane(
        item = item,
        appIconBase64 = appIconBase64,
        onTogglePin = onTogglePin,
        onDelete = onDelete,
        onCopyExtractedText = onCopyExtractedText,
        modifier = Modifier.width(previewWidth.dp).fillMaxHeight(),
    )

    if (onLeft) PreviewDivider(previewWidth, onLeft, onWidthChange)
}

/** The divider that stores `Defaults[.previewWidth]` while it is dragged. */
@Composable
private fun PreviewDivider(currentWidth: Int, onLeft: Boolean, onWidthChange: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .padding(vertical = 16.dp)
            .padding(horizontal = Popup.horizontalPadding)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    // Dragging the divider towards the list always shrinks the preview.
                    val signed = if (onLeft) -delta else delta
                    val next = currentWidth - signed / density.density
                    val clamped = next.roundToInt().coerceIn(
                        Popup.minimumPreviewWidth.value.toInt(),
                        PreviewMaxWidth,
                    )
                    if (clamped != currentWidth) onWidthChange(clamped)
                },
            ),
    ) {
        VerticalDivider(color = colors.outline.copy(alpha = 0.5f))
    }
}
