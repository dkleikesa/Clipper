package com.qcmian.clipper.feature.history.viewmodel

import androidx.compose.ui.input.key.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.model.SearchResult
import com.qcmian.clipper.core.domain.search.ClipSearch
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.PinPosition
import com.qcmian.clipper.core.settings.ShortcutSpec
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
import com.qcmian.clipper.feature.history.state.FooterAction
import com.qcmian.clipper.feature.history.state.ClearConfirmation
import com.qcmian.clipper.feature.history.state.ClipboardDialog
import com.qcmian.clipper.feature.history.state.ClipboardUiAction
import com.qcmian.clipper.feature.history.state.ClipboardUiState
import com.qcmian.clipper.feature.history.state.defaultSelectionIndex
import com.qcmian.clipper.feature.preferences.viewmodel.ShortcutRecorder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 历史界面的状态持有者。它持有整个 [ClipboardUiState]，把 [ClipboardUiAction] 转成领域用例调用，
 * 并且是唯一决定界面下一步长什么样的地方。界面本身始终是它所接收状态的纯函数。
 *
 * 界面中三块自洽的行为——搜索节流、列表 / 页脚导航、设置页的快捷键录制——分别位于
 * [HistorySearchController]、[HistoryNavigationController] 与 [ShortcutRecorder]（它们直接
 * 读写本类持有的状态）。本类持有状态、把各部分耦合起来、触达剪贴板生命周期，以及驱动用例。
 *
 * **内存里只有元数据**：状态里的历史是全部条目的元数据（id、标题、类型、时间戳与计数）。
 * 图片随视口按需取回（[ClipboardUiAction.RequestImage]），完整正文只在选中项变化时取一次
 * （见 `syncPreview`）。因此历史有 10 万条时常驻的是十万条元数据，而真正的量级
 * ——图片与长正文——始终留在库里。
 *
 * @param showQuit 宿主是否能退出应用，会因此多出一行页脚。
 * @param canUseGlobalShortcut 试注册一次全局快捷键，判断它是否已被系统或其它应用占用；设置页
 *   录制系统级快捷键时用它把关。宿主无法判断时保持默认值——无从判断不该拦住用户。
 */
