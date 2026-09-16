package com.qcmian.clipper.feature.history.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
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
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.SearchResult
import com.qcmian.clipper.core.settings.PinPosition
import com.qcmian.clipper.core.ui.ModifierFlags
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.core.ui.visibleShortcut
import kotlinx.coroutines.flow.collect
import com.qcmian.clipper.feature.history.ui.components.EmptyState
import com.qcmian.clipper.feature.history.ui.components.FooterRows
import com.qcmian.clipper.feature.history.ui.components.HistoryHeader
import com.qcmian.clipper.feature.history.ui.components.HistoryRow
import com.qcmian.clipper.feature.history.ui.components.HistoryScrollbar
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
import com.qcmian.clipper.feature.history.viewmodel.shortcutMap

/** 低于此宽度时，预览面板改为覆盖在列表上，而不是并排显示。 */
private val OverlayThreshold = 700.dp

/** 缩略图在 `imageMaxHeight` 之上额外增加的垂直内边距。 */
private val ImageRowPadding = 10.dp

/** 高度估算的安全余量，见 `preferredHeight` 的注释。 */
private val HeightSlack = 4.dp

/**
 * 列表 `contentPadding` 中落在条目之下的部分：列表底部内边距 + 置顶分隔线的间距。
 *
 * 窗口高度如果只补到「条目总高」，列表视口就比内容少这一条，`canScrollForward` 会一直是
 * true——表现为**内容明明全部放得下，右侧滚动条却始终显示**，还能滚几个像素。
 * 补上它，内容放得下时窗口才真正装得下全部内容。
 */
private val ListPaddingBelowItems = Popup.verticalSeparatorPadding + Popup.verticalSeparatorPadding - 1.dp

/** 面板最小高度对应的内容条数：历史很少时窗口也保持这么高。 */
private const val MinimumVisibleItems = 6

/** 预览卡片横向滑入的时长。 */
private const val PreviewAnimationMillis = 180

/** 宽度比较容差，吸收 dp↔px 取整。 */
private val WidthTolerance = 2.dp

/**
 * 呼出面板后为搜索框争取焦点的最大重试帧数。
 *
 * 面板显示时界面会重新测量并重组，搜索框节点随之重建；一次请求很容易落在节点就绪之前而
 * 静默失败（异常被 `runCatching` 吞掉），表现为「呼出后打不了字」。因此多试几帧——
 * `requestFocus()` 幂等，已聚焦时是空操作。
 */
