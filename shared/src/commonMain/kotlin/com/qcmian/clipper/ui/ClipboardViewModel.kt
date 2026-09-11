package com.qcmian.clipper.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qcmian.clipper.di.ClipboardUseCases
import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.domain.repository.ClipboardPlatform
import com.qcmian.clipper.domain.repository.ClipboardRepository
import com.qcmian.clipper.domain.action.ClipAction
import com.qcmian.clipper.domain.action.defaultAction
import com.qcmian.clipper.domain.usecase.SelectResult
import com.qcmian.clipper.ui.components.FooterAction
import com.qcmian.clipper.ui.state.ClearConfirmation
import com.qcmian.clipper.ui.state.ClipboardDialog
import com.qcmian.clipper.ui.state.ClipboardUiAction
import com.qcmian.clipper.ui.state.ClipboardUiState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 历史界面的状态持有者。它持有整个 [ClipboardUiState]，把 [ClipboardUiAction] 转成领域用例调用，
 * 并且是唯一决定界面下一步长什么样的地方。界面本身始终是它所接收状态的纯函数。
 *
 * 界面中两块自洽的行为——搜索节流、列表 / 页脚导航——分别位于 [HistorySearchController] 与
 * [HistoryNavigationController]（它们直接读写本类持有的状态）。本类持有状态、把各部分耦合
 * 起来、触达剪贴板生命周期，以及驱动用例。
 *
 * @param showQuit 宿主是否能退出应用，会因此多出一行页脚。
 */
