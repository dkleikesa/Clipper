package com.qcmian.logviewer.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.logviewer.core.doc.DocumentSource
import com.qcmian.logviewer.core.model.DisplayMode
import com.qcmian.logviewer.core.model.MarkerPalette
import com.qcmian.logviewer.platform.KeyboardState
import com.qcmian.logviewer.platform.dragSelect
import com.qcmian.logviewer.platform.secondaryClick
import com.qcmian.logviewer.state.ViewerState
import com.qcmian.logviewer.ui.common.MarkerDots
import com.qcmian.logviewer.ui.common.MiniButton
import com.qcmian.logviewer.ui.theme.LocalLvColors
import com.qcmian.logviewer.ui.theme.toComposeColor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 日志主视图（F2 系列）：
 * - 虚拟滚动：任意行数流畅（F2-2）
 * - 行结构：标记 | 行号(原始) | 文本（F2-1/F2-3）
 * - 长文本原则：显示截断、数据完整（F2-4）
 * - 单选/多选/Shift 连选/拖动多选（F2-5）、右键菜单（F2-9）
 * - 三种显示模式渲染（F3-7）
 * - 非换行模式：内容平移式横向滚动（行号列固定，LazyColumn 宽恒=视口）
 */
@Composable
fun LogPane(
    state: ViewerState,
    fontSize: Int,
    showLineNumbers: Boolean,
    showMarkerColumn: Boolean,
    wrapLines: Boolean,
    onContextAction: (ContextAction, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalLvColors.current
    val listState = rememberLazyListState()
    var contextMenuLine by remember { mutableStateOf(-1) }
    var contextMenuPos by remember { mutableStateOf(Offset.Zero) }

    // 滚动请求（跳转/搜索定位）
    LaunchedEffect(state.scrollTarget) {
        val target = state.scrollTarget ?: return@LaunchedEffect
        val count = state.displayedCount()
        if (count > 0) {
            val pos = target.first.coerceIn(0, count - 1)
            runCatching {
                listState.scrollToItem(pos, scrollOffset = -(listState.layoutInfo.viewportEndOffset / 4))
            }.onFailure {
                runCatching { listState.scrollToItem(pos) }
            }
        }
    }

    // 可见区上报（大文件按需缓存预取 + atBottom 判定）
    LaunchedEffect(listState.firstVisibleItemIndex, listState.layoutInfo.visibleItemsInfo.size) {
        val first = listState.firstVisibleItemIndex
        val last = first + max(0, listState.layoutInfo.visibleItemsInfo.size - 1)
        state.requestVisibleRange(first, last)
        state.atBottom = last >= state.displayedCount() - 2
    }

    val rowCount = state.displayedCount()

    // 首次过滤判定：有启用的过滤器、尚无结果且无可显示行（filterBusy 置位有毫秒级协程窗口，
    // 以"无结果"兜底，避免加载浮层消失后闪现空提示）
    val firstFiltering = state.loadPhase == ViewerState.LoadPhase.Ready &&
        state.hasActiveFilters() && rowCount == 0 &&
        (state.filterBusy || state.filterResult == null)

    Box(
        modifier
            .background(c.bg)
            // 渲染区持有焦点：键盘事件经 preview 链更新全局修饰键状态（Cmd/Ctrl+点击多选依赖）
            // 修饰键状态只由它自身的 Down/Up 维护——Up 事件的 isXxxPressed 仍为 true（AWT 行为），
            // 若无差别赋值会永久残留 → 点选/拖选全部错乱
            .focusable()
            .onPreviewKeyEvent { e ->
                val isDown = e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
                when (e.key) {
                    Key.CtrlLeft, Key.CtrlRight, Key.MetaLeft, Key.MetaRight -> KeyboardState.ctrl = isDown
                    Key.ShiftLeft, Key.ShiftRight -> KeyboardState.shift = isDown
                    else -> {}
                }
                false
            },
    ) {
        when {
            state.loadPhase == ViewerState.LoadPhase.Loading ->
                ProgressOverlay("正在加载…", state.loadProgress)
            firstFiltering ->
                ProgressOverlay("正在过滤…", state.filterProgress)
            state.loadError != null -> LoadErrorPane(state)
            rowCount == 0 && state.loadPhase == ViewerState.LoadPhase.Ready -> EmptyHintPane(
                hint = when {
                    state.hasActiveFilters() && state.displayMode == DisplayMode.SHOW_ONLY_FILTERED ->
                        "当前过滤条件下没有命中行\n调整过滤器或切换显示模式"
                    else -> "文档为空"
                },
            )
            else -> LogListPane(
                state = state,
                listState = listState,
                rowCount = rowCount,
                fontSize = fontSize,
                showLineNumbers = showLineNumbers,
                showMarkerColumn = showMarkerColumn,
                wrapLines = wrapLines,
                onContextAction = onContextAction,
                contextMenuLine = contextMenuLine,
                contextMenuPos = contextMenuPos,
                onOpenMenu = { line, pos ->
                    contextMenuPos = pos
                    contextMenuLine = line
                },
                onCloseMenu = { contextMenuLine = -1 },
            )
        }
    }
}

/* ==================== 列表主体（含横向滚动状态机） ==================== */

/** 横向滚动状态机（非换行模式；平移式，偏移自管理自 clamp） */
private class HScrollState {
    var viewportW by mutableStateOf(0f)     // 整个日志视口宽（px）
    var visibleContentW by mutableStateOf(0f) // 当前可见行的最大文本宽（px）
    var perCharPx by mutableStateOf(0f)     // 每字符渲染宽（探针实测，等宽字体恒定）
    var scrollX by mutableStateOf(0f)       // 当前横向偏移

    private val rowWidths = mutableMapOf<Int, Float>()
    private var visibleRows: Set<Int> = emptySet()

    fun reset() {
        rowWidths.clear()
        visibleRows = emptySet()
        visibleContentW = 0f
        scrollX = 0f
        // perCharPx 不清零：探针 Text 尺寸不变时 onSizeChanged 不会重新触发，
        // 清掉后无法恢复 → 所有行宽归零 → 文本消失。字号变化由探针自身重组自动重校准。
    }

    /** 探针上报每字符渲染宽（隐形 100 字符等宽串实测） */
    fun onProbeWidth(w: Float) {
        if (w > 0) perCharPx = w / 100f
    }

    fun setVisibleRows(rows: Set<Int>) {
        visibleRows = rows
        rowWidths.keys.retainAll(rows)
        updateVisibleContentW()
    }

    fun onRowWidth(row: Int, width: Float) {
        if (width <= 0f) return
        rowWidths[row] = width
        if (row in visibleRows) updateVisibleContentW()
    }

    private fun updateVisibleContentW() {
        val width = visibleRows.maxOfOrNull { rowWidths[it] ?: 0f } ?: 0f
        if (visibleContentW != width) visibleContentW = width
    }
}

/** 显示列数（等宽字体：CJK/全角按 2 列计） */
private fun displayColumns(text: String): Int {
    var cols = 0
    for (ch in text) {
        cols += if (ch.code >= 0x1100 && (
                ch.code in 0x1100..0x115F ||   // Hangul Jamo
                ch.code >= 0x2E80              // CJK 统一范围（含全角符号）
                )
        ) 2 else 1
    }
    return cols
}

@Composable
private fun rememberHScroll(doc: DocumentSource?, fontSize: Int): HScrollState {
    val s = remember { HScrollState() }
    // 换文档/字号变化后重置；最大偏移统一由 LogListPane 按文本视口计算。
    LaunchedEffect(doc, fontSize) { s.reset() }
    return s
}

@Composable
private fun LogListPane(
    state: ViewerState,
    listState: LazyListState,
    rowCount: Int,
    fontSize: Int,
    showLineNumbers: Boolean,
    showMarkerColumn: Boolean,
    wrapLines: Boolean,
    onContextAction: (ContextAction, Int) -> Unit,
    contextMenuLine: Int,
    contextMenuPos: Offset,
    onOpenMenu: (Int, Offset) -> Unit,
    onCloseMenu: () -> Unit,
) {
    val c = LocalLvColors.current
    val hs = rememberHScroll(state.document, fontSize)
    val density = LocalDensity.current
    val fixedColumnW = with(density) {
        (if (showMarkerColumn) 18.dp else 0.dp).toPx() +
            (if (showLineNumbers) 82.dp else 0.dp).toPx()
    }
    // scrollX 只平移文本列；固定列不应被算进文本视口，否则 thumb 长度和最大偏移都会偏大。
    val textViewportW = (hs.viewportW - fixedColumnW).coerceAtLeast(0f)
    // 只根据当前可见行计算内容宽度，避免短行可以滑到没有文本的区域。
    val visibleRowKeys = listState.layoutInfo.visibleItemsInfo.map { it.index }.toSet()
    LaunchedEffect(visibleRowKeys) {
        hs.setVisibleRows(visibleRowKeys)
    }
    val contentW = hs.visibleContentW
    val maxX = (contentW - textViewportW).coerceAtLeast(0f)
    // 最大偏移只有一个来源，窗口变窄或可见行变化时不会残留越界位置。
    LaunchedEffect(maxX) {
        if (hs.scrollX > maxX) hs.scrollX = maxX
    }
    // 每字符渲染宽探针（隐形 100 字符等宽串，与行文本同字体管线实测；CJK 列宽由 displayColumns 校正）
    val probeStr = remember { "0123456789".repeat(10) }
    Text(
        probeStr,
        fontSize = fontSize.sp,
        fontFamily = FontFamily.Monospace,
        softWrap = false,
        maxLines = 1,
        color = Color.Transparent,
        modifier = Modifier
            .offset { IntOffset(-20000, 0) } // 移出可见区，仍参与测量布局
            .onSizeChanged { hs.onProbeWidth(it.width.toFloat()) },
    )

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { hs.viewportW = it.width.toFloat() }
                    // 拖动多选（expect/actual：桌面鼠标主键拖动区间选；Initial pass 不干扰单击/右键）
                    .dragSelect(state, listState)
                    // 横向滚轮：触控板横滑（scrollDelta.x）或 Shift+竖滚 → 平移
                    .pointerInput(wrapLines, maxX) {
                        if (wrapLines) return@pointerInput
                        awaitEachGesture {
                            val e = awaitPointerEvent()
                            val change = e.changes.firstOrNull() ?: return@awaitEachGesture
                            val dx = change.scrollDelta.x
                            val dy = change.scrollDelta.y
                            when {
                                dx != 0f -> {
                                    hs.scrollX = (hs.scrollX + dx).coerceIn(0f, maxX)
                                    change.consume()
                                }
                                dy != 0f && KeyboardState.shift -> {
                                    hs.scrollX = (hs.scrollX + dy * 60f).coerceIn(0f, maxX)
                                    change.consume()
                                }
                            }
                        }
                    },
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(
                        count = rowCount,
                        key = { pos -> pos },
                    ) { pos ->
                        // 直取行数据（免 DisplayLine 中转对象；显示序列语义与 displayAt 一致）
                        val matched = state.filterResult?.matched
                        val lineIndex: Int
                        val isMatch: Boolean
                        if (state.displayMode == DisplayMode.SHOW_ONLY_FILTERED &&
                            state.hasActiveFilters() && matched != null
                        ) {
                            lineIndex = matched.getOrNull(pos) ?: pos
                            isMatch = true
                        } else {
                            lineIndex = pos
                            isMatch = state.isMatchLine(pos)
                        }
                        LogRow(
                            state = state,
                            lineIndex = lineIndex,
                            isMatch = isMatch,
                            fontSize = fontSize,
                            showLineNumbers = showLineNumbers,
                            showMarkerColumn = showMarkerColumn,
                            wrapLines = wrapLines,
                            scrollX = hs.scrollX,
                            perCharPx = hs.perCharPx,
                            onTextWidth = { w -> hs.onRowWidth(pos, w) },
                            menuOpen = contextMenuLine == lineIndex,
                            menuPos = contextMenuPos,
                            onOpenMenu = { pos -> onOpenMenu(lineIndex, pos) },
                            onCloseMenu = onCloseMenu,
                            onContextAction = onContextAction,
                        )
                    }
                }
            }
            // 竖向滚动条（快速定位）
            if (rowCount > 0) {
                LogScrollbar(
                    listState = listState,
                    total = rowCount,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(vertical = 2.dp),
                )
            }
            // 横向滚动条（非换行模式且有横向溢出时；自管理偏移，clamp 保证不越界）
            if (!wrapLines && maxX > 0f) {
                HorizontalScrollbarTrack(
                    value = hs.scrollX,
                    max = maxX,
                    onScroll = { hs.scrollX = it.coerceIn(0f, maxX) },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 2.dp),
                )
            }
        }
    }
}

