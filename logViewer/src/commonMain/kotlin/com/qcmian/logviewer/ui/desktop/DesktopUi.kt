package com.qcmian.logviewer.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.logviewer.core.export.Exporter
import com.qcmian.logviewer.core.export.ExportFormat
import com.qcmian.logviewer.core.model.DisplayLine
import com.qcmian.logviewer.core.model.DisplayMode
import com.qcmian.logviewer.core.model.FilterPresets
import com.qcmian.logviewer.core.model.FilterSet
import com.qcmian.logviewer.core.model.RecentEntry
import com.qcmian.logviewer.platform.nsResizeCursor
import com.qcmian.logviewer.state.AppState
import com.qcmian.logviewer.state.ViewerState
import com.qcmian.logviewer.ui.common.DialogShell
import com.qcmian.logviewer.ui.common.EncodingDialog
import com.qcmian.logviewer.ui.common.ExportDialog
import com.qcmian.logviewer.ui.common.GoToLineDialog
import com.qcmian.logviewer.ui.common.MiniButton
import com.qcmian.logviewer.ui.common.PresetsDialog
import com.qcmian.logviewer.ui.common.Segmented
import com.qcmian.logviewer.ui.theme.LocalLvColors
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/* ==================== 对话框状态 ==================== */

internal sealed interface Dlg {
    data object None : Dlg
    data object Encoding : Dlg
    data object GoTo : Dlg
    data object Export : Dlg
    data object Presets : Dlg
}

/* ==================== 桌面主布局 ==================== */