class ClipboardViewModel(
    private val repository: ClipboardRepository,
    private val platform: ClipboardPlatform,
    private val useCases: ClipboardUseCases,
    private val showQuit: Boolean = false,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClipboardUiState(showQuit = showQuit))
    val uiState: StateFlow<ClipboardUiState> = _uiState.asStateFlow()

    /** 持有查询节流与搜索任务。 */
    private val search = HistorySearchController(
        state = _uiState,
        settings = { repository.settings.value },
        items = { repository.items.value },
        scope = viewModelScope,
    )

    /** 持有键盘 / 鼠标导航与待处理的悬停。 */
    private val navigation = HistoryNavigationController(
        state = _uiState,
        footerCount = ::footerCount,
        onFooterHovered = ::togglePreview,
    )

    /** 由宿主设置：隐藏面板，使合成粘贴能到达目标应用。 */
    var onRequestHideWindow: () -> Unit = {}

    /** 由宿主设置：页脚中的「退出」一行。 */
    var onQuitRequest: () -> Unit = {}

    private var statusJob: Job? = null

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
        // 在数据源开始发射之前先订阅，这样第一次复制不会丢失；
        // UNDISPATCHED 会让收集器同步运行到它的挂起点。
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { useCases.captureClipboard.run() }
        repository.start()
    }

    override fun onCleared() {
        // 捕获任务运行在 `viewModelScope` 中，会随它一起被取消。
        repository.stop()
        repository.flush()
    }

    /** 对应 `AppDelegate.applicationWillTerminate`。 */
    fun onQuit() = useCases.handleQuit()

    // ---------------------------------------------------------------------------------
    // 动作
    // ---------------------------------------------------------------------------------

    fun onAction(action: ClipboardUiAction) {
        when (action) {
            is ClipboardUiAction.UpdateQuery -> search.updateQuery(action.value)
            ClipboardUiAction.ClearSearch -> search.clearSearch()
            ClipboardUiAction.DeleteSearchChar -> search.deleteSearchChar()
            ClipboardUiAction.DeleteSearchWord -> search.deleteSearchWord()
            ClipboardUiAction.CopySearchQuery -> {
                if (useCases.copySearchQuery(_uiState.value.query)) search.clearSearch()
            }

            ClipboardUiAction.PointerMoved -> navigation.onPointerMoved()
            is ClipboardUiAction.HoverHistory -> navigation.hoverHistory(action.index)
            is ClipboardUiAction.HoverFooter -> navigation.hoverFooter(action.index)
            is ClipboardUiAction.SelectHistory -> navigation.selectHistory(action.index)
            is ClipboardUiAction.MoveNext -> navigation.moveNext(action.allowCycle)
            ClipboardUiAction.MovePrevious -> navigation.movePrevious()
            ClipboardUiAction.MoveToFirst -> navigation.selectHistory(0)
            ClipboardUiAction.MoveToLast -> navigation.moveToLast()

            is ClipboardUiAction.Activate ->
                activate(action.index, resolveAction(action.shift, action.alt, action.meta))
            is ClipboardUiAction.ActivateShortcut -> activate(action.index, action.action)
            is ClipboardUiAction.RunFooter -> runFooter(action.action)
            ClipboardUiAction.Escape ->
                // 搜索状态下 `Esc` 只清空搜索（`KeyChord.clearSearch`）；搜索为空时才关闭面板
                // （`KeyChord.close`）。
                if (_uiState.value.query.isEmpty()) {
                    onRequestHideWindow()
                } else {
                    search.clearSearch()
                }

            ClipboardUiAction.TogglePinSelected -> _uiState.value.selectedItem?.let {
                useCases.togglePin(it)
                // 对应 `History.togglePin`：置顶之后总是会离开搜索状态。
                search.clearSearch()
            }

            ClipboardUiAction.DeleteSelected -> _uiState.value.selectedItem?.let { useCases.deleteClip(it) }
            ClipboardUiAction.TogglePreview -> togglePreview()
            ClipboardUiAction.CopyExtractedText -> _uiState.value.selectedItem?.let { item ->
                if (useCases.copyExtractedText(item)) search.clearSearch()
            }

            is ClipboardUiAction.SetPreviewWidth ->
                useCases.updateSettings { it.copy(previewWidth = action.width) }

            is ClipboardUiAction.TogglePin -> useCases.togglePin(action.item)
            is ClipboardUiAction.DeleteItem -> useCases.deleteClip(action.item)

            is ClipboardUiAction.UpdateSettings -> useCases.updateSettings(action.transform)
            is ClipboardUiAction.UpdatePin -> useCases.updatePin(action.item, action.pin)
            is ClipboardUiAction.UpdateTitle -> useCases.updateTitle(action.item, action.title)
            is ClipboardUiAction.UpdateContent -> useCases.updateContent(action.item, action.text)
            ClipboardUiAction.PickIgnoredApplication -> pickIgnoredApplication()

            ClipboardUiAction.ShowPreferences ->
                _uiState.update { it.copy(dialog = ClipboardDialog.PREFERENCES) }

            ClipboardUiAction.DismissPreferences ->
                _uiState.update { it.copy(dialog = null) }

            is ClipboardUiAction.RequestClear -> requestClear(action.all, action.hidePanel)
            ClipboardUiAction.ConfirmClear -> confirmClear()
            ClipboardUiAction.DismissClear -> _uiState.update { it.copy(confirmation = null) }

            ClipboardUiAction.DismissStatus -> repository.setStatusMessage(null)

            ClipboardUiAction.Opened -> onOpened()
            ClipboardUiAction.Cycle -> navigation.moveNext(allowCycle = true)
            ClipboardUiAction.Accept -> onAccept()
            ClipboardUiAction.Hidden -> _uiState.update { it.copy(previewOpen = false) }
            is ClipboardUiAction.TogglePause -> useCases.updateSettings {
                // `AppDelegate.performStatusItemClick`：⌥ 暂停，⇧⌥ 只暂停下一次。
                val paused = !it.ignoreEvents
                it.copy(
                    ignoreEvents = paused,
                    ignoreOnlyNextEvent = if (action.onlyNext) paused else false,
                )
            }
        }
    }

    /** 对应 `PinsSettingsPane` 的快捷键列。 */
    fun availablePins(item: ClipItem): List<String> = useCases.availablePins(item)

    /** 条目来源应用的图标 base64 PNG。 */
    fun applicationIcon(bundleId: String?): String? = platform.applicationIcon(bundleId)

    /** `NSWorkspace.applicationName(at:)`：把 bundle id 变成显示名。 */
    fun applicationName(bundleId: String): String? = platform.applicationName(bundleId)

    /** `⌃K` 只在第一个条目未被高亮时向上移动，见 Maccy #1055。 */
    fun canMovePreviousWithCtrlK(): Boolean = navigation.canMovePreviousWithCtrlK()

    // ---------------------------------------------------------------------------------
    // 激活
    // ---------------------------------------------------------------------------------

    private fun resolveAction(shift: Boolean, alt: Boolean, meta: Boolean): ClipAction =
        defaultAction(_uiState.value.settings, shift, alt, meta)

    private fun activate(index: Int, action: ClipAction) {
        if (action == ClipAction.UNKNOWN) return
        val item = _uiState.value.results.getOrNull(index)?.item ?: return

        viewModelScope.launch {
            val result = useCases.selectClip(item, action) { onRequestHideWindow() }
            if (result == SelectResult.COPIED || result == SelectResult.PASTING) {
                search.clearSearch()
            }
        }
    }

    private fun runFooter(action: FooterAction) {
        when (action) {
            FooterAction.CLEAR -> requestClear(all = false)
            FooterAction.CLEAR_ALL -> requestClear(all = true)
            FooterAction.PREFERENCES ->
                _uiState.update { it.copy(dialog = ClipboardDialog.PREFERENCES) }

            FooterAction.QUIT -> onQuitRequest()
        }
    }

    /** 对应 `AppState.select` + `ConfirmationView`：遵循「不再提示」。 */
    private fun requestClear(all: Boolean, hidePanel: Boolean = true) {
        if (_uiState.value.settings.suppressClearAlert) {
            useCases.clearHistory(all)
            // `History.clear` 在历史清空后关闭弹窗。
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
        useCases.clearHistory(confirmation.all)
        if (confirmation.hidePanel) onRequestHideWindow()
        _uiState.update { it.copy(confirmation = null) }
    }

    private fun pickIgnoredApplication() {
        val application = platform.pickApplication() ?: return
        val key = application.bundleId ?: application.name
        if (key.isBlank()) return
        useCases.updateSettings { current ->
            if (key in current.ignoredApps) current else current.copy(ignoredApps = current.ignoredApps + key)
        }
    }

    // ---------------------------------------------------------------------------------
    // 宿主事件
    // ---------------------------------------------------------------------------------

    /** 对应 `Popup.handleFirstKeyDown`。 */
    private fun onOpened() {
        _uiState.update {
            it.copy(
                keyboardNavigating = true,
                historySelection = 0,
                footerSelection = -1,
                focusRequestToken = it.focusRequestToken + 1,
            )
        }
    }

    /** 对应 `Popup.handleFlagsChanged`：松开修饰键时接受当前高亮的条目。 */
    private fun onAccept() {
        val state = _uiState.value
        if (state.footerSelection < 0 && state.results.isNotEmpty()) {
            activate(state.historySelection, ClipAction.DEFAULT)
            onRequestHideWindow()
        }
    }

    // ---------------------------------------------------------------------------------
    // 状态投影
    // ---------------------------------------------------------------------------------

    private fun refresh() {
        val settings = repository.settings.value
        val results = search.resultsFor(_uiState.value.appliedQuery)

        _uiState.update {
            it.copy(
                settings = settings,
                results = results,
                historySelection = it.historySelection.coerceIn(0, maxOf(0, results.lastIndex)),
                storageSize = platform.storageSize,
                screenCount = platform.screenCount,
                supportsLaunchAtLogin = platform.supportsLaunchAtLogin,
                supportsApplicationInfo = platform.supportsApplicationInfo,
            )
        }
    }

    /** 对应 `SlideoutController.togglePreview(trigger: .manual)`。 */
    private fun togglePreview() = _uiState.update { it.copy(previewOpen = !it.previewOpen) }

    private fun footerCount(): Int = 3 + if (showQuit) 1 else 0

    private companion object {
        const val STATUS_DURATION_MILLIS = 1_600L
    }
}
