package com.qcmian.clipper.feature.history.ui

import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isAltPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.qcmian.clipper.core.domain.model.SearchResult
import com.qcmian.clipper.core.settings.PinPosition
import com.qcmian.clipper.core.ui.ModifierFlags
import com.qcmian.clipper.core.ui.Popup
import kotlinx.coroutines.flow.collect
import kotlin.math.roundToInt
import com.qcmian.clipper.feature.history.ui.components.DeepSearchFooter
import com.qcmian.clipper.feature.history.ui.components.DeepSearchFooterHeight
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
     * 最近一次指针按下期间按住的修饰键；点击的分流靠它：`⌘` 多选、`⇧` 连续选中、`⌥` 粘贴。
     *
     * 刻意不用 Compose 状态：这些值只在点击闭包里读一次，若用 `mutableStateOf`，
     * 「按下鼠标」就会重组整棵界面。
     */
    val pointerModifiers = remember { PointerModifiers() }

    /** 右键菜单的弹出点（视口坐标）；`null` 表示没开着。纯界面状态，因此不进 `ClipboardUiState`。 */
    var contextMenuAt by remember { mutableStateOf<Offset?>(null) }

    // `focusRequestToken` 由宿主在请求显示面板时自增（`Popup.handleFirstKeyDown`）；首次组合时它
    // 为 0，这次请求顺带覆盖了「面板第一次打开」，因此不需要再单独起一个 `Unit` 副作用。
    // 重申焦点的理由见 [awaitSearchFocus]。
    LaunchedEffect(state.focusRequestToken) {
        // 面板重新打开：上一次留下的右键菜单不该飘在新一轮内容上。
        contextMenuAt = null
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
            is ClipboardUiAction.RequestImage,
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
    // 只要置顶区有内容就画分隔线。
    //
    // 从前还要求内容区非空，但内容区为空时那里并不是「什么都没有」——它是一个空状态区块
    // （「没有匹配的项目」）。两块内容之间没有分界，看起来像置顶区把下面的区域吞掉了。
    val pinsSeparator = pinnedEntries.isNotEmpty()

    // 条目高度是**精确值**，不是估算：文本行恒为 `Popup.itemHeight`，图片行恒为
    // `imageMaxHeight` 加 `ImageRowPadding`。两个高度都由 `historyRowHeight` 一处给出，
    // 列表渲染（`HistoryRow` 传给 `ListItemRow` 的 `height`）读的也是它——行高是内容高度、
    // 窗口高度与滚动条三者共用的唯一依据，因此不可能各自推算出一套数来。
    // 这些都是**固定高度而非下限**，任何让行长高的内容都会让它们一起算少。
    val rowHeight: (SearchResult) -> Dp = { result ->
        historyRowHeight(result.meta, settings.imageMaxHeight.dp)
    }

    // 全文搜索的入口只在有查询词时才出现：没有查询就没有要找的东西。
    val deepSearchVisible = state.appliedQuery.isNotEmpty()
    val deepSearchHeight = if (deepSearchVisible) DeepSearchFooterHeight else 0.dp

    // 滚动条所需的逐条高度。与窗口高度共用同一个 [rowHeight]：两个消费者读同一个函数，
    // 「窗口为什么这么高」与「滑块为什么这么长」就不可能对不上。末尾那一格是全文搜索入口，
    // 它也是列表里的一行（空状态时它在列表之外，但高度照算，两处口径保持一致）。
    val scrollHeights = remember(unpinnedEntries, settings.imageMaxHeight, density, deepSearchHeight) {
        val heights = FloatArray(unpinnedEntries.size + if (deepSearchVisible) 1 else 0)
        for (index in unpinnedEntries.indices) {
            heights[index] = with(density) { rowHeight(unpinnedEntries[index].value).toPx() }
        }
        if (deepSearchVisible) {
            heights[unpinnedEntries.size] = with(density) { deepSearchHeight.toPx() }
        }
        heights
    }

    // 窗口高度是内容的纯函数，推导见 [historyHeightMetrics]。
    val metrics = historyHeightMetrics(
        itemsHeight = unpinnedEntries.fold(0.dp) { total, entry -> total + rowHeight(entry.value) } +
            deepSearchHeight,
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

    // 查询一变，结果列表就整份换掉了，滚动位置必须回到顶部：留在原处会让人看到「旧位置
    // 附近的一批新结果」，与高亮落点（内容区第一条）也对不上。
    //
    // 用 `scrollToItem` 而不是 `animateScrollToItem`：连续输入时每次都要回到顶部，动画
    // 会被下一次输入打断，看起来是在抖。
    LaunchedEffect(state.appliedQuery) {
        listState.scrollToItem(0)
    }

    // `NavigationManager.scroll(to:)` 只会滚动未置顶列表；置顶区块始终可见。
    // 目标行已经完整可见时不再滚动——这条只服务于键盘导航：连续按方向键时，行已经在视野里
    // 就不该再动，只有走出可视区才跟随。
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

    val keyHandler: (KeyEvent) -> Boolean = handler@{ event ->
        flags.update(event)
        if (event.type == KeyEventType.Unknown) {
            val swallow = swallowTypedCharacter
            swallowTypedCharacter = false
            swallow
        } else {
            // 菜单开着时任意按键先收掉它；`Esc` 到此为止，不顺带清多选、也不关面板。
            if (contextMenuAt != null) {
                contextMenuAt = null
                if (event.key == Key.Escape) return@handler true
            }
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
        val meta = indexed.value.meta
        val image = state.images[meta.id]

        // 进入组合就通知一次：命中缓存时那一下是把该条挪到 LRU 最新端，未命中才去读库。
        // 判据必须是 `hasImage`（而不是「还没有图片」），否则行滚回时缓存收不到「又被用到」，
        // 淘汰就退化成先进先出。
        if (meta.hasImage) {
            LaunchedEffect(meta.id) { onUiAction(ClipboardUiAction.RequestImage(meta.id)) }
        }

        HistoryRow(
            meta = meta,
            image = image,
            ranges = indexed.value.ranges,
            shortcuts = shortcuts[meta.id].orEmpty(),
            flags = flags,
            isSelected = state.isRowSelected(indexed.index),
            isCursor = state.isHistoryHighlighted && indexed.index == state.historySelection,
            highlight = settings.highlightMatch,
            showColorSwatch = settings.showHexColorSwatch,
            showSpecialSymbols = settings.showSpecialSymbols,
            maxImageHeight = settings.imageMaxHeight.dp,
            appIconBase64 = if (settings.showApplicationIcons) {
                rememberApplicationIcon(applicationIcon, meta.application?.bundleId)
            } else {
                null
            },
            onClick = {
                when {
                    // ⌘：切换这一条的多选状态（点完不激活——用户是在攒要一起粘贴的那一批）。
                    pointerModifiers.command -> onUiAction(ClipboardUiAction.ToggleSelection(indexed.index))
                    // ⇧：从锚点连续选中到这一条。
                    pointerModifiers.shift -> onUiAction(ClipboardUiAction.SelectRange(indexed.index))
                    else -> {
                        // 先折叠为单选再激活：`Activate` 作用在**选中集**上，不先落单选的话，
                        // 这一次点击会把上一批选中项一起激活。
                        onUiAction(ClipboardUiAction.SelectOnly(indexed.index))
                        onUiAction(
                            ClipboardUiAction.Activate(
                                alt = pointerModifiers.alt,
                                meta = pointerModifiers.control,
                            ),
                        )
                    }
                }
            },
            onHover = {
                onUiAction(
                    ClipboardUiAction.HoverHistory(
                        index = indexed.index,
                        // 按住选中修饰键时划过的行不改写选中集，否则那一次点击会成为空操作。
                        selectionModifierHeld = flags.command || flags.shift,
                    ),
                )
            },
        )
    }

    // 每个选中项只解析一次，且不在组合线程上。
    val previewAppIcon = rememberApplicationIcon(
        load = applicationIcon,
        bundleId = state.selectedMeta?.application?.bundleId,
    )

    // 实测的窗口宽度。两个用途：分隔条的拖动上限（见 `maxDragWidth`）与用户拖窗口边缘期间的
    // 主列表宽度（见 `mainWidth`）；其余时候主列表用设置算出的定值、预览显示读揭示进度，都不读它。
    //
    // 刻意不用 `BoxWithConstraints`：它每次测量都会给 `SubcomposeLayout` 传一个新的 content
    // lambda，而后者按引用比较 lambda、判定「内容变了」就重组整个槽位——拖动窗口尺寸时约束每帧
    // 都在变，等于每帧把整棵界面重组一遍。这里只记录一个数字，它的变化频率远低于每帧。
    var windowWidth by remember { mutableStateOf(0.dp) }

    /** 实测的窗口高度：右键菜单靠它把自己夹在窗口内（`Popup` 不会自己躲开窗口边缘）。 */
    var windowHeight by remember { mutableStateOf(0.dp) }

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
                windowHeight = with(density) { size.height.toDp() }
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
                                pointerModifiers.command = event.keyboardModifiers.isMetaPressed
                                pointerModifiers.control = event.keyboardModifiers.isCtrlPressed

                                if (event.buttons.isSecondaryPressed) {
                                    // 菜单作用于**当前选中集**，不改变选择（这里也做不了行的命中测试）。
                                    contextMenuAt = event.changes.last().position
                                    // 就地消费：`Initial` 阶段跑在最外层，消费之后行上的 `clickable`
                                    // 看不到这次按下，不会顺手激活这一条。
                                    event.changes.forEach { it.consume() }
                                } else if (contextMenuAt != null) {
                                    // 菜单开着时的第一下左键只关菜单，不激活指针底下那一行。
                                    contextMenuAt = null
                                    event.changes.forEach { it.consume() }
                                }
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
                // 预览要的是**完整条目**（含正文 / 图片），由状态持有者按选中 id 异步补齐；
                // 列表里流动的元数据里没有这些。
                item = state.previewItem,
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

                    // 筛选栏紧贴内容区上方（也就是置顶区之下）：它只作用于内容区。
                    //
                    // 位置之所以稳定，不是因为把它挪到了别处，而是因为**置顶区不参与筛选与搜索**
                    // （见 `ClipboardViewModel.matchResults`）——置顶项数量恒定，上面那块的高度
                    // 就不会变，它也就不会跟着跳。
                    //
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
                        // 判据是**内容区**是否为空，而不是整个 `results`：置顶区不参与筛选与
                        // 搜索，它会一直有内容，按 `results` 判断就永远显示不出空状态。
                        if (unpinnedEntries.isEmpty()) {
                            EmptyState(searching = state.query.isNotEmpty())
                            // 空状态时入口照样出现，而且最该出现：标题一条都没命中，正是
                            // 「去正文里找找」最可能有用的时刻。它浮在空状态之上，不改变那里
                            // 原有的居中布局。
                            if (deepSearchVisible) {
                                DeepSearchFooter(
                                    state = state.deepSearch,
                                    hits = state.deepSearchHits,
                                    onRun = { onUiAction(ClipboardUiAction.RunDeepSearch) },
                                    modifier = Modifier.align(Alignment.BottomCenter),
                                )
                            }
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
                                items(unpinnedEntries, key = { it.value.meta.id }) { indexed ->
                                    entryRow(indexed)
                                }
                                // 入口固定在内容末尾：用户滚到这儿本身就是「这些还不够」的
                                // 表达，因此它不需要额外的提示，也不该悬在界面上方随时可见。
                                if (deepSearchVisible) {
                                    item {
                                        DeepSearchFooter(
                                            state = state.deepSearch,
                                            hits = state.deepSearchHits,
                                            onRun = { onUiAction(ClipboardUiAction.RunDeepSearch) },
                                        )
                                    }
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

        // 选中集的右键菜单。弹出点只在打开的那一帧定一次，之后不跟鼠标走。
        contextMenuAt?.let { at ->
            val pasteModifier = !settings.pasteByDefault
            SelectionContextMenu(
                at = at,
                selectionCount = state.selectionCount,
                // `↵` 与 `⌥↵` **互为镜像**：自动粘贴关着时前者复制、后者粘贴，开着时正好反过来
                // （`defaultAction` 里 `⌥` 那一支在开启时落到 `COPY`）。
                copyHint = if (settings.pasteByDefault) "⌥↵" else "↵",
                pasteHint = if (settings.pasteByDefault) "↵" else "⌥↵",
                allPinned = state.isSelectionAllPinned,
                // 置顶 / 删除绑的是可录制快捷键，用户清掉绑定后就没有提示可写（菜单项仍可点）。
                pinHint = settings.pinShortcut?.label,
                deleteHint = settings.deleteShortcut?.label,
                // 明确要复制，不走修饰键解析：自动粘贴开着时键盘上没有任何组合能触发复制。
                onCopy = {
                    contextMenuAt = null
                    onUiAction(ClipboardUiAction.CopySelection)
                },
                onPaste = {
                    contextMenuAt = null
                    onUiAction(ClipboardUiAction.Activate(alt = pasteModifier))
                },
                onTogglePin = {
                    contextMenuAt = null
                    onUiAction(ClipboardUiAction.TogglePinSelected)
                },
                onDelete = {
                    contextMenuAt = null
                    onUiAction(ClipboardUiAction.DeleteSelected)
                },
                onClearSelection = {
                    contextMenuAt = null
                    onUiAction(ClipboardUiAction.ClearSelection)
                },
                onDismiss = { contextMenuAt = null },
                windowWidth = windowWidth,
                windowHeight = windowHeight,
            )
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
    var command = false
    var control = false
}

/**
 * 选中集的右键菜单。
 *
 * 用 `Popup(TopStart + offset)` 而不是 `DropdownMenu`：后者的偏移是「从锚点往下展开」的语义，
 * 要落在指针处得反过来推算；`Popup` 就是「把左上角摆在这里」，且同样自带点外面收掉。
 *
 * 配色只取主题里**确实定义过**的 token：`ClipperTheme` 只填了 colorScheme 的一部分，
 * `surfaceContainer*` 那些没被指定的会退回 M3 基线色（带紫调），和这套中性灰不是一家人。
 *
 * 尺寸由常量算出来，所以打开前就能把它夹进窗口——`Popup` 不会自己躲开窗口边缘。
 */
@Composable
private fun SelectionContextMenu(
    at: Offset,
    selectionCount: Int,
    copyHint: String,
    pasteHint: String,
    allPinned: Boolean,
    pinHint: String?,
    deleteHint: String?,
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onClearSelection: () -> Unit,
    onDismiss: () -> Unit,
    windowWidth: Dp,
    windowHeight: Dp,
) {
    val colors = MaterialTheme.colorScheme
    val multi = selectionCount > 1
    // 「复制 / 粘贴 / 置顶 / 删除」四项，多选时再多一行「取消多选」；分隔线数量跟着走。
    val itemCount = if (multi) 5 else 4
    val separatorCount = if (multi) 2 else 1
    val estimatedHeight = ContextMenuItemHeight * itemCount +
        ContextMenuPadding * 2 +
        ContextMenuSeparatorHeight * separatorCount
    val suffix = if (multi) " $selectionCount 条" else ""
    val offset = with(LocalDensity.current) {
        val margin = ContextMenuMargin.toPx()
        val maxX = (windowWidth.toPx() - ContextMenuWidth.toPx() - margin).coerceAtLeast(margin)
        val maxY = (windowHeight.toPx() - estimatedHeight.toPx() - margin).coerceAtLeast(margin)
        IntOffset(
            x = at.x.coerceIn(margin, maxX).roundToInt(),
            y = at.y.coerceIn(margin, maxY).roundToInt(),
        )
    }

    Popup(
        alignment = Alignment.TopStart,
        offset = offset,
        onDismissRequest = onDismiss,
        properties = PopupProperties(),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = colors.surfaceVariant,
            contentColor = colors.onSurface,
            border = BorderStroke(1.dp, colors.outline.copy(alpha = 0.5f)),
            shadowElevation = 12.dp,
            modifier = Modifier.width(ContextMenuWidth),
        ) {
            Column(Modifier.padding(vertical = ContextMenuPadding)) {
                ContextMenuItem(
                    title = "复制$suffix",
                    shortcut = copyHint,
                    onClick = onCopy,
                )
                ContextMenuItem(
                    title = if (multi) "逐条粘贴$suffix" else "粘贴",
                    shortcut = pasteHint,
                    onClick = onPaste,
                )
                ContextMenuDivider()
                // 文案跟着 `allPinned` 走，点下去发生的一定就是它写的那件事（见
                // `ClipboardViewModel.togglePinSelected`）。
                ContextMenuItem(
                    title = (if (allPinned) "取消置顶" else "置顶") + suffix,
                    shortcut = pinHint,
                    onClick = onTogglePin,
                )
                ContextMenuItem(
                    title = "删除$suffix",
                    shortcut = deleteHint,
                    onClick = onDelete,
                )
                if (multi) {
                    ContextMenuDivider()
                    ContextMenuItem(
                        title = "取消多选",
                        shortcut = "Esc",
                        onClick = onClearSelection,
                    )
                }
            }
        }
    }
}

/** 菜单里的分组线。 */
@Composable
private fun ContextMenuDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** 菜单里的一项：动作在左、快捷键提示在右。整行可点。[shortcut] 为 `null` 时只显示动作。 */
@Composable
private fun ContextMenuItem(title: String, shortcut: String?, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(ContextMenuItemHeight)
            .padding(horizontal = ContextMenuPadding)
            .clip(RoundedCornerShape(5.dp))
            .background(if (hovered) colors.primary.copy(alpha = 0.16f) else Color.Transparent)
            .hoverable(interaction)
            // 自带的水波纹 / 底色变化与这一行自绘的悬停态叠起来会很脏，因此不带 indication。
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp),
    ) {
        Text(
            text = title,
            fontSize = 12.sp,
            color = colors.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        if (shortcut != null) {
            Text(
                text = shortcut,
                fontSize = 11.sp,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** 菜单的固定尺寸与行高：与 [SelectionContextMenu] 里那份高度估算必须是同一套数。 */
private val ContextMenuWidth = 190.dp
private val ContextMenuItemHeight = 28.dp
private val ContextMenuPadding = 4.dp
private val ContextMenuMargin = 6.dp

/** 一条分组线占的高度：线本身 1dp，加上两侧各 3dp 的间距。 */
private val ContextMenuSeparatorHeight = 7.dp

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
