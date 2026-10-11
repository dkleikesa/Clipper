package com.qcmian.clipper.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed as pointerIsCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed as pointerIsMetaPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed as pointerIsShiftPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.ui.HorizontalResizePointerIcon
import com.qcmian.clipper.core.ui.VerticalResizePointerIcon
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.theme.hintColor
import kotlinx.coroutines.launch

/**
 * 数据表格：把「一批行 × 一批列」画成一张能用的表。
 *
 * 与 `TreeView` 一样，这一层**只认识自己画的是什么**，不碰数据从哪来——所以它既服务数据库结果集，
 * 也服务任何「行列对齐」的展示。能力边界划在「看 + 用」：
 *
 *  - **看**：列头（列名 + 右侧的排序按钮）、行号列、`NULL` 用斜体灰与空串区分、单元格**按换行
 *    符换行**（不按宽度折行；超宽的那一段末尾省略）、整行悬停与选中高亮、横向纵向都能滚、
 *    **列宽可拖（列头右缘）、逐行行高也可拖（行号格下缘）**——把行高拖大就多显示几段。
 *  - **用**：点击列头选中整列、单元格 / 整行 / 矩形区域选中、`⇧` 连续扩选、`⌘`（`Ctrl`）多选
 *    （可攒出若干互不相邻的块）、键盘导航（`↑↓←→` `PgUp/PgDn` `Home/End`）、`⌘C` 复制成
 *    TSV（能直接贴进 Excel 与 DataGrip）、右键菜单（内置复制两项，其余由调用方追加）。
 *  - **不碰**：排序与筛选**只在点击时回调**（[onSort] / [extraMenuEntries]），真正怎么做由调用方
 *    决定——对数据库来说必须下推成 `ORDER BY` / `WHERE`，在内存里排一张几十万行的表没有意义。
 *
 * 与 `LazyColumn` 的关系：这里的数据区用 `Column`（非懒加载），因为**行数由调用方的分页控制**
 * （一次一页），而 `LazyColumn` 与「外层横向滚动 + 表头与内容同步横滚」这套结构不好共存
 * （它要求有界宽度）。等哪天真要做「无限流式加载」，再连同滚动条一起换。
 *
 * @param columns 列定义（顺序即显示顺序）。
 * @param rows 本页数据，单元格已经是**显示文本**，`null` 表示 SQL `NULL`。
 * @param showRowNumbers 是否画左侧行号列；点它可以整行选中。列宽按本页行数自动定
 *   （见 [dataGridRowNumberWidth]）。
 * @param sortKey 当前排序的列 [DataGridColumn.key]；`null` 表示没排序。由调用方持有——排序要查库，
 *   状态不能在控件里各存一份。
 * @param onSort 点了列头**右侧的排序按钮**（点列头本身是选中那一列，不排序）。三态循环：
 *   升序 → 降序 → 撤掉排序。控件已经算好下一个状态，调用方直接拿去查；`key` 为 `null` 表示这一
 *   列的排序被撤掉——对数据库来说就是下次查询不再带 `ORDER BY`。
 * @param onCopy 复制（`⌘C` 或右键菜单）。给的是 TSV 文本，写进系统剪贴板由调用方做。
 * @param extraMenuEntries 右键时追加的条目（例如「按此值筛选」「复制为 INSERT」）。给的是被点中
 *   的那个单元格，调用方据此决定加什么。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun DataGrid(
    columns: List<DataGridColumn>,
    rows: List<List<String?>>,
    modifier: Modifier = Modifier,
    showRowNumbers: Boolean = true,
    sortKey: String? = null,
    sortAscending: Boolean = true,
    onSort: ((key: String?, ascending: Boolean) -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
    extraMenuEntries: ((row: Int, column: Int, value: String?) -> List<AppContextMenuEntry>)? = null,
) {
    if (columns.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("没有可显示的列", fontSize = 13.sp, color = MaterialTheme.hintColor)
        }
        return
    }

    // 用户拖过的列宽按**列 key** 记：切页、换数据集时同一列保持它被拖到的宽度，新列取声明值。
    val draggedWidths = remember { mutableStateMapOf<String, Dp>() }
    fun widthOf(column: DataGridColumn): Dp = draggedWidths[column.key] ?: column.width

    val density = LocalDensity.current
    // 逐行行高：拖**某一行的行号格下缘**调整它自己的高度。按**页内行号**记——行没有稳定身份
    // （表可能没主键，同一个 rowid 也会因为排序 / 筛选换位置），而调行高多半是为了「让这一行的
    // 内容看得清」，翻页后同序号的行沿用同一高度，是够用的默认。
    val rowHeights = remember { mutableStateMapOf<Int, Dp>() }
    fun heightOf(row: Int): Dp = rowHeights[row] ?: DataGridDefaultRowHeight
    // 这一页每行的高度（像素）：命中测试与滚动换算都得靠它累加。
    val rowHeightsPx: List<Float> = (0 until rows.size).map { with(density) { heightOf(it).toPx() } }

    // 行号列的宽度跟着**这一页的行数**走：写死一个宽度，十几行的表上会白白占掉小半列的位置。
    val rowNumberWidth = if (showRowNumbers) dataGridRowNumberWidth(rows.size) else 0.dp
    val totalWidth = rowNumberWidth + columns.fold(0.dp) { sum, column -> sum + widthOf(column) }

    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()

    // 选区是「一到多块」：`⇧` 扩选改的是活跃块，`⌘` 加选追加的是新块。
    var selection by remember(columns, rows.size) { mutableStateOf<DataGridSelections?>(null) }
    // 正在拖什么（列宽 / 行高）：拖动期间整块表格都把光标钉成对应形状（见根节点那段注释）。
    var dragging by remember { mutableStateOf(DataGridDrag.None) }
    var originInWindow by remember { mutableStateOf(Offset.Zero) }
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var menuCell by remember { mutableStateOf<Pair<Int, Int>?>(null) }

    fun dismissMenu() {
        menuAt = null
        menuCell = null
    }

    /**
     * 指针位置 → 单元格；落在**表体之外**（表的上方、下方、以及右侧的空白）返回 `null`。
     *
     * 这里必须严格：`dataGridRowAt` 对表体之下的 `y` 会给最后一行的号（那是给拖拽用的——指针
     * 跑出表体也得有个行号好继续扩选），可一旦照搬到「点击 / 右键」上，就会把「点在表格下面的
     * 空白处」也算成点中了最后一行，选区于是被悄悄挪走，看起来正是「选中被取消了」。
     */
    fun cellAt(position: Offset, rowNumberWidthPx: Float): Pair<Int, Int>? {
        val y = position.y + vScroll.value
        if (y < 0f || y >= rowHeightsPx.sum()) return null
        val row = dataGridRowAt(y, rowHeightsPx) ?: return null
        var x = position.x - rowNumberWidthPx
        if (x < 0f) return row to 0
        columns.forEachIndexed { index, column ->
            val width = with(density) { widthOf(column).toPx() }
            if (x < width) return row to index
            x -= width
        }
        return row to columns.lastIndex
    }

    /** 内容坐标里的 `y` → 行号。拖拽时指针常常跑出表体，所以这里夹住一个有效行号。 */
    fun rowAt(y: Float): Int = dataGridRowAt(y, rowHeightsPx) ?: 0

    fun copySelection() {
        val current = selection ?: return
        // 整行选中走 CSV，框选格子走 TSV——见 `dataGridClipboardText`。
        val text = dataGridClipboardText(current, rows)
        if (text.isNotEmpty()) onCopy?.invoke(text)
    }

    /** 把选中项挪到 [row] / [column]（夹进范围），并让它滚进视口。 */
    fun moveCursor(row: Int, column: Int) {
        if (rows.isEmpty()) return
        val target = DataGridSelection.cell(
            row = row.coerceIn(0, rows.lastIndex),
            column = column.coerceIn(0, columns.lastIndex),
        )
        // 键盘只挪**活跃块**：`⌘` 选出来的那几块不该因为按了个方向键就没了。
        selection = selection?.replaceActive(target) ?: DataGridSelections.of(target)
        // 键盘把光标推出了视口就把它带回来——不然「按了方向键但什么都没发生」。
        // 逐行行高之后「第 N 行在哪」得累加着算，不能再乘一个统一行高。
        val top = dataGridRowTop(target.cursorRow, rowHeightsPx)
        val bottom = top + (rowHeightsPx.getOrNull(target.cursorRow) ?: 0f)
        val viewport = vScroll.viewportSize.toFloat()
        val current = vScroll.value.toFloat()
        val to = when {
            top < current -> top
            bottom > current + viewport -> bottom - viewport
            else -> null
        }
        // `scrollTo` 是挂起的（它是「滚到某处」而不是「请求滚」），因此要起一个协程。
        if (to != null) scope.launch { vScroll.scrollTo(to.toInt()) }
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        // 键盘作用于**活跃块**的游标。
        val current = selection?.active
        val row = current?.cursorRow ?: 0
        val column = current?.cursorColumn ?: 0
        // 翻一页多少行按**默认行高**估：各行实际高度不一，估多少都只是估，而按默认算既稳定，
        // 又不会因为某一行被拉高就让翻页距离突然跳很远。
        val page = (vScroll.viewportSize / with(density) { DataGridDefaultRowHeight.toPx() })
            .toInt().coerceAtLeast(1)
        return when {
            event.key == Key.DirectionDown -> { moveCursor(row + 1, column); true }
            event.key == Key.DirectionUp -> { moveCursor(row - 1, column); true }
            event.key == Key.DirectionRight -> { moveCursor(row, column + 1); true }
            event.key == Key.DirectionLeft -> { moveCursor(row, column - 1); true }
            event.key == Key.PageDown -> { moveCursor(row + page, column); true }
            event.key == Key.PageUp -> { moveCursor(row - page, column); true }
            event.key == Key.MoveHome -> { moveCursor(0, column); true }
            event.key == Key.MoveEnd -> { moveCursor(rows.lastIndex, column); true }
            (event.isMetaPressed || event.isCtrlPressed) && event.key == Key.C -> {
                if (onCopy != null) { copySelection(); true } else false
            }
            else -> false
        }
    }

    Box(
        modifier
            .onGloballyPositioned { originInWindow = it.positionInWindow() }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent(::handleKey)
            // 拖动期间把光标钉在整块表格上（`overrideDescendants`）。`pointerHoverIcon` 只在指针
            // 进出节点时设一次，而拖动中指针会离开那条窄把手——那一刻它退回默认箭头，再回到把手
            // 上才有下一个 Enter 事件，表现就是「一拖光标就变回箭头」。钉在根上并覆盖子孙之后，
            // 指针在表格里怎么移动都是这个形状，直到松手。
            .then(
                when (dragging) {
                    DataGridDrag.ColumnWidth -> Modifier.pointerHoverIcon(
                        HorizontalResizePointerIcon,
                        overrideDescendants = true,
                    )
                    DataGridDrag.RowHeight -> Modifier.pointerHoverIcon(
                        VerticalResizePointerIcon,
                        overrideDescendants = true,
                    )
                    DataGridDrag.None -> Modifier
                }
            )
            // 右键检测挂在**根节点**上，而不是另加一个全屏覆盖层：覆盖层是同级、又在最上层，
            // 会把两条滚动条连同整块区域的交互一起截住——看上去一切都在，却点不动、拖不动。
            .pointerInput(columns, rows.size, rowNumberWidth, rowHeightsPx) {
                val rowNumberWidthPx = rowNumberWidth.toPx()
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press) continue
                        if (!event.buttons.isSecondaryPressed) continue
                        val change = event.changes.firstOrNull() ?: continue
                        // 这段挂在**根节点**上（为的是连滚动条上方也一并照看），拿到的是根节点的
                        // 坐标：y 从表格顶部起算，比数据区多出一个表头的高度；x 则比内容坐标少了
                        // 横向滚动量。不换算的话行号整整偏一行——右键点在「选中的最后一行」上，
                        // 算出来却是它的下一行，落点不在选区里，选区于是被替换掉（看着就是「选中
                        // 被取消」）。
                        val content = Offset(
                            x = change.position.x + hScroll.value,
                            y = change.position.y - with(density) { DataGridDefaultRowHeight.toPx() },
                        )
                        val hit = cellAt(content, rowNumberWidthPx) ?: continue
                        change.consume()
                        // 只有点在**选区之外**才把光标挪过去。落在已有的选区内就不动——右键是
                        // 「对这片选区做点什么」（复制、按此值筛选），把选区清成落点那一格，
                        // 菜单里的动作就没了对象，用户刚选好的东西也白选了。
                        if (selection?.contains(hit.first, hit.second) != true) {
                            selection = DataGridSelections.cell(hit.first, hit.second)
                        }
                        menuCell = hit
                        menuAt = change.position
                    }
                }
            },
    ) {
        Box(Modifier.fillMaxSize().horizontalScroll(hScroll)) {
            Column(Modifier.width(totalWidth).fillMaxHeight()) {
                DataGridHeader(
                    columns = columns,
                    widthOf = ::widthOf,
                    rowNumberWidth = rowNumberWidth,
                    sortKey = sortKey,
                    sortAscending = sortAscending,
                    selection = selection,
                    rowCount = rows.size,
                    onSelectColumn = { index ->
                        // 整列选中：从第一行到最末一行。空表不选——那时 `rows.lastIndex` 是 -1。
                        if (rows.isNotEmpty()) {
                            selection = DataGridSelections.of(
                                DataGridSelection(0, index, rows.lastIndex, index)
                            )
                        }
                        focusRequester.requestFocus()
                    },
                    onSort = onSort,
                    onResize = { column, width -> draggedWidths[column.key] = width },
                    onColumnWidthDragChange = { active ->
                        dragging = if (active) DataGridDrag.ColumnWidth else DataGridDrag.None
                    },
                )

                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(vScroll)
                        .pointerInput(columns, rows.size, rowNumberWidth, rowHeightsPx) {
                            val rowNumberWidthPx = rowNumberWidth.toPx()
                            val lastColumnWidthPx = with(density) { widthOf(columns.last()).toPx() }
                            // 按落点造一块：行号列上按 = 整行，数据格上按 = 单格。
                            fun blockAt(row: Int, column: Int, wholeRow: Boolean): DataGridSelection =
                                dataGridBlockAt(row, column, wholeRow, columns.size)
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                // 修饰键读的是**按下那一次事件**上的：拖到一半再读，读到的可能已经是
                                // 松开 `⇧` 之后的状态。`currentEvent` 正是刚处理完的那一个。
                                val modifiers = currentEvent.keyboardModifiers
                                down.consume()
                                focusRequester.requestFocus()
                                val hit = cellAt(down.position, rowNumberWidthPx)
                                    ?: return@awaitEachGesture
                                val onRowNumber = down.position.x < rowNumberWidthPx
                                val previous = selection
                                val block = blockAt(hit.first, hit.second, onRowNumber)
                                // 三种按下：
                                //   `⌘`（`Ctrl`）加选——落点自成一块，已有的块一块不动；
                                //   `⇧` 扩选——把**活跃块**从它原本的锚点拉到落点；
                                //   什么都不按——整体换掉，只留落点这一块。
                                val extend = modifiers.pointerIsShiftPressed && previous != null
                                val base = when {
                                    modifiers.pointerIsMetaPressed || modifiers.pointerIsCtrlPressed ->
                                        previous?.plus(block) ?: DataGridSelections.of(block)
                                    extend -> previous
                                    else -> DataGridSelections.of(block)
                                }
                                // 扩选沿用活跃块的锚点（否则一按 `⇧`，选区就整体跳回落点）。
                                val extendFrom = if (extend) previous.active else null
                                val anchorRow = extendFrom?.anchorRow ?: hit.first
                                val anchorColumn = extendFrom?.anchorColumn ?: if (onRowNumber) 0 else hit.second
                                // 从行号列起手的这一整个手势都按**整行**算。`cellAt` 在行号列上只能
                                // 给出第 0 列（它不知道「整行」这回事），照搬过来就会把「点行号选整行」
                                // 变成「只选中第一格」——所以这里显式把右端拉到最后一列。
                                selection = base.replaceActive(
                                    DataGridSelection(
                                        anchorRow = anchorRow,
                                        anchorColumn = anchorColumn,
                                        cursorRow = hit.first,
                                        cursorColumn = if (onRowNumber) columns.lastIndex else hit.second,
                                    )
                                )
                                var lastColumn = anchorColumn
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    change.consume()
                                    if (!change.pressed) break
                                    // 拖到列或行的范围之外也照样扩展：这是框选，不是「必须精确落在格上」。
                                    val row = rowAt(change.position.y + vScroll.value)
                                        .coerceIn(0, rows.lastIndex.coerceAtLeast(0))
                                    var x = change.position.x - rowNumberWidthPx
                                    var column = columns.lastIndex
                                    columns.forEachIndexed { index, column0 ->
                                        val w = with(density) { widthOf(column0).toPx() }
                                        if (x < w) { column = index; return@forEachIndexed }
                                        x -= w
                                    }
                                    if (x > lastColumnWidthPx) column = columns.lastIndex
                                    // 顺着走（向右/向下）与逆着走（向左/向上）用同一套 anchor + cursor。
                                    lastColumn = column
                                    selection = base.replaceActive(
                                        DataGridSelection(
                                            anchorRow = anchorRow,
                                            anchorColumn = anchorColumn,
                                            cursorRow = row,
                                            // 行号列起拖的仍是整行：横向拖到哪一列，都不该把整行削成一段。
                                            cursorColumn = if (onRowNumber) columns.lastIndex else lastColumn,
                                        )
                                    )
                                }
                            }
                        },
                ) {
                    rows.forEachIndexed { index, row ->
                        DataGridRow(
                            index = index,
                            values = row,
                            columns = columns,
                            widthOf = ::widthOf,
                            rowNumberWidth = rowNumberWidth,
                            rowHeight = heightOf(index),
                            onRowHeightChange = { height -> rowHeights[index] = height },
                            onRowHeightDragChange = { active ->
                                dragging = if (active) DataGridDrag.RowHeight else DataGridDrag.None
                            },
                            selection = selection,
                        )
                    }
                    if (rows.isEmpty()) {
                        Text(
                            "（没有数据）",
                            fontSize = 12.sp,
                            color = MaterialTheme.hintColor,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                }
            }
        }

        VerticalScrollbar(
            scrollState = vScroll,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
        HorizontalScrollbar(
            scrollState = hScroll,
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        )

        menuCell?.let { (row, column) ->
            menuAt?.let { at ->
                val value = rows.getOrNull(row)?.getOrNull(column)
                AppContextMenu(
                    at = at,
                    anchor = originInWindow,
                    onDismiss = ::dismissMenu,
                    entries = buildList {
                        add(
                            AppContextMenuAction(title = "复制", shortcut = "⌘C", enabled = onCopy != null) {
                                dismissMenu()
                                copySelection()
                            }
                        )
                        add(
                            AppContextMenuAction(title = "复制整行", enabled = onCopy != null) {
                                dismissMenu()
                                // 这条菜单说的就是「把这一行当一条记录拿走」，所以按 CSV 给。
                                onCopy?.invoke(
                                    dataGridCsv(
                                        DataGridSelections.of(
                                            DataGridSelection(row, 0, row, columns.lastIndex)
                                        ),
                                        rows,
                                    )
                                )
                            }
                        )
                        val extra = extraMenuEntries?.invoke(row, column, value).orEmpty()
                        if (extra.isNotEmpty()) {
                            add(AppContextMenuDivider)
                            addAll(extra)
                        }
                    },
                )
            }
        }
    }
}

