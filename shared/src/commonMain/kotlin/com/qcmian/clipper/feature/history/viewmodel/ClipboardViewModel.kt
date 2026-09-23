package com.qcmian.clipper.feature.history.viewmodel

import androidx.compose.ui.input.key.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.model.SearchResult
import com.qcmian.clipper.core.domain.search.ClipSearch
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.ClipFilterType
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
import com.qcmian.clipper.feature.history.state.DeepSearchState
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
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

    /** 正在跑的全文搜索；查询变化或再次触发都会取消它。 */
    private var deepSearchJob: Job? = null

    /** 正在跑的批量连续粘贴；用户再发起一次激活时取消旧的。 */
    private var pasteJob: Job? = null

    /**
     * 全文搜索从正文里找到的结果，**追加**在标题命中之后。
     *
     * 它不参与 [refresh] 的全量重排：追加结果若插进列表中间，用户会当场丢失浏览位置，而他们
     * 点那个入口时人就在列表末尾，期待的是「在下面补上更多」。
     */
    private var deepResults: List<SearchResult> = emptyList()

    /**
     * 上一次全文搜索所属的条件：查询词与筛选类型。`null` 表示还没搜过。
     *
     * 两者各自变化都要让上次的结果作废——旧查询从正文里捞出的条目、或不再属于当前类型筛选的
     * 条目，都不该继续挂在新结果后面。
     */
    private var deepQuery: String? = null
    private var deepTypes: Set<ClipFilterType>? = null

    /** 正在取图的条目 id；防止同一行在重组中反复发起请求。 */
    private val imageRequests = mutableSetOf<String>()

    /**
     * 正文缓存：最近看过的那些**完整条目**，按访问顺序排列（最旧的在最前）。
     *
     * 预览是来回看的操作（`A → B → A`），单槽会让每次回头都重新读库；命中它就直接投影进
     * [ClipboardUiState.previewItem]，连防抖都不用等。上限见 [PREVIEW_CACHE_ITEMS]。
     */
    private val previewCache = LinkedHashMap<String, ClipItem>()

    /** [previewCache] 占用的近似字节数（见 `ClipItem.approximateSizeBytes`），与它同步增减。 */
    private var previewBytes = 0L

    /**
     * 已取回的图片字节，**按访问顺序**排列（最旧的在最前）。界面读到的是
     * [ClipboardUiState.images] 那份快照，这里才是决定「装哪几条」的地方。
     *
     * 用 `LinkedHashMap` 加手动前移，而不是 `java.util` 的访问顺序构造器：后者并非在所有
     * Kotlin 目标上都可用（同 `ImageCache`）。
     */
    private val imageCache = LinkedHashMap<String, ClipImage>()

    /** [imageCache] 里所有图片占用的字节数，与它同步增减。 */
    private var imageBytes = 0L

    init {
        // 窗口的任一部分变化都要重新投影一次结果（搜索是窗口的纯函数）。
        viewModelScope.launch {
            combine(
                repository.pinned,
                repository.unpinned,
                repository.totalUnpinned,
            ) { _, _, _ -> Unit }.collect { refresh() }
        }
        // 偏好变化分两类：筛选类型与置顶位置决定「结果集合与顺序」，必须重算匹配；其余偏好
        // （主题、图片高度、快捷键、预览宽度……）与结果无关，重算一次全量匹配是白跑
        // ——一万条要几十毫秒，而改这些设置时用户正瞪着界面等它变。
        viewModelScope.launch {
            repository.settings.collect { settings ->
                val previous = _uiState.value.settings
                if (previous.filterTypes != settings.filterTypes || previous.pinTo != settings.pinTo) {
                    refresh()
                } else {
                    syncSettingsOnly()
                }
            }
        }
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
            ClipboardUiAction.RunDeepSearch -> runDeepSearch()

            ClipboardUiAction.PointerMoved -> navigation.onPointerMoved()
            is ClipboardUiAction.HoverHistory -> navigation.hoverHistory(action.index, action.selectionModifierHeld)
            is ClipboardUiAction.HoverFooter -> navigation.hoverFooter(action.index)
            is ClipboardUiAction.MoveNext -> navigation.moveNext(action.allowCycle)
            ClipboardUiAction.MovePrevious -> navigation.movePrevious()
            ClipboardUiAction.MoveToFirst -> navigation.selectHistory(0)
            ClipboardUiAction.MoveToLast -> navigation.moveToLast()

            is ClipboardUiAction.SelectOnly -> navigation.selectHistory(action.index)
            is ClipboardUiAction.SelectRange -> navigation.extendSelectionTo(action.index)
            is ClipboardUiAction.ToggleSelection -> navigation.toggleSelection(action.index)
            ClipboardUiAction.SelectAll -> navigation.selectAll()
            ClipboardUiAction.ClearSelection -> navigation.clearSelection()

            is ClipboardUiAction.Activate ->
                activate(resolveAction(action.shift, action.alt, action.meta))

            // 数字键指向确定的某一条：先把它收成单选，再按该条目激活。
            is ClipboardUiAction.ActivateShortcut -> {
                navigation.selectHistory(action.index)
                activate(action.action)
            }

            // 菜单里的「复制」：明确要复制，不经过修饰键解析（见 `ClipboardUiAction.CopySelection`）。
            ClipboardUiAction.CopySelection -> activate(ClipAction.COPY)

            is ClipboardUiAction.RunFooter -> runFooter(action.action)

            // 一次按键只做一件事：多选 → 搜索 → 关窗，逐级往后退。
            ClipboardUiAction.Escape -> when {
                _uiState.value.isMultiSelect -> navigation.clearSelection()
                // 搜索状态下 `Esc` 只清空搜索（`KeyChord.clearSearch`）；搜索为空时才关闭面板
                // （`KeyChord.close`）。
                _uiState.value.query.isEmpty() -> onRequestHideWindow()
                else -> search.clearSearch()
            }

            ClipboardUiAction.TogglePinSelected -> togglePinSelected()
            ClipboardUiAction.DeleteSelected -> deleteSelected()

            ClipboardUiAction.TogglePreview -> togglePreview()
            ClipboardUiAction.ToggleRecordingPause -> toggleRecordingPause()
            ClipboardUiAction.CopyExtractedText -> _uiState.value.selectedMeta?.let { meta ->
                // 完整识别原文在载荷里（元数据的标题是截断过的），因此要按 id 取一次。
                viewModelScope.launch {
                    val recognized = repository.payload(meta.id)?.recognizedText
                    if (platform.copyExtractedText(recognized)) search.clearSearch()
                }
            }

            is ClipboardUiAction.SetPreviewWidth -> setPreviewWidth(action.width)

            // 预览面板上的按钮只针对当前这一条（它渲染的就是光标行）：明确给出目标状态，
            // 不走「整批统一方向」那条路。
            is ClipboardUiAction.TogglePin ->
                viewModelScope.launch {
                    useCases.togglePin(listOf(action.meta.id), !action.meta.isPinned)
                }

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
     * ——每条都要对每个查询词做一次子串查找，没连续命中的还要再判一次子序列。
     *
     * 置顶区只拼接、不参与查询；未置顶部分**按匹配质量重排**，因此结果顺序不再等于
     * SQL 给出的顺序（那是没有查询时的顺序）。
     */
    private fun matchResults(
        query: String,
        settings: AppSettings,
        pinned: List<ClipMeta>,
        unpinned: List<ClipMeta>,
        /** 转发给 [ClipSearch.search]：这次查询是否已经被后一次输入取代。 */
        isCancelled: () -> Boolean,
    ): List<SearchResult> {
        // 置顶区**不参与筛选，也不参与搜索**：它是用户钉住的常驻参考项。
        //
        // 这样做同时解决两件事：语义上筛选栏只作用于内容区（它就贴在内容区上方），
        // 布局上置顶区的条目数恒定——否则「搜索无匹配」会让置顶区整块消失，
        // 它下面的筛选栏跟着上下跳。
        val pinnedResults = pinned.map { SearchResult(it) }

        // 空集表示用户取消了所有类型：未置顶内容一条都不显示。
        val types = settings.filterTypes
        val searched = ClipSearch.search(query, unpinned.filter { it.kind in types }, isCancelled)

        // 正文命中**不在这里拼**（见 [deepExtras]）：它可能正好在这次后台计算期间落地，
        // 拼进这份快照就等于丢掉它。
        return when (settings.pinTo) {
            PinPosition.TOP -> pinnedResults + searched
            PinPosition.BOTTOM -> searched + pinnedResults
        }
    }

    /**
     * 把全文搜索从正文里找到的结果接到标题命中之后。
     *
     * 两件事都必须在**读取的那一刻**现做，而不是更早：
     *
     * - **现读 [deepResults]**：一次 [runDeepSearch] 可能正好在标题匹配的后台计算期间落地，
     *   若把它拼进那份更早算好的快照，结果就是「入口写着找到 N 条、列表里却没有」。
     * - **逐条确认那一项还在**：正文命中是那一次搜索留下的快照，其间的删除（或筛选变化）
     *   会让它变成列表里点不开、也删不掉的幽灵条目。
     */
    private fun deepExtras(titleHits: List<SearchResult>, types: Set<ClipFilterType>): List<SearchResult> {
        if (deepResults.isEmpty()) return emptyList()
        val live = repository.unpinned.value.associateBy { it.id }
        val shown = titleHits.mapTo(HashSet(titleHits.size)) { it.meta.id }
        return deepResults.mapNotNull { result ->
            val meta = live[result.meta.id] ?: return@mapNotNull null
            // 类型不再匹配、或标题也命中的条目都不该再补一行。
            if (meta.kind !in types || meta.id in shown) null else SearchResult(meta)
        }
    }

    /**
     * 有图片的行进入组合时取回它的图片，缓存在 [imageCache] 里。
     *
     * 命中也算一次访问：行滚回视野时会再调一次这里，把该条挪到最新端，于是反复看到的图片
     * 不会被淘汰。
     */
    private fun loadImage(id: String) {
        // 命中就挪到最新端：界面只是读 Map、回写不了顺序，「用过了」这个信号只能在这里收。
        imageCache.remove(id)?.let { cached ->
            imageCache[id] = cached
            return
        }

        // 同一个 id 只请求一次：行会在重组中反复调用这里。
        if (!imageRequests.add(id)) return
        viewModelScope.launch {
            try {
                val image = repository.payload(id)?.image ?: return@launch
                imageCache[id] = image
                imageBytes += image.size
                trimImageCache()
                // 顺序对界面没有意义（它只按 id 取），给一份快照是为了让状态保持不可变。
                _uiState.update { it.copy(images = imageCache.toMap()) }
            } finally {
                imageRequests.remove(id)
            }
        }
    }

    /**
     * 淘汰最久没用到的图片，直到回到上限之内。
     *
     * 按字节而不是条数：一张 1080p 截图能到几 MB、一个小图标只有几百字节，按条数限制等于
     * 没有上限。至少留下刚放进去的那一张。
     */
    private fun trimImageCache() {
        while (imageBytes > MAX_IMAGE_CACHE_BYTES && imageCache.size > 1) {
            val oldest = imageCache.keys.firstOrNull() ?: break
            imageCache.remove(oldest)?.let { imageBytes -= it.size }
        }
    }

    /**
     * 把 [meta] 的完整内容取回来给预览面板（列表里流动的元数据不含正文与图片字节）。
     *
     * 命中 [previewCache] 就不读库。防抖对两条路径都保留：鼠标划过列表时每一行都会短暂成为
     * 「选中项」，把中间那些压掉，预览才不会一路闪过去。
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
            val item = cachedPreview(meta) ?: repository.item(meta.id)?.also(::cachePreview)
            // 加载期间选中项可能又变了：只在仍然是同一条时落地。
            _uiState.update { if (it.selectedMeta?.id == meta.id) it.copy(previewItem = item) else it }
        }
    }

    /** 缓存里那一份还能用就返回它（顺带挪到最新端）；不可用时返回 `null`，由调用方去读库。 */
    private fun cachedPreview(meta: ClipMeta): ClipItem? {
        val cached = previewCache.remove(meta.id) ?: return null
        // 库里的条目被改写过时缓存已过期——典型是图片识别刚补上原文，而缓存里还是那份
        // 「没有识别文字」的旧快照（界面会少一个「复制图片文字」按钮）。
        if (cached.hasRecognizedText != meta.hasRecognizedText) return null
        previewCache[meta.id] = cached
        return cached
    }

    private fun cachePreview(item: ClipItem) {
        previewCache[item.id] = item
        previewBytes += item.approximateSizeBytes
        trimPreviewCache()
    }

    /**
     * 淘汰最久没看到的条目，直到回到条数与字节上限之内。
     *
     * 条数上限是主要的（50 条足够覆盖来回翻看），字节上限是保险：`ClipItem` 是**完整载荷**，
     * 图片字节也在里面，一条大图条目能到几 MB。
     */
    private fun trimPreviewCache() {
        while (previewCache.size > PREVIEW_CACHE_ITEMS ||
            (previewBytes > PREVIEW_CACHE_BYTES && previewCache.size > 1)
        ) {
            val oldest = previewCache.keys.firstOrNull() ?: break
            previewCache.remove(oldest)?.let { previewBytes -= it.approximateSizeBytes }
        }
    }

    // ---------------------------------------------------------------------------------
    // 激活
    // ---------------------------------------------------------------------------------

    private fun resolveAction(shift: Boolean, alt: Boolean, meta: Boolean): ClipAction =
        defaultAction(_uiState.value.settings, shift, alt, meta)

    /**
     * 激活**当前选中集**。上一次还没粘完就来了新的一次：旧的当场取消，已经写出去的那几条
     * 会在用例里补记一次复制。
     */
    private fun activate(action: ClipAction) {
        if (action == ClipAction.UNKNOWN) return
        val ids = _uiState.value.selectedMetaIds
        if (ids.isEmpty()) return

        pasteJob?.cancel()
        pasteJob = viewModelScope.launch {
            // 载荷由用例按 id 取：写回剪贴板需要的正文 / 图片不在列表的元数据里。
            val result = useCases.selectClip(ids, action) { onRequestHideWindow() }
            if (result == SelectResult.COPIED || result == SelectResult.PASTING) {
                search.clearSearch()
            }
        }
    }

    /**
     * 整批置顶 / 取消置顶：只要有一条没置顶就整批置顶，全都置顶了才整批取消。
     * 不能让每条各自取反——混合选中时那会把一半翻上去、一半翻下来。
     */
    private fun togglePinSelected() {
        val entries = _uiState.value.selectedEntries
        if (entries.isEmpty()) return
        val pinned = entries.any { !it.meta.isPinned }
        viewModelScope.launch { useCases.togglePin(entries.map { it.meta.id }, pinned) }
        // 置顶之后总是会离开搜索状态。
        search.clearSearch()
    }

    /** 删除整个选中集。删掉的 id 会在下一次 [refresh] 里被剪出选中集，界面随之收敛回单选。 */
    private fun deleteSelected() {
        val ids = _uiState.value.selectedMetaIds
        if (ids.isEmpty()) return
        viewModelScope.launch { repository.delete(ids) }
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
            // 默认选中内容区（未置顶）第一条，而不是最顶上的置顶项（见 `defaultSelectionIndex`）。
            // 上一次会话留下的多选在这里收敛回单选：面板每打开一次，都是一次全新的选择。
            val index = it.results.defaultSelectionIndex()
            it.copy(
                historySelection = index,
                footerSelection = -1,
                selectionAnchor = index,
                selectedIds = it.results.getOrNull(index)?.meta?.id?.let { id -> setOf(id) }.orEmpty(),
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
            activate(ClipAction.DEFAULT)
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

        // 全文搜索的结果属于**它那一次的条件**：查询词或筛选类型一变它就作废，正在跑的那次也
        // 一并停掉。否则旧查询从正文里捞出的条目会挂在新结果后面。
        val types = settings.filterTypes
        val deepSearchStale = query != deepQuery || types != deepTypes
        if (deepSearchStale) {
            deepSearchJob?.cancel()
            deepResults = emptyList()
            deepQuery = query
            deepTypes = types
        }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // 取消只在挂起点生效，而匹配是一整段同步 CPU 循环：把「这次任务还在不在」传进去，
            // 让它在循环里自检（见 `ClipSearch.search`）。否则连续输入时，被取消的旧查询仍然
            // 会把整段扫描跑完，白白占着一个线程。
            val job = coroutineContext[Job]
            val results = withContext(Dispatchers.Default) {
                matchResults(query, settings, pinned, unpinned) { job?.isActive == false }
            }

            val queryChanged = query != lastAppliedQuery
            lastAppliedQuery = query

            _uiState.update { latest ->
                // 正文命中在这里现读现拼：一次 `runDeepSearch` 可能正好在这次后台计算期间落地，
                // 把它拼进上面那份更早的结果就等于丢掉它——表现为「入口写着找到 N 条、
                // 列表里却没有」。
                val extras = deepExtras(results, types)
                val merged = results + extras
                // 结果 id 集合：选中集在它上面剪枝。图片缓存不在这里裁剪——它有自己的按字节
                // 上限（见 `loadImage`），按结果集裁只会让滚回去时重新读库。
                val visibleIds = merged.mapTo(HashSet(merged.size)) { it.meta.id }

                // 换了查询就把高亮落回**内容区第一条**（与面板打开时一致）。
                //
                // 不能写成字面量 `0`：列表顶部可能有置顶项，而置顶区在滚动列表之外。
                // 高亮落在置顶项上时，界面按它在滚动列表里找目标行会找不到，于是既不
                // 滚回顶部、高亮也没落在用户正在看的那一段上。
                //
                // 其余刷新只做越界收敛，不打扰用户当前的选中位置。
                val defaultIndex = merged.defaultSelectionIndex()
                // 选中集是**跨越刷新**的用户意图：新复制、排序变化都不该把它清掉，
                // 因此这里只把已经不存在的条目剪掉（那可能来自删除，也可能来自筛选）。
                val selectedIds = if (queryChanged) {
                    merged.getOrNull(defaultIndex)?.meta?.id?.let { setOf(it) }.orEmpty()
                } else {
                    latest.selectedIds intersect visibleIds
                }

                latest.withSyncedFields(settings, total).copy(
                    results = merged,
                    historySelection = if (queryChanged) defaultIndex else latest.historySelection.coerceIn(0, maxOf(0, merged.lastIndex)),
                    footerSelection = if (queryChanged) -1 else latest.footerSelection,
                    selectionAnchor = if (queryChanged) defaultIndex else latest.selectionAnchor.coerceIn(0, maxOf(0, merged.lastIndex)),
                    selectedIds = selectedIds,
                    // 历史内容真的变了（新条目 / 删除 / 换查询）才递增：让界面把选中项滚回可视区，
                    // 普通的设置刷新不应打扰用户当前的滚动位置。
                    historyScrollToken = if (queryChanged || merged != latest.results) {
                        latest.historyScrollToken + 1
                    } else {
                        latest.historyScrollToken
                    },
                    // 条件变了，上一次的全文搜索结果就不再作数；没变则原样保留——列表因新复制
                    // 而刷新时，用户刚搜出来的那些正文命中不该消失。
                    deepSearch = if (deepSearchStale) DeepSearchState.AVAILABLE else latest.deepSearch,
                    // 报**实际显示**的那几条，而不是搜索当时的命中数：其后的删除或筛选变化会让
                    // 两者不等，而入口上写着的数字必须与列表里多出来的行数一致。
                    deepSearchHits = if (deepSearchStale) 0 else extras.size,
                )
            }
        }
    }

    /**
     * 对**正文**再搜一次当前查询。
     *
     * 正文留在库里（它就是「标题之外的那部分」，一条可能几十 KB，不可能常驻内存），因此这里
     * **分批**读：每批几百个 id，读出来立刻匹配、只留下命中的分数与区间，正文随即丢弃。于是
     * 峰值内存只与「一批的大小」成正比，与历史总量无关。
     *
     * 与 [refresh] 一样跑在后台，并允许被后一次输入取消——但取消点不同：这里的每一批都经过
     * `repository.texts` 的挂起点，协程取消天然生效，不需要像标题匹配那样在循环里自检。
     *
     * 已经匹配过标题的条目直接跳过：它们就在列表里，再扫一遍正文没有意义。
     */
    private fun runDeepSearch() {
        val query = _uiState.value.appliedQuery
        if (query.isEmpty()) return

        val types = _uiState.value.settings.filterTypes
        val unpinned = repository.unpinned.value
        val alreadyShown = _uiState.value.results.mapTo(HashSet()) { it.meta.id }
        // 元数据全量在内存，所以「要搜哪些 id」这个集合问题在这里就能定下来，不必让数据库去
        // 回答它——数据库只负责把正文送过来。
        val pending = unpinned
            .filter { it.kind in types && it.id !in alreadyShown }
            .map { it.id }

        deepSearchJob?.cancel()
        deepResults = emptyList()
        _uiState.update { it.copy(deepSearch = DeepSearchState.RUNNING, deepSearchHits = 0) }

        deepSearchJob = viewModelScope.launch {
            val metaById = unpinned.associateBy { it.id }
            val found = ArrayList<SearchResult>()
            val seen = HashSet<String>()
            var scannedChars = 0L

            for (chunk in pending.chunked(DEEP_SEARCH_BATCH)) {
                if (!isActive) return@launch

                val texts = repository.texts(chunk)
                if (texts.isEmpty()) continue

                scannedChars += texts.sumOf { it.text.length.toLong() }
                val hits = withContext(Dispatchers.Default) {
                    ClipSearch.searchTexts(query, texts.map { it.text }) { !isActive }
                }
                for (hit in hits) {
                    if (found.size >= DEEP_SEARCH_MAX_HITS) break
                    val text = texts[hit.index]
                    val meta = metaById[text.id] ?: continue
                    // 同一个条目的两段正文是两条候选，只留匹配更好的那条：命中已按分数降序，
                    // 所以第一次见到的就是最好的。
                    if (!seen.add(text.id)) continue
                    // 不带高亮区间：命中区间是相对**正文**算的，而这一行渲染的是标题，
                    // 两者不是同一份文本，区间不能直接用。
                    found += SearchResult(meta)
                }

                // 两道闸门：结果够多，或正文读得够多。后者防的是一条超长正文（或一个很大的库）
                // 把一次点击拖成几秒——用户要的是「再看看有没有」，不是把整个库读完。
                if (found.size >= DEEP_SEARCH_MAX_HITS || scannedChars >= DEEP_SEARCH_MAX_CHARS) break
            }

            if (!isActive) return@launch
            deepResults = found
            _uiState.update { it.copy(deepSearch = DeepSearchState.DONE, deepSearchHits = found.size) }
            // 交给 [refresh] 这条统一路径去拼结果：它会在**写入状态的那一刻**逐条校验是否还在
            // （搜索期间可能被删掉），也顺带处理与标题命中的重复。这里自己拼一份则绕开了那些
            // 校验，还多出一次「谁后落地」的竞态。
            refresh()
        }
    }

    /**
     * 只把与结果无关的偏好写进界面状态，**不重算匹配**。
     *
     * 用于「改了设置但结果一模一样」的那些偏好（主题、图片高度、快捷键、预览宽度……）。
     * 结果本身没变，因此选中位置、滚动位置也不该被碰。
     */
    private fun syncSettingsOnly() {
        val settings = repository.settings.value
        val total = repository.totalUnpinned.value
        _uiState.update { latest -> latest.withSyncedFields(settings, total) }
    }

    /**
     * 界面状态里与结果无关的那一份：偏好、存储占用、屏幕数、平台能力。
     *
     * 抽出来是因为有两条路径都要写它——[refresh]（重算完结果顺手补上）与 [syncSettingsOnly]
     * （只写这一份）。两边共用同一个函数，才不会出现「改了某项设置但界面没反应」的漏项。
     */
    private fun ClipboardUiState.withSyncedFields(settings: AppSettings, historyCount: Int) = copy(
        settings = settings,
        // 预览开关来自设置：它是持久化的用户选择，界面状态只是它的投影。
        previewOpen = settings.previewOpen,
        storageBytes = platform.storageBytes,
        historyCount = historyCount,
        screenCount = platform.screenCount,
        supportsLaunchAtLogin = platform.supportsLaunchAtLogin,
        supportsTextRecognition = platform.supportsTextRecognition,
    )

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
         * 全文搜索每批取多少条正文。
         *
         * 一批就是一次 `IN` 查询的规模。几百条让往返次数可接受，又保证单批的正文（哪怕都是
         * 长文本）只占几 MB——这就是「不全读进内存」的落点。
         */
        const val DEEP_SEARCH_BATCH = 200

        /** 全文搜索最多从正文里补多少条：用户要的是「再看看有没有」，不是一份新列表。 */
        const val DEEP_SEARCH_MAX_HITS = 200

        /**
         * 全文搜索最多读多少字符的正文。
         *
         * 上一条限的是**结果数**，这一条限的是**工作量**：正文里没有命中时结果数永远不涨，
         * 只有字符预算能拦住它把整个库读完。
         */
        const val DEEP_SEARCH_MAX_CHARS = 16L * 1024 * 1024

        /**
         * 预览内容的加载防抖。
         *
         * 鼠标划过一个 30 行的列表会产生 30 次「选中项变化」，不防抖就是 30 次数据库往返，
         * 预览面板会跟着一闪一闪。40 ms 低于人的感知阈值，又足以把中间那些行压掉。
         */
        const val PREVIEW_DEBOUNCE_MILLIS = 40L

        /** 正文缓存的条数上限：预览是来回看的操作，单槽会让每次回头都重新读库。 */
        const val PREVIEW_CACHE_ITEMS = 50

        /** 正文缓存的字节上限（`ClipItem` 是完整载荷，图片字节也在里面）。 */
        const val PREVIEW_CACHE_BYTES = 16L * 1024L * 1024L

        /** 图片字节缓存的上限。与 `ImageCache`（解码后的位图，同样 32 MB）构成两级缓存。 */
        const val MAX_IMAGE_CACHE_BYTES = 32L * 1024L * 1024L
    }
}