/* ==================== 滚动条 ==================== */

/**
 * 竖向滚动条（自绘全平台通用）：thumb 位置/高度由可视区与总行数推算，
 * 拖动 thumb 或点击轨道实时 scrollToItem 快速定位
 */
@Composable
private fun LogScrollbar(listState: LazyListState, total: Int, modifier: Modifier = Modifier) {
    val c = LocalLvColors.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragY by remember { mutableStateOf(0f) }

    BoxWithConstraints(modifier.width(12.dp)) {
        val trackHpx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val visible = listState.layoutInfo.visibleItemsInfo.size.coerceAtLeast(1)
        val first = listState.firstVisibleItemIndex
        val maxFirst = (total - visible).coerceAtLeast(0)
        val trackUsable = (trackHpx - trackHpx * (visible.toFloat() / total).coerceIn(0.03f, 1f))
        val thumbHpx = trackHpx * (visible.toFloat() / total).coerceIn(0.03f, 1f)
        val thumbYpx = if (maxFirst > 0) (first.toFloat() / maxFirst) * trackUsable else 0f
        val displayY = (if (dragging) dragY else thumbYpx).coerceIn(0f, trackUsable.coerceAtLeast(0f))

        val thumbHdp = with(density) { thumbHpx.toDp() }
        val thumbColor = if (dragging) c.accent else c.borderStrong

        // 热区（含 thumb）：按下即在 thumb 内则拖动，否则点击轨道跳转
        Box(
            Modifier
                .fillMaxHeight()
                .pointerHoverIcon(PointerIcon.Hand, overrideDescendants = true)
                .pointerInput(total, trackHpx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val clickPos = down.position.y
                        var y = displayY
                        if (clickPos !in displayY..(displayY + thumbHpx)) {
                            // 点击轨道：跳到点击位置
                            y = (clickPos - thumbHpx / 2).coerceIn(0f, trackUsable.coerceAtLeast(0f))
                            val frac = if (trackUsable > 0) y / trackUsable else 0f
                            val target = (frac * maxFirst).roundToInt().coerceIn(0, maxFirst)
                            scope.launch { listState.scrollToItem(target, 0) }
                        }
                        dragging = true
                        dragY = y
                        var lastPos = clickPos
                        try {
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull() ?: break
                                if (change.changedToUp()) break
                                val delta = change.position.y - lastPos
                                lastPos = change.position.y
                                dragY = (dragY + delta).coerceIn(0f, trackUsable.coerceAtLeast(0f))
                                val frac = if (trackUsable > 0) dragY / trackUsable else 0f
                                val target = (frac * maxFirst).roundToInt().coerceIn(0, maxFirst)
                                scope.launch { listState.scrollToItem(target, 0) }
                                change.consume()
                            }
                        } finally {
                            dragging = false
                        }
                    }
                },
        ) {
            Box(
                Modifier
                    .offset { IntOffset(0, displayY.roundToInt()) }
                    .width(if (dragging) 8.dp else 5.dp)
                    .height(thumbHdp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(thumbColor),
            )
        }
    }
}

