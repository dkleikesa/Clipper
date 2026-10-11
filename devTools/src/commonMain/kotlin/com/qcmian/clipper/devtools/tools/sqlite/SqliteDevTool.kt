package com.qcmian.clipper.devtools.tools.sqlite

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.ui.HorizontalResizePointerIcon
import com.qcmian.clipper.core.ui.code.DevToolCodeField
import com.qcmian.clipper.core.ui.code.scanPlain
import com.qcmian.clipper.core.ui.components.AppContextMenuAction
import com.qcmian.clipper.core.ui.components.AppContextMenuDivider
import com.qcmian.clipper.core.ui.components.AppContextMenuEntry
import com.qcmian.clipper.core.ui.components.DataGrid
import com.qcmian.clipper.core.ui.components.DataGridColumn
import com.qcmian.clipper.core.ui.components.TreeView
import com.qcmian.clipper.core.ui.components.TreeNode
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.devtools.api.DevTool
import com.qcmian.clipper.devtools.api.DevToolGroup
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.DevToolMetadata
import com.qcmian.clipper.devtools.api.writeTextFile
import com.qcmian.clipper.devtools.api.writeTextFileChunks
import com.qcmian.clipper.devtools.ui.components.DevToolButton
import com.qcmian.clipper.devtools.ui.components.DevToolFieldAction
import com.qcmian.clipper.devtools.ui.components.DevToolFileField
import com.qcmian.clipper.devtools.ui.components.DevToolResultActions
import com.qcmian.clipper.devtools.ui.components.DevToolSegmentedControl
import com.qcmian.clipper.devtools.ui.components.DevToolSingleLineField
import com.qcmian.clipper.devtools.ui.components.DevToolToggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * SQLite 查看 / 编辑工具。
 *
 * 面向的场景是「手上有一个 `.db` 文件，想看一眼里面有什么」，因此与其它工具有两点先定的不同：
 *
 *  - **默认只读**。工具默认打开的是用户的真实库，误改的代价远大于「不能改」。要写必须由用户
 *    显式关掉「只读」开关，那时才以读写模式重开一次连接（SQL 页签里的写语句因此才生效）。
 *  - **通用输入，不参与自动选中**。`.db` 是二进制，探测器（只看文本）认不出它；打开工具时若
 *    剪贴板条目恰好是个 `.db` 文件就自动载入，否则由用户在工具栏里自行打开一个文件。
 *
 * 左侧是一棵**懒加载**的对象树（用 `TreeView`，见 `SqliteTree.kt` 与 `core/ui/components`）：
 * 展开哪张表才去查哪张表的列 / 键 / 索引。右侧分「数据 / 结构 / SQL」三块。平台那一层（JDBC）
 * 收在 [SqliteSession] 后面，见 `SqliteEngine.jvm.kt`。
 */
internal object SqliteDevTool : DevTool {

    override val metadata: DevToolMetadata = DevToolMetadata(
        id = "sqlite",
        name = "SQLite 数据库",
        description = "打开 .db / .sqlite 文件，浏览对象树与数据、执行 SQL。默认只读，避免误改。",
        group = DevToolGroup.OTHER,
        icon = ClipperIconKind.SQL,
    )