private const val FOCUS_REQUEST_ATTEMPTS = 12

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
    onPreviewOpenChange: (Boolean) -> Unit,
    onPreferredHeightChange: (Dp) -> Unit,
    applicationIcon: (String?) -> String?,
    applicationName: (String) -> String?,
    availablePins: (ClipItem) -> List<String>,
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

    // `BoxWithConstraints` 会在布局阶段对内容做子组合，因此搜索框的焦点修饰符要等到第一帧
    // 之后才会挂上；没挂上时 `requestFocus()` 会抛 "FocusRequester is not initialized"，
    // 所以统一包一层 runCatching。
    //
    // `focusRequestToken` 由宿主在请求显示面板时自增（`Popup.handleFirstKeyDown`）；首次组合
    // 时它为 0，这次请求顺带覆盖了「面板第一次打开」，因此不需要再单独起一个 `Unit` 副作用。
    //
    // 只请求一次是不够的：面板每次显示都会重新测量、搜索框节点随之重建，请求早一帧就会
    // 静默失败（异常被 `runCatching` 吞掉），表现为「呼出后打不了字」。因此跨若干帧重申焦点：
    // `requestFocus()` 幂等，已聚焦时是空操作。
    //
    // 注意这里只解决「场景内哪个节点接收键盘」；「按键能不能进到场景」是另一件事，由宿主在
    // 窗口显示时把 AWT 焦点交给窗口内容组件负责（见 `ClipperWindow.focusKeyboardTarget`）。
    LaunchedEffect(state.focusRequestToken) {
        repeat(FOCUS_REQUEST_ATTEMPTS) {
            withFrameNanos { }
            runCatching { searchFocusRequester.requestFocus() }
        }
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
    val listBottomPadding = if (!pinsAtTop && pinsSeparator) {
        Popup.verticalSeparatorPadding
    } else {
        Popup.verticalSeparatorPadding - 1.dp
    }

    // 对应 `Popup.suitableHeight(for:)` + `Popup.preferredHeight(for:)`。
    //
    // 条目高度是**精确值**，不是估算：文本行恒为 `Popup.itemHeight`，图片行的槽位恒为
    // `imageMaxHeight`（`HistoryRow` 里的 `.height()`，小图靠 `ContentScale.Inside`
    // 在原尺寸居中留白）。两处都是**固定高度而非下限**——行高是内容高度、窗口高度与
    // 滚动条三者共用的唯一依据，任何让行长高的内容都会让它们一起算少。
    //
    // 刻意不再保留「全部条目进入布局后用实测值覆盖求和」的兜底：它只在所有条目都可见时
    // 才更新，一旦有一帧因为图片行高度不定而没做到，那个实测值就永远停在旧内容上——
    // 窗口从此不再跟随条数变化。高度确定之后，兜底没有存在价值，反而是个单向锁。
    val imageRowHeight = settings.imageMaxHeight.dp + ImageRowPadding
    val rowHeight: (SearchResult) -> Dp = { result ->
        if (result.item.image != null) imageRowHeight else Popup.itemHeight
    }

    // 滚动条所需的逐条高度。与窗口高度共用同一个 [rowHeight]：两个消费者读同一个函数，
    // 「窗口为什么这么高」与「滑块为什么这么长」就不可能对不上。
    val scrollHeights = remember(unpinnedEntries, settings.imageMaxHeight, density) {
        FloatArray(unpinnedEntries.size) { index ->
            with(density) { rowHeight(unpinnedEntries[index].value).toPx() }
        }
    }
    val scrollPadding = with(density) {
        (Popup.verticalSeparatorPadding + listBottomPadding).toPx()
    }

    val itemsHeight = unpinnedEntries.fold(0.dp) { total, entry -> total + rowHeight(entry.value) }
    val listHeight = itemsHeight + Popup.verticalSeparatorPadding + listBottomPadding

    val chromeHeight = headerHeight + topPinsHeight + bottomPinsHeight + footerHeight
    val suitableHeight = listHeight + chromeHeight
    // 面板最小高度：至少放下 [MinimumVisibleItems] 条内容（原来是 3 条，按要求翻倍），
    // 因此历史很少时窗口也不会缩成一条缝。预览不参与这里——预览面板的高度恒等于窗口高度
    // （`fillMaxHeight`），打开或关闭预览都不会改变窗口尺寸，也就不会出现「开预览时窗口
    // 突然长高」的跳动。
    val minimumHeight = (chromeHeight + Popup.itemHeight * MinimumVisibleItems)
        .coerceAtLeast(headerHeight + Popup.verticalPadding)
    // 内容高度已经是精确值，这里只吸收 dp↔px 的取整：AWT 窗口尺寸按整数点应用，而
    // 内容高可能带小数，差一点点就会让列表「差一点装得下」——残留一小段可滚动区间和一截
    // 滚动条。留一点余量，内容本该放得下时窗口总是略高于内容；内容真的超高时余量会被吃掉，
    // 滚动行为不受影响。
    val preferredHeight = (suitableHeight + ListPaddingBelowItems + HeightSlack)
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

    // 「预览能不能与主列表并排显示」。刻意不用 `BoxWithConstraints`：它每次测量都会给
    // `SubcomposeLayout` 传一个新的 content lambda，而后者按引用比较 lambda、判定「内容变了」
    // 就重组整个槽位——拖动窗口尺寸时约束每帧都在变，等于每帧把整棵界面重组一遍。
    // 这里只记录两个数字（窗口宽度、主列表宽度），它们的变化频率远低于每帧。
    var windowWidth by remember { mutableStateOf(0.dp) }
    // 主列表的宽度：预览关闭时它等于窗口宽度，预览并排展开后它等于窗口宽度减去滑出面板。
    // 桌面端把窗口加宽到「它 + 滑出面板」时，就说明窗口已经为预览让出位置了。
    var listWidth by remember { mutableStateOf(0.dp) }

    val slideoutWidth = Popup.slideoutWidth(settings.previewWidth)
    val docked = when {
        !state.previewOpen -> false
        // 固定尺寸窗口（手机）：窗口本身够宽才并排，否则退回覆盖层。
        !previewHost.expandsWindow -> windowWidth >= OverlayThreshold
        // 桌面端：宿主把窗口加宽到「主列表宽度 + 滑出面板」才算到位。
        else -> listWidth > 0.dp &&
            windowWidth >= listWidth + slideoutWidth - WidthTolerance
    }
    val placement = previewPlacement(
        previewOpen = state.previewOpen,
        host = previewHost,
        docked = docked,
    )
    // 预览槽位「已经让出来、但还没被卡片占住」的那几帧：
    //
    // - 打开时：窗口正在加宽，卡片还没进场；
    // - 收起时：卡片已经移除，窗口还没收回来——窗口比主列表宽出来的那一段正是残留的槽位。
    //
    // 这几帧要把主列表的宽度钉在打开前的值，否则它会先占满整窗（文字重排、滚动条跟着跑）
    // 再被窗口收回来——一次收起闪两下。
    //
    // 宽度差只在「恰好一块滑出面板」时才算残留槽位：别的差值只是两者尚未同步，据此钉住
    // 列表会把它永久留在旧宽度上。
    val leftoverSlot = windowWidth - listWidth
    val slotLeftOver = leftoverSlot > WidthTolerance &&
        leftoverSlot <= slideoutWidth + WidthTolerance
    // 覆盖层占位不走这条路：那种情况窗口尺寸不变，列表照旧跟随窗口。
    val slotReserved = previewHost.expandsWindow && !previewHost.overlays &&
        listWidth > 0.dp && !docked && (state.previewOpen || slotLeftOver)

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
                AnimatedPreviewCard(
                    visible = placement == PreviewPlacement.DOCK_LEFT,
                    onLeft = true,
                ) {
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

                // 预览的槽位先占住：主列表于是始终停在「窗口左边缘 + 预览宽度」上，与窗口
                // 宽度无关。预览停靠左侧时窗口要同时「左移」和「变宽」，界面看到的中间状态
                // 可能只是其中之一——按宽度定位（靠右对齐）的话，主列表会跟着窗口宽度左右
                // 跳一下。卡片进场 / 退场时直接接管或让出这段槽位，位置不变。
                if (previewHost.onLeft && slotReserved) {
                    Spacer(Modifier.width(slideoutWidth).fillMaxHeight())
                }

                Column(
                    Modifier
                        // 预览关闭时列表跟随窗口；并排显示时列表占满预览之外的部分；
                        // 槽位空着的那几帧把宽度钉住，避免宽度跳变（见 [slotReserved]）。
                        .then(if (slotReserved) Modifier.width(listWidth) else Modifier.weight(1f))
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
                            onQueryChange = { value -> onAction(ClipboardUiAction.UpdateQuery(value)) },
                            onCompositionChange = { composing = it },
                            focusRequester = searchFocusRequester,
                            previewOpen = state.previewOpen,
                            previewOnLeft = previewHost.onLeft,
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

                        AnimatedPreviewCard(
                            visible = placement == PreviewPlacement.OVERLAY,
                            onLeft = previewHost.onLeft,
                        ) {
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

                AnimatedPreviewCard(
                    visible = placement == PreviewPlacement.DOCK_RIGHT,
                    onLeft = false,
                ) {
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
 * 预览卡片的进场动画：从**主面板那一侧**横向推出来。
 *
 * 卡片停靠右侧时，它的槽位紧贴主面板右边缘，于是让卡片从槽位左侧（也就是主面板右边缘）
 * 向右滑到位——观感就是「卡片从主面板右侧滑出来，左→右」；停靠左侧时方向相反，自槽位右侧
 * 向左滑到位，即「右→左」。
 *
 * 槽位加了 [clipToBounds]：滑出过程中卡片超出槽位的部分（也就是压在主列表上的部分）会被裁掉，
 * 卡片才像是从主面板边缘推出来，而不是从窗口外侧飞进来。
 *
 * 卡片内部的内容不做淡入：淡入会让文字一点点浮现，看起来像「整块在闪」。
 *
 * 关闭不做退场动画是有意为之：桌面宿主收到「预览已关闭」后会把窗口收窄，卡片若还占着布局，
 * 就会和收窄中的窗口抢同一段宽度。现在卡片立即让出槽位，槽位由 [slotReserved] 先占着，
 * 等窗口收回来再一起消失——主列表在整段过程中宽度不变。
 *
 * 只做水平位移：卡片高度由 `fillMaxHeight` 跟随窗口，宽度由布局决定，主列表的宽度也由窗口
 * 宽度减去槽位宽度得到，因此整段动画期间主面板的位置与大小都不会变化。
 *
 * 三处卡片由同一个 [PreviewPlacement] 驱动，同一时刻只有一处可见，因此一次打开只会跑一次
 * 进场动画。
 */
@Composable
private fun AnimatedPreviewCard(
    visible: Boolean,
    onLeft: Boolean,
    content: @Composable AnimatedVisibilityScope.() -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.clipToBounds(),
        enter = slideInHorizontally(
            animationSpec = tween(durationMillis = PreviewAnimationMillis),
            initialOffsetX = { width -> if (onLeft) width else -width },
        ),
        exit = ExitTransition.None,
        content = content,
    )
}
