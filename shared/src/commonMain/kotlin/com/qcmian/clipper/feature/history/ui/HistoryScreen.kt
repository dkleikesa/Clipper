package com.qcmian.clipper.feature.history.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import com.qcmian.clipper.core.domain.model.SearchResult
import com.qcmian.clipper.core.settings.PinPosition
import com.qcmian.clipper.core.ui.ModifierFlags
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.core.ui.visibleShortcut
import kotlinx.coroutines.flow.collect
import com.qcmian.clipper.feature.history.ui.components.AnimatedPreviewCard
import com.qcmian.clipper.feature.history.ui.components.EmptyState
import com.qcmian.clipper.feature.history.ui.components.FooterRows
import com.qcmian.clipper.feature.history.ui.components.HistoryHeader
import com.qcmian.clipper.feature.history.ui.components.HistoryRow
import com.qcmian.clipper.feature.history.ui.components.HistoryScrollbar
import com.qcmian.clipper.feature.history.ui.components.PausedBanner
import com.qcmian.clipper.feature.history.ui.components.PinnedSection
import com.qcmian.clipper.feature.history.ui.components.PreviewPane
import com.qcmian.clipper.feature.history.ui.components.PreviewSlideout
import com.qcmian.clipper.feature.history.ui.components.previewSlot
import com.qcmian.clipper.feature.history.ui.components.StatusToast
import com.qcmian.clipper.feature.history.ui.components.footerEntries
import com.qcmian.clipper.feature.history.ui.components.historyRowHeight
import com.qcmian.clipper.core.ui.components.rememberApplicationIcon
import com.qcmian.clipper.feature.history.state.ClipboardUiAction
import com.qcmian.clipper.feature.history.state.ClipboardUiState
import com.qcmian.clipper.feature.history.viewmodel.resolveKeyActions
import com.qcmian.clipper.feature.history.viewmodel.shortcutMap