/** 横向滚动条（自绘；偏移自管理，thumb 可拖，最小 24dp 保证可抓） */
@Composable
private fun HorizontalScrollbarTrack(
    value: Float,
    max: Float,
    onScroll: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalLvColors.current
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var trackWpx by remember { mutableStateOf(1f) }

    val maxV = max.coerceAtLeast(1f)
    val minThumbPx = with(density) { 24.dp.toPx() }
    // 标准比例：thumb / track = viewport / content；max 是内容超出视口的像素数。
    // 首帧 trackWpx 未测量（=1）：下限不超过轨道宽，避免 coerceIn 空 range 崩溃。
    val thumbW = (trackWpx / (1f + maxV / trackWpx.coerceAtLeast(1f)))
        .coerceIn(minOf(minThumbPx, trackWpx), trackWpx)
    val thumbX = if (maxV > 0f) (value / maxV) * (trackWpx - thumbW) else 0f
    // pointerInput 不因 value 改变而重启，始终读取最新 thumb 位置；否则拖动时会使用首帧位置。
    val currentThumbX by rememberUpdatedState(thumbX)
    val currentThumbW by rememberUpdatedState(thumbW)

    BoxWithConstraints(
        modifier
            .height(14.dp)
            .onSizeChanged { trackWpx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(maxV, trackWpx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val range = (trackWpx - currentThumbW).coerceAtLeast(0f)
                    val onThumb = down.position.x in currentThumbX..(currentThumbX + currentThumbW)
                    var thumbPosition = if (onThumb) {
                        currentThumbX
                    } else {
                        (down.position.x - currentThumbW / 2f).coerceIn(0f, range).also { x ->
                            onScroll((x / range.coerceAtLeast(1f)) * maxV)
                        }
                    }
                    dragging = true
                    var lastX = down.position.x
                    try {
                        while (true) {
                            val e = awaitPointerEvent()
                            val change = e.changes.firstOrNull() ?: break
                            if (change.changedToUp()) break
                            thumbPosition = (thumbPosition + change.position.x - lastX).coerceIn(0f, range)
                            lastX = change.position.x
                            onScroll((thumbPosition / range.coerceAtLeast(1f)) * maxV)
                            change.consume()
                        }
                    } finally {
                        dragging = false
                    }
                }
            },
    ) {
        val thumbWdp = with(density) { thumbW.toDp() }
        Box(
            Modifier
                .padding(vertical = 3.dp)
                .offset { IntOffset(thumbX.roundToInt(), 0) }
                .width(thumbWdp)
                .height(7.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(if (dragging) c.accent else c.borderStrong),
        )
    }
}

/* ==================== 单行渲染 ==================== */

@Composable
private fun LogRow(
    state: ViewerState,
    lineIndex: Int,
    isMatch: Boolean,
    fontSize: Int,
    showLineNumbers: Boolean,
    showMarkerColumn: Boolean,
    wrapLines: Boolean,
    scrollX: Float,
    perCharPx: Float,
    onTextWidth: (Float) -> Unit,
    menuOpen: Boolean,
    menuPos: Offset,
    onOpenMenu: (Offset) -> Unit,
    onCloseMenu: () -> Unit,
    onContextAction: (ContextAction, Int) -> Unit,
) {
    val c = LocalLvColors.current
    // 行文本与富文本缓存：文档不变时同一行内容恒定；查找条件不变时高亮恒定
    val doc = state.document
    val text = remember(lineIndex, doc) { state.textOf(lineIndex) }
    val query = state.findQuery
    val regex = state.findRegex
    val caseSensitive = state.findCaseSensitive
    val wholeWord = state.findWholeWord
    val lineText = remember(text, query, regex, caseSensitive, wholeWord, c) {
        buildLineText(text, state, c)
    }
    val isCurrent = state.currentLine == lineIndex
    val isSelected = state.selectedLines.contains(lineIndex)
    val colors = state.lineColors(lineIndex)

    val bgColor = colors.second
    val fgColor = colors.first
    // "全部显示"模式下命中行：无过滤器着色时用强调色淡背景标识
    val matchTint = state.displayMode == DisplayMode.SHOW_ALL && isMatch && bgColor == null
    val rowMinHeight = (fontSize * 1.9f).dp

    Box(
        Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = rowMinHeight)
            .background(if (isSelected) c.active else Color.Transparent)
            .background(
                when {
                    isCurrent -> c.currentLineBg
                    bgColor != null -> bgColor.toComposeColor()
                    matchTint -> c.accent.copy(alpha = 0.12f)
                    else -> Color.Transparent
                },
            )
            .combinedClickable(
                onClick = {
                    state.onLineClicked(lineIndex, KeyboardState.ctrl, KeyboardState.shift)
                },
                onLongClick = { onOpenMenu(Offset.Zero) },
            )
            // 右键（desktop actual 检测）：菜单跟随鼠标位置（相对本行）
            .secondaryClick { pos -> onOpenMenu(pos) },
    ) {
        // 当前行左侧指示条
        if (isCurrent) {
            Box(
                Modifier
                    .padding(start = 1.dp)
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(c.accent),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = if (wrapLines) 3.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showMarkerColumn) {
                Box(
                    Modifier.width(18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MarkerDots(state.markersOf(lineIndex))
                }
            }
            if (showLineNumbers) {
                // 行号字符串缓存（滚动全量时免每行组合重复分配）
                val lineNo = remember(lineIndex) { (lineIndex + 1).toString() }
                Text(
                    text = lineNo,
                    color = if (isCurrent) c.accent else c.textFaint,
                    fontSize = (fontSize - 1).sp,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .width(72.dp)
                        .padding(end = 10.dp),
                    maxLines = 1,
                )
            }
            if (wrapLines) {
                Text(
                    text = lineText,
                    color = fgColor?.toComposeColor() ?: c.text,
                    fontSize = fontSize.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = (fontSize * 1.5).sp,
                    softWrap = true,
                    overflow = TextOverflow.Clip,
                    maxLines = Int.MAX_VALUE,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp, end = 16.dp),
                )
            } else {
                // 非换行：文本宽度 = 显示列数 × 探针每字符渲染宽（同一字体管线，确定性计算无布局反馈），
                // scrollX 平移显示（视口窗裁剪；行号/标记列固定不滚）
                val rowDensity = LocalDensity.current
                val textHorizontalPadding = with(rowDensity) { 24.dp.toPx() }
                val cols = remember(text) { text?.let { displayColumns(it) } ?: 0 }
                val textWpx = cols * perCharPx
                LaunchedEffect(text, cols, perCharPx, textHorizontalPadding) {
                    if (cols > 0 && perCharPx > 0f) onTextWidth(textWpx + textHorizontalPadding)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clipToBounds(),
                ) {
                    // 先建立完整的可平移内容层，再移动内容层；不要直接移动 Text，
                    // 否则 Text 自身的约束可能在偏移后把新进入视口的部分裁掉。
                    Box(
                        Modifier
                            .offset { IntOffset(-scrollX.roundToInt(), 0) }
                            .requiredWidth(with(rowDensity) { (textWpx + textHorizontalPadding).toDp() }),
                    ) {
                        Text(
                            text = lineText,
                            color = fgColor?.toComposeColor() ?: c.text,
                            fontSize = fontSize.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = (fontSize * 1.5).sp,
                            softWrap = false,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 8.dp, end = 16.dp),
                        )
                    }
                }
            }
        }

        // 右键 / 长按菜单（F2-9，右键时跟随鼠标位置）
        if (menuOpen) {
            LineContextMenu(
                state = state,
                lineIndex = lineIndex,
                menuPos = menuPos,
                onAction = { action ->
                    onCloseMenu()
                    onContextAction(action, lineIndex)
                },
                onDismiss = onCloseMenu,
            )
        }
    }
}