    @Composable
    override fun Content(input: ClipItem?, host: DevToolHost) {
        val scope = rememberCoroutineScope()

        var session by remember { mutableStateOf<SqliteSession?>(null) }
        // `path` 是**已经打开**的库（树与状态栏读它）；`pathInput` 是输入框里正在编辑的那一份。
        // 两者分开，改输入框才不会让树按一个还没打开的路径重建。
        var path by remember { mutableStateOf("") }
        var pathInput by remember { mutableStateOf("") }
        var passphrase by remember { mutableStateOf("") }
        // **打开当前这个库时实际用的口令**。它是「刷新 / 切只读」重开时唯一该带的口令来源；
        // 打开别的库一律不带（见 `open` 里的说明）。
        var openedPassphrase by remember { mutableStateOf<String?>(null) }
        var needsPassphrase by remember { mutableStateOf(false) }
        var readOnly by remember { mutableStateOf(true) }
        var objects by remember { mutableStateOf<List<SqliteObject>>(emptyList()) }
        var openError by remember { mutableStateOf<String?>(null) }

        // 树的展开状态与已加载的子节点都由这里持有：控件本身不记状态（见 `TreeView` 的说明）。
        var expandedKeys by remember { mutableStateOf(SqliteDefaultExpandedKeys) }
        var childrenCache by remember { mutableStateOf<Map<String, List<TreeNode>>>(emptyMap()) }
        var selectedNodeKey by remember { mutableStateOf<String?>(null) }
        // 左树宽度：由中间那条分隔条拖动改（见 `TreeSplitter`）。
        var treeWidth by remember { mutableStateOf(260.dp) }
        // 排序与筛选都属于「当前这张表」，且都要下推给 SQLite——所以状态在工具这边，不在
        // `DataGrid` 里（那个控件只负责把箭头画对、把点击转成回调）。
        var sortColumn by remember { mutableStateOf<String?>(null) }
        var sortAscending by remember { mutableStateOf(true) }
        var filter by remember { mutableStateOf<SqliteFilter?>(null) }

        var selected by remember { mutableStateOf<SqliteObject?>(null) }
        var pageRows by remember { mutableStateOf<SqliteRows?>(null) }
        var rowCount by remember { mutableStateOf<Long?>(null) }
        var pageIndex by remember { mutableStateOf(0) }

        var sqlText by remember { mutableStateOf("") }
        var sqlRows by remember { mutableStateOf<SqliteRows?>(null) }
        var sqlError by remember { mutableStateOf<String?>(null) }

        var busy by remember { mutableStateOf(false) }
        var statusText by remember { mutableStateOf<String?>(null) }
        // 口令框一出现就自动聚焦，用户可以直接打字；输错一次之后也把焦点送回去（见 `open` 的失败分支）。
        val passphraseFocus = remember { FocusRequester() }
        var passphraseFocusTick by remember { mutableStateOf(0) }

        // 常驻状态栏文案走 `reportStatus`（在副作用里调，不能在组合期间写状态）。
        LaunchedEffect(statusText) { host.reportStatus(statusText) }
        // 在副作用里请求焦点：那时口令框已经挂上，`FocusRequester` 才有落点可指。
        //
        // 还要**等一帧**：口令框是 `needsPassphrase` 变真的那一帧才挂上去的，此刻它尚未完成布局，
        // `requestFocus()` 会静默失败（不抛异常，但焦点没落上去）——表现出来就是「框出来了却
        // 打不了字，得先用鼠标点一下」。
        LaunchedEffect(needsPassphrase, passphraseFocusTick) {
            if (!needsPassphrase) return@LaunchedEffect
            withFrameNanos { }
            passphraseFocus.requestFocus()
        }
        // 离开工具时把连接关掉：JDBC 连接是有限资源，靠 GC 收不可靠。
        val latestSession = rememberUpdatedState(session)
        DisposableEffect(Unit) { onDispose { latestSession.value?.close() } }

        /** 关掉当前连接并清空所有与它绑定的状态。 */
        fun resetSession() {
            session?.close()
            session = null
            objects = emptyList()
            expandedKeys = SqliteDefaultExpandedKeys
            childrenCache = emptyMap()
            selectedNodeKey = null
            selected = null
            pageRows = null
            rowCount = null
            pageIndex = 0
            sqlRows = null
            sqlError = null
            statusText = null
            openedPassphrase = null
            sortColumn = null
            sortAscending = true
            filter = null
            // 文件都清掉了，口令框与错误提示没有任何理由留下——它们说的是**上一个文件**。
            needsPassphrase = false
            openError = null
        }

        /** 选中一个对象：读它的第一页数据与总行数。列清单不在这里读——左树展开时才按需取。 */
        fun select(target: SqliteObject) {
            selected = target
            selectedNodeKey = objectKey(target)
            // 排序与筛选属于**上一张表**：换表就清掉，否则会拿着另一张表的列名去 `ORDER BY`。
            sortColumn = null
            sortAscending = true
            filter = null
            // 状态栏给的是**新表**第一页的查询（此刻排序与筛选都已经清空）。
            statusText = sqlitePageSql(
                table = target.name,
                limit = SqlitePageSize,
                offset = 0,
                orderColumn = sortColumn,
                ascending = sortAscending,
                filter = filter,
            )
            pageIndex = 0
            pageRows = null
            rowCount = null
            val current = session ?: return
            scope.launch {
                busy = true
                val result = withContext(Dispatchers.Default) {
                    runCatching {
                        val rows = current.page(target.name, SqlitePageSize, 0)
                        val count = runCatching { current.count(target.name) }.getOrNull()
                        rows to count
                    }
                }
                result.onSuccess { (newRows, count) ->
                    if (selected?.name == target.name) {
                        pageRows = newRows
                        rowCount = count
                    }
                }.onFailure { host.showStatus(it.message ?: "读取失败") }
                busy = false
            }
        }

        /**
         * 翻页 / 换排序 / 换筛选走的都是这条路：**对象没变，只是这一页的内容变了**。
         *
         * 两者都作为参数交给 SQLite（`ORDER BY` / `WHERE`），行数也跟着重数一遍——筛过之后的
         * 「共几行」与不筛时不是一回事。
         */
        fun reload(index: Int) {
            val target = selected ?: return
            val current = session ?: return
            val name = target.name
            pageIndex = index
            // 状态栏给出**这一页实际在查的那条 SQL**：翻到第 5 页、加了筛选、换了排序之后，
            // 「现在到底在查什么」只有这条语句说得清——排序与筛选本来就写在这条语句里。
            statusText = sqlitePageSql(
                table = name,
                limit = SqlitePageSize,
                offset = index * SqlitePageSize,
                orderColumn = sortColumn,
                ascending = sortAscending,
                filter = filter,
            )
            scope.launch {
                busy = true
                val result = withContext(Dispatchers.Default) {
                    runCatching {
                        val rows = current.page(
                            table = name,
                            limit = SqlitePageSize,
                            offset = index * SqlitePageSize,
                            orderColumn = sortColumn,
                            ascending = sortAscending,
                            filter = filter,
                        )
                        val count = runCatching { current.count(name, filter) }.getOrNull()
                        rows to count
                    }
                }
                result.onSuccess { (newRows, count) ->
                    if (selected?.name == name) {
                        pageRows = newRows
                        rowCount = count
                    }
                }.onFailure { host.showStatus(it.message ?: "读取失败") }
                busy = false
            }
        }

        /** 翻页。 */
        fun loadPage(target: Int) {
            if (target < 0) return
            reload(target)
        }

        /**
         * 换了排序（`key` 为 `null` 表示撤掉排序）：回第一页重读——排序变了之后「第 3 页」已经
         * 不是原来那一批行了。
         *
         * 状态栏那行 SQL 由 [reload] 一并更新，`ORDER BY` 就写在语句里，不必再单独说一句
         * 「按 x 升序」。
         */
        fun sortBy(key: String?, ascending: Boolean) {
            sortColumn = key
            sortAscending = ascending
            reload(0)
        }

        /** 换了筛选（`null` 表示清掉）：同样回第一页。 */
        fun filterBy(next: SqliteFilter?) {
            filter = next
            reload(0)
        }

        /**
         * 导出**整张表**为 CSV：按 [SqliteExportPageSize] 一页页读、一页页写。
         *
         * 与结果框上那个保存图标（只存**当前这一页**）不是一回事：这个要跑完整张表。因此它有两条
         * 硬要求——**在后台线程跑**（不能把界面卡住），且**边读边写**（先把几十万行拼成一个字符串
         * 再落盘，文件还没写完内存先炸）。排序与筛选照旧下推，所以导出的就是当前看到的那个视图。
         */
        fun exportCsv() {
            val current = session ?: return
            val target = selected ?: return
            val path = host.pickFileToSave("${target.name}.csv") ?: return
            scope.launch {
                busy = true
                val result = withContext(Dispatchers.Default) {
                    runCatching {
                        var exported = 0L
                        val written = writeTextFileChunks(
                            path = path,
                            chunks = sequence {
                                var offset = 0
                                var headerWritten = false
                                while (true) {
                                    val chunk = current.page(
                                        table = target.name,
                                        limit = SqliteExportPageSize,
                                        offset = offset,
                                        orderColumn = sortColumn,
                                        ascending = sortAscending,
                                        filter = filter,
                                    )
                                    // 表头只写一次，哪怕一页就把整表读完了。
                                    if (!headerWritten) {
                                        yield(chunk.toCsvHeaderLine() + "\n")
                                        headerWritten = true
                                    }
                                    yield(chunk.toCsvBody())
                                    exported += chunk.rows.size
                                    // 没读满一页说明到头了。
                                    if (chunk.rows.size < SqliteExportPageSize) break
                                    offset += SqliteExportPageSize
                                }
                            },
                        )
                        if (!written) error("写不进这个位置：$path")
                        exported
                    }
                }
                result.onSuccess { rows -> host.showStatus("已导出 $rows 行到 $path") }
                    .onFailure { host.showStatus(it.message ?: "导出失败") }
                busy = false
            }
        }

        /** 执行 SQL 页签里的一条语句。 */
        fun runSql() {
            val current = session ?: return
            if (sqlText.isBlank()) return
            scope.launch {
                busy = true
                val result = withContext(Dispatchers.Default) {
                    runCatching { current.query(sqlText, SqliteQueryRowLimit) }
                }
                result.onSuccess {
                    sqlRows = it
                    sqlError = null
                    statusText = "返回 ${it.rows.size} 行" + if (it.truncated) "（已截断）" else ""
                }.onFailure {
                    sqlRows = null
                    sqlError = it.message ?: "执行失败"
                    statusText = null
                }
                busy = false
            }
        }

        /** 展开一张表 / 视图时，去取它的列 / 键 / 索引（只在第一次展开时取）。 */
        fun loadObjectChildren(node: TreeNode) {
            val current = session ?: return
            val name = node.label
            // 视图没有外键与索引；不区分的话会白查两次。
            val isView = node.key.startsWith("obj:${SqliteObjectType.VIEW.name}:")
            scope.launch {
                val children = withContext(Dispatchers.Default) {
                    runCatching {
                        val columns = current.schema(name)?.columns.orEmpty()
                        val keys = if (isView) emptyList() else current.foreignKeys(name)
                        val indexes = if (isView) emptyList() else current.indexes(name)
                        buildObjectChildren(node.key, columns, keys, indexes)
                    }.getOrDefault(emptyList())
                }
                childrenCache = childrenCache + (node.key to children)
            }
        }

        /** 点了展开箭头：折叠已展开的，或展开并（按需）加载子节点。 */
        fun toggleNode(node: TreeNode) {
            if (node.key in expandedKeys) {
                expandedKeys = expandedKeys - node.key
                return
            }
            expandedKeys = expandedKeys + node.key
            // 只有对象节点（表 / 视图）需要临时取数；分组与根节点的子节点早就跟着树一起来了。
            if (node.key.startsWith("obj:") && node.key !in childrenCache) loadObjectChildren(node)
        }

        /**
         * 单击某一行：只把它标成选中，**不碰数据库**。
         *
         * 「选中看一眼」与「把它打开」是两件事：点一下常常只是想扫一眼有哪些表，而打开一张表要
         * 跑一次 count 加一页数据。分成单击 / 双击之后，误触的代价就只是一次高亮。
         */
        fun selectNode(node: TreeNode) {
            selectedNodeKey = node.key
        }

        /** 双击某一行：表 / 视图才真正打开（读结构、第一页数据与行数）。 */
        fun activateNode(node: TreeNode) {
            selectedNodeKey = node.key
            val target = sqliteObjectOf(node.key, objects) ?: return
            if (target.type == SqliteObjectType.TABLE || target.type == SqliteObjectType.VIEW) select(target)
        }

        /**
         * 在后台线程打开一个库，成功后载入对象清单。
         *
         * 打开会**换掉**当前连接：先同步关掉旧的，再异步建新的——失败时把错误留在 [openError]
         * 里，而不是把上一次的库悄悄留在界面上（那会让人以为新库已经打开）。
         */
        fun open(target: String, key: String?) {
            if (target.isBlank()) return
            // 一进来就把它填进输入框：**失败时也要填**——加密库拖进来只会走到「要口令」那一步，
            // 路径若不显示，用户不知道刚才拖的是哪个文件，解锁按钮也无从下手（见下面那个按钮）。
            pathInput = target
            // 这次没带口令，说明打开的**不是**当前这个库（拖入 / 选文件 / 回车都走这里）。把输入框里
            // 上一份口令清掉：它是上一个库的，留着既会显示给用户看，又可能被顺手拿去开新库
            // ——口令只属于某一个文件，跨库复用就是泄露。
            if (key == null) passphrase = ""
            scope.launch {
                busy = true
                resetSession()
                val opened = withContext(Dispatchers.Default) {
                    runCatching { openSqliteSession(target, key, readOnly) }
                }
                opened.onSuccess { newSession ->
                    path = target
                    session = newSession
                    // 记下这个库的口令，供「刷新 / 切只读」重开时用；输入框里那份随即清掉。
                    openedPassphrase = key
                    passphrase = ""
                    needsPassphrase = false
                    openError = null
                    objects = withContext(Dispatchers.Default) { newSession.objects() }
                    statusText = "${path.substringAfterLast('/')} · ${objects.size} 个对象"
                    host.showStatus("已打开 ${path.substringAfterLast('/')}")
                    // 默认展开第一张表：常见场景是「打开就是想看数据」，少一次点击。
                    objects.firstOrNull { it.type == SqliteObjectType.TABLE }?.let { select(it) }
                }.onFailure { error ->
                    needsPassphrase = (error as? SqliteError)?.needsPassphrase == true
                    openError = error.message ?: "打开失败"
                    session = null
                    openedPassphrase = null
                    // 口令没对上（或压根没给）：把焦点送回输入框，用户可以接着重打。
                    passphraseFocusTick++
                }
                busy = false
            }
        }

        // 剪贴板条目若是个 .db / .sqlite 文件就自动打开（此时还什么都没打开）。
        LaunchedEffect(input) {
            if (session != null) return@LaunchedEffect
            val candidate = input?.files?.firstOrNull { it.looksLikeSqlitePath() } ?: return@LaunchedEffect
            open(candidate, null)
        }

        Column(Modifier.fillMaxSize()) {
            OpenBar(
                pathInput = pathInput,
                onPathInputChange = { pathInput = it },
                readOnly = readOnly,
                canReload = path.isNotBlank() && !busy,
                host = host,
                // 拖入 / 选文件 / 回车打开的**都算新库**：不带任何既存口令，加密库会照常要口令。
                onOpenRequested = { target -> open(target, null) },
                // 刷新用的是**已经打开的那个库**与它自己的口令，与输入框里现在写着什么无关。
                onReload = { if (path.isNotBlank()) open(path, openedPassphrase) },
                onClear = {
                    // 关闭库时把口令一并清掉：它属于这个库，库都关了没有任何理由再留着。
                    passphrase = ""
                    pathInput = ""
                    path = ""
                    resetSession()
                },
                onReadOnlyChange = { enabled ->
                    // 只读是**打开时**决定的：改它必须重开连接，不能让已经打开的库悄悄换语义。
                    readOnly = enabled
                    if (path.isNotBlank()) open(path, openedPassphrase)
                },
            )

            if (needsPassphrase) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("口令", fontSize = 13.sp, color = MaterialTheme.hintColor, modifier = Modifier.width(40.dp))
                    DevToolSingleLineField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        placeholder = "加密库的口令（只在内存里，不落盘）",
                        secret = true,
                        modifier = Modifier.weight(1f).focusRequester(passphraseFocus),
                        // 回车即解锁——与路径框「回车打开」同一套手感，省得再去够那个按钮。
                        onSubmit = { open(pathInput, passphrase) },
                    )
                    Spacer(Modifier.width(8.dp))
                    DevToolButton(
                        title = "解锁",
                        primary = true,
                        // 用输入框里那一份路径：拖进来一个加密库时 `path` 还没生效（连接没建成），
                        // 但输入框里已经是它了。
                        enabled = pathInput.isNotBlank() && !busy,
                        onClick = { open(pathInput, passphrase) },
                    )
                }
            }

            openError?.let { message ->
                Spacer(Modifier.height(8.dp))
                Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            }

            if (session == null) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = if (busy) "正在打开…" else "选择一个 SQLite 文件，或把一个 .db 文件拖进来 / 从剪贴板打开。",
                    fontSize = 13.sp,
                    color = MaterialTheme.hintColor,
                )
                return@Column
            }

            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxSize()) {
                val treeNodes = remember(objects, childrenCache, path) {
                    buildSqliteTree(path.substringAfterLast('/'), objects, childrenCache)
                }
                TreeView(
                    nodes = treeNodes,
                    expandedKeys = expandedKeys,
                    onToggle = ::toggleNode,
                    selectedKey = selectedNodeKey,
                    onSelect = ::selectNode,
                    onActivate = ::activateNode,
                    // ⌘C 复制选中项的名字——与右键菜单里那一项是同一个动作，键位提示因此对得上。
                    onCopy = { host.copyToClipboard(it.label) },
                    menuEntries = { node -> treeMenuEntries(node, objects, host, ::activateNode) },
                    modifier = Modifier.width(treeWidth).fillMaxHeight(),
                )
                // 分隔条：左右拖改左树宽度。范围给得宽松——列名可以很长，允许把它拉到很宽。
                TreeSplitter(
                    onDrag = { delta -> treeWidth = (treeWidth + delta).coerceIn(160.dp, 640.dp) },
                )
                DetailPane(
                    host = host,
                    selected = selected,
                    rows = pageRows,
                    rowCount = rowCount,
                    pageIndex = pageIndex,
                    sortColumn = sortColumn,
                    sortAscending = sortAscending,
                    filter = filter,
                    onPrevPage = { if (pageIndex > 0) loadPage(pageIndex - 1) },
                    onNextPage = { loadPage(pageIndex + 1) },
                    onSort = ::sortBy,
                    onFilter = ::filterBy,
                    onExportCsv = ::exportCsv,
                    sqlText = sqlText,
                    onSqlChange = { sqlText = it },
                    sqlRows = sqlRows,
                    sqlError = sqlError,
                    onRunSql = ::runSql,
                    busy = busy,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        }
    }
}

