package com.qcmian.clipper.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.theme.hintColor

/**
 * 树里的一个节点。
 *
 * [children] 是**已经拿到**的子节点；还没加载时留空但把 [expandable] 置真——控件据此照画展开
 * 箭头，点开后由调用方去取子节点、再带着新的 [children] 重组。控件因此完全不管「子节点从哪来」，
 * 文件系统、数据库、任何层级数据都能喂进来（这正是它被放进「核心组件」而不是某个工具里的理由）。
 *
 * [key] 必须在整棵树里唯一：它同时是展开状态的标识与选中态的标识。
 * 约定用「路径式」的写法（`table:clips:column:id`），父子关系因此一眼看得出。
 */
@Immutable
class TreeNode(
    val key: String,
    val label: String,
    /** 名字右侧的淡色注记（数据库工具拿它显示列类型）。没有时为 `null`。 */
    val detail: String? = null,
    val icon: ClipperIconKind? = null,
    /** 还有没加载出来的子节点时为真：控制展开箭头在子节点为空时也照画。 */
    val expandable: Boolean = false,
    val children: List<TreeNode> = emptyList(),
)

/**
 * 通用树：文件浏览器 / 数据库对象树共用一套外观与交互。
 *
 * 几条与「状态上提」有关的约定，都是刻意的：
 *
 *  - **展开状态不外存**：哪些节点展开了由调用方的 `expandedKeys` 说了算，控件只是画出来。于是
 *    「展开时去加载子节点」这件事天然落在调用方手里，控件不必持有任何异步状态。
 *  - **选中状态同理**：`selectedKey` 由调用方给，控件不自己记。
 *  - **单击 / 双击分开**：单击只 [onSelect]（选中），双击才 [onActivate]（打开）。树里点一下常常
 *    只是想扫一眼有哪些条目，而「打开」往往要真去取数据——两者合成一件事就必然误触。
 *
 * 尺寸与滚动：
 *
 *  - **两个方向都能滚**。名字**不做省略**：该多宽就多宽，放不下靠横向滚动看。层级深、名字长的
 *    树（数据库的列名、文件路径）里，省略号会同时吃掉「这是哪个」和「后缀是什么」两截信息，
 *    比多拖一下滚动条更难受。
 *  - 因此这里用 `Column` 而不是 `LazyColumn`：后者必须有界宽，与「内容自己撑开、外层横向滚」
 *    直接冲突。代价是纵向不再虚拟化——对树这种规模（几十到几百行）不成问题。
 *  - 短行也会**铺满视口宽**（见 [TreeRow] 的 `minWidth`），否则选中高亮只有名字那么长。
 *
 * 交互：
 *
 *  - **右键**：指针落在哪一行就弹哪一行的菜单（条目由 [menuEntries] 给，长相与摆位归
 *    [AppContextMenu]）。键位提示写什么由调用方决定，但要与下面这套键盘操作对得上。
 *  - **键盘**：点一下树就把焦点收过来，此后 `↑` `↓` 走行、`→` 展开、`←` 折叠、`Enter` 打开、
 *    `⌘C`（或 `Ctrl+C`）[onCopy]。
 *
 * @param nodes 根节点列表（已按需要的顺序排好）。
 * @param expandedKeys 处于展开态的节点 [TreeNode.key] 集合。
 * @param onToggle 展开箭头被点了，或键盘按了 `→` / `←`。**只有带子节点（或 [TreeNode.expandable]）
 *   的节点会触发**。
 * @param selectedKey 当前选中节点的 key；`null` 表示没有选中。
 * @param onSelect 单击某一行（键盘上下移动也走它）。
 * @param onActivate 双击某一行、或按 `Enter`——「打开」它。为 `null` 时双击与单击同义。
 * @param onCopy `⌘C` / `Ctrl+C` 时复制什么。为 `null` 时这一组合不做处理，键照常往下传。
 * @param menuEntries 右键某一行时菜单里放什么；为 `null` 表示这棵树不提供右键菜单。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TreeView(
    nodes: List<TreeNode>,
    expandedKeys: Set<String>,
    onToggle: (TreeNode) -> Unit,
    selectedKey: String?,
    onSelect: (TreeNode) -> Unit,
    onActivate: ((TreeNode) -> Unit)? = null,
    onCopy: ((TreeNode) -> Unit)? = null,
    menuEntries: ((TreeNode) -> List<AppContextMenuEntry>)? = null,
    modifier: Modifier = Modifier,
) {
    // 拍平只跟「节点」与「展开了哪些」有关，与选中无关；选中变化时因此不必重排整棵树。
    val rows = remember(nodes, expandedKeys) { flattenTree(nodes, expandedKeys) }
    val vScroll = rememberScrollState()
    val hScroll = rememberScrollState()
    // 视口宽度：短行也要铺满它，选中高亮才是一条通栏，而不是只有名字那么长。
    var viewportWidth by remember { mutableStateOf(0.dp) }
    val density = LocalDensity.current
    val focusRequester = remember { FocusRequester() }
    // 手势与按键都跑在非重组的协程里，读到的必须是**当前**那份行表，不能是建协程时捕进去的。
    val currentRows = rememberUpdatedState(rows)

    // 右键菜单：指针落点（相对本控件）＋ 本控件在窗口里的原点——菜单要按窗口收边，两者缺一不可。
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    var menuNode by remember { mutableStateOf<TreeNode?>(null) }
    var originInWindow by remember { mutableStateOf(Offset.Zero) }

    fun dismissMenu() {
        menuNode = null
        menuAt = null
    }

    /** 选中项在可见行里的下标；没有选中、或选中项被折叠藏起来时返回 -1。 */
    fun selectedIndex(): Int = currentRows.value.indexOfFirst { it.node.key == selectedKey }

    /** 上下移动选中：从当前项走 [delta] 行，到头就停在原地。没有选中时从两头进场。 */
    fun moveSelection(delta: Int) {
        val list = currentRows.value
        if (list.isEmpty()) return
        val current = selectedIndex()
        val next = if (current < 0) {
            if (delta > 0) 0 else list.lastIndex
        } else {
            (current + delta).coerceIn(0, list.lastIndex)
        }
        list.getOrNull(next)?.let { onSelect(it.node) }
    }

    fun handleKey(event: KeyEvent): Boolean {
        if (event.type != KeyEventType.KeyDown) return false
        val node = currentRows.value.getOrNull(selectedIndex())?.node
        return when {
            event.key == Key.DirectionDown -> {
                moveSelection(1)
                true
            }

            event.key == Key.DirectionUp -> {
                moveSelection(-1)
                true
            }

            // → 展开、← 折叠。不可展开 / 没展开的节点上按了等于没按，但仍**吃掉**按键：
            // 放它过去会让焦点跑出树（`→` 在别处常被读成「往右移焦点」）。
            event.key == Key.DirectionRight -> {
                if (node != null && node.key !in expandedKeys) onToggle(node)
                true
            }

            event.key == Key.DirectionLeft -> {
                if (node != null && node.key in expandedKeys) onToggle(node)
                true
            }

            event.key == Key.Enter || event.key == Key.NumPadEnter -> {
                if (node != null) onActivate?.invoke(node)
                true
            }

            (event.isMetaPressed || event.isCtrlPressed) && event.key == Key.C -> {
                if (node != null && onCopy != null) {
                    onCopy(node)
                    true
                } else {
                    false
                }
            }

            else -> false
        }
    }

    Box(
        modifier
            // 原点常驻量着：等菜单打开那一刻才量会晚一帧，第一帧会摆错位置再跳一下。
            .onGloballyPositioned { originInWindow = it.positionInWindow() }
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent(::handleKey)
            .pointerInput(Unit) {
                val rowHeight = TreeRowHeight.toPx()
                awaitPointerEventScope {
                    while (true) {
                        // `Initial` 那一趟先看：把右键从下层的点击处理里截下来（见 `TreeNode` 的说明）。
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type != PointerEventType.Press) continue
                        // 点一下就把焦点收过来，键盘导航才能接着用。
                        focusRequester.requestFocus()
                        if (menuEntries == null) continue
                        if (!event.buttons.isSecondaryPressed) continue
                        val change = event.changes.firstOrNull() ?: continue
                        change.consume()
                        // 指针落在哪一行：内容坐标 = 指针 y（相对本控件）＋ 已经滚过的距离，再按行高折算。
                        val index = ((change.position.y + vScroll.value) / rowHeight).toInt()
                        val node = currentRows.value.getOrNull(index)?.node ?: continue
                        menuNode = node
                        menuAt = change.position
                    }
                }
            }
            .onSizeChanged { viewportWidth = with(density) { it.width.toDp() } },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .horizontalScroll(hScroll)
                .verticalScroll(vScroll)
                // 两条滚动条都是 overlay，这里给它们各自让出位置，免得最后一行被滑块压住。
                .padding(
                    end = VerticalScrollbarWidth + TreeScrollbarGap,
                    bottom = HorizontalScrollbarHeight + TreeScrollbarGap,
                ),
        ) {
            rows.forEach { row ->
                TreeRow(
                    row = row,
                    expanded = row.node.key in expandedKeys,
                    selected = row.node.key == selectedKey,
                    minWidth = viewportWidth,
                    onToggle = { onToggle(row.node) },
                    onSelect = { onSelect(row.node) },
                    onActivate = onActivate?.let { activate -> { activate(row.node) } },
                )
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

        menuNode?.let { node ->
            menuAt?.let { at ->
                AppContextMenu(
                    at = at,
                    anchor = originInWindow,
                    onDismiss = ::dismissMenu,
                    // 点完一项就收起来。`AppContextMenu` 把「收不收」留给调用方（主面板那份点完还要
                    // 顺手清选中态），而树上的每一项都只是「做一件事」——在这里统一包一层，调用方
                    // 不必逐项自己收，漏掉一个就是「点了没反应」。
                    entries = menuEntries?.invoke(node).orEmpty().map { entry ->
                        if (entry is AppContextMenuAction) {
                            AppContextMenuAction(
                                title = entry.title,
                                shortcut = entry.shortcut,
                                enabled = entry.enabled,
                            ) {
                                dismissMenu()
                                entry.onClick()
                            }
                        } else {
                            entry
                        }
                    },
                )
            }
        }
    }
}

