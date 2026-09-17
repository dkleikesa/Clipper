package com.qcmian.clipper.feature.history.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.di.ClipboardUseCases
import com.qcmian.clipper.core.domain.repository.ClipboardPlatform
import com.qcmian.clipper.core.domain.repository.ClipboardRepository
import com.qcmian.clipper.core.domain.action.ClipAction
import com.qcmian.clipper.core.domain.action.defaultAction
import com.qcmian.clipper.core.domain.usecase.SelectResult
import com.qcmian.clipper.core.domain.usecase.copyExtractedText
import com.qcmian.clipper.core.domain.usecase.copySearchQuery
import com.qcmian.clipper.core.domain.usecase.deleteClip
import com.qcmian.clipper.core.domain.usecase.updateContent
import com.qcmian.clipper.core.domain.usecase.updateTitle
import com.qcmian.clipper.feature.history.state.FooterAction
import com.qcmian.clipper.feature.history.state.ClearConfirmation
import com.qcmian.clipper.feature.history.state.ClipboardDialog
import com.qcmian.clipper.feature.history.state.ClipboardUiAction
import com.qcmian.clipper.feature.history.state.ClipboardUiState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

    /** 持有键盘 / 鼠标导航与待处理的悬停。 */
    private val navigation = HistoryNavigationController(
        state = _uiState,
        footerCount = ::footerCount,
    )

    /** 持有查询节流与搜索任务。 */
    private val search = HistorySearchController(
        state = _uiState,
        settings = { repository.settings.value },
        items = { repository.items.value },
        scope = viewModelScope,
        onQueryApplied = navigation::resetKeyboardNavigation,
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
            ClipboardUiAction.CopySearchQuery -> {
                if (platform.copySearchQuery(_uiState.value.query)) search.clearSearch()
            }

            ClipboardUiAction.PointerMoved -> navigation.onPointerMoved()
            is ClipboardUiAction.HoverHistory -> navigation.hoverHistory(action.index)
            is ClipboardUiAction.HoverFooter -> navigation.hoverFooter(action.index)
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

            ClipboardUiAction.DeleteSelected -> _uiState.value.selectedItem?.let { repository.deleteClip(it) }
            ClipboardUiAction.TogglePreview -> togglePreview()
            ClipboardUiAction.ToggleRecordingPause -> toggleRecordingPause()
            ClipboardUiAction.CopyExtractedText -> _uiState.value.selectedItem?.let { item ->
                if (platform.copyExtractedText(item)) search.clearSearch()
            }

            is ClipboardUiAction.SetPreviewWidth -> setPreviewWidth(action.width)

            is ClipboardUiAction.TogglePin -> useCases.togglePin(action.item)
            is ClipboardUiAction.DeleteItem -> repository.deleteClip(action.item)

            is ClipboardUiAction.UpdateSettings -> useCases.updateSettings(action.transform)
            is ClipboardUiAction.UpdateTitle -> repository.updateTitle(action.item, action.title)
            is ClipboardUiAction.UpdateContent -> repository.updateContent(action.item, action.text)
            ClipboardUiAction.PickIgnoredApplication -> pickIgnoredApplication()

            ClipboardUiAction.ShowPreferences ->
                _uiState.update { it.copy(dialog = ClipboardDialog.PREFERENCES) }

            ClipboardUiAction.DismissPreferences ->
                _uiState.update { it.copy(dialog = null) }

            // 录制状态要透给宿主：系统级热键在此期间必须停手（见 `ClipboardUiState`）。
            is ClipboardUiAction.SetShortcutRecording ->
                _uiState.update { it.copy(isRecordingShortcut = action.active) }

            is ClipboardUiAction.RequestClear -> requestClear(action.all, action.hidePanel)
            ClipboardUiAction.ConfirmClear -> confirmClear()
            ClipboardUiAction.DismissClear -> _uiState.update { it.copy(confirmation = null) }

            ClipboardUiAction.Opened -> onOpened()
            ClipboardUiAction.Cycle -> navigation.moveNext(allowCycle = true)
            ClipboardUiAction.Accept -> onAccept()
            // 面板被宿主隐藏时不再收起预览：开关是持久化的用户选择，
            // 只有用户主动切换才会翻转（见 `AppSettings.previewOpen`）。
            ClipboardUiAction.Hidden -> Unit
        }
    }

    /** 条目来源应用的图标 base64 PNG。 */
    fun applicationIcon(bundleId: String?): String? = platform.applicationIcon(bundleId)

    /** `NSWorkspace.applicationName(at:)`：把 bundle id 变成显示名。 */
    fun applicationName(bundleId: String): String? = platform.applicationName(bundleId)

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
            FooterAction.PREFERENCES ->
                _uiState.update { it.copy(dialog = ClipboardDialog.PREFERENCES) }

            FooterAction.QUIT -> onQuitRequest()
        }
    }

    /** 对应 `AppState.select` + `ConfirmationView`：清除历史前总是先确认。 */
    private fun requestClear(all: Boolean, hidePanel: Boolean = true) {
        _uiState.update {
            it.copy(
                confirmation = ClearConfirmation(
                    message = "确定要清除历史记录吗？",
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
        navigation.resetKeyboardNavigation()
        _uiState.update {
            it.copy(
                historySelection = 0,
                footerSelection = -1,
                focusRequestToken = it.focusRequestToken + 1,
                // 面板重新打开选中第一条：允许界面把它滚进可视区（回到列表顶部）。
                historyScrollToken = it.historyScrollToken + 1,
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

        _uiState.update { latest ->
            latest.copy(
                settings = settings,
                // 预览开关来自设置：它是持久化的用户选择，界面状态只是它的投影。
                previewOpen = settings.previewOpen,
                results = results,
                historySelection = latest.historySelection.coerceIn(0, maxOf(0, results.lastIndex)),
                // 历史内容真的变了（新条目 / 删除等）才递增：让界面把选中项滚回可视区，
                // 普通的设置刷新不应打扰用户当前的滚动位置。
                historyScrollToken = if (results != latest.results) {
                    latest.historyScrollToken + 1
                } else {
                    latest.historyScrollToken
                },
                storageSize = platform.storageSize,
                historyBytes = repository.items.value
                    .filter { it.isUnpinned }
                    .sumOf { it.approximateSizeBytes },
                screenCount = platform.screenCount,
                supportsLaunchAtLogin = platform.supportsLaunchAtLogin,
                supportsApplicationInfo = platform.supportsApplicationInfo,
                supportsTextRecognition = platform.supportsTextRecognition,
            )
        }
    }

    /**
     * 对应 `SlideoutController.togglePreview(trigger: .manual)`。
     *
     * 写的是设置而不是界面状态：开关随设置持久化，因此面板关闭、应用重启都不会把它复位，
     * 打开的预览会一直开着（见 `AppSettings.previewOpen`）。
     */
    private fun togglePreview() =
        useCases.updateSettings { it.copy(previewOpen = !it.previewOpen) }

    /**
     * 暂停 / 恢复记录（可录制的 `pause` 快捷键，默认 `⌘P`）。
     *
     * 与设置页里的开关、暂停横幅上的「恢复」写的是同一份偏好，因此托盘图标置灰、横幅显隐
     * 都会跟着变——这里刻意不做第二套状态。
     */
    private fun toggleRecordingPause() =
        useCases.updateSettings { it.copy(ignoreEvents = !it.ignoreEvents) }

    /**
     * 拖动分隔条松手：把预览宽度落盘。
     *
     * 窗口宽度在拖动期间固定不变（见 `HistoryScreen.draggedPreviewWidth`），因此落盘时必须**连同
     * 主列表的新宽度一起**写——预览多占的那一段正是主列表让出来的。两者之和不变，宿主算出来的
     * 窗口几何与拖动前完全相同，`applyWindowBounds` 直接去重掉这次「变化」，原生窗口一动不动。
     *
     * 主列表的下限取**划分下限**（[Popup.minimumSplitContentWidth]，比窗口自身的下限小）：默认
     * 窗口里主列表正好站在窗口下限上，若划分也按它卡住，预览就再也宽不了（拖动上限等于当前宽度）。
     * 窗口自身的下限只约束「拖窗口边缘」，两者在 `WindowSizing.minimumWindowSizeOf` 里区分。
     */
    private fun setPreviewWidth(width: Int) {
        useCases.updateSettings { current ->
            val preview = width.coerceIn(
                Popup.minimumPreviewWidth.value.toInt(),
                Popup.maximumPreviewWidth.value.toInt(),
            )
            val listBefore = Popup.contentWidthOf(current.customWindowWidth).value.roundToInt()
            val listAfter = listBefore + current.previewWidth - preview
            current.copy(
                previewWidth = preview,
                // 主列表宽度没变时保持原值：`null` 表示「自动宽度」，别被一次没改变布局的
                // 拖动写成常量。
                customWindowWidth = if (listAfter == listBefore) {
                    current.customWindowWidth
                } else {
                    listAfter.coerceAtLeast(Popup.minimumSplitContentWidth.value.toInt())
                },
            )
        }
    }

    private fun footerCount(): Int = 3 + if (showQuit) 1 else 0

    private companion object {
        const val STATUS_DURATION_MILLIS = 1_600L
    }
}