/**
 * 顶部：库路径（通用文件控件）、打开按钮、只读开关。
 *
 * 路径那一格直接用 [DevToolFileField]——它与签名页选 APK / KeyStore 是同一个控件：可敲、可拖、
 * 带「打开」与「关闭」两个动作。这几样说的是同一件事「这个库文件」，拆开摆就会被读成别的
 * （见那个控件的说明）。
 */
@Composable
private fun OpenBar(
    pathInput: String,
    onPathInputChange: (String) -> Unit,
    readOnly: Boolean,
    canReload: Boolean,
    host: DevToolHost,
    onOpenRequested: (String) -> Unit,
    onReload: () -> Unit,
    onClear: () -> Unit,
    onReadOnlyChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 与下面口令那一行的标签取同一个宽度：两行贴在一起，标签左缘对齐才不会显得错位。
        Text("路径", fontSize = 13.sp, color = MaterialTheme.hintColor, modifier = Modifier.width(40.dp))
        DevToolFileField(
            value = pathInput,
            onValueChange = onPathInputChange,
            placeholder = "选择一个 SQLite 文件，或把 .db / .sqlite 拖进来",
            host = host,
            onDropFiles = { files -> files.firstOrNull()?.let(onOpenRequested) },
            onOpen = { host.pickFileToOpen()?.let(onOpenRequested) },
            onClear = onClear,
            clearTooltip = "关闭数据库",
            modifier = Modifier.weight(1f),
            // 手敲 / 粘贴一条路径之后按回车即打开；选文件与拖入都已经自动打开，用不到它。
            onSubmit = { if (pathInput.isNotBlank()) onOpenRequested(pathInput) },
        )
        Spacer(Modifier.width(8.dp))
        // 刷新：用当前路径重开一次，重新读对象树、行数与当前页。打开 Clipper 自己的库时这是常事
        // ——应用一直在往里写，缓存的那份会过时。
        DevToolFieldAction(
            kind = ClipperIconKind.RESET,
            tooltip = "重新打开",
            enabled = canReload,
            onClick = onReload,
        )
        Spacer(Modifier.width(10.dp))
        DevToolToggle(title = "只读", checked = readOnly, onCheckedChange = onReadOnlyChange)
    }
}