/** 一列的静态定义。取 `data class`：控件用 `remember(columns)` 判断「列变了没有」，得靠值相等。 */
@Immutable
data class DataGridColumn(
    /** 唯一标识：记住列宽、排序回调都以它为准（标题可以变，它不该变）。 */
    val key: String,
    val title: String,
    /** 初始宽度；用户拖过之后以拖到的为准。 */
    val width: Dp,
    val minWidth: Dp = 56.dp,
)

/**
 * 一次选中：从 `anchor` 拖到 `cursor` 的矩形区域，四个下标都**含端点**。
 *
 * 用两个角而不是「左上 + 宽高」来存，是因为拖拽时 anchor 不动、cursor 乱跑：两个角各自就是当时
 * 按下的和现在指针所在的那一格，正着拖、反着拖不必分别处理。
 */
@Immutable
data class DataGridSelection(
    val anchorRow: Int,
    val anchorColumn: Int,
    val cursorRow: Int,
    val cursorColumn: Int,
) {
    val firstRow: Int get() = minOf(anchorRow, cursorRow)
    val lastRow: Int get() = maxOf(anchorRow, cursorRow)
    val firstColumn: Int get() = minOf(anchorColumn, cursorColumn)
    val lastColumn: Int get() = maxOf(anchorColumn, cursorColumn)

    fun contains(row: Int, column: Int): Boolean =
        row in firstRow..lastRow && column in firstColumn..lastColumn

    /** 整行是不是都被选中了（行号列据此一起高亮）。 */
    fun coversWholeRow(row: Int, columnCount: Int): Boolean =
        row in firstRow..lastRow && firstColumn <= 0 && lastColumn >= columnCount - 1

    /** 整列是不是都被选中了（列头据此一起高亮）。 */
    fun coversWholeColumn(column: Int, rowCount: Int): Boolean =
        column in firstColumn..lastColumn && firstRow <= 0 && lastRow >= rowCount - 1

    companion object {
        fun cell(row: Int, column: Int): DataGridSelection = DataGridSelection(row, column, row, column)
    }
}