/** 一行树节点的排版数据：节点本身 + 它在树里的层级（0 基）。 */
@Immutable
internal class TreeRowData(val node: TreeNode, val depth: Int)

/**
 * 把树按「哪些节点展开着」拍成从上到下的一串可见行。
 *
 * 抽成纯函数是为了能直接单测：折叠时把整枝藏起来、展开时按序插回来，这层逻辑出错在界面上
 * 只是「少了几行」，肉眼很难发现，只有对着断言才查得清。
 */
internal fun flattenTree(nodes: List<TreeNode>, expandedKeys: Set<String>): List<TreeRowData> {
    val rows = ArrayList<TreeRowData>()
    fun visit(list: List<TreeNode>, depth: Int) {
        list.forEach { node ->
            rows += TreeRowData(node, depth)
            // 只有「展开了且真拿到了子节点」才往下走：展开了但还在加载时，这一枝暂时没有内容。
            if (node.key in expandedKeys && node.children.isNotEmpty()) {
                visit(node.children, depth + 1)
            }
        }
    }
    visit(nodes, 0)
    return rows
}

/** 每一层缩进多少。 */
private val TreeIndent = 14.dp

/** 树行高度。右键时按它换算「指针落在第几行」，改这个值不必改别处。 */
private val TreeRowHeight = 24.dp