/** 右侧：数据 / 结构 / SQL 三块，用分段控件切换。 */
@Composable
private fun DetailPane(
    host: DevToolHost,
    selected: SqliteObject?,
    rows: SqliteRows?,
    rowCount: Long?,
    pageIndex: Int,
    sortColumn: String?,
    sortAscending: Boolean,
    filter: SqliteFilter?,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onSort: (key: String?, ascending: Boolean) -> Unit,
    onFilter: (SqliteFilter?) -> Unit,
    onExportCsv: () -> Unit,
    sqlText: String,
    onSqlChange: (String) -> Unit,
    sqlRows: SqliteRows?,
    sqlError: String?,
    onRunSql: () -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(PaneTab.Data) }

    Column(modifier) {
        DevToolSegmentedControl(
            options = PaneTab.entries.toList(),
            selected = tab,
            optionLabel = { it.label },
            onSelect = { tab = it },
        )
        Spacer(Modifier.height(10.dp))

        when (tab) {
            PaneTab.Data -> DataPane(
                host = host,
                selected = selected,
                rows = rows,
                rowCount = rowCount,
                pageIndex = pageIndex,
                sortColumn = sortColumn,
                sortAscending = sortAscending,
                filter = filter,
                onPrevPage = onPrevPage,
                onNextPage = onNextPage,
                onSort = onSort,
                onFilter = onFilter,
                onExportCsv = onExportCsv,
                busy = busy,
                modifier = Modifier.weight(1f),
            )

            PaneTab.Sql -> SqlPane(
                host = host,
                sqlText = sqlText,
                onSqlChange = onSqlChange,
                rows = sqlRows,
                error = sqlError,
                onRun = onRunSql,
                busy = busy,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DataPane(
    host: DevToolHost,
    selected: SqliteObject?,
    rows: SqliteRows?,
    rowCount: Long?,
    pageIndex: Int,
    sortColumn: String?,
    sortAscending: Boolean,
    filter: SqliteFilter?,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    onSort: (key: String?, ascending: Boolean) -> Unit,
    onFilter: (SqliteFilter?) -> Unit,
    onExportCsv: () -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    if (selected == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("双击左侧的表或视图，打开它", fontSize = 13.sp, color = MaterialTheme.hintColor)
        }
        return
    }
    val page = rows
    if (page == null) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("读取中…", fontSize = 13.sp, color = MaterialTheme.hintColor)
        }
        return
    }
    Column(modifier) {
        DataGrid(
            columns = dataGridColumnsOf(page),
            rows = page.rows,
            sortKey = sortColumn,
            sortAscending = sortAscending,
            onSort = onSort,
            onCopy = { host.copyToClipboard(it) },
            // 右键里那两项都**必须下推给 SQL**：筛选是 `WHERE`，而「按此值筛选」的语义是「这一列
            // 等于它」——在内存里筛当前这一页只会得到错的结论。
            extraMenuEntries = { row, column, value ->
                val columnName = page.columns.getOrNull(column)
                buildList {
                    if (columnName != null) {
                        add(
                            AppContextMenuAction(title = "按此值筛选") {
                                onFilter(SqliteFilter(columnName, value))
                            }
                        )
                    }
                    add(
                        AppContextMenuAction(title = "复制为 INSERT") {
                            val single = SqliteRows(
                                columns = page.columns,
                                rows = listOfNotNull(page.rows.getOrNull(row)),
                                truncated = false,
                            )
                            host.copyToClipboard(single.toInsertStatements(selected.name))
                        }
                    )
                }
            },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 行数是精确值（重读这一页时已经数过一遍），不是估算。
            val total = rowCount?.let { "共 $it 行 · " }.orEmpty()
            val from = if (page.rows.isEmpty()) 0 else pageIndex * SqlitePageSize + 1
            val to = pageIndex * SqlitePageSize + page.rows.size
            Text("$total$from–$to", fontSize = 12.sp, color = MaterialTheme.hintColor)
            // 筛选生效时给一条明确的出口——否则「行数怎么少了」得自己回想。
            filter?.let { active ->
                Spacer(Modifier.width(10.dp))
                Text(
                    text = "已筛选：${active.column} = ${active.value ?: "NULL"}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(6.dp))
                DevToolButton(title = "清除筛选", outlined = true, onClick = { onFilter(null) })
            }
            Spacer(Modifier.weight(1f))
            DevToolButton(title = "上一页", enabled = pageIndex > 0, outlined = true, onClick = onPrevPage)
            Spacer(Modifier.width(8.dp))
            // 还有下一页当且仅当这一页被填满（拿满一页说明后面可能还有）。
            DevToolButton(
                title = "下一页",
                enabled = page.rows.size >= SqlitePageSize,
                outlined = true,
                onClick = onNextPage,
            )
            Spacer(Modifier.width(10.dp))
            // 导出**整张表**：与右边那个保存图标（只存当前这一页）不是一回事，所以单列一个按钮、
            // 名字也写全——两个入口看起来一样的话，用户只会在导完之后发现「怎么不全」。
            DevToolButton(
                title = "导出整表 CSV",
                outlined = true,
                enabled = !busy,
                onClick = onExportCsv,
            )
            Spacer(Modifier.width(8.dp))
            // 导出 / 复制跟着这一页数据走。CSV 分不出 NULL 与空串，因此另给一条 `INSERT` 的出路。
            DevToolResultActions(
                value = page.toCsv(),
                host = host,
                suggestedFileName = "${selected.name}.csv",
            )
            Spacer(Modifier.width(8.dp))
            DevToolButton(
                title = "导出 SQL",
                outlined = true,
                onClick = {
                    val target = host.pickFileToSave("${selected.name}.sql")
                    if (target != null) {
                        val written = writeTextFile(target, page.toInsertStatements(selected.name))
                        host.showStatus(if (written) "已导出 $target" else "导出失败：$target")
                    }
                },
            )
        }
    }
}

@Composable
private fun SqlPane(
    host: DevToolHost,
    sqlText: String,
    onSqlChange: (String) -> Unit,
    rows: SqliteRows?,
    error: String?,
    onRun: () -> Unit,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DevToolButton(title = "运行", primary = true, enabled = !busy && sqlText.isNotBlank(), onClick = onRun)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "只读模式下写语句会被拒绝；要写入请关掉顶部的「只读」并重开。",
                fontSize = 12.sp,
                color = MaterialTheme.hintColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        DevToolCodeField(
            label = "SQL",
            value = sqlText,
            onValueChange = onSqlChange,
            placeholder = "SELECT * FROM …",
            scan = ::scanPlain,
            folding = false,
            modifier = Modifier.height(160.dp),
        )
        Spacer(Modifier.height(10.dp))
        if (error != null) {
            Text(error, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
        }
        val result = rows
        if (result != null) {
            // SQL 页签的结果是任意查询，没有「这张表」可言——因此不给排序，只给选中与复制。
            DataGrid(
                columns = dataGridColumnsOf(result),
                rows = result.rows,
                onCopy = { host.copyToClipboard(it) },
                modifier = Modifier.weight(1f),
            )
            if (result.truncated) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "结果超过 $SqliteQueryRowLimit 行，只显示前 $SqliteQueryRowLimit 行。",
                    fontSize = 12.sp,
                    color = MaterialTheme.hintColor,
                )
            }
        } else {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text("结果会显示在这里", fontSize = 13.sp, color = MaterialTheme.hintColor)
            }
        }
    }
}