@Composable
fun LogViewerDesktop(app: AppState) {
    // 自动化验证：环境变量注入的路径启动即打开
    val autoPath = com.qcmian.logviewer.platform.AutoOpenPath.value
    LaunchedEffect(autoPath) {
        if (autoPath != null) {
            com.qcmian.logviewer.platform.DiagLog.log("[autopen] path=$autoPath")
            val name = autoPath.substringAfterLast('/')
            app.openFile(
                com.qcmian.logviewer.core.model.FileRef(name, autoPath, "file://$autoPath"),
            )
        }
    }
    val c = LocalLvColors.current
    val active = app.active ?: return
    val scope = rememberCoroutineScope()
    var dlg by remember { mutableStateOf<Dlg>(Dlg.None) }

    // 规则工作台显隐原来在「设置」里；设置去掉后给它一个工具栏入口
    var showRules by remember { mutableStateOf(true) }

    Column(
        Modifier
            .fillMaxSize()
            .background(c.bg),
    ) {
        TabBar(app)
        Toolbar(
            app = app,
            state = active,
            onOpen = { app.platform.pickFiles { refs -> refs.forEach { app.openFile(it) } } },
            onReload = { active.reload() },
            onPaste = { app.importFromClipboard() },
            onExport = { dlg = Dlg.Export },
            onGoTo = { dlg = Dlg.GoTo },
            onAggregate = { app.openAggregateView() },
            showRules = showRules,
            onToggleRules = { showRules = !showRules },
        )

        Row(Modifier.weight(1f)) {
            Box(Modifier.weight(1f)) {
                if (active.loadPhase == ViewerState.LoadPhase.Ready || active.loadPhase == ViewerState.LoadPhase.Loading) {
                    LogPane(
                        state = active,
                        fontSize = LOG_FONT_SIZE,
                        showLineNumbers = true,
                        showMarkerColumn = true,
                        wrapLines = false,
                        onContextAction = { action, line -> handleContextAction(app, active, action, line) },
                    )
                } else if (active.loadPhase == ViewerState.LoadPhase.NeedEncoding) {
                    // 由 dlg = Encoding 处理
                    Box(Modifier.fillMaxSize())
                } else {
                    WelcomePane(app)
                }
            }
        }

        if (showRules) {
            // 高度可拖：拖动条调整过滤器区高度（与日志区互相挤占）；hover 显示上下调整光标
            var wbHeight by remember { mutableStateOf(220.dp) }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(c.border)
                    .nsResizeCursor()
                    .pointerInput(Unit) {
                        detectDragGestures { _, drag: androidx.compose.ui.geometry.Offset ->
                            wbHeight = (wbHeight - drag.y.toDp()).coerceIn(120.dp, 560.dp)
                        }
                    },
            )
            FilterWorkbench(
                modifier = Modifier.fillMaxWidth().height(wbHeight),
                state = active,
                onImportFilters = {
                    app.platform.pickFiles { refs ->
                        val ref = refs.firstOrNull() ?: return@pickFiles
                        val opened = app.platform.openReader(ref)
                        if (opened != null) {
                            // Source 读取为同步调用（过滤器集合 JSON 通常很小）
                            val bytes = ByteArray(opened.sizeBytes.toInt()).also { all ->
                                var off = 0
                                while (off < all.size) {
                                    val n = opened.source.readAtMostTo(all, off, all.size)
                                    if (n <= 0) break
                                    off += n
                                }
                            }
                            val text = bytes.decodeToString()
                            runCatching {
                                val set =
                                    Json { ignoreUnknownKeys = true }.decodeFromString(FilterSet.serializer(), text)
                                active.applyFilterSet(set)
                            }
                        }
                    }
                },
                onExportFilters = {
                    val set = active.filterSetSnapshot()
                    val text = Json { prettyPrint = true }.encodeToString(FilterSet.serializer(), set)
                    app.platform.saveTextFile("${set.name}.filters.json", text) { }
                },
                onApplyPresets = { dlg = Dlg.Presets },
            )
        }

        StatusBar(state = active)
    }

    /* ----- 对话框渲染 ----- */
    when (val d = dlg) {
        is Dlg.Encoding -> com.qcmian.logviewer.ui.common.EncodingDialog(
            onDismiss = { dlg = Dlg.None },
            onConfirm = {
                dlg = Dlg.None
                active.confirmEncoding(it)
            },
        )

        Dlg.GoTo -> com.qcmian.logviewer.ui.common.GoToLineDialog(
            maxLine = active.lineCount,
            currentLine = active.currentLine,
            onDismiss = { dlg = Dlg.None },
            onGo = { n ->
                dlg = Dlg.None
                if (!active.goToLineNumber(n)) {
                    // 行号越界提示
                }
            },
        )

        Dlg.Export -> com.qcmian.logviewer.ui.common.ExportDialog(
            viewLineCount = active.displayedCount(),
            totalLines = active.lineCount,
            findMatchCount = active.findMatches?.size ?: -1,
            selectedCount = active.selectedLines.size,
            onDismiss = { dlg = Dlg.None },
            onExport = { scopeIdx, format, includeLn ->
                dlg = Dlg.None
                scope.launch { doExport(app, active, scopeIdx, format, includeLn) }
            },
        )

        Dlg.Presets -> com.qcmian.logviewer.ui.common.PresetsDialog(
            onDismiss = { dlg = Dlg.None },
            onApply = { which ->
                dlg = Dlg.None
                when (which) {
                    0 -> {
                        active.clearFilters()
                        FilterPresets.levelPresets().forEach { active.addFilter(it) }
                    }

                    1 -> {
                        active.clearFilters()
                        FilterPresets.androidCrashSet().forEach { active.addFilter(it) }
                    }

                    else -> active.addFilter(FilterPresets.levelPresets()[1])
                }
            },
        )

        Dlg.None -> {}
    }

    // 编码选择自动弹出（加载失败于自动检测时）
    LaunchedEffect(active.loadPhase) {
        if (active.loadPhase == ViewerState.LoadPhase.NeedEncoding && dlg == Dlg.None) {
            dlg = Dlg.Encoding
        }
    }
}

/* ==================== 标签栏（F9-6） ==================== */

@Composable
private fun TabBar(app: AppState) {
    val c = LocalLvColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .background(c.surface)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // macOS：为系统交通灯（关闭/最小化/最大化）预留左侧空间
        if (com.qcmian.logviewer.platform.windowTrafficLightsInset) {
            Spacer(Modifier.width(68.dp))
        }
        app.tabs.forEach { tab ->
            val isActive = tab.id == app.activeTabId
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (isActive) c.surfaceHigh else c.surfaceAlt)
                    .clickable { app.activateTab(tab.id) }
                    .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    tab.title.ifEmpty { tab.viewer.fileName.ifEmpty { "未打开文件" } },
                    color = if (isActive) c.text else c.textDim,
                    fontSize = 11.5.sp,
                    maxLines = 1,
                    modifier = Modifier.widthIn(max = 180.dp),
                )
                Text(
                    " ✕",
                    color = c.textFaint,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { app.closeTab(tab.id) }
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
        Text(
            "＋",
            color = c.textDim,
            fontSize = 13.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(5.dp))
                .clickable { app.newEmptyTab() }
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
        // 剩余空白：Chrome 式拖拽移动窗口区域
        com.qcmian.logviewer.platform.WindowDragRegion(Modifier.weight(1f)) {}
    }
}

/* ==================== 工具栏 + 查找栏 ==================== */