/** 正文与滚动条之间的空隙（本地常量：核心组件的滚动条另有自己的间距口径）。 */
private val TreeScrollbarGap = 5.dp

@Composable
private fun TreeRow(
    row: TreeRowData,
    expanded: Boolean,
    selected: Boolean,
    minWidth: Dp,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
    onActivate: (() -> Unit)?,
) {
    val colors = MaterialTheme.colorScheme
    val node = row.node
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val shape = RoundedCornerShape(4.dp)
    val hasChildren = node.children.isNotEmpty() || node.expandable

    Row(
        modifier = Modifier
            // 至少铺满视口：短行的高亮才不会只有名字那么长。长行按内容撑出去，由外层横向滚。
            .widthIn(min = minWidth)
            .height(TreeRowHeight)
            .clip(shape)
            .background(
                when {
                    selected -> colors.primary.copy(alpha = 0.14f)
                    hovered -> colors.onSurface.copy(alpha = 0.06f)
                    else -> Color.Transparent
                }
            )
            .hoverable(interaction)
            // 单击选中、双击打开：两件事在这一行上并存，交给 `combinedClickable` 分派。
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onSelect,
                onDoubleClick = onActivate,
            )
            .padding(start = 4.dp + TreeIndent * row.depth, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 展开箭头占一格固定宽度：没有子节点时也占着，同层的名字才会左缘对齐。
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            if (hasChildren) {
                // 折叠时把向下箭头转成向右。旋转比再画一套 `chevron.right` 省事，形状也必然一致。
                ClipperIcon(
                    kind = ClipperIconKind.CHEVRON_DOWN,
                    size = 13.dp,
                    tint = if (selected) colors.primary else colors.onSurfaceVariant,
                    modifier = Modifier
                        .rotate(if (expanded) 0f else -90f)
                        .clickable(onClick = onToggle),
                )
            }
        }
        node.icon?.let { icon ->
            ClipperIcon(
                kind = icon,
                size = 15.dp,
                tint = if (selected) colors.primary else colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = node.label,
            fontSize = 13.sp,
            maxLines = 1,
            // **不省略、也不 `weight`**：名字该多宽就多宽，放不下靠树的横向滚动看（见 `TreeView`）。
            color = if (selected) colors.primary else colors.onSurface,
        )
        node.detail?.let { detail ->
            Spacer(Modifier.width(8.dp))
            Text(
                text = detail,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                color = MaterialTheme.hintColor,
            )
        }
    }
}