/** 行文本 + 查找命中高亮（基于完整文本，F2-4/F4-2） */
private fun buildLineText(text: String?, state: ViewerState, c: com.qcmian.logviewer.ui.theme.LvColors): AnnotatedString {
    if (text == null) return AnnotatedString("…")
    if (state.findQuery.isEmpty()) return AnnotatedString(text)
    val ranges = state.findRanges(text)
    if (ranges.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (r in ranges) {
            addStyle(
                SpanStyle(
                    background = c.markBg,
                    color = c.markText,
                    fontWeight = FontWeight.Bold,
                ),
                r.first.coerceIn(0, text.length),
                (r.last + 1).coerceIn(0, text.length),
            )
        }
    }
}

/* ==================== 菜单 ==================== */

/** 行操作菜单动作 */
enum class ContextAction {
    COPY_LINE,            // 复制行
    COPY_SELECTED,        // 复制选中（多选时）
    NEW_FILTER_FROM_LINE, // 以此为条件新建过滤器
    TOGGLE_MARKER,        // 添加/移除标记
}

@Composable
private fun LineContextMenu(
    state: ViewerState,
    lineIndex: Int,
    menuPos: Offset,
    onAction: (ContextAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    androidx.compose.material3.DropdownMenu(
        expanded = true,
        onDismissRequest = onDismiss,
        offset = with(density) { DpOffset(menuPos.x.toDp(), menuPos.y.toDp()) },
    ) {
        val c = LocalLvColors.current
        val selCount = state.selectedLines.size
        val items = buildList {
            add("复制行" to ContextAction.COPY_LINE)
            if (selCount > 1) add("复制选中（$selCount 行）" to ContextAction.COPY_SELECTED)
            add("以此为条件新建过滤器" to ContextAction.NEW_FILTER_FROM_LINE)
            add("添加/移除标记（${MarkerPalette.names[state.activeMarker]}）" to ContextAction.TOGGLE_MARKER)
        }
        items.forEach { (label, action) ->
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(label, fontSize = 12.sp, color = c.text) },
                onClick = { onAction(action) },
            )
        }
    }
}

/* ==================== 状态浮层 ==================== */

@Composable
private fun ProgressOverlay(label: String, progress: Float) {
    val c = LocalLvColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("$label ${(progress * 100).toInt()}%", color = c.textDim, fontSize = 13.sp)
    }
}

/** 加载失败态（重试入口），与进度浮层分离 */
@Composable
private fun LoadErrorPane(state: ViewerState) {
    val c = LocalLvColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(state.loadError ?: "", color = c.levelE, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            MiniButton("重试") { state.fileRef?.let { state.loadRef(it, state.encodingEnum) } }
        }
    }
}

@Composable
private fun EmptyHintPane(hint: String) {
    val c = LocalLvColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            hint,
            color = c.textFaint,
            fontSize = 13.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center,
        )
    }
}