@Composable
private fun Toolbar(
    app: AppState,
    state: ViewerState,
    onOpen: () -> Unit,
    onReload: () -> Unit,
    onPaste: () -> Unit,
    onExport: () -> Unit,
    onGoTo: () -> Unit,
    onAggregate: () -> Unit,
    showRules: Boolean,
    onToggleRules: () -> Unit,
) {
    val c = LocalLvColors.current
    Column(Modifier.background(c.surface)) {
        // —— 单行工具栏：文件操作 + 查找栏 + 显示模式 / 视图操作；高度固定 32dp ——
        Row(
            Modifier
                .fillMaxWidth()
                .height(32.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            MiniButton("打开", onClick = onOpen, primary = true)
            MiniButton("重载", onClick = onReload)
            MiniButton("粘贴导入", onClick = onPaste)
            // 常驻（避免 Ready 切换时按钮出现/消失导致布局闪动），未就绪时置灰
            MiniButton("导出", enabled = state.loadPhase == ViewerState.LoadPhase.Ready, onClick = onExport)
            MiniButton("跳转", enabled = state.loadPhase == ViewerState.LoadPhase.Ready, onClick = onGoTo)

            // 查找栏（紧凑，占中间弹性空间）
            FindBar(state, Modifier.weight(1f))

            // 显示模式
            Segmented(
                options = DisplayMode.entries.map { it.zh },
                selected = DisplayMode.entries.indexOf(state.displayMode),
            ) {
                state.displayMode = DisplayMode.entries[it]
                state.scheduleRefilter(0)
                // 所见即所搜：显示模式变化 → 搜索范围随之变化，重跑
                if (state.findQuery.isNotEmpty()) state.runFind()
            }

            MiniButton("←", enabled = state.canGoBack, onClick = { state.goBack() })
            MiniButton("→", enabled = state.canGoForward, onClick = { state.goForward() })
            MiniButton("聚合", onClick = onAggregate)
            MiniButton("规则", primary = showRules, onClick = onToggleRules)
        }

        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
    }
}

/* ==================== 查找栏（F4，紧凑内嵌版） ==================== */

@Composable
private fun FindBar(state: ViewerState, modifier: Modifier = Modifier) {
    val c = LocalLvColors.current
    var showHistory by remember { mutableStateOf(false) }
    val focusRequester = androidx.compose.ui.focus.FocusRequester()

    Row(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(c.surfaceAlt)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        // 左侧：搜索图标（点击弹出历史）+ 输入框 + 清空
        Box {
            Text(
                "🔍",
                fontSize = 10.sp,
                color = if (showHistory) c.accent else c.textFaint,
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .clickable { showHistory = !showHistory }
                    .padding(horizontal = 2.dp, vertical = 2.dp),
            )
            androidx.compose.material3.DropdownMenu(
                expanded = showHistory,
                onDismissRequest = { showHistory = false },
            ) {
                if (state.findHistory.isEmpty()) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text("暂无搜索历史", fontSize = 11.sp, color = c.textFaint) },
                        enabled = false,
                        onClick = {},
                    )
                } else {
                    state.findHistory.take(10).forEach { q ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(q, fontSize = 11.sp, maxLines = 1) },
                            onClick = {
                                state.findQuery = q
                                state.runFind()
                                showHistory = false
                                // 填入后聚焦编辑框
                                runCatching { focusRequester.requestFocus() }
                            },
                        )
                    }
                }
            }
        }
        BasicTextField(
            value = state.findQuery,
            onValueChange = { state.findQuery = it },
            singleLine = true,
            // 显式 lineHeight：光标高度 = 行盒高度，文字在行盒内垂直居中 → 光标与文字视觉一致
            textStyle = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, color = c.text),
            cursorBrush = SolidColor(c.accent),
            // 关键：inner 始终组合（承载点击与光标），占位文本叠加显示
            decorationBox = { inner ->
                Box(Modifier.fillMaxWidth()) {
                    inner()
                    if (state.findQuery.isEmpty()) {
                        Text("查找…", fontSize = 11.sp, lineHeight = 14.sp, color = c.textFaint)
                    }
                }
            },
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyUp) return@onPreviewKeyEvent false
                    when {
                        // 回车 = 提交：这时才把这一句记进查找历史
                        e.key == Key.Enter && !com.qcmian.logviewer.platform.KeyboardState.shift -> {
                            state.commitFindQuery(); state.findNext(); true
                        }

                        e.key == Key.Enter && com.qcmian.logviewer.platform.KeyboardState.shift -> {
                            state.commitFindQuery(); state.findPrev(); true
                        }

                        else -> false
                    }
                },
        )
        // 清空输入
        if (state.findQuery.isNotEmpty()) {
            Text(
                "×",
                fontSize = 11.sp, color = c.textFaint,
                modifier = Modifier
                    .clip(RoundedCornerShape(3.dp))
                    .clickable { state.findQuery = "" }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        // 分隔线
        Box(Modifier.width(1.dp).height(14.dp).background(c.borderStrong.copy(alpha = 0.4f)))

        // 选项开关（图标风格，激活时高亮）
        FindToggle("Cc", state.findCaseSensitive) { state.findCaseSensitive = !state.findCaseSensitive }
        FindToggle(".*", state.findRegex) { state.findRegex = !state.findRegex }
        FindToggle("W", state.findWholeWord) { state.findWholeWord = !state.findWholeWord }

        // 分隔线
        Box(Modifier.width(1.dp).height(14.dp).background(c.borderStrong.copy(alpha = 0.4f)))

        // 计数（固定宽度：数字长度变化不再推挤布局导致抖动）
        Text(
            if (state.findQuery.isEmpty()) "0个结果"
            else if (state.findBusy) "…"
            else {
                val m = state.findMatches
                if (m == null) "—" else "${if (state.findCursor >= 0) state.findCursor + 1 else 0}/${m.size}"
            },
            color = c.textFaint, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 1,
            modifier = Modifier
                .width(72.dp)
                .padding(horizontal = 2.dp),
        )

        // 上/下一项
        MiniButton("↑", onClick = { state.findPrev() })
        MiniButton("↓", onClick = { state.findNext() })

        // 分隔线
        Box(Modifier.width(1.dp).height(14.dp).background(c.borderStrong.copy(alpha = 0.4f)))

        // 存为规则（搜索条最后）
        Text(
            "存规则",
            fontSize = 10.sp, color = c.textFaint,
            modifier = Modifier
                .clip(RoundedCornerShape(3.dp))
                .clickable(enabled = state.findQuery.isNotEmpty()) {
                    state.quickFilter(state.findQuery, "查找:" + state.findQuery.take(12))
                }
                .padding(horizontal = 4.dp, vertical = 3.dp),
        )
        // 输入即搜（防抖 400ms）；不记历史——中间态（e / er / err…）会把历史刷成前缀树，
        // 历史只由回车 / 点历史项 / 存规则写入（见 `commitFindQuery`）
        LaunchedEffect(state.findQuery, state.findRegex, state.findCaseSensitive, state.findWholeWord) {
            kotlinx.coroutines.delay(400)
            if (state.findQuery.isNotEmpty()) state.runFind(rememberQuery = false)
            else {
                state.clearFind()
            }
        }
    }
}