/**
 * 一次选区：一到多块。
 *
 * 普通点击 / 拖拽只会得到一块；`⌘`（`Ctrl`）加选会攒出多块——「第 3 行和第 900 行一起复制」
 * 这种不连续的选择，一个矩形表达不了。
 *
 * [blocks] 的最后一块是**活跃块**：键盘导航、`⇧` 扩选与拖拽都作用在它上面，其余块只保持被选中
 * 的样子。把「活跃的是哪一块」收进列表末尾，就不必再维护一套与选区并行的「当前块」状态。
 *
 * [DataGridSelection] 本身仍是「一个矩形」，被这里当作「一块」用——「一块」的几何逻辑与它的
 * 测试因此不必因为多选而改动。
 */
@Immutable
data class DataGridSelections(
    val blocks: List<DataGridSelection>,
) {
    val active: DataGridSelection get() = blocks.last()

    fun contains(row: Int, column: Int): Boolean = blocks.any { it.contains(row, column) }

    fun coversWholeRow(row: Int, columnCount: Int): Boolean =
        blocks.any { it.coversWholeRow(row, columnCount) }

    fun coversWholeColumn(column: Int, rowCount: Int): Boolean =
        blocks.any { it.coversWholeColumn(column, rowCount) }

    /** 追加一块，并让它成为活跃块（`⌘` 加选用；已有选区原样保留）。 */
    fun plus(block: DataGridSelection): DataGridSelections = copy(blocks = blocks + block)

    /** 只换掉活跃块，其余块不动（`⇧` 扩选与拖拽用）。 */
    fun replaceActive(block: DataGridSelection): DataGridSelections =
        copy(blocks = blocks.dropLast(1) + block)

    /**
     * 选中的是不是**整行**：每一块都把该行的所有列选满了。
     *
     * 用来决定复制成 CSV 还是 TSV——「把一批记录拿走」（存成 CSV、发给别人、喂给脚本）与
     * 「抠一片格子贴到另一张表」（要求列对得上）是两件事，统一用同一个分隔符总有一边别扭。
     */
    fun coversWholeRows(columnCount: Int): Boolean =
        blocks.all { it.firstColumn <= 0 && it.lastColumn >= columnCount - 1 }

    companion object {
        fun of(block: DataGridSelection): DataGridSelections = DataGridSelections(listOf(block))

        fun cell(row: Int, column: Int): DataGridSelections = of(DataGridSelection.cell(row, column))
    }
}

