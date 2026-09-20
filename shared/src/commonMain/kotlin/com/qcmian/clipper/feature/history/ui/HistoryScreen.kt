package com.qcmian.clipper.feature.history.ui

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.flow.collect
import com.qcmian.clipper.feature.history.ui.components.EmptyState
import com.qcmian.clipper.feature.history.ui.components.FooterRows
import com.qcmian.clipper.feature.history.ui.components.HistoryHeader
import com.qcmian.clipper.feature.history.ui.components.HistoryFilterBar
import com.qcmian.clipper.feature.history.ui.components.HistoryRow
import com.qcmian.clipper.feature.history.ui.components.HistoryScrollbar
import com.qcmian.clipper.feature.history.ui.components.PausedBanner
import com.qcmian.clipper.feature.history.ui.components.PinnedSection
import com.qcmian.clipper.feature.history.ui.components.PreviewSlideout
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
 * @param previewHost 宿主为预览面板提供的空间约束（停靠侧与屏幕余量）。预览该怎么展示只由
 *   界面自己的状态推出，见 [PreviewHostPolicy]。
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
     *
     * 刻意**不**用 Compose 状态：这些值只在点击闭包里、点击那一刻读取，不参与任何渲染，
     * 若用 `mutableStateOf`，「按下鼠标」就会重组整棵界面（视觉零变化）。普通可变容器
     * 写入不触发重组，点击时读到的仍是最新值。
     */
    val pointerModifiers = remember { PointerModifiers() }

    // `focusRequestToken` 由宿主在请求显示面板时自增（`Popup.handleFirstKeyDown`）；首次组合时它
    // 为 0，这次请求顺带覆盖了「面板第一次打开」，因此不需要再单独起一个 `Unit` 副作用。
    // 重申焦点的理由见 [awaitSearchFocus]。
    LaunchedEffect(state.focusRequestToken) {
        awaitSearchFocus(searchFocusRequester)
    }

    // 搜索框是面板的常驻输入点，光标
    // 应当一直可见、随时可输入。但点击面板内的任何按钮（预览开关、行内操作、页脚……）都会
    // 把焦点从搜索框抢走，光标消失，用户得再点一次输入框。因此每次「有实质的」交互之后把
    // 焦点请回来；设置 / 清除确认弹窗打开期间除外——那时焦点属于弹窗，关闭后自动恢复。
    var refocusToken by remember { mutableStateOf(0) }
    // 预览开关直接翻转设置，没有「画面里此刻有没有预览」这一层判定：界面显示什么只由
    // [ClipboardUiState.previewOpen] 决定，一次点击因此必定对应一次开 / 关，不存在被吃掉的点击。
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
    var filterHeight by remember { mutableStateOf(0.dp) }
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
        filterHeight = filterHeight,
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
            shortcuts = shortcuts[item.id].orEmpty(),
            flags = flags,
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
                        shift = pointerModifiers.shift,
                        alt = pointerModifiers.alt,
                        meta = pointerModifiers.meta,
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

    // 实测的窗口宽度。两个用途：分隔条的拖动上限（见 `maxDragWidth`）与用户拖窗口边缘期间的
    // 主列表宽度（见 `mainWidth`）；其余时候主列表用设置算出的定值、预览显示读揭示进度，都不读它。
    //
    // 刻意不用 `BoxWithConstraints`：它每次测量都会给 `SubcomposeLayout` 传一个新的 content
    // lambda，而后者按引用比较 lambda、判定「内容变了」就重组整个槽位——拖动窗口尺寸时约束每帧
    // 都在变，等于每帧把整棵界面重组一遍。这里只记录一个数字，它的变化频率远低于每帧。
    var windowWidth by remember { mutableStateOf(0.dp) }

    // 拖动分隔条期间的预览宽度：只作用于界面渲染，**不**写设置——设置一变，宿主就会按
    // 「内容区 + 预览」重算窗口宽度，于是「拖分隔条」变成了「拖整个窗口」。松手时才把新宽度
    // 一次性写回（见 `ClipboardUiAction.SetPreviewWidth`），窗口全程不动。
    var draggedPreviewWidth by remember { mutableStateOf<Int?>(null) }
    // 设置一变、或预览一开一关就丢掉本地值：它是「这次拖动的临时态」，设置跟上了说明拖动已经
    // 落盘（预览关掉则说明这次拖动被中断，落盘的那一条永远不会来了）。反过来（本地值一直压着
    // 设置）会让别处改动的预览宽度在界面上看不见。
    LaunchedEffect(settings.previewWidth, state.previewOpen) { draggedPreviewWidth = null }
    val previewWidth = draggedPreviewWidth ?: settings.previewWidth

    // 内容区（主列表）宽度，与滑出宽度（预览面板 + 分隔条）。
    //
    // 滑出宽度取**当前**预览宽度而不是设置里的值：拖动分隔条时它已经变、设置还没写，卡片要照着
    // 拖到的那一段重新占位（见下面 `mainWidth` 的配对公式），窗口才不会跟着动。
    val contentWidth = Popup.contentWidthOf(settings.customWindowWidth)
    val slideoutWidth = Popup.slideoutWidth(previewWidth)
    // 分隔条能拖到的上限，见 [previewMaxDragWidth]。
    val maxDragWidth = previewMaxDragWidth(host = previewHost, windowWidth = windowWidth)

    // 预览开关：唯一真值就是设置。窗口本身**一次到位**——打开时宿主立刻把窗口撑到并排宽度，
    // 收起时等揭示动画走完再缩回去（见 `WindowGeometryController`）。两次 snap 都看不出来：
    // 窗口是透明的，多出来 / 还没收回去的那一段没有内容，透过它看到的就是桌面。「让出来
    // 多少」因此不是窗口宽度的时间线，而是下面这个由界面自己驱动的揭示进度：卡片与列表同在
    // 一棵组合树里，平移与重排同帧发生，不存在「窗口先动、内容后到」的错位——那正是先前
    // 逐帧改窗口 bounds 的卡顿来源（停靠左侧时窗口原点每帧左移，已画好的内容每帧先右偏再被
    // 纠正，整块抖）。收起时卡片滑回列表后面、让出的区域变回透明，看起来就是窗口边缘在收拢。
    val previewOpen = state.previewOpen

    // 主列表（内容区）宽度。两个来源，切换点是「用户是不是正在拖窗口边缘」：
    //
    // - **在拖**：设置里的尺寸要等 250ms 静默期才落盘，跟不上手——跟随实测窗口宽度（预览开着时
    //   再减掉卡片那一段，卡片自己贴着窗口边缘），拖到哪就跟到哪；
    // - **没在拖**：用设置算出来的定值。刻意**不**用实测宽度——预览开关那一拍窗口一次变宽，
    //   界面要下一拍才量得到新宽度，照它算主列表会跟着窗口一起变宽变窄，文字重排。预览开关
    //   不该动主列表：窗口宽出来的那一段**全部**给预览卡（揭示期间那一段是透明的）。
    //
    // 窗口被用户拖得比内容区还窄时不必特殊处理：固定宽度会被 `Modifier.width` 再夹进父约束
    // （也就是窗口宽度）里，列表自然跟随窗口。
    val mainWidth = if (previewHost.userResizing) {
        (windowWidth - if (previewOpen) slideoutWidth else 0.dp).coerceAtLeast(0.dp)
    } else {
        contentWidth + (settings.previewWidth - previewWidth).dp
    }

    Box(
        modifier
            .fillMaxSize()
            // 圆角裁剪必须排在所有绘制之前：内容区与预览卡的底色都会被它裁进圆角。
            .clip(RoundedCornerShape(10.dp))
            .safeDrawingPadding()
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
                                pointerModifiers.shift = event.keyboardModifiers.isShiftPressed
                                pointerModifiers.alt = event.keyboardModifiers.isAltPressed
                                pointerModifiers.meta = event.keyboardModifiers.isMetaPressed ||
                                    event.keyboardModifiers.isCtrlPressed
                            }

                            else -> Unit
                        }
                    }
                }
            }
            .onPreviewKeyEvent(keyHandler),
    ) {


        // 预览卡：**必须声明在主列表之前**，z 序才在它下层。揭示动画期间它整体平移到主列表
        // 后面——列表不透明又压在它上面，藏进去的部分自然看不见；一旦声明在列表之后（更
        // 不用说挪出根 `Box`，那样连 `align` 都解析不了），动画那几帧卡片就会当场盖住列表的
        // 半边，看起来就是一帧的闪。
        //
        // 揭示动画只动 graphicsLayer 的平移，不触发任何重新测量：卡片内容（文字折行、图片缩放）
        // 全程按最终宽度排版，只是被列表盖住、逐帧露出来。收起时反向平移，卡片外沿一路退回
        // 列表底下、身后让出的区域变回透明——视觉上就是窗口边缘在收拢，但窗口一个像素都没动。
        //
        // 宽度必须用 `requiredWidth`：窗口还窄着时（刚显示）它大于父约束，`width` 会被夹成
        // 「窗口此刻多宽」，面板内容当场按那个宽度重排；卡片要的是「被窗口裁掉一部分」，而不是
        // 「被压扁」。超出窗口的那部分由根 `Box` 的圆角裁剪挡在外面，指针也到不了。
        PreviewSlideoutHost(
            previewOpen = previewOpen,
            slideoutWidth = slideoutWidth,
            onLeft = previewHost.onLeft,
        ) {
            PreviewSlideout(
                item = state.selectedItem,
                appIconBase64 = previewAppIcon,
                previewWidth = previewWidth,
                maxDragWidth = maxDragWidth,
                onLeft = previewHost.onLeft,
                onTogglePin = { onUiAction(ClipboardUiAction.TogglePinSelected) },
                onDelete = { onUiAction(ClipboardUiAction.DeleteSelected) },
                onCopyExtractedText = { onUiAction(ClipboardUiAction.CopyExtractedText) },
                // 拖动中只改界面上的宽度（窗口不动），松手才把新宽度写回设置。
                onWidthChange = { value -> draggedPreviewWidth = value },
                onWidthChangeFinished = {
                    draggedPreviewWidth?.let {
                        onUiAction(ClipboardUiAction.SetPreviewWidth(it))
                    }
                },
            )
        }

        // 主窗口盖在卡片之上，因此**必须不透明**，否则卡片会透出来。
        //
        // 宽度是一个可推导的定值（见 `mainWidth`），不读窗口宽度：预览开着时窗口宽出来的那一段
        // 全归卡片，关着时两者本就相等。
        Column(
            Modifier
                // 宽度是设置算出来的一个定值，与窗口宽度、与预览开没开都无关。它同时是唯一的不透
                // 明底色：窗口还没收回去那几帧，它把卡片整块盖住。
                .width(mainWidth)
                .align(
                    if (previewHost.onLeft) Alignment.CenterEnd else Alignment.CenterStart,
                )
                .fillMaxHeight()
                .background(colors.background),
        ) {
            // 暂停横幅折进同一个被测量的区块。
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
                Column(Modifier.fillMaxSize()) {
                    // 固定的置顶项及其分隔线。
                    if (pinsAtTop && pinnedEntries.isNotEmpty()) {
                        PinnedSection(
                            entries = pinnedEntries,
                            separator = pinsSeparator,
                            separatorFirst = false,
                            onHeightChange = { topPinsHeight = it },
                            row = entryRow,
                        )
                    }

                    // 筛选栏放在内容区（未置顶列表）上方，且只作用于内容区：置顶项不受筛选影响。
                    // 开关关闭时整块隐藏；外层 Box 始终存在，让 filterHeight 能跟着归零。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .onSizeChanged { filterHeight = with(density) { it.height.toDp() } },
                    ) {
                        if (settings.showFilterBar) {
                            HistoryFilterBar(
                                filterTypes = settings.filterTypes,
                                sortBy = settings.sortBy,
                                sortOrder = settings.sortOrder,
                                onFilterTypesChange = { value ->
                                    onUiAction(ClipboardUiAction.UpdateSettings { it.copy(filterTypes = value) })
                                },
                                onSortByChange = { value ->
                                    onUiAction(ClipboardUiAction.UpdateSettings { it.copy(sortBy = value) })
                                },
                                onSortOrderChange = { value ->
                                    onUiAction(ClipboardUiAction.UpdateSettings { it.copy(sortOrder = value) })
                                },
                            )
                        }
                    }

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (results.isEmpty()) {
                            EmptyState(searching = state.query.isNotEmpty())
                        } else {
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
                    }

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

            FooterRows(
                selectedIndex = state.footerSelection,
                showQuit = state.showQuit,
                onAction = { action -> onUiAction(ClipboardUiAction.RunFooter(action)) },
                // `FooterItemView.onHover` 原本会在悬停页脚时收起预览；预览开关现在是持久化的
                // 用户选择（见 `AppSettings.previewOpen`），只由按钮 / 快捷键切换，这里不再动它。
                onHover = { index -> onUiAction(ClipboardUiAction.HoverFooter(index)) },
                modifier = Modifier.onSizeChanged {
                    footerHeight = with(density) { it.height.toDp() }
                },
            )
        }

        state.statusMessage?.let { message ->
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp)) {
                StatusToast(message)
            }
        }
    }

    HistoryDialogs(
        state = state,
        onAction = onUiAction,
        captureShortcutKey = captureShortcutKey,
    )
}