class ClipboardViewModel(
    private val repository: ClipboardRepository,
    private val platform: ClipboardPlatform,
    private val useCases: ClipboardUseCases,
    private val showQuit: Boolean = false,
    canUseGlobalShortcut: (ShortcutSpec) -> Boolean = { true },
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClipboardUiState(showQuit = showQuit))
    val uiState: StateFlow<ClipboardUiState> = _uiState.asStateFlow()

    /** 持有键盘 / 鼠标导航与待处理的悬停。 */
    private val navigation = HistoryNavigationController(
        state = _uiState,
        footerCount = ::footerCount,
    )

    /**
     * 持有查询节流。匹配本身不在这里做——见 [refresh]：它要放到后台线程，并且允许被
     * 后一次输入取消。
     */
    private val search = HistorySearchController(
        state = _uiState,
        scope = viewModelScope,
        onQueryApplied = {
            navigation.resetKeyboardNavigation()
            refresh()
        },
    )

    /** 设置页的快捷键录制状态机。 */
    private val shortcutRecorder = ShortcutRecorder(
        state = _uiState,
        updateSettings = { transform -> useCases.updateSettings(transform) },
        canUseGlobalShortcut = canUseGlobalShortcut,
    )

    /** 由宿主设置：隐藏面板，使合成粘贴能到达目标应用。 */
    var onRequestHideWindow: () -> Unit = {}

    /** 由宿主设置：页脚中的「退出」一行。 */
    var onQuitRequest: () -> Unit = {}

    private var statusJob: Job? = null

    /** 当前 [ClipboardUiState.previewItem] 对应的条目 id，避免同一条目被反复加载。 */
    private var previewId: String? = null
    private var previewJob: Job? = null

    /** 正在跑的结果重算；新的一次会取消旧的，因此连续输入只有最后一次会落地。 */
    private var refreshJob: Job? = null

    /** 上一次算 [ClipboardUiState.results] 用的查询词，用来判断「是不是换了查询」。 */
    private var lastAppliedQuery: String? = null

    /** 正在取图的条目 id；防止同一行在重组中反复发起请求。 */
    private val imageRequests = mutableSetOf<String>()

    init {
        // 窗口的任一部分变化都要重新投影一次结果（搜索是窗口的纯函数）。
        viewModelScope.launch {
            combine(
                repository.pinned,
                repository.unpinned,
                repository.totalUnpinned,
            ) { _, _, _ -> Unit }.collect { refresh() }
        }
        viewModelScope.launch { repository.settings.collect { refresh() } }
        // 存储占用变了（空闲页回收完成）：重读一次，设置页里的「存储文件」才会跟着掉。
        viewModelScope.launch { repository.storageRevision.collect { refresh() } }
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
        // 选中项（悬停 / 键盘导航 / 点击 / 删除后的收敛）一变，就去把它的完整内容取回来。
        //
        // 这条链路**不能**挂在 `refresh` 上：鼠标悬停只改 `historySelection`，根本不经过
        // refresh，那样预览面板会一直停在上一条上——而图标是直接读 `selectedMeta` 的，
        // 于是出现「图标和时间在变、内容不变」。
        viewModelScope.launch {
            uiState
                .map { it.selectedMeta }
                .distinctUntilChanged()
                .collect { syncPreview(it) }
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

    fun onQuit() {
        viewModelScope.launch { useCases.handleQuit() }
    }

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

            ClipboardUiAction.TogglePinSelected -> _uiState.value.selectedMeta?.let { meta ->
                viewModelScope.launch { useCases.togglePin(meta) }
                // 置顶之后总是会离开搜索状态。
                search.clearSearch()
            }

            ClipboardUiAction.DeleteSelected -> _uiState.value.selectedMeta?.let { meta ->
                viewModelScope.launch { repository.deleteClip(meta.id) }
            }

            ClipboardUiAction.TogglePreview -> togglePreview()
            ClipboardUiAction.ToggleRecordingPause -> toggleRecordingPause()
            ClipboardUiAction.CopyExtractedText -> _uiState.value.selectedMeta?.let { meta ->
                if (platform.copyExtractedText(meta)) search.clearSearch()
            }

            is ClipboardUiAction.SetPreviewWidth -> setPreviewWidth(action.width)

            is ClipboardUiAction.TogglePin ->
                viewModelScope.launch { useCases.togglePin(action.meta) }

            is ClipboardUiAction.UpdateSettings -> useCases.updateSettings(action.transform)

            is ClipboardUiAction.RequestImage -> loadImage(action.id)

            ClipboardUiAction.ShowPreferences ->
                _uiState.update { it.copy(dialog = ClipboardDialog.PREFERENCES) }

            ClipboardUiAction.DismissPreferences ->
                _uiState.update { it.copy(dialog = null) }

            // 录制期间状态要透给宿主：系统级热键在此期间必须停手（见 `ClipboardUiState`）。
            is ClipboardUiAction.StartShortcutRecording -> shortcutRecorder.start(action.slot)
            ClipboardUiAction.CancelShortcutRecording -> shortcutRecorder.cancel()

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

    /**
     * 设置页录制快捷键期间的一次按键；返回 `true` 表示这次按键已被录制器消费。
     *
     * 界面在**预览阶段**转发它（见 `PreferencesDialog` 根 `Column` 的 `onPreviewKeyEvent`）：
     * 录制中的按键必须就地截下，否则会打进对话框里的输入框、或落到面板自己的快捷键上。
     * 之所以不走 `onAction`，是因为这里要拿返回值，而动作是单向的。
     */
    fun captureShortcutKey(event: KeyEvent): Boolean = shortcutRecorder.onKeyEvent(event)

    // ---------------------------------------------------------------------------------
    // 窗口
    // ---------------------------------------------------------------------------------

    /**
     * 在当前窗口上执行一次查询。
     *
     * 它总是被放到 [Dispatchers.Default] 上调用：这是纯 CPU 计算，成本与历史条数成正比
     * ——模糊模式是 O(查询长 × 标题长) 的编辑距离，两万条长标题足够把主线程按住几百毫秒。
     *
     * 排序已经在 SQL 里完成，这里只做拼接，所以结果顺序不可能与列表里看到的顺序不一致。
     */
    private fun matchResults(
        query: String,
        settings: AppSettings,
        pinned: List<ClipMeta>,
        unpinned: List<ClipMeta>,
    ): List<SearchResult> {
        // 置顶区**不参与筛选，也不参与搜索**：它是用户钉住的常驻参考项。
        //
        // 这样做同时解决两件事：语义上筛选栏只作用于内容区（它就贴在内容区上方），
        // 布局上置顶区的条目数恒定——否则「搜索无匹配」会让置顶区整块消失，
        // 它下面的筛选栏跟着上下跳。
        val pinnedResults = pinned.map { SearchResult(it) }

        // 空集表示用户取消了所有类型：未置顶内容一条都不显示。
        val types = settings.filterTypes
        val searched = ClipSearch.search(query, unpinned.filter { it.kind in types }, settings.searchMode)

        return when (settings.pinTo) {
            PinPosition.TOP -> pinnedResults + searched
            PinPosition.BOTTOM -> searched + pinnedResults
        }
    }

    /**
     * 有图片的行进入组合时取回它的图片。
     *
     * `LazyColumn` 只组合可见项，因此这个函数天然只对视口内的行生效；配合 [refresh] 里对
     * 窗口外图片的清理，[ClipboardUiState.images] 的规模与视口相关，与历史里的图片总数无关。
     */
    private fun loadImage(id: String) {
        if (_uiState.value.images.containsKey(id)) return
        // 同一个 id 只请求一次：行会在重组中反复调用这里。
        if (!imageRequests.add(id)) return
        viewModelScope.launch {
            try {
                val image = repository.payload(id)?.image
                if (image != null) {
                    _uiState.update { it.copy(images = it.images + (id to image)) }
                }
            } finally {
                imageRequests.remove(id)
            }
        }
    }

    /**
     * 把 [meta] 的完整内容取回来。
     *
     * [ClipboardUiState.previewItem] 是预览面板与「复制图片文字」唯一的数据来源：列表里流动的
     * 元数据不含正文与图片字节，它们只有在这一刻才按 id 取一次。
     *
     * 带 [PREVIEW_DEBOUNCE_MILLIS] 的防抖：鼠标划过列表时每一行都会短暂成为「选中项」，
     * 不防抖的话每一行都要读一次库，预览会一路闪过去。
     */
    private fun syncPreview(meta: ClipMeta?) {
        if (meta == null) {
            previewJob?.cancel()
            previewId = null
            _uiState.update { if (it.previewItem != null) it.copy(previewItem = null) else it }
            return
        }
        if (meta.id == previewId) return

        previewId = meta.id
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            delay(PREVIEW_DEBOUNCE_MILLIS)
            val item = repository.item(meta.id)
            // 加载期间选中项可能又变了：只在仍然是同一条时落地。
            _uiState.update { if (it.selectedMeta?.id == meta.id) it.copy(previewItem = item) else it }
        }
    }

    // ---------------------------------------------------------------------------------
    // 激活
    // ---------------------------------------------------------------------------------

    private fun resolveAction(shift: Boolean, alt: Boolean, meta: Boolean): ClipAction =
        defaultAction(_uiState.value.settings, shift, alt, meta)

    private fun activate(index: Int, action: ClipAction) {
        if (action == ClipAction.UNKNOWN) return
        val meta = _uiState.value.results.getOrNull(index)?.meta ?: return

        viewModelScope.launch {
            // 载荷由用例按 id 取：写回剪贴板需要的正文 / 图片不在列表的元数据里。
            val result = useCases.selectClip(meta.id, action) { onRequestHideWindow() }
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

    /** 清除历史前总是先确认。 */
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
        viewModelScope.launch { useCases.clearHistory(confirmation.all) }
        if (confirmation.hidePanel) onRequestHideWindow()
        _uiState.update { it.copy(confirmation = null) }
    }

    // ---------------------------------------------------------------------------------
    // 宿主事件
    // ---------------------------------------------------------------------------------

    private fun onOpened() {
        navigation.resetKeyboardNavigation()
        _uiState.update {
            it.copy(
                // 默认选中内容区（未置顶）第一条，而不是最顶上的置顶项（见 `defaultSelectionIndex`）。
                historySelection = it.results.defaultSelectionIndex(),
                footerSelection = -1,
                focusRequestToken = it.focusRequestToken + 1,
                // 面板重新打开选中第一条：允许界面把它滚进可视区（回到列表顶部）。
                historyScrollToken = it.historyScrollToken + 1,
            )
        }
    }

    /** 松开修饰键时接受当前高亮的条目。 */
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

    /**
     * 重算界面状态。
     *
     * 匹配被放到 [Dispatchers.Default] 上，并且**新的一次会取消旧的**：连续输入时只有最后
     * 一次会跑完，界面不会因为「两万条 × 模糊匹配」而卡住。
     */
    private fun refresh() {
        val settings = repository.settings.value
        val query = _uiState.value.appliedQuery
        val pinned = repository.pinned.value
        val unpinned = repository.unpinned.value
        val total = repository.totalUnpinned.value

        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val results = withContext(Dispatchers.Default) {
                matchResults(query, settings, pinned, unpinned)
            }

            val queryChanged = query != lastAppliedQuery
            lastAppliedQuery = query

            _uiState.update { latest ->
                // 窗口外的图片立即释放：它们只服务视口内的渲染，留着就退化成「图片全量常驻」。
                val visibleIds = results.mapTo(HashSet(results.size)) { it.meta.id }
                val images = if (latest.images.isEmpty()) {
                    latest.images
                } else {
                    latest.images.filterKeys { it in visibleIds }
                }

                latest.copy(
                    settings = settings,
                    // 预览开关来自设置：它是持久化的用户选择，界面状态只是它的投影。
                    previewOpen = settings.previewOpen,
                    results = results,
                    images = images,
                    // 换了查询就把高亮落回第一条（清空搜索时落到内容区第一条，与面板打开时一致）；
                    // 其余刷新只做越界收敛，不打扰用户当前的选中位置。
                    historySelection = when {
                        queryChanged && query.isEmpty() -> results.defaultSelectionIndex()
                        queryChanged -> 0
                        else -> latest.historySelection.coerceIn(0, maxOf(0, results.lastIndex))
                    },
                    footerSelection = if (queryChanged) -1 else latest.footerSelection,
                    // 历史内容真的变了（新条目 / 删除 / 换查询）才递增：让界面把选中项滚回可视区，
                    // 普通的设置刷新不应打扰用户当前的滚动位置。
                    historyScrollToken = if (queryChanged || results != latest.results) {
                        latest.historyScrollToken + 1
                    } else {
                        latest.historyScrollToken
                    },
                    storageBytes = platform.storageBytes,
                    historyCount = total,
                    screenCount = platform.screenCount,
                    supportsLaunchAtLogin = platform.supportsLaunchAtLogin,
                    supportsTextRecognition = platform.supportsTextRecognition,
                )
            }
        }
    }

    /**
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

        /**
         * 预览内容的加载防抖。
         *
         * 鼠标划过一个 30 行的列表会产生 30 次「选中项变化」，不防抖就是 30 次数据库往返，
         * 预览面板会跟着一闪一闪。40 ms 低于人的感知阈值，又足以把中间那些行压掉。
         */
        const val PREVIEW_DEBOUNCE_MILLIS = 40L
    }
}