/**
 * 按下处 → 一块选区：**行号列上按 = 整行，数据格上按 = 单格**。
 *
 * 独立成纯函数是为了能单测——「点行号选整行」正是那种看着显然、错了却看不出来的地方：行号列上
 * 命中测试只能给出第 0 列（它不知道「整行」这回事），照着它构造选区就会退化成「只选中第一格」。
 */
internal fun dataGridBlockAt(
    row: Int,
    column: Int,
    wholeRow: Boolean,
    columnCount: Int,
): DataGridSelection =
    if (wholeRow) {
        DataGridSelection(row, 0, row, columnCount - 1)
    } else {
        DataGridSelection.cell(row, column)
    }

/**
 * 内容坐标里的 `y` → 行号；[heights] 是每行的高度（像素），按顺序累加着找。
 *
 * 独立成纯函数是为了能单测：**逐行行高**之后，行号不再是「`y` 除以行高」一步除法，而是一次
 * 累加——累加写错的话，越靠下点得越偏，而那种偏差在界面上看着只是「有点点歪了」。
 *
 * 落在表体之上给 `0`，落在表体之下给最后一行的号（拖拽时指针常常跑出表体），空表给 `null`。
 */
internal fun dataGridRowAt(y: Float, heights: List<Float>): Int? {
    if (heights.isEmpty()) return null
    if (y <= 0f) return 0
    var top = 0f
    heights.forEachIndexed { index, height ->
        if (y < top + height) return index
        top += height
    }
    return heights.lastIndex
}

