package com.qcmian.clipper.feature.history.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.drawscope.clipRect
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
import com.qcmian.clipper.feature.history.ui.components.previewSlot
import com.qcmian.clipper.feature.history.ui.components.StatusToast
import com.qcmian.clipper.feature.history.ui.components.footerEntries
import com.qcmian.clipper.feature.history.ui.components.historyRowHeight
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

/** 预览卡片逐帧揭示的时长。 */
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
    /** 内容希望得到的高度：贴合内容，随条目变化。 */
    onPreferredHeightChange: (Dp) -> Unit,
    /** 窗口的下限高度：滑动区的下限加上置顶区与头部 / 页脚，手动拖拽时宿主不会低于它。 */
    onMinimumHeightChange: (Dp) -> Unit,
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
    // 条目高度是**精确值**，不是估算：文本行恒为 `Popup.itemHeight`，图片行恒为
    // `imageMaxHeight` 加 `ImageRowPadding`。两个高度都由 `historyRowHeight` 一处给出，
    // 列表渲染（`HistoryRow` 传给 `ListItemRow` 的 `height`）读的也是它——行高是内容高度、
    // 窗口高度与滚动条三者共用的唯一依据，因此不可能各自推算出一套数来。
    // 这些都是**固定高度而非下限**，任何让行长高的内容都会让它们一起算少。
    //
    // 刻意不再保留「全部条目进入布局后用实测值覆盖求和」的兜底：它只在所有条目都可见时
    // 才更新，一旦有一帧因为图片行高度不定而没做到，那个实测值就永远停在旧内容上——
    // 窗口从此不再跟随条数变化。高度确定之后，兜底没有存在价值，反而是个单向锁。
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
    val scrollPadding = with(density) {
        (Popup.verticalSeparatorPadding + listBottomPadding).toPx()
    }

    val itemsHeight = unpinnedEntries.fold(0.dp) { total, entry -> total + rowHeight(entry.value) }
    val listHeight = itemsHeight + Popup.verticalSeparatorPadding + listBottomPadding

    val chromeHeight = headerHeight + topPinsHeight + bottomPinsHeight + footerHeight
    val suitableHeight = listHeight + chromeHeight
    // 窗口最小高度：滑动区（内容区）至少 [Popup.minimumContentHeight]——也就是剪贴板为空时的
    // 默认值，因此历史很少时窗口也不会缩成一条缝。置顶区与头部 / 页脚都在滑动区之外，先由
    // `chromeHeight` 计入，所以置顶项再多也只是把窗口顶高，不会吃掉滑动区的高度。
    //
    // 预览不参与这里——预览面板的高度恒等于窗口高度（`fillMaxHeight`），打开或关闭预览都不会
    // 改变窗口尺寸，也就不会出现「开预览时窗口突然长高」的跳动。
    val minimumHeight = (chromeHeight + Popup.minimumContentHeight)
        .coerceAtLeast(headerHeight + Popup.verticalPadding)
    // 内容高度已经是精确值，这里只吸收 dp↔px 的取整：AWT 窗口尺寸按整数点应用，而
    // 内容高可能带小数，差一点点就会让列表「差一点装得下」——残留一小段可滚动区间和一截
    // 滚动条。留一点余量，内容本该放得下时窗口总是略高于内容；内容真的超高时余量会被吃掉，
    // 滚动行为不受影响。
    val preferredHeight = (suitableHeight + ListPaddingBelowItems + HeightSlack)
        .coerceAtLeast(minimumHeight)

    LaunchedEffect(preferredHeight) { onPreferredHeightChange(preferredHeight) }
    // 窗口的下限与「希望多高」是两件事：前者是滑动区的下限（外加置顶区与头部 / 页脚），
    // 后者随内容条数变化。宿主手动拖拽时用的下限就是这里报上去的值。
    LaunchedEffect(minimumHeight) { onMinimumHeightChange(minimumHeight) }

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

    // 拖动分隔条期间的预览宽度：只作用于界面渲染，**不**写设置——设置一变，宿主就会按
    // 「主列表 + 预览」重算窗口宽度，于是「拖分隔条」变成了「拖整个窗口」。松手时才把新宽度与
    // 新的主列表宽度一次性写回（见 `ClipboardUiAction.SetPreviewWidth`），窗口全程不动。
    var draggedPreviewWidth by remember { mutableStateOf<Int?>(null) }
    // 设置一变、或预览一开一关就丢掉本地值：它是「这次拖动的临时态」，设置跟上了说明拖动已经
    // 落盘（预览关掉则说明这次拖动被中断，落盘的那一条永远不会来了）。反过来（本地值一直压着
    // 设置）会让别处改动的预览宽度在界面上看不见。
    LaunchedEffect(settings.previewWidth, state.previewOpen) { draggedPreviewWidth = null }
    val previewWidth = draggedPreviewWidth ?: settings.previewWidth

    // 分隔条能拖到的上限 = min(窗口内剩余空间, 屏幕余量)：
    //
    // - 窗口内剩余空间 = 窗口宽 − 分隔条 − 主列表的**划分下限**（[Popup.minimumSplitContentWidth]，
    //   比窗口自身的下限小，因此默认窗口里也留得出余量）。窗口在拖动期间固定不变，预览变宽只能
    //   挤窄主列表，最多挤到这里；
    // - 屏幕余量由宿主给出（[PreviewHostPolicy.maxPreviewWidth]）：超过它，落盘时会被夹回来
    //   （窗口跟着缩一截）。
    //
    // 只用前者会拖出屏幕放不下的宽度；只用后者，主列表还站在下限上时同样拖不动。
    // 「窗口内剩余空间」用实测的 [windowWidth] 而不是「内容区宽度 + 预览宽度」：两者稳态下相等，
    // 但窗口还没跟上设置的几帧里只有实测值是对的。
    val maxDragWidth = minOf(
        windowWidth - Popup.previewDividerWidth - Popup.minimumSplitContentWidth,
        previewHost.maxPreviewWidth,
    ).coerceAtLeast(Popup.minimumPreviewWidth)

    // 内容区（主列表）的宽度。取自设置，与主列表实测出来的宽度是两回事：后者在槽位钉住的
    // 几帧里是旧值，甚至是被上一帧挤出来的窄值。
    val contentWidth = Popup.contentWidthOf(settings.customWindowWidth)

    val slideoutWidth = Popup.slideoutWidth(settings.previewWidth)
    val docked = when {
        !state.previewOpen -> false
        // 固定尺寸窗口（手机）：窗口本身够宽才并排，否则退回覆盖层。
        !previewHost.expandsWindow -> windowWidth >= OverlayThreshold
        // 桌面端：窗口有没有让出位置，由宿主说了算（见 [PreviewHostPolicy.windowReady]）。
        // 它和那次加宽 / 收回是同一次计算的产物，因此不会落后窗口一帧。
        //
        // 刻意**不**用界面量到的窗口宽度（`windowWidth`）判断：那是上一帧的测量值。窗口收起
        // 是宿主在同一瞬间用原生调用完成的，界面却要在下一帧才知道——照实测值判断，收起的
        // 那一帧会认为「还放得下」，把卡片画在已经变窄的窗口里（主面板闪一下预览内容）。
        else -> previewHost.windowReady
    }
    val placement = previewPlacement(
        // 宿主说「窗口已经让出位置」就等于说「预览开着」；把它并进来，进场的判定就与窗口加宽
        // 同帧发生。只用界面自己那份 `previewOpen` 不行：它要晚一帧，那一帧窗口已经加宽、卡片
        // 却还没进场，主列表会先铺满整窗再缩回去——一次打开闪两下。
        previewOpen = state.previewOpen || previewHost.windowReady,
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
    //
    // 判据用「窗口比**内容区**宽出多少」，而不是「窗口比主列表宽出多少」：主列表被钉住的那几帧
    // 里测出来的正是那个钉住的宽度，拿它去比会自我印证——差值一旦落在容差里就再也解不开，
    // 表现为预览收起后主列表右侧永远留一条空白（窗口已经收回来了，这里却还认为槽位没让出）。
    // 内容区宽度取自设置，与钉不钉住无关，因此窗口一收窄，这个差值必定落到 0。
    val leftoverSlot = windowWidth - contentWidth
    val slotLeftOver = leftoverSlot > WidthTolerance &&
        leftoverSlot <= slideoutWidth + WidthTolerance
    // 覆盖层占位不走这条路：那种情况窗口尺寸不变，列表照旧跟随窗口。
    val slotReserved = previewHost.expandsWindow && !previewHost.overlays &&
        listWidth > 0.dp && !docked && (state.previewOpen || slotLeftOver)
    // 主列表的宽度什么时候要钉住：预览槽位空着的那几帧（[slotReserved]）——让卡片进场时
    // 列表不要先占满又缩回。拖动分隔条期间**不**钉住：窗口这时一帧都不动，预览变宽挤窄的
    // 本来就该是主列表（`weight(1f)` 收走剩余空间），钉住反而会让两个面板一起溢出窗口。
    val listPinned = listWidth > 0.dp && slotReserved

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
                        previewWidth = previewWidth,
                        maxDragWidth = maxDragWidth,
                        onLeft = true,
                        onTogglePin = { onAction(ClipboardUiAction.TogglePinSelected) },
                        onDelete = { onAction(ClipboardUiAction.DeleteSelected) },
                        onCopyExtractedText = { onAction(ClipboardUiAction.CopyExtractedText) },
                        // 拖动中只改界面上的宽度（窗口不动），松手才连同新的主列表宽度写回设置。
                        onWidthChange = { value -> draggedPreviewWidth = value },
                        onWidthChangeFinished = {
                            draggedPreviewWidth?.let {
                                onAction(ClipboardUiAction.SetPreviewWidth(it))
                            }
                        },
                    )
                }

                // 预览的槽位先占住：主列表于是始终停在「窗口左边缘 + 预览宽度」上，与窗口
                // 宽度无关。预览停靠左侧时窗口要同时「左移」和「变宽」，界面看到的中间状态
                // 可能只是其中之一——按宽度定位（靠右对齐）的话，主列表会跟着窗口宽度左右
                // 跳一下。卡片进场 / 退场时直接接管或让出这段槽位，位置不变。
                // 空槽位也按这一帧的约束夹住（见 `previewSlot`）：窗口还没左移加宽的那一两帧，
                // 它会被压成 0，主列表因此停在原地、也不会被挤窄。
                if (previewHost.onLeft && slotReserved) {
                    Spacer(Modifier.previewSlot().width(slideoutWidth).fillMaxHeight())
                }

                Column(
                    Modifier
                        // 预览关闭时列表跟随窗口；并排显示时列表占满预览之外的部分；
                        // 槽位空着的那几帧把宽度钉住，避免宽度跳变（见 [slotReserved]）。
                        .then(if (listPinned) Modifier.width(listWidth) else Modifier.weight(1f))
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
                            previewTooltip = "显示 / 隐藏预览（${settings.togglePreviewShortcut.label}）",
                            onTogglePreview = { onAction(ClipboardUiAction.TogglePreview) },
                        )

                        if (settings.ignoreEvents) {
                            PausedBanner(
                                onResume = {
                                    onAction(
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
                        // `FooterItemView.onHover` 原本会在悬停页脚时收起预览；预览开关现在是持久化的
                        // 用户选择（见 `AppSettings.previewOpen`），只由按钮 / 快捷键切换，这里不再动它。
                        onHover = { index -> onAction(ClipboardUiAction.HoverFooter(index)) },
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
                        previewWidth = previewWidth,
                        maxDragWidth = maxDragWidth,
                        onLeft = false,
                        onTogglePin = { onAction(ClipboardUiAction.TogglePinSelected) },
                        onDelete = { onAction(ClipboardUiAction.DeleteSelected) },
                        onCopyExtractedText = { onAction(ClipboardUiAction.CopyExtractedText) },
                        // 见上面 `DOCK_LEFT` 的说明：拖动中不落盘，松手才一次写回。
                        onWidthChange = { value -> draggedPreviewWidth = value },
                        onWidthChangeFinished = {
                            draggedPreviewWidth?.let {
                                onAction(ClipboardUiAction.SetPreviewWidth(it))
                            }
                        },
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
                supportsTextRecognition = state.supportsTextRecognition,
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
            onConfirm = { onAction(ClipboardUiAction.ConfirmClear) },
            onDismiss = { onAction(ClipboardUiAction.DismissClear) },
        )
    }
}

/**
 * 预览卡片的进场动画：从**主面板那一侧**逐帧揭示出来。
 *
 * 刻意不做水平位移，也不用 `expandHorizontally`——后两者都会让卡片在动画期间的布局宽度与最终
 * 值不同：位移方案里内容滑进来、槽位先空着，展开方案则会把布局宽度从 0 拉起来，主列表跟着从
 * 「整窗宽」缩到最终宽度，整段过程都在重排。这里卡片的布局宽度从一开始就是最终宽度，内容原地
 * 不动，只是被裁剪着逐帧露出：
 *
 * - 主列表的宽度全程不变（槽位从第一帧起就已占住）；
 * - 揭示方向是「主面板 → 窗口外缘」：停靠右侧时自左向右推开，停靠左侧时自右向左；
 * - 和窗口变宽是同一个动作。桌面宿主的加宽是瞬时的（原生 `setBounds` 一次到位，逐帧改窗口
 *   尺寸会拖垮界面，见 `DesktopShellViewModel.applyBounds`），卡片若再花 180ms 滑进来，就会
 *   看成「背景先撑开、内容后滑入」两段。
 *
 * 内容不做淡入：淡入会让文字一点点浮现，看起来像「整块在闪」。
 *
 * 关闭不做退场动画是有意为之：桌面宿主收到「预览已关闭」后会把窗口收窄，卡片若还占着布局，
 * 就会和收窄中的窗口抢同一段宽度。现在卡片立即让出槽位，槽位由 [slotReserved] 先占着，
 * 等窗口收回来再一起消失——主列表在整段过程中宽度不变。
 *
 * 三处卡片由同一个 [PreviewPlacement] 驱动，同一时刻只有一处可见，因此一次打开只会跑一次
 * 进场动画。
 */
@Composable
private fun AnimatedPreviewCard(
    visible: Boolean,
    onLeft: Boolean,
    content: @Composable () -> Unit,
) {
    // 关闭是瞬时的：立刻从组合里移除，槽位交给 [slotReserved]。
    if (!visible) return

    // 进度只在绘制阶段读取，动画的每一帧因此只触发重绘、不触发重组。
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        reveal.animateTo(1f, tween(durationMillis = PreviewAnimationMillis))
    }

    Box(
        modifier = Modifier
            .fillMaxHeight()
            .drawWithContent {
                val revealed = size.width * reveal.value
                clipRect(
                    left = if (onLeft) size.width - revealed else 0f,
                    top = 0f,
                    right = if (onLeft) size.width else revealed,
                    bottom = size.height,
                ) {
                    this@drawWithContent.drawContent()
                }
            },
    ) {
        content()
    }
}