/**
 * 结果集的列名 → 表格列定义。
 *
 * 宽度统一给一个初值：用户拖过之后由 `DataGrid` 按**列名**记住，所以这里不必去猜「这一列该多
 * 宽」——猜错了用户照样得自己拖一遍。
 */
private fun dataGridColumnsOf(rows: SqliteRows): List<DataGridColumn> =
    rows.columns.map { name -> DataGridColumn(key = name, title = name, width = 150.dp) }

/**
 * 树与内容之间的分隔条：按住左右拖，改左树的宽度。
 *
 * 只改一个 `Dp` 状态，不动窗口尺寸（预览面板那条分隔条要「松手才写设置」，是因为写设置会让宿主
 * 每帧重算窗口宽度、整个窗口跟着抖；这里没有要落盘的东西，也就没有这层顾虑）。
 */
@Composable
private fun TreeSplitter(onDrag: (Dp) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        Modifier
            .width(TreeSplitterWidth)
            .fillMaxHeight()
            // 指针变成左右箭头：光看一条竖线看不出这里能拖。
            .pointerHoverIcon(HorizontalResizePointerIcon)
            .hoverable(interaction)
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta -> onDrag(with(density) { delta.toDp() }) },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(
                    if (hovered) {
                        colors.primary.copy(alpha = 0.6f)
                    } else {
                        colors.outline.copy(alpha = 0.5f)
                    }
                ),
        )
    }
}