/** 逐行行高的累加：第 [row] 行的顶部在内容坐标系里的 y（像素）。 */
internal fun dataGridRowTop(row: Int, heights: List<Float>): Float {
    var top = 0f
    for (index in 0 until row.coerceAtMost(heights.size)) top += heights[index]
    return top
}

/**
 * 选中区域导成 TSV：行用 `\n`、列用 `\t`。
 *
 * 选 TSV 而不是 CSV，是因为它的去处基本都是「贴进表格软件 / 另一张数据库的表」——`\t` 在那些地方
 * 天然分列，而 CSV 的逗号在数据里本来就常见，得靠自己再加一层转义规则。
 *
 * `NULL` 与空串都写成空字段：TSV 分不出这两者（与 DataGrip 的复制一致）。要保留 `NULL` 语义
 * 得走「复制为 INSERT」，那是调用方在右键菜单里另给的一项。
 */
internal fun dataGridTsv(selection: DataGridSelection, rows: List<List<String?>>): String =
    (selection.firstRow..selection.lastRow).joinToString("\n") { row ->
        (selection.firstColumn..selection.lastColumn).joinToString("\t") { column ->
            rows.getOrNull(row)?.getOrNull(column).orEmpty()
        }
    }

/**
 * 多块选区取成「若干行 × 若干格」：**按行归并**——同一行上被选中的格子并成一行。
 *
 * 起初是「各块各占一段，块间空一行」。粘出去才发现全不对：同一行的两段（第 1 列与第 3 列）
 * 被断成了两行，块之间还凭空多一道空行——而用户要的只是「我选中的那些格子」，行还是那些行。
 *
 * 列只保留**被选中的那些**，中间没选中的列不占位（贴出去会少一列，但那是用户自己没选）。
 */
