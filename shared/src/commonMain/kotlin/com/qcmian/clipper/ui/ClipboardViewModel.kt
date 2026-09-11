package com.qcmian.clipper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qcmian.clipper.data.model.ClipItem
import com.qcmian.clipper.data.repository.ClipboardRepository
import com.qcmian.clipper.domain.action.ClipAction
import com.qcmian.clipper.domain.action.defaultAction
import com.qcmian.clipper.domain.search.ClipSearch
import com.qcmian.clipper.domain.usecase.AvailablePinsUseCase
import com.qcmian.clipper.domain.usecase.CaptureClipboardUseCase
import com.qcmian.clipper.domain.usecase.ClearHistoryUseCase
import com.qcmian.clipper.domain.usecase.CopyExtractedTextUseCase
import com.qcmian.clipper.domain.usecase.CopySearchQueryUseCase
import com.qcmian.clipper.domain.usecase.DeleteClipUseCase
import com.qcmian.clipper.domain.usecase.HandleQuitUseCase
import com.qcmian.clipper.domain.usecase.SelectClipUseCase
import com.qcmian.clipper.domain.usecase.SelectResult
import com.qcmian.clipper.domain.usecase.TogglePinUseCase
import com.qcmian.clipper.domain.usecase.UpdateContentUseCase
import com.qcmian.clipper.domain.usecase.UpdatePinUseCase
import com.qcmian.clipper.domain.usecase.UpdateSettingsUseCase
import com.qcmian.clipper.domain.usecase.UpdateTitleUseCase
import com.qcmian.clipper.ui.components.FooterAction
import com.qcmian.clipper.ui.state.ClearConfirmation
import com.qcmian.clipper.ui.state.ClipboardDialog
import com.qcmian.clipper.ui.state.ClipboardUiAction
import com.qcmian.clipper.ui.state.ClipboardUiState
import com.qcmian.clipper.util.currentTimeMillis
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * State holder of the history screen. It owns the whole [ClipboardUiState], turns
 * [ClipboardUiAction]s into domain use case calls and is the only place that decides what the
 * screen looks like next. The UI itself stays a pure function of the state it receives.
 *
 * @param showQuit whether the host can quit the application, which adds a footer row.
 * @param autoPreview whether the preview may slide out on its own; only hosts with room for a
 *   side-by-side pane enable it.
 */