/** 分隔条的宽度：中间那条线只有 1dp，剩下的都是好抓的命中区。 */
private val TreeSplitterWidth = 6.dp

/**
 * 树上某个节点的右键菜单。
 *
 * 只有表 / 视图才有「打开」与「查询语句」——列、索引、分组这些节点没有「数据」可看。每一项的
 * 可用与文案都在**打开这一刻**算，不留悬空的动作；键位提示也必须与 `TreeView` 真接上的键盘操作
 * 一致（`Enter` 打开、`⌘C` 复制），否则等于骗人。
 */
private fun treeMenuEntries(
    node: TreeNode,
    objects: List<SqliteObject>,
    host: DevToolHost,
    onOpen: (TreeNode) -> Unit,
): List<AppContextMenuEntry> {
    val target = sqliteObjectOf(node.key, objects)
    val openable = target != null &&
        (target.type == SqliteObjectType.TABLE || target.type == SqliteObjectType.VIEW)
    return buildList {
        if (openable) {
            add(AppContextMenuAction(title = "打开", shortcut = "Enter") { onOpen(node) })
            add(AppContextMenuDivider)
        }
        add(AppContextMenuAction(title = "复制名称", shortcut = "⌘C") { host.copyToClipboard(node.label) })
        target?.sql?.let { ddl ->
            add(AppContextMenuAction(title = "复制建表语句") { host.copyToClipboard(ddl) })
        }
        if (target != null && openable) {
            add(
                AppContextMenuAction(title = "复制查询语句") {
                    host.copyToClipboard("SELECT * FROM ${quoteIdentifier(target.name)} LIMIT 100;")
                }
            )
        }
    }
}


/** 右侧详情区的两块。 */
private enum class PaneTab(val label: String) {
    Data("数据"),
    Sql("SQL"),
}