private fun dataGridSelectedRows(
    selection: DataGridSelections,
    rows: List<List<String?>>,
): List<List<String>> {
    // 行 → 这一行里被选中的列（用 `Set`，重叠的块会自动合并，同一格不会输出两遍）。
    val byRow = mutableMapOf<Int, MutableSet<Int>>()
    selection.blocks.forEach { block ->
        for (row in block.firstRow..block.lastRow) {
            val columns = byRow.getOrPut(row) { mutableSetOf() }
            for (column in block.firstColumn..block.lastColumn) columns += column
        }
    }
    return byRow.entries
        .sortedBy { it.key }
        .map { (row, columns) ->
            columns.sorted().map { column -> rows.getOrNull(row)?.getOrNull(column).orEmpty() }
        }
}

/** 多块选区导成 TSV（制表符分隔）——框选一片格子时用，贴进另一张表列才对得上。 */
internal fun dataGridTsv(selection: DataGridSelections, rows: List<List<String?>>): String =
    dataGridSelectedRows(selection, rows)
        .joinToString("\n") { it.joinToString("\t") }

/** 多块选区导成 CSV（逗号分隔，字段按 RFC 4180 转义）——整行选中时用。 */
internal fun dataGridCsv(selection: DataGridSelections, rows: List<List<String?>>): String =
    dataGridSelectedRows(selection, rows)
        .joinToString("\n") { line -> line.joinToString(",") { dataGridCsvField(it) } }

/** 字段转义：值里含逗号、引号或换行时用双引号包住，内部的引号翻倍（RFC 4180）。 */
private fun dataGridCsvField(value: String): String =
    if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }

/**
 * 复制时用哪种格式：**整行选中 → CSV，其余 → TSV**。
 *
 * 分两条路是因为两种意图的去处不同：整行是一批「记录」，要粘进 CSV、发给别人、喂给脚本；框选的
 * 一片格子是「一块数据」，要贴进另一张表，那里制表符才对得上列。
 */
internal fun dataGridClipboardText(selection: DataGridSelections, rows: List<List<String?>>): String =
    if (selection.coversWholeRows(rows.firstOrNull()?.size ?: 0)) {
        dataGridCsv(selection, rows)
    } else {
        dataGridTsv(selection, rows)
    }

/**
 * 数据行高的默认值，也是逐行行高的**下限**。命中测试、键盘翻页、滚动换算都从它起步。
 *
 * 只让往上调、不让往下调：比默认还矮，字就贴到格线上了，而「看得更清」才是调行高的目的。
 * 上不封顶——想多高就多高，真拖过分了使用者自己会往回拖。
 */
private val DataGridDefaultRowHeight = 28.dp

/** 行号格下缘那条行高热区的高度：够抓住，又不至于盖住序号本身。 */
private val DataGridRowResizeHandleHeight = 5.dp

/**
 * 单元格里一行文字占的高度（12sp 的字，约 1.4 倍行距）。
 *
 * 「这一行够放几行文字」由它除出来：**多行文本**得按这个数告诉 `Text` 显示到第几行为止，
 * 否则要么只显示一行（行高就白调了），要么溢出格子画到隔壁去。
 */
private val DataGridCellLineHeight = 17.dp

/** 单元格上下留的边距。算「能放几行」时要从行高里先扣掉它，不然最后一行会被切一半。 */
private val DataGridCellVerticalPadding = 2.dp

/** 正在拖什么——决定拖动期间光标钉成哪一种。 */
private enum class DataGridDrag { None, ColumnWidth, RowHeight }

/** 行号列的两侧留白：数字右对齐（右边 10dp），左边再留一点呼吸。 */
private val DataGridRowNumberPadding = 18.dp

/** 一个数字占的宽度。行号是 11sp 的等宽字体，按 7dp 估——估宽无非多留白，估窄会把序号截掉。 */
private val DataGridRowNumberDigitWidth = 7.dp

/**
 * 行号列的宽度：按**这一页有多少行**算，够写下最大的那个序号就行。
 *
 * 原来写死 56dp：一张只有十来行的表上，序号占一两位，列却占了小半列的位置。
 */
internal fun dataGridRowNumberWidth(rowCount: Int): Dp =
    DataGridRowNumberPadding + DataGridRowNumberDigitWidth * rowCount.coerceAtLeast(0).toString().length

/** 列宽拖拽把手的热区宽度：够抓，又不至于盖住列头文字。 */
private val DataGridResizeHandleWidth = 6.dp

/** 列头里排序按钮的边长。列头高 28dp，按钮 20dp——上下各留 4dp，点起来不挤。 */
private val DataGridSortButtonSize = 20.dp