class ClipboardViewModel(
    private val repository: ClipboardRepository,
    private val captureClipboard: CaptureClipboardUseCase,
    private val selectClip: SelectClipUseCase,
    private val togglePin: TogglePinUseCase,
    private val updatePin: UpdatePinUseCase,
    private val updateTitle: UpdateTitleUseCase,
    private val updateContent: UpdateContentUseCase,
    private val deleteClip: DeleteClipUseCase,
    private val clearHistory: ClearHistoryUseCase,
    private val updateSettings: UpdateSettingsUseCase,
    private val availablePinsUseCase: AvailablePinsUseCase,
    private val copySearchQuery: CopySearchQueryUseCase,
    private val copyExtractedText: CopyExtractedTextUseCase,
    private val handleQuit: HandleQuitUseCase,
    private val showQuit: Boolean = false,
    private val autoPreview: Boolean = false,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClipboardUiState(showQuit = showQuit))
    val uiState: StateFlow<ClipboardUiState> = _uiState.asStateFlow()

    /** Set by the host: hides the panel so a synthetic paste reaches the target application. */
    var onRequestHideWindow: () -> Unit = {}

    /** Set by the host: the "退出" footer row. */
    var onQuitRequest: () -> Unit = {}

    private var searchJob: Job? = null
    private var autoPreviewJob: Job? = null
    private var statusJob: Job? = null
    private var lastSearchAt = 0L

    /** Port of `NavigationManager.isKeyboardNavigating`'s pending hover. */
    private var pendingHoverSelection = -1

    /** Port of `SlideoutController`: keeps the pane from springing back after a manual close. */
    private var autoOpenSuppressed = false

    private var autoPreviewItemId: String? = null

    init {
        viewModelScope.launch { repository.items.collect { refresh() } }
        viewModelScope.launch { repository.settings.collect { refresh() } }
        viewModelScope.launch {
            repository.statusMessage.collect { message ->
                _uiState.update { it.copy(statusMessage = message) }
                statusJob?.cancel()
                if (message != null) {
                    statusJob = viewModelScope.launch {
                        delay(STATUS_DURATION_MILLIS)
                        repository.setStatusMessage(null)
                    }
                }
            }
        }
        repository.start()
        captureClipboard.start()
    }

    override fun onCleared() {
        captureClipboard.stop()
        repository.stop()
        repository.flush()
    }

    /** Port of `AppDelegate.applicationWillTerminate`. */
    fun onQuit() = handleQuit()

    // ---------------------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------------------

    fun onAction(action: ClipboardUiAction) {
        when (action) {
            is ClipboardUiAction.UpdateQuery -> updateQuery(action.value)
            ClipboardUiAction.ClearSearch -> clearSearch()
            ClipboardUiAction.DeleteSearchChar -> deleteSearchChar()
            ClipboardUiAction.DeleteSearchWord -> deleteSearchWord()
            ClipboardUiAction.CopySearchQuery -> {
                if (copySearchQuery(_uiState.value.query)) clearSearch()
            }

            ClipboardUiAction.PointerMoved -> onPointerMoved()
            is ClipboardUiAction.HoverHistory -> hoverHistory(action.index)
            is ClipboardUiAction.HoverFooter -> hoverFooter(action.index)
            is ClipboardUiAction.SelectHistory -> selectHistory(action.index)
            is ClipboardUiAction.MoveNext -> moveNext(action.allowCycle)
            ClipboardUiAction.MovePrevious -> movePrevious()
            ClipboardUiAction.MoveToFirst -> selectHistory(0)
            ClipboardUiAction.MoveToLast -> moveToLast()

            is ClipboardUiAction.Activate ->
                activate(action.index, resolveAction(action.shift, action.alt, action.meta))
            is ClipboardUiAction.ActivateShortcut -> activate(action.index, action.action)
            is ClipboardUiAction.RunFooter -> runFooter(action.action)
            ClipboardUiAction.Escape -> {
                clearSearch()
                onRequestHideWindow()
            }

            ClipboardUiAction.TogglePinSelected -> _uiState.value.selectedItem?.let {
                togglePin(it)
                // Port of `History.togglePin`: pinning always leaves the search behind.
                clearSearch()
            }

            ClipboardUiAction.DeleteSelected -> _uiState.value.selectedItem?.let { deleteClip(it) }
            ClipboardUiAction.TogglePreview -> togglePreview()
            ClipboardUiAction.CopyExtractedText -> _uiState.value.selectedItem?.let { item ->
                if (copyExtractedText(item)) clearSearch()
            }

            is ClipboardUiAction.SetPreviewWidth ->
                updateSettings { it.copy(previewWidth = action.width) }

            is ClipboardUiAction.TogglePin -> togglePin(action.item)
            is ClipboardUiAction.DeleteItem -> deleteClip(action.item)

            is ClipboardUiAction.UpdateSettings -> updateSettings(action.transform)
            is ClipboardUiAction.UpdatePin -> updatePin(action.item, action.pin)
            is ClipboardUiAction.UpdateTitle -> updateTitle(action.item, action.title)
            is ClipboardUiAction.UpdateContent -> updateContent(action.item, action.text)
            ClipboardUiAction.PickIgnoredApplication -> pickIgnoredApplication()
            is ClipboardUiAction.OpenUrl -> repository.openUrl(action.url)

            ClipboardUiAction.ShowPreferences ->
                _uiState.update { it.copy(dialog = ClipboardDialog.PREFERENCES) }

            ClipboardUiAction.DismissPreferences ->
                _uiState.update { it.copy(dialog = null) }

            ClipboardUiAction.ShowAbout ->
                _uiState.update { it.copy(dialog = ClipboardDialog.ABOUT) }

            ClipboardUiAction.DismissAbout ->
                _uiState.update { it.copy(dialog = null) }

            is ClipboardUiAction.RequestClear -> requestClear(action.all, action.hidePanel)
            ClipboardUiAction.ConfirmClear -> confirmClear()
            ClipboardUiAction.DismissClear -> _uiState.update { it.copy(confirmation = null) }

            ClipboardUiAction.DismissStatus -> repository.setStatusMessage(null)

            ClipboardUiAction.Opened -> onOpened()
            ClipboardUiAction.Cycle -> moveNext(allowCycle = true)
            ClipboardUiAction.Accept -> onAccept()
            ClipboardUiAction.Hidden -> _uiState.update { it.copy(previewOpen = false) }
            is ClipboardUiAction.TogglePause -> updateSettings {
                // `AppDelegate.performStatusItemClick`: ⌥ pauses, ⇧⌥ pauses for one copy.
                val paused = !it.ignoreEvents
                it.copy(
                    ignoreEvents = paused,
                    ignoreOnlyNextEvent = if (action.onlyNext) paused else false,
                )
            }
        }
    }

    /** Port of `PinsSettingsPane`'s key column. */
    fun availablePins(item: ClipItem): List<String> = availablePinsUseCase(item)

    /** Base64 PNG of the icon of the application an item came from. */
    fun applicationIcon(bundleId: String?): String? = repository.applicationIcon(bundleId)

    /** `NSWorkspace.applicationName(at:)`: turns a bundle id into a display name. */
    fun applicationName(bundleId: String): String? = repository.applicationName(bundleId)

    // ---------------------------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------------------------

    /** Port of `History.searchQuery.didSet` + `Throttler(minimumDelay: 0.2)`. */
    private fun updateQuery(value: String) {
        _uiState.update { it.copy(query = value) }

        val now = currentTimeMillis()
        val elapsed = now - lastSearchAt
        val throttle = _uiState.value.settings.searchThrottleMillis
        searchJob?.cancel()

        if (value.isEmpty() || elapsed >= throttle) {
            lastSearchAt = now
            applyQuery(value)
        } else {
            searchJob = viewModelScope.launch {
                delay((throttle - elapsed).toLong())
                lastSearchAt = currentTimeMillis()
                applyQuery(value)
            }
        }
    }

    /** Port of `History.searchQuery.didSet`: a new query highlights the first match. */
    private fun applyQuery(value: String) {
        val settings = repository.settings.value
        val items = repository.items.value
        val results = ClipSearch.search(value, items, settings.searchMode)
        val firstUnpinned = results.indexOfFirst { it.item.isUnpinned }.coerceAtLeast(0)

        _uiState.update {
            it.copy(
                appliedQuery = value,
                results = results,
                keyboardNavigating = true,
                footerSelection = -1,
                historySelection = if (value.isEmpty()) firstUnpinned else 0,
            )
        }
        syncAutoPreview()
    }

    private fun clearSearch() {
        searchJob?.cancel()
        val wasEmpty = _uiState.value.appliedQuery.isEmpty()
        _uiState.update { it.copy(query = "") }
        if (!wasEmpty) applyQuery("")
    }

    /** Port of `KeyChord.deleteOneCharFromSearch` (⌃H). */
    private fun deleteSearchChar() {
        val query = _uiState.value.query
        if (query.isNotEmpty()) updateQuery(query.dropLast(1))
    }

    /** Port of `KeyChord.deleteLastWordFromSearch` (⌃W). */
    private fun deleteSearchWord() {
        val words = _uiState.value.query.split(" ").filter { it.isNotEmpty() }.dropLast(1)
        updateQuery(if (words.isEmpty()) "" else words.joinToString(" ") + " ")
    }

    // ---------------------------------------------------------------------------------
    // Navigation
    // ---------------------------------------------------------------------------------

    private fun onPointerMoved() {
        val state = _uiState.value
        if (!state.keyboardNavigating) return
        val pending = pendingHoverSelection
        pendingHoverSelection = -1
        _uiState.update { current ->
            val shouldApply = pending >= 0 && current.footerSelection < 0
            current.copy(
                keyboardNavigating = false,
                historySelection = if (shouldApply) {
                    pending.coerceIn(0, maxOf(0, current.results.lastIndex))
                } else {
                    current.historySelection
                },
            )
        }
    }

    /** Port of `HoverSelectionModifier`. */
    private fun hoverHistory(index: Int) {
        val state = _uiState.value
        if (!state.keyboardNavigating) {
            if (state.footerSelection >= 0 || state.historySelection != index) {
                _uiState.update {
                    it.copy(
                        historySelection = index.coerceIn(0, maxOf(0, it.results.lastIndex)),
                        footerSelection = -1,
                    )
                }
            }
        } else {
            pendingHoverSelection = index
        }
    }

    /** `FooterItemView.onHover`: hovering the footer closes the preview. */
    private fun hoverFooter(index: Int) {
        selectFooter(index)
        if (_uiState.value.previewOpen) togglePreview()
    }

    private fun selectHistory(index: Int) {
        _uiState.update {
            it.copy(
                keyboardNavigating = true,
                historySelection = index.coerceIn(0, maxOf(0, it.results.lastIndex)),
                footerSelection = -1,
            )
        }
        syncAutoPreview()
    }

    private fun selectFooter(index: Int) {
        _uiState.update {
            it.copy(footerSelection = index.coerceIn(0, maxOf(0, footerCount() - 1)))
        }
    }

    /** Port of `NavigationManager.isFirstItemHighlighted`. */
    private fun isFirstItemHighlighted(): Boolean {
        val state = _uiState.value
        return state.footerSelection < 0 && state.historySelection == 0
    }

    /** Port of `NavigationManager.highlightNext(allowCycle:)`. */
    private fun moveNext(allowCycle: Boolean) {
        val state = _uiState.value
        val footer = footerCount()
        when {
            state.footerSelection >= 0 ->
                if (state.footerSelection < footer - 1) {
                    selectFooter(state.footerSelection + 1)
                } else if (footer > 0) {
                    // Maccy wraps around inside the footer.
                    selectFooter(0)
                }

            state.historySelection < state.results.lastIndex ->
                selectHistory(state.historySelection + 1)

            footer > 0 -> selectFooter(0)
            allowCycle && state.results.isNotEmpty() -> selectHistory(0)
        }
    }

    /** Port of `NavigationManager.highlightPrevious`. */
    private fun movePrevious() {
        val state = _uiState.value
        when {
            state.footerSelection > 0 -> selectFooter(state.footerSelection - 1)
            state.footerSelection == 0 -> selectHistory(maxOf(0, state.results.lastIndex))
            state.historySelection > 0 -> selectHistory(state.historySelection - 1)
        }
    }

    /** Port of `NavigationManager.highlightLast`: the last history item hands over to the footer. */
    private fun moveToLast() {
        val state = _uiState.value
        val footer = footerCount()
        if (state.footerSelection >= 0) {
            selectFooter(maxOf(0, footer - 1))
            return
        }

        val lastIndex = state.results.lastIndex
        if (lastIndex >= 0 && state.historySelection == lastIndex && footer > 0) {
            selectFooter(0)
        } else {
            selectHistory(maxOf(0, lastIndex))
        }
    }

    /** `⌃K` only moves up while the first item is not highlighted, see Maccy #1055. */
    fun canMovePreviousWithCtrlK(): Boolean = !isFirstItemHighlighted()

    private fun footerCount(): Int = 3 + if (showQuit) 1 else 0

    // ---------------------------------------------------------------------------------
    // Activation
    // ---------------------------------------------------------------------------------

    private fun resolveAction(shift: Boolean, alt: Boolean, meta: Boolean): ClipAction =
        defaultAction(_uiState.value.settings, shift, alt, meta)

    private fun activate(index: Int, action: ClipAction) {
        if (action == ClipAction.UNKNOWN) return
        val item = _uiState.value.results.getOrNull(index)?.item ?: return

        val result = selectClip(item, action) { onRequestHideWindow() }
        if (result == SelectResult.COPIED || result == SelectResult.PASTING) {
            clearSearch()
        }
    }

    private fun runFooter(action: FooterAction) {
        when (action) {
            FooterAction.CLEAR -> requestClear(all = false)
            FooterAction.CLEAR_ALL -> requestClear(all = true)
            FooterAction.PREFERENCES ->
                _uiState.update { it.copy(dialog = ClipboardDialog.PREFERENCES) }

            FooterAction.ABOUT -> _uiState.update { it.copy(dialog = ClipboardDialog.ABOUT) }
            FooterAction.QUIT -> onQuitRequest()
        }
    }

    /** Port of `AppState.select` + `ConfirmationView`: honour "don't ask again". */
    private fun requestClear(all: Boolean, hidePanel: Boolean = true) {
        if (_uiState.value.settings.suppressClearAlert) {
            clearHistory(all)
            // `History.clear` closes the popup once the history is gone.
            if (hidePanel) onRequestHideWindow()
            return
        }
        _uiState.update {
            it.copy(
                confirmation = ClearConfirmation(
                    message = "确定要清除历史记录吗？",
                    comment = "无法撤消此操作。",
                    all = all,
                    hidePanel = hidePanel,
                ),
            )
        }
    }

    private fun confirmClear() {
        val confirmation = _uiState.value.confirmation ?: return
        clearHistory(confirmation.all)
        if (confirmation.hidePanel) onRequestHideWindow()
        _uiState.update { it.copy(confirmation = null) }
    }

    /** Port of `SlideoutController.togglePreview(trigger: .manual)`. */
    private fun togglePreview() {
        val willOpen = !_uiState.value.previewOpen
        autoOpenSuppressed = !willOpen
        _uiState.update { it.copy(previewOpen = willOpen) }
    }

    private fun pickIgnoredApplication() {
        val application = repository.pickApplication() ?: return
        val key = application.bundleId ?: application.name
        if (key.isBlank()) return
        updateSettings { current ->
            if (key in current.ignoredApps) current else current.copy(ignoredApps = current.ignoredApps + key)
        }
    }

    // ---------------------------------------------------------------------------------
    // Host events
    // ---------------------------------------------------------------------------------

    /** Port of `Popup.handleFirstKeyDown`. */
    private fun onOpened() {
        autoOpenSuppressed = false
        _uiState.update {
            it.copy(
                keyboardNavigating = true,
                historySelection = 0,
                footerSelection = -1,
                focusRequestToken = it.focusRequestToken + 1,
            )
        }
    }

    /** Port of `Popup.handleFlagsChanged`: releasing the modifiers accepts the highlighted item. */
    private fun onAccept() {
        val state = _uiState.value
        if (state.footerSelection < 0 && state.results.isNotEmpty()) {
            activate(state.historySelection, ClipAction.DEFAULT)
            onRequestHideWindow()
        }
    }

    // ---------------------------------------------------------------------------------
    // State projection
    // ---------------------------------------------------------------------------------

    private fun refresh() {
        val settings = repository.settings.value
        val items = repository.items.value
        val results = ClipSearch.search(_uiState.value.appliedQuery, items, settings.searchMode)

        _uiState.update {
            it.copy(
                settings = settings,
                results = results,
                historySelection = it.historySelection.coerceIn(0, maxOf(0, results.lastIndex)),
                storageSize = repository.storageSize,
                screenCount = repository.screenCount,
                supportsLaunchAtLogin = repository.supportsLaunchAtLogin,
                supportsApplicationInfo = repository.supportsApplicationInfo,
            )
        }
        syncAutoPreview()
    }

    /** Port of `SlideoutController.startAutoOpen`: slide the preview out once selection settles. */
    private fun syncAutoPreview() {
        val state = _uiState.value
        val id = state.selectedItem?.id
        if (id == autoPreviewItemId) return
        autoPreviewItemId = id

        autoPreviewJob?.cancel()
        if (id == null || !autoPreview) return
        if (!state.settings.openPreviewAutomatically || autoOpenSuppressed || state.previewOpen) return

        autoPreviewJob = viewModelScope.launch {
            delay(state.settings.previewDelay.toLong())
            if (!_uiState.value.previewOpen && !autoOpenSuppressed) {
                _uiState.update { it.copy(previewOpen = true) }
            }
        }
    }

    private companion object {
        const val STATUS_DURATION_MILLIS = 1_600L
    }
}