/** 紧凑图标开关（激活时高亮、灰底） */
@Composable
private fun FindToggle(label: String, active: Boolean, onClick: () -> Unit) {
    val c = LocalLvColors.current
    Text(
        text = label,
        fontSize = 10.sp,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        color = if (active) c.accent else c.textFaint,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .background(if (active) c.accent.copy(alpha = 0.15f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 3.dp),
    )
}

/* ==================== 状态栏（F1-4） ==================== */

@Composable
private fun StatusBar(state: ViewerState) {
    val c = LocalLvColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.loadPhase == ViewerState.LoadPhase.Ready) {
                StatusBarItem("编码 ${state.encodingZh} ")
                StatusBarItem("大小 ${Exporter.formatSize(state.fileSizeBytes)}")
                StatusBarItem("${state.lineCount} 行")
            } else if (state.loadPhase == ViewerState.LoadPhase.Loading) {
                StatusBarItem("加载中 ${(state.loadProgress * 100).toInt()}%", color = c.accent)
            } else {
                StatusBarItem("未打开文件")
            }
        }

        // —— 组 2：查询 / 定位（与组 1 之间 28dp 大间距 + 竖线分隔） ——
        if (state.loadPhase == ViewerState.LoadPhase.Ready) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 8.dp)) {
                StatusBarItem("│", color = c.border)
                val matched = state.filterResult?.matched?.size
                if (state.hasActiveFilters() && matched != null) {
                    StatusBarItem(
                        "命中 ${matched}/${state.lineCount}",
                        color = c.accent,
                    )
                }
                if (state.currentLine >= 0) StatusBarItem("当前第 ${state.currentLine + 1} 行")
                if (state.selectedLines.size > 1) StatusBarItem("选中 ${state.selectedLines.size} 行")
                if (state.findMatches?.isNotEmpty() == true) {
                    StatusBarItem("查找 ${state.findMatches?.size ?: 0} 处", color = c.accent)
                }
            }
        }

        // —— 中间：文件绝对路径（真居中，两侧等权重；过长时中间省略） ——
        val path = state.fileRef?.uri
        if (path != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                StatusBarItem("│", color = c.border)
                SelectionContainer() {
                    Text(
                        path,
                        color = c.textFaint,
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        if (state.filterBusy) StatusBarItem("过滤中…", color = c.accent)
    }
}

@Composable
private fun StatusBarItem(
    text: String,
    color: androidx.compose.ui.graphics.Color? = null,
    fontWeight: FontWeight? = null
) {
    val c = LocalLvColors.current
    // 可复制：单条独立 SelectionContainer（不吞整行点击）
    SelectionContainer {
        Text(
            text,
            color = color ?: c.textFaint,
            fontSize = 10.5.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = fontWeight ?: FontWeight.Normal,
        )
    }
}

/* ==================== 欢迎页（空态） ==================== */

@Composable
private fun WelcomePane(app: AppState) {
    val c = LocalLvColors.current
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.width(420.dp),
        ) {
            Text("LogViewer", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.text)
            Text("本地优先的跨端日志排障工作台", fontSize = 12.sp, color = c.textDim)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiniButton("打开文件", primary = true, onClick = {
                    app.platform.pickFiles { refs -> refs.forEach { app.openFile(it) } }
                })
                MiniButton("加载示例日志", onClick = { app.loadSample() })
                MiniButton("从剪贴板粘贴", onClick = { app.importFromClipboard() })
            }
            if (app.recentFiles.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                com.qcmian.logviewer.ui.common.SectionLabel("最近文件")
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    app.recentFiles.take(6).forEach { entry ->
                        RecentRow(entry) {
                            app.openFile(
                                com.qcmian.logviewer.core.model.FileRef(entry.fileName, entry.fileUri, entry.fileUri),
                            )
                        }
                    }
                }
                Text(
                    "最近文件仅保存在本设备，可在列表中右键删除",
                    fontSize = 10.sp, color = c.textFaint,
                )
            }
        }
    }
}