/** 指针按下期间按住的修饰键；普通可变容器，写入不触发重组（见 [HistoryScreen] 内注释）。 */
private class PointerModifiers {
    var shift = false
    var alt = false
    var meta = false
}

/**
 * 预览卡的揭示动画容器。
 *
 * 揭示进度 [reveal] 是动画状态，展示 / 收起期间逐帧变化。把它圈在这个小组件里（而不是
 * `HistoryScreen` 顶层），动画期间就只重组这个轻量的 `Box`——列表、度量、`fold` 都不会
 * 跟着每帧重算；[content]（[PreviewSlideout]）的参数在动画期间不变，因此能整体跳过。
 *
 * 只有 graphicsLayer 的平移每帧读取 [reveal]（那是绘制阶段，本就只重绘不重组）。
 */
@Composable
private fun BoxScope.PreviewSlideoutHost(
    previewOpen: Boolean,
    slideoutWidth: Dp,
    onLeft: Boolean,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val slideoutPx = with(LocalDensity.current) { slideoutWidth.toPx() }
    val reveal by animateFloatAsState(
        targetValue = if (previewOpen) 1f else 0f,
        animationSpec = tween(Popup.previewRevealMillis, easing = EaseInOutCubic),
        label = "previewReveal",
    )
    // 刚关上时卡片要留到动画走完（宿主同样等动画走完才缩窗口），归零后自然为假、整块消失。
    if (!previewOpen && reveal <= 0f) return
    Box(
        Modifier
            .requiredWidth(slideoutWidth)
            .align(if (onLeft) Alignment.CenterStart else Alignment.CenterEnd)
            .fillMaxHeight()
            // 往主列表那一侧平移「还没揭示的宽度」：reveal = 0 时整块卡在列表下面，= 1 时归位。
            .graphicsLayer {
                val hidden = (1f - reveal) * slideoutPx
                translationX = if (onLeft) hidden else -hidden
            }
            .background(colors.background),
    ) {
        content()
    }
}