/**
 * 主界面：头部、历史列表、页脚，外加预览滑出面板。
 *
 * 界面是 [state] 的纯函数；每一次交互都通过 [onAction] 回传。所有行为都在
 * `ClipboardViewModel` 中。
 *
 * @param previewHost 宿主为预览面板提供的空间约束（停靠侧、是否只能覆盖、是否加宽窗口）。
 *   落位的推导见 [previewPlacement]。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun HistoryScreen(
    state: ClipboardUiState,
    onAction: (ClipboardUiAction) -> Unit,
    /** 内容希望得到的高度：贴合内容，随条目变化。 */
    onPreferredHeightChange: (Dp) -> Unit,
    /** 窗口的下限高度：滑动区的下限加上置顶区与头部 / 页脚，手动拖拽时宿主不会低于它。 */
    onMinimumHeightChange: (Dp) -> Unit,
    applicationIcon: (String?) -> String?,
    applicationName: (String) -> String?,
    /**
     * 设置页录制快捷键期间的一次按键；返回 `true` 表示已被录制器消费。
     *
     * 它由宿主直接提供（`ClipboardViewModel.captureShortcutKey`），而不是走 [onAction]：录制器
     * 要读最新状态、返回值也要同帧拿到，否则按键会晚一帧才被拦住。
     */
    captureShortcutKey: (KeyEvent) -> Boolean = { false },
    previewHost: PreviewHostPolicy = PreviewHostPolicy(),
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

    // `focusRequestToken` 由宿主在请求显示面板时自增（`Popup.handleFirstKeyDown`）；首次组合时它
    // 为 0，这次请求顺带覆盖了「面板第一次打开」，因此不需要再单独起一个 `Unit` 副作用。
    // 重申焦点的理由见 [awaitSearchFocus]。
    LaunchedEffect(state.focusRequestToken) {
        awaitSearchFocus(searchFocusRequester)
    }

    // 对应 Maccy `KeyHandlingView` 常驻第一响应者的体验：搜索框是面板的常驻输入点，光标
    // 应当一直可见、随时可输入。但点击面板内的任何按钮（预览开关、行内操作、页脚……）都会
    // 把焦点从搜索框抢走，光标消失，用户得再点一次输入框。因此每次「有实质的」交互之后把
    // 焦点请回来；设置 / 清除确认弹窗打开期间除外——那时焦点属于弹窗，关闭后自动恢复。
    var refocusToken by remember { mutableStateOf(0) }
    val onUiAction: (ClipboardUiAction) -> Unit = { action ->
        when (action) {
            // 高频且与焦点无关的动作不触发重聚焦，避免悬停 / 输入时逐帧重挂副作用。
            is ClipboardUiAction.HoverHistory,
            is ClipboardUiAction.HoverFooter,
            is ClipboardUiAction.PointerMoved,
            is ClipboardUiAction.Hidden -> Unit
            else -> refocusToken++
        }
        onAction(action)
    }
    LaunchedEffect(refocusToken, state.isModalOpen) {
        if (state.isModalOpen) return@LaunchedEffect
        awaitSearchFocus(searchFocusRequester)
    }

    val density = LocalDensity.current

    // 量出的各区块高度。它们从 0 开始，与 `Popup.height` 一致：
    // 面板先以最小高度打开，测量结果随后调整大小，使其贴合内容。
    var headerHeight by remember { mutableStateOf(0.dp) }
    var topPinsHeight by remember { mutableStateOf(0.dp) }
    var bottomPinsHeight by remember { mutableStateOf(0.dp) }
    var footerHeight by remember { mutableStateOf(0.dp) }

    val footerEntries = footerEntries(state.showQuit)

    // 置顶 / 未置顶的切分由 [ClipboardUiState] 按实例缓存（见其 KDoc），这里直接读即可。
    val pinnedEntries = state.pinnedEntries
    val unpinnedEntries = state.unpinnedEntries
    // 快捷键映射是纯 UI 交互模型，不在状态里，因此这里自己缓存：`BoxWithConstraints` 在约束
    // 变化时会重新子组合整个界面——拖动窗口尺寸时每帧都会发生——缓存可避免每帧重建映射。
    val shortcuts = remember(results, settings.pasteByDefault) {
        shortcutMap(results, settings.pasteByDefault)
    }

    // 对应 `HistoryListView.scrollBottomPadding`。
    val pinsAtTop = settings.pinTo == PinPosition.TOP
    val pinsSeparator = pinnedEntries.isNotEmpty() && unpinnedEntries.isNotEmpty()

    // 条目高度是**精确值**，不是估算：文本行恒为 `Popup.itemHeight`，图片行恒为
    // `imageMaxHeight` 加 `ImageRowPadding`。两个高度都由 `historyRowHeight` 一处给出，
    // 列表渲染（`HistoryRow` 传给 `ListItemRow` 的 `height`）读的也是它——行高是内容高度、
    // 窗口高度与滚动条三者共用的唯一依据，因此不可能各自推算出一套数来。
    // 这些都是**固定高度而非下限**，任何让行长高的内容都会让它们一起算少。
    val rowHeight: (SearchResult) -> Dp = { result ->
        historyRowHeight(result.item, settings.imageMaxHeight.dp)
    }

    // 滚动条所需的逐条高度。与窗口高度共用同一个 [rowHeight]：两个消费者读同一个函数，
    // 「窗口为什么这么高」与「滑块为什么这么长」就不可能对不上。
    val scrollHeights = remember(unpinnedEntries, settings.imageMaxHeight, density) {
        FloatArray(unpinnedEntries.size) { index ->
            with(density) { rowHeight(unpinnedEntries[index].value).toPx() }
        }
    }

    // 窗口高度是内容的纯函数，推导见 [historyHeightMetrics]。
    val metrics = historyHeightMetrics(
        itemsHeight = unpinnedEntries.fold(0.dp) { total, entry -> total + rowHeight(entry.value) },
        headerHeight = headerHeight,
        topPinsHeight = topPinsHeight,
        bottomPinsHeight = bottomPinsHeight,
        footerHeight = footerHeight,
        pinsAtTop = pinsAtTop,
        pinsSeparator = pinsSeparator,
    )
    val scrollPadding = with(density) {
        (Popup.verticalSeparatorPadding + metrics.listBottomPadding).toPx()
    }

    LaunchedEffect(metrics.preferredHeight) { onPreferredHeightChange(metrics.preferredHeight) }
    // 窗口的下限与「希望多高」是两件事：前者是滑动区的下限（外加置顶区与头部 / 页脚），
    // 后者随内容条数变化。宿主手动拖拽时用的下限就是这里报上去的值。
    LaunchedEffect(metrics.minimumHeight) { onMinimumHeightChange(metrics.minimumHeight) }

    // `NavigationManager.scroll(to:)` 只会滚动未置顶列表；置顶区块始终可见。
    // 目标行已经完整可见时不再滚动。
    //
    // 只跟随 [ClipboardUiState.historyScrollToken]——它只在键盘导航、新查询结果、面板重新打开、
    // 历史内容变化时递增。悬停也会更新选中项但不递增令牌：鼠标划过列表时行只高亮、列表不动，
    // 否则会出现「悬停 → 选中变化 → 滚动 → 鼠标下换了行」的循环。
    LaunchedEffect(state.historyScrollToken) {
        val target = unpinnedEntries.indexOfFirst { it.index == state.historySelection }
        if (target < 0) return@LaunchedEffect
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == target }
        val fullyVisible = item != null &&
            item.offset >= info.viewportStartOffset &&
            item.offset + item.size <= info.viewportEndOffset
        if (!fullyVisible) listState.animateScrollToItem(target)
    }

    /**
     * 平台会为带修饰键的按键**额外补送一个字符事件**（AWT 的 `KEY_TYPED`，在 Compose 里类型是
     * [KeyEventType.Unknown]，字符就是 `utf16CodePoint`）：macOS 上 `⌃1` 送 `1`、`⌥1` 送 `¡`。
     * 它不是用户想搜索的内容，必须在预览阶段吞掉，否则条目快捷键会一边生效一边把字符打进搜索框
     * （`⌘` 组合不补送该事件，所以只有 `⌃` / `⌥` 变体会漏）。
     *
     * 只吞「刚刚被面板处理过的那一次按键」补送的字符：普通输入照常进搜索框；⌃K 那种有意不被
     * 面板消费、留给搜索框的按键也照旧（见 `resolveKeyActions` 的 ⌃K 分支）。
     */
    var swallowTypedCharacter by remember { mutableStateOf(false) }

    val keyHandler: (KeyEvent) -> Boolean = { event ->
        flags.update(event)
        if (event.type == KeyEventType.Unknown) {
            val swallow = swallowTypedCharacter
            swallowTypedCharacter = false
            swallow
        } else {
            val actions = resolveKeyActions(
                event = event,
                state = state,
                flags = flags,
                composing = composing,
                shortcuts = shortcuts,
                footerActions = footerEntries.map { it.action },
            )
            // 这次按键已被面板消费（无论是条目快捷键还是别的动作），它随后补送的字符不该再落进搜索框。
            swallowTypedCharacter = actions.isNotEmpty()
            actions.forEach(onUiAction)
            actions.isNotEmpty()
        }
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
                onUiAction(
                    ClipboardUiAction.Activate(
                        index = indexed.index,
                        shift = pointerShift,
                        alt = pointerAlt,
                        meta = pointerMeta,
                    ),
                )
            },
            onHover = { onUiAction(ClipboardUiAction.HoverHistory(indexed.index)) },
        )
    }

    // 每个选中项只解析一次，且不在组合线程上。
    val previewAppIcon = rememberApplicationIcon(
        load = applicationIcon,
        bundleId = state.selectedItem?.application?.bundleId,
    )

    // 「预览能不能与主列表并排显示」。刻意不用 `BoxWithConstraints`：它每次测量都会给
    // `SubcomposeLayout` 传一个新的 content lambda，而后者按引用比较 lambda、判定「内容变了」
    // 就重组整个槽位——拖动窗口尺寸时约束每帧都在变，等于每帧把整棵界面重组一遍。
    // 这里只记录两个数字（窗口宽度、主列表宽度），它们的变化频率远低于每帧。
    var windowWidth by remember { mutableStateOf(0.dp) }
    // 主列表的宽度：预览关闭时它等于窗口宽度，预览并排展开后它等于窗口宽度减去滑出面板。
    // 桌面端把窗口加宽到「它 + 滑出面板」时，就说明窗口已经为预览让出位置了。
    var listWidth by remember { mutableStateOf(0.dp) }

    // 拖动分隔条期间的预览宽度：只作用于界面渲染，**不**写设置——设置一变，宿主就会按
    // 「主列表 + 预览」重算窗口宽度，于是「拖分隔条」变成了「拖整个窗口」。松手时才把新宽度与
    // 新的主列表宽度一次性写回（见 `ClipboardUiAction.SetPreviewWidth`），窗口全程不动。
    var draggedPreviewWidth by remember { mutableStateOf<Int?>(null) }
    // 设置一变、或预览一开一关就丢掉本地值：它是「这次拖动的临时态」，设置跟上了说明拖动已经
    // 落盘（预览关掉则说明这次拖动被中断，落盘的那一条永远不会来了）。反过来（本地值一直压着
    // 设置）会让别处改动的预览宽度在界面上看不见。
    LaunchedEffect(settings.previewWidth, state.previewOpen) { draggedPreviewWidth = null }
    val previewWidth = draggedPreviewWidth ?: settings.previewWidth

    // 内容区（主列表）的宽度。取自设置，与主列表实测出来的宽度是两回事：后者在槽位钉住的
    // 几帧里是旧值，甚至是被上一帧挤出来的窄值。
    val contentWidth = Popup.contentWidthOf(settings.customWindowWidth)

    val slideoutWidth = Popup.slideoutWidth(settings.previewWidth)
    // 预览槽位的几何推导见 [previewSlotMetrics]。
    val slot = previewSlotMetrics(
        previewOpen = state.previewOpen,
        host = previewHost,
        windowWidth = windowWidth,
        listWidth = listWidth,
        contentWidth = contentWidth,
        slideoutWidth = slideoutWidth,
    )
    val placement = previewPlacement(
        // 宿主说「窗口已经让出位置」就等于说「预览开着」；把它并进来，进场的判定就与窗口加宽
        // 同帧发生。只用界面自己那份 `previewOpen` 不行：它要晚一帧，那一帧窗口已经加宽、卡片
        // 却还没进场，主列表会先铺满整窗再缩回去——一次打开闪两下。
        previewOpen = state.previewOpen || previewHost.windowReady,
        host = previewHost,
        docked = slot.docked,
    )

    /** 单侧停靠的预览卡片；两侧共用，仅停靠侧与可见性不同。 */
    val dockedCard: @Composable (onLeft: Boolean, visible: Boolean) -> Unit = { onLeft, visible ->
        AnimatedPreviewCard(visible = visible, onLeft = onLeft) {
            PreviewSlideout(
                item = state.selectedItem,
                appIconBase64 = previewAppIcon,
                previewWidth = previewWidth,
                maxDragWidth = slot.maxDragWidth,
                onLeft = onLeft,
                onTogglePin = { onUiAction(ClipboardUiAction.TogglePinSelected) },
                onDelete = { onUiAction(ClipboardUiAction.DeleteSelected) },
                onCopyExtractedText = { onUiAction(ClipboardUiAction.CopyExtractedText) },
                // 拖动中只改界面上的宽度（窗口不动），松手才连同新的主列表宽度写回设置。
                onWidthChange = { value -> draggedPreviewWidth = value },
                onWidthChangeFinished = {
                    draggedPreviewWidth?.let {
                        onUiAction(ClipboardUiAction.SetPreviewWidth(it))
                    }
                },
            )
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(10.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .safeDrawingPadding()
                // 与主列表量在同一层（安全区内侧），两个宽度才可比。
                .onSizeChanged { size ->
                    windowWidth = with(density) { size.width.toDp() }
                }
                // 记录指针按下期间按住的修饰键，这样 ⌥-点击会粘贴、
                // ⌘⇧-点击会不带格式粘贴（`HistoryItemView.performSelect`）。
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            when (event.type) {
                                // `MouseMovedViewModifier`：鼠标移动会结束键盘导航，
                                // 于是悬停重新开始选择。
                                PointerEventType.Move -> onUiAction(ClipboardUiAction.PointerMoved)
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
                dockedCard(true, placement == PreviewPlacement.DOCK_LEFT)

                // 预览的槽位先占住：主列表于是始终停在「窗口左边缘 + 预览宽度」上，与窗口
                // 宽度无关。预览停靠左侧时窗口要同时「左移」和「变宽」，界面看到的中间状态
                // 可能只是其中之一——按宽度定位（靠右对齐）的话，主列表会跟着窗口宽度左右
                // 跳一下。卡片进场 / 退场时直接接管或让出这段槽位，位置不变。
                // 空槽位也按这一帧的约束夹住（见 `previewSlot`）：窗口还没左移加宽的那一两帧，
                // 它会被压成 0，主列表因此停在原地、也不会被挤窄。
                if (previewHost.onLeft && slot.slotReserved) {
                    Spacer(Modifier.previewSlot().width(slideoutWidth).fillMaxHeight())
                }

                Column(
                    Modifier
                        // 预览关闭时列表跟随窗口；并排显示时列表占满预览之外的部分；
                        // 槽位空着的那几帧把宽度钉住，避免宽度跳变（见 [PreviewSlotMetrics.slotReserved]）。
                        .then(if (slot.slotReserved) Modifier.width(listWidth) else Modifier.weight(1f))
                        .fillMaxHeight()
                        .onSizeChanged { size ->
                            listWidth = with(density) { size.width.toDp() }
                        },
                ) {
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
                            onQueryChange = { value -> onUiAction(ClipboardUiAction.UpdateQuery(value)) },
                            onCompositionChange = { composing = it },
                            focusRequester = searchFocusRequester,
                            previewOpen = state.previewOpen,
                            previewOnLeft = previewHost.onLeft,
                            previewTooltip = "显示 / 隐藏预览（${settings.togglePreviewShortcut?.label ?: "未设置"}）",
                            onTogglePreview = { onUiAction(ClipboardUiAction.TogglePreview) },
                        )

                        if (settings.ignoreEvents) {
                            PausedBanner(
                                onResume = {
                                    onUiAction(
                                        ClipboardUiAction.UpdateSettings {
                                            it.copy(ignoreEvents = false)
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
                                // 对应 `HistoryListView` 顶部区块的 `readHeight`
                                // （`popup.extraTopHeight`）：固定的置顶项及其分隔线。
                                if (pinsAtTop && pinnedEntries.isNotEmpty()) {
                                    PinnedSection(
                                        entries = pinnedEntries,
                                        separator = pinsSeparator,
                                        separatorFirst = false,
                                        onHeightChange = { topPinsHeight = it },
                                        row = entryRow,
                                    )
                                }

                                Box(Modifier.weight(1f).fillMaxWidth()) {
                                    LazyColumn(
                                        state = listState,
                                        modifier = Modifier.fillMaxSize(),
                                        contentPadding = PaddingValues(
                                            start = Popup.horizontalPadding,
                                            end = Popup.horizontalPadding,
                                            top = Popup.verticalSeparatorPadding,
                                            bottom = metrics.listBottomPadding,
                                        ),
                                    ) {
                                        items(unpinnedEntries, key = { it.value.item.id }) { indexed ->
                                            entryRow(indexed)
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

                                // 对应 `HistoryListView` 底部区块的 `readHeight`
                                // （`popup.extraBottomHeight`）。
                                if (!pinsAtTop && pinnedEntries.isNotEmpty()) {
                                    PinnedSection(
                                        entries = pinnedEntries,
                                        separator = pinsSeparator,
                                        separatorFirst = true,
                                        onHeightChange = { bottomPinsHeight = it },
                                        row = entryRow,
                                    )
                                }
                            }
                        }

                        AnimatedPreviewCard(
                            visible = placement == PreviewPlacement.OVERLAY,
                            onLeft = previewHost.onLeft,
                        ) {
                            Surface(color = colors.background, modifier = Modifier.fillMaxSize()) {
                                PreviewPane(
                                    item = state.selectedItem,
                                    appIconBase64 = previewAppIcon,
                                    onTogglePin = { onUiAction(ClipboardUiAction.TogglePinSelected) },
                                    onDelete = { onUiAction(ClipboardUiAction.DeleteSelected) },
                                    onCopyExtractedText = { onUiAction(ClipboardUiAction.CopyExtractedText) },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }

                    FooterRows(
                        selectedIndex = state.footerSelection,
                        showQuit = state.showQuit,
                        onAction = { action -> onUiAction(ClipboardUiAction.RunFooter(action)) },
                        // `FooterItemView.onHover` 原本会在悬停页脚时收起预览；预览开关现在是持久化的
                        // 用户选择（见 `AppSettings.previewOpen`），只由按钮 / 快捷键切换，这里不再动它。
                        onHover = { index -> onUiAction(ClipboardUiAction.HoverFooter(index)) },
                        // 对应 `FooterView.readHeight(appState, into: \.popup.footerHeight)`。
                        modifier = Modifier.onSizeChanged {
                            footerHeight = with(density) { it.height.toDp() }
                        },
                    )
                }

                dockedCard(false, placement == PreviewPlacement.DOCK_RIGHT)
            }

            state.statusMessage?.let { message ->
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)) {
                    StatusToast(message)
                }
            }
        }
    }

    HistoryDialogs(
        state = state,
        onAction = onUiAction,
        applicationName = applicationName,
        applicationIcon = applicationIcon,
        captureShortcutKey = captureShortcutKey,
    )
}