@Composable
private fun DataGridHeader(
    columns: List<DataGridColumn>,
    widthOf: (DataGridColumn) -> Dp,
    rowNumberWidth: Dp,
    sortKey: String?,
    sortAscending: Boolean,
    selection: DataGridSelections?,
    rowCount: Int,
    onSelectColumn: (Int) -> Unit,
    onSort: ((key: String?, ascending: Boolean) -> Unit)?,
    onResize: (column: DataGridColumn, width: Dp) -> Unit,
    onColumnWidthDragChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // 拖动回调跑在组合之外，密度必须先取出来——在回调里读 `LocalDensity.current` 会直接抛。
    val density = LocalDensity.current
    Row(
        Modifier
            .fillMaxWidth()
            // 表头不跟着任何一行的行高走：它是表头，不是数据行。
            .height(DataGridDefaultRowHeight)
            .background(colors.onSurface.copy(alpha = 0.06f))
            // 表头下沿这条线比数据行重一档：它是「表头」与「数据」的分界，也是整张表的顶边。
            .dataGridRowDivider(dataGridHeaderLineColor(colors)),
    ) {
        if (rowNumberWidth > 0.dp) {
            // 行号列表头留空：那一列的数字本身就是标签；竖线照画，不然这一列的边界断一截。
            Box(
                Modifier
                    .width(rowNumberWidth)
                    .fillMaxHeight()
                    .dataGridColumnDivider(dataGridLineColor(colors)),
            )
        }
        // 每个列头单独 `key`：悬停与列宽都跟着**列**走，换表换列时不会串到隔壁去。
        columns.forEachIndexed { index, column ->
            key(column.key) {
                val width = widthOf(column)
                val sorted = column.key == sortKey
                val columnSelected = selection?.coversWholeColumn(index, rowCount) == true
                Box(
                    Modifier
                        .width(width)
                        .fillMaxHeight()
                        .dataGridColumnDivider(dataGridLineColor(colors)),
                ) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            // 整列选中时列头跟着亮：不然「选中的是哪一列」在表头这一行看不出来。
                            .background(
                                if (columnSelected) colors.primary.copy(alpha = 0.12f) else Color.Transparent
                            )
                            .hoverable(remember { MutableInteractionSource() })
                            // 点列头 = 选中这一列。排序不在这里——它在右边那个按钮上。
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                            ) { onSelectColumn(index) }
                            .padding(start = 8.dp, end = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = column.title,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (sorted) colors.primary else colors.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (onSort != null) {
                            DataGridSortButton(
                                sorted = sorted,
                                ascending = sortAscending,
                                // 三态：没排 → 升 → 降 → 撤掉。第三下必须能退出排序——否则
                                // 「点两下就回不去」，想取消只能换一张表。
                                onClick = {
                                    val next = when {
                                        !sorted -> column.key to true
                                        sortAscending -> column.key to false
                                        else -> null to true
                                    }
                                    onSort(next.first, next.second)
                                },
                            )
                        }
                    }
                    // 列宽把手压在右边缘：拖它只改宽度，既不选中列也不排序。
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .width(DataGridResizeHandleWidth)
                            .fillMaxHeight()
                            .pointerHoverIcon(HorizontalResizePointerIcon)
                            .draggable(
                                orientation = Orientation.Horizontal,
                                // 拖起来 / 松手都报一声：拖动期间由表格那一层把光标钉住，
                                // 免得一指头拖出这 6dp 宽的热区光标就变回箭头。
                                onDragStarted = { onColumnWidthDragChange(true) },
                                onDragStopped = { onColumnWidthDragChange(false) },
                                state = rememberDraggableState { delta ->
                                    val next = width + with(density) { delta.toDp() }
                                    onResize(column, next.coerceAtLeast(column.minWidth))
                                },
                            ),
                    )
                }
            }
        }
    }
}

/**
 * 列头右侧的排序按钮。
 *
 * 与「点列头」分开是刻意的：点列头说的是「我要这一列」（选中），点这里才是「按这一列排」。
 * 合成一个手势的话，想选中一列就得先把表排一遍——而对数据库来说，排序是一次重新查库。
 *
 * 没排时画淡色的双箭头（「这一列可以排」），排上之后换成实色的单向箭头并指出方向。
 */
@Composable
private fun DataGridSortButton(
    sorted: Boolean,
    ascending: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .size(DataGridSortButtonSize)
            .clip(RoundedCornerShape(4.dp))
            .background(if (hovered) colors.onSurface.copy(alpha = 0.10f) else Color.Transparent)
            .hoverable(interaction)
            .clickable(indication = null, interactionSource = interaction, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        ClipperIcon(
            // 没排时画上下相对的实心三角，说的是「这一列可以排」；画一个淡色的 ↑ 会被读成
            // 「已经在升序，只是颜色淡」——而这两件事在界面上必须分得开。
            kind = when {
                !sorted -> ClipperIconKind.SORT
                ascending -> ClipperIconKind.ARROW_UP
                else -> ClipperIconKind.ARROW_DOWN
            },
            size = 13.dp,
            tint = if (sorted) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.38f),
        )
    }
}