@Composable
private fun RecentRow(entry: RecentEntry, onClick: () -> Unit) {
    val c = LocalLvColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(c.surfaceAlt)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.fileName,
            color = c.text, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            "${Exporter.formatCount(entry.lineCount)} 行 · ${Exporter.formatSize(entry.sizeBytes)}",
            color = c.textFaint, fontSize = 10.sp,
        )
    }
}

/* ==================== 外观常量 ==================== */

/**
 * 日志区的字号。
 *
 * 固定值，跟着宿主（开发者工具）的默认排版走：上游那套「字号 +/−、主题切换、设置对话框」
 * 都属于独立应用的配置项，嵌进宿主后这些统统由宿主决定，插件不再有第二套外观设置。
 */
private const val LOG_FONT_SIZE = 13

/* ==================== 通用动作处理 ==================== */

internal fun handleContextAction(app: AppState, state: ViewerState, action: ContextAction, line: Int) {
    when (action) {
        ContextAction.COPY_LINE -> app.scope.launch { copyLines(app, state, listOf(line)) }
        ContextAction.COPY_SELECTED -> app.scope.launch { copyLines(app, state, state.selectedLines.sorted().toList()) }
        ContextAction.NEW_FILTER_FROM_LINE -> {
            val text = state.textOf(line) ?: ""
            state.quickFilter(text.trim().take(200))
        }
        ContextAction.TOGGLE_MARKER -> state.toggleMarker(line, state.activeMarker)
    }
}

internal suspend fun copyLines(app: AppState, state: ViewerState, lines: List<Int>) {
    val text = state.copyLinesText(lines)
    app.platform.setClipboard(text)
}

internal suspend fun doExport(
    app: AppState,
    state: ViewerState,
    scopeIdx: Int,
    format: ExportFormat,
    includeLineNumbers: Boolean,
) {
    val doc = state.document ?: return
    val lines: List<DisplayLine> = when (scopeIdx) {
        0 -> state.currentViewLines()
        1 -> state.findMatches?.map { DisplayLine(it, true) } ?: emptyList()
        2 -> state.selectedLines.map { DisplayLine(it, true) }
        else -> (0 until doc.lineCount).map { DisplayLine(it, false) }
    }
    val content = com.qcmian.logviewer.core.export.Exporter.export(
        doc, lines, format,
        com.qcmian.logviewer.core.export.ExportSpec(includeLineNumbers),
    ) { idx -> state.lineColors(idx) }
    app.platform.saveTextFile("logviewer-export.${format.ext}", content) { }
}