@Composable
private fun DataGridRow(
    index: Int,
    values: List<String?>,
    columns: List<DataGridColumn>,
    widthOf: (DataGridColumn) -> Dp,
    rowNumberWidth: Dp,
    rowHeight: Dp,
    onRowHeightChange: (Dp) -> Unit,
    onRowHeightDragChange: (Boolean) -> Unit,
    selection: DataGridSelections?,
) {
    val colors = MaterialTheme.colorScheme
    // 拖动回调跑在组合之外，密度必须先取出来——在回调里读 `LocalDensity.current` 会直接抛。
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // 行号列高亮：只要这一行落进**任一**块的行范围就亮（不要求整行都被选中）。
    val rowSelected = selection?.blocks?.any { index in it.firstRow..it.lastRow } == true

    Row(
        Modifier
            .fillMaxWidth()
            .height(rowHeight)
            .hoverable(interaction)
            .background(
                when {
                    // 整行都被选中时铺一层底色，「这一行」比「这几个格」更该被看见。
                    selection?.coversWholeRow(index, columns.size) == true -> colors.primary.copy(alpha = 0.10f)
                    hovered -> colors.onSurface.copy(alpha = 0.04f)
                    else -> Color.Transparent
                }
            )
            .dataGridRowDivider(dataGridLineColor(colors)),
    ) {
        if (rowNumberWidth > 0.dp) {
            Box(
                Modifier
                    .width(rowNumberWidth)
                    .fillMaxHeight()
                    // 没被选中时也铺一层极浅的灰：这是 Excel 那类表格给行号栏的做法——它一眼
                    // 看着就不像「第一列数据」，点下去会选中整行这件事也就不再来得突然。
                    .background(
                        if (rowSelected) {
                            colors.primary.copy(alpha = 0.08f)
                        } else {
                            colors.onSurface.copy(alpha = 0.05f)
                        }
                    )
                    .dataGridColumnDivider(dataGridLineColor(colors)),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text(
                    text = (index + 1).toString(),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.hintColor,
                    modifier = Modifier.padding(end = 10.dp),
                )
                // 行号格下缘压着一条热区：拖它改**这一行**的高度，与「拖列头右缘改列宽」是
                // 同一套手势语言（Excel 拖行号的上下边界也是这么做的）。
                //
                // 只占行号列这一小块、不横跨整行：横跨过去会把数据区的框选手势挡掉。点它不拖也
                // 什么也不做——事件照旧传到数据区，于是「点行号选中整行」保持原样。
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(DataGridRowResizeHandleHeight)
                        .pointerHoverIcon(VerticalResizePointerIcon)
                        // 按下就**消费**掉这一次按下。数据区那层手势（框选、点行号选整行）在更外层，
                        // 它等的是「未被消费的按下」；不在这里先截住，它会在同一帧把这手势接管过去，
                        // 而 `draggable` 之后再也等不到「超过滑动阈值的那次移动」——表现正是
                        // 「光标变了，但拖不动」。（`draggable` 自己用 `requireUnconsumed = false`
                        // 取按下，所以这里消费掉不影响它。）
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false).consume()
                            }
                        }
                        .draggable(
                            orientation = Orientation.Vertical,
                            // 拖起来 / 松手都报一声：拖动期间指针多半已经离开这条 5dp 的热区，
                            // 那时由表格那一层接手显示 ↕。
                            onDragStarted = { onRowHeightDragChange(true) },
                            onDragStopped = { onRowHeightDragChange(false) },
                            state = rememberDraggableState { delta ->
                                // 只夹下限：默认行高就是最矮的那一档，往上不封顶。
                                onRowHeightChange(
                                    (rowHeight + with(density) { delta.toDp() })
                                        .coerceAtLeast(DataGridDefaultRowHeight)
                                )
                            },
                        ),
                )
            }
        }
        // 这一行够放几行文字：先扣掉上下边距，再除以单行高度。至少 1 行——再矮也得显示点什么。
        val cellMaxLines = ((rowHeight - DataGridCellVerticalPadding * 2) / DataGridCellLineHeight)
            .toInt()
            .coerceAtLeast(1)
        columns.forEachIndexed { columnIndex, column ->
            val selected = selection?.contains(index, columnIndex) == true
            Box(
                Modifier
                    .width(widthOf(column))
                    .fillMaxHeight()
                    .background(if (selected) colors.primary.copy(alpha = 0.18f) else Color.Transparent)
                    .dataGridColumnDivider(dataGridLineColor(colors)),
                // 单行时垂直居中（本来就是它该有的样子，视觉最稳），多行时从**顶**上排：居中会
                // 让被截断的那一行上下都不靠，顶部对齐则把截断固定在下方，扫一列时更好读。
                contentAlignment = if (cellMaxLines > 1) Alignment.TopStart else Alignment.CenterStart,
            ) {
                DataGridCell(
                    value = values.getOrNull(columnIndex),
                    maxLines = cellMaxLines,
                )
            }
        }
    }
}

/**
 * 一个单元格。
 *
 * `NULL` 用斜体灰、空串用普通色——**这两者在数据上完全不同**（一个是「没有值」，一个是「值是空
 * 字符串」），界面上不分开，看数据时最容易看错。
 */
@Composable
private fun DataGridCell(value: String?, maxLines: Int) {
    val isNull = value == null
    Text(
        text = if (isNull) "NULL" else value,
        fontSize = 12.sp,
        fontFamily = FontFamily.Monospace,
        fontStyle = if (isNull) FontStyle.Italic else FontStyle.Normal,
        // **只按换行符换行，不按宽度自动折行**：格子里的换行应当由数据本身决定（多行文本、
        // 日志、JSON 都是这个形状），宽度折行会把「一行数据」切得看不出原样。超宽的那一段在
        // 末尾打省略号即可。
        softWrap = false,
        // 能显示几段由行高决定（`DataGridRow` 算好传进来），放不下才省略。
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        color = if (isNull) {
            MaterialTheme.hintColor
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.padding(
            horizontal = 8.dp,
            vertical = DataGridCellVerticalPadding,
        ),
    )
}

/** 数据格之间的分隔线颜色。压得很淡：它的用途是让人「看出这是一张表」，不该抢数据的注意力。 */
private fun dataGridLineColor(colors: ColorScheme): Color = colors.onSurface.copy(alpha = 0.10f)

/** 表头下面那条横线的颜色。比数据格之间的线重一档，「表头」与「数据」的分界要一眼看得出来。 */
private fun dataGridHeaderLineColor(colors: ColorScheme): Color = colors.onSurface.copy(alpha = 0.22f)

/**
 * 单元格右缘的竖线。
 *
 * 只画**一条**，而不是四边描一圈：相邻两格本来共用同一条边，描一圈等于在同一条线上叠两次，
 * 明明声明的是 1dp，看上去却比横线粗——一张网格线粗细不一的表，比没有线还难看。
 *
 * 画在背景之后（调用处一律接在 `background(...)` 后面），因此选中高亮铺上来时线仍压在最上层。
 */
private fun Modifier.dataGridColumnDivider(color: Color): Modifier =
    drawBehind {
        val stroke = 1.dp.toPx()
        val x = size.width - stroke / 2f
        drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
    }

/** 每一行下缘的横线。表头那一行也用它，只是换一个更重的颜色。 */
private fun Modifier.dataGridRowDivider(color: Color): Modifier =
    drawBehind {
        val stroke = 1.dp.toPx()
        val y = size.height - stroke / 2f
        drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
    }
