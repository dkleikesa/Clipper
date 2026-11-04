package com.qcmian.logviewer.ui.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.logviewer.core.model.FilterConfig
import com.qcmian.logviewer.core.model.FilterType
import com.qcmian.logviewer.core.model.MatchMode
import com.qcmian.logviewer.state.ViewerState
import com.qcmian.logviewer.ui.common.MiniButton
import com.qcmian.logviewer.ui.common.SectionLabel
import com.qcmian.logviewer.ui.theme.LocalLvColors
import com.qcmian.logviewer.ui.theme.toComposeColor

/* ==================== 列规格：比例权重 + 拖拽下限 ==================== */

/** 列标题 */
private val TITLES = listOf("开启", "类型", "匹配", "匹配内容", "Aa", "着色", "命中", "删除")

/** 初始权重（相对值，按比例瓜分整行宽，无右侧空白尾巴） */
private val DEFAULT_WEIGHTS = listOf(4f, 7f, 7f, 40f, 5f, 7f, 6f, 8f)

/** 拖拽时可收缩到的最小列宽（dp） */
private val MIN_DP = listOf(34.dp, 50.dp, 50.dp, 90.dp, 30.dp, 46.dp, 36.dp, 40.dp)

/** 着色下拉调色板（淡背景色 + 中文名；值为 ARGB Long） */
private val TINT_PALETTE = listOf(
    0x332196F3L to "蓝",
    0x33F9A825L to "黄",
    0x3343A047L to "绿",
    0x338E24AAL to "紫",
    0x33E53935L to "红",
    0x3300796BL to "青",
)

/**
 * 底部规则工作台（比例列宽表格）：
 * - 列宽按权重瓜分整行（表头与数据行由同一权重列表驱动，结构同构，像素级对齐）
 * - 拖动列间分隔线可调整相邻两列宽度（自动 clamp 最小值）
 * - 下拉选择 / 双击编辑模式列（提交才应用） / 末行 “＋” 新增
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilterWorkbench(
    state: ViewerState,
    onImportFilters: () -> Unit,
    onExportFilters: () -> Unit,
    onApplyPresets: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalLvColors.current
    var weights by remember { mutableStateOf(DEFAULT_WEIGHTS) }
    var tableWidthPx by remember { mutableStateOf(1f) }

    /** 拖动第 index 条分隔线：把 dw 权重从第 index+1 列转移给第 index 列（已 clamp） */
    val onDividerDrag: (Int, Float) -> Unit = { index, dw ->
        weights = weights.toMutableList().also {
            it[index] += dw
            it[index + 1] -= dw
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(c.surface)
            .onSizeChanged { tableWidthPx = it.width.toFloat().coerceAtLeast(1f) },
    ) {
        // 头部：标题 + 管道 + 操作
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel("规则")
            Text(
                " ${state.filters.size}",
                color = c.accent, fontSize = 10.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(end = 10.dp),
            )
            Text(
                "包含 → 排除 → 当前视图 ${state.lineCount}",
                color = c.textFaint, fontSize = 10.sp,
                modifier = Modifier.padding(end = 10.dp),
            )
            Spacer(Modifier.weight(1f))
            if (state.filterBusy) {
                Text("过滤中 ${(state.filterProgress * 100).toInt()}%", color = c.accent, fontSize = 10.sp)
                Spacer(Modifier.width(8.dp))
            }
            MiniButton("预设", onClick = onApplyPresets)
            Spacer(Modifier.width(6.dp))
            MiniButton("导入", onClick = onImportFilters)
            Spacer(Modifier.width(6.dp))
            MiniButton("导出", onClick = onExportFilters)
            Spacer(Modifier.width(6.dp))
            MiniButton("清空", onClick = { state.clearFilters() })
        }

        HeaderRow(weights, tableWidthPx, onDividerDrag)

        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(state.filters, key = { _, f -> f.id }) { idx, f ->
                FilterRow(
                    filter = f,
                    hitCount = state.filterResult?.counts?.getOrNull(idx) ?: 0,
                    weights = weights,
                    tableWidthPx = tableWidthPx,
                    onDividerDrag = onDividerDrag,
                    onChange = { state.updateFilter(it) },
                    onDelete = { state.removeFilter(f.id) },
                )
            }
            item(key = "add-row") {
                // “＋” 新增行
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clickable { state.addFilter(FilterConfig()) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text("＋  新增过滤器", color = c.accent, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/* ==================== 表格骨架（表头与数据行同构） ==================== */

/** 比例列单元格：宽度 = weight 权重瓜分（表头/数据行由同一权重列表驱动） */
@Composable
private fun RowScope.WCell(
    weight: Float,
    alignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        Modifier
            .weight(weight)
            .fillMaxHeight(),
        contentAlignment = alignment,
        content = content,
    )
}

/** 列分隔线：竖线视觉 + 拖拽改宽热区 */
@Composable
private fun RowScope.ColDivider(
    index: Int,
    weights: List<Float>,
    tableWidthPx: Float,
    onDrag: (Int, Float) -> Unit,
) {
    val c = LocalLvColors.current
    val currentWeights by rememberUpdatedState(weights)
    val currentWidth by rememberUpdatedState(tableWidthPx)
    Box(
        Modifier
            .width(9.dp)
            .fillMaxHeight()
            .pointerHoverIcon(com.qcmian.logviewer.platform.colResizeIcon, overrideDescendants = true)
            .pointerInput(index) {
                detectDragGestures { change, amount ->
                    change.consume()
                    val ws = currentWeights
                    val wPx = currentWidth.coerceAtLeast(1f)
                    val total = ws.sum()
                    val minA = with(this@pointerInput) { MIN_DP[index].toPx() } / wPx * total
                    val minB = with(this@pointerInput) { MIN_DP[index + 1].toPx() } / wPx * total
                    var dw = amount.x / wPx * total
                    // clamp：拖动后左右两列都不小于最小宽
                    dw = dw.coerceIn(minA - ws[index], ws[index + 1] - minB)
                    if (dw != 0f) onDrag(index, dw)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(c.borderStrong))
    }
}

@Composable
private fun HeaderRow(weights: List<Float>, tableWidthPx: Float, onDrag: (Int, Float) -> Unit) {
    val c = LocalLvColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .background(c.surfaceAlt),
    ) {
        TITLES.forEachIndexed { i, t ->
            WCell(weights[i]) {
                Text(
                    t,
                    color = c.textFaint,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
            if (i < TITLES.lastIndex) {
                ColDivider(i, weights, tableWidthPx, onDrag)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderStrong))
}

/* ==================== 数据行 ==================== */

@Composable
private fun FilterRow(
    filter: FilterConfig,
    hitCount: Int,
    weights: List<Float>,
    tableWidthPx: Float,
    onDividerDrag: (Int, Float) -> Unit,
    onChange: (FilterConfig) -> Unit,
    onDelete: () -> Unit,
) {
    val c = LocalLvColors.current
    Row(Modifier.fillMaxWidth().height(32.dp)) {
        // 开启
        WCell(weights[0]) {
            Checkbox(
                checked = filter.enabled,
                onCheckedChange = { onChange(filter.copy(enabled = it)) },
                modifier = Modifier.size(13.dp),
            )
        }
        ColDivider(0, weights, tableWidthPx, onDividerDrag)
        // 类型（下拉）
        WCell(weights[1]) {
            DropCell(
                label = if (filter.type == FilterType.INCLUDE) "包含" else "排除",
                options = listOf("包含" to FilterType.INCLUDE, "排除" to FilterType.EXCLUDE),
                selected = filter.type,
            ) { onChange(filter.copy(type = it)) }
        }
        ColDivider(1, weights, tableWidthPx, onDividerDrag)
        // 匹配（下拉）
        WCell(weights[2]) {
            DropCell(
                label = when (filter.matchMode) {
                    MatchMode.STRING -> "文本"
                    MatchMode.REGEX -> "正则"
                    MatchMode.MARKER -> "标记"
                },
                options = listOf(
                    "文本" to MatchMode.STRING,
                    "正则" to MatchMode.REGEX,
                    "标记" to MatchMode.MARKER,
                ),
                selected = filter.matchMode,
            ) { onChange(filter.copy(matchMode = it)) }
        }
        ColDivider(2, weights, tableWidthPx, onDividerDrag)
        // 匹配内容（双击编辑，提交才应用）
        WCell(weights[3], Alignment.CenterStart) {
            PatternCell(filter, onChange)
        }
        ColDivider(3, weights, tableWidthPx, onDividerDrag)
        // Aa（区分大小写 checkbox）
        WCell(weights[4]) {
            Checkbox(
                checked = filter.caseSensitive,
                onCheckedChange = { onChange(filter.copy(caseSensitive = it)) },
                modifier = Modifier.size(13.dp),
            )
        }
        ColDivider(4, weights, tableWidthPx, onDividerDrag)
        // 着色（下拉）
        WCell(weights[5]) {
            val tintIdx = TINT_PALETTE.indexOfFirst { it.first == filter.background }
            DropCell(
                label = if (tintIdx < 0) "无" else TINT_PALETTE[tintIdx].second,
                options = listOf("无" to -1L) + TINT_PALETTE.mapIndexed { i, t -> t.second to t.first },
                selected = if (tintIdx < 0) -1L else TINT_PALETTE[tintIdx].first,
                swatchOf = { value ->
                    if (value == -1L) null else TINT_PALETTE.firstOrNull { it.first == value }?.first?.toComposeColor()
                },
            ) { value ->
                onChange(filter.copy(background = if (value == -1L) null else value, foreground = null))
            }
        }
        ColDivider(5, weights, tableWidthPx, onDividerDrag)
        // 命中
        WCell(weights[6]) {
            Text(
                hitCount.toString(),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = if (hitCount > 0) c.accent else c.textFaint,
                maxLines = 1,
            )
        }
        ColDivider(6, weights, tableWidthPx, onDividerDrag)
        // 删除
        WCell(weights[7]) {
            MiniButton("×", danger = true, onClick = onDelete)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.borderStrong.copy(alpha = 0.6f)))
}

/* ==================== 单元格组件 ==================== */

/** 下拉三角箭头（Canvas 绘制，大小可控、清晰） */
@Composable
private fun DropdownArrow(sizeDp: androidx.compose.ui.unit.Dp = 6.dp, color: Color) {
    Canvas(Modifier.size(sizeDp)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(0f, h * 0.28f)
            lineTo(w, h * 0.28f)
            lineTo(w * 0.5f, h * 0.78f)
            close()
        }
        drawPath(path, color)
    }
}

/** 下拉选择单元格：内容居中 + 清晰三角 + 菜单项居中 */
@Composable
private fun <T> DropCell(
    label: String,
    options: List<Pair<String, T>>,
    selected: T,
    swatchOf: ((T) -> Color?)? = null,
    onSelect: (T) -> Unit,
) {
    val c = LocalLvColors.current
    var expanded by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .clickable { expanded = true }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        swatchOf?.invoke(selected)?.let { col ->
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(col)
                    .border(0.5.dp, c.border, CircleShape)
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(label, fontSize = 10.5.sp, color = c.textDim, maxLines = 1)
        Spacer(Modifier.width(4.dp))
        DropdownArrow(color = c.textFaint)
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        options.forEach { (text, value) ->
            DropdownMenuItem(
                text = {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        swatchOf?.invoke(value)?.let { col ->
                            Box(Modifier.size(10.dp).clip(CircleShape).background(col))
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            if (value == selected) "✓ $text" else text,
                            fontSize = 12.sp,
                            color = if (value == selected) c.accent else c.text,
                        )
                    }
                },
                onClick = {
                    onSelect(value)
                    expanded = false
                },
            )
        }
    }
}

/** 匹配内容单元格：双击进入编辑，✓/Enter 提交后才应用（编辑期间不触发重过滤） */
@Composable
private fun PatternCell(filter: FilterConfig, onChange: (FilterConfig) -> Unit) {
    val c = LocalLvColors.current
    var editing by remember(filter.id) { mutableStateOf(false) }
    var draft by remember(filter.id) { mutableStateOf(filter.pattern) }

    if (filter.matchMode == MatchMode.MARKER) {
        // 标记模式：色点选择
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            com.qcmian.logviewer.core.model.MarkerPalette.colors.forEachIndexed { i, col ->
                Box(
                    Modifier
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(col.toLong().toComposeColor())
                        .clickable { onChange(filter.copy(markerIndex = i)) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (filter.markerIndex == i) {
                        Box(Modifier.size(4.dp).clip(CircleShape).background(c.text))
                    }
                }
            }
        }
        return
    }

    if (!editing) {
        // 只读态：双击进入编辑
        Box(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                    onDoubleClick = {
                        draft = filter.pattern
                        editing = true
                    },
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                filter.pattern.ifEmpty { "双击编辑" },
                fontSize = 11.sp,
                color = if (filter.pattern.isEmpty()) c.textFaint else c.text,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    } else {
        // 编辑态：输入框 + 内嵌右侧按钮组（确定/取消/清空），单行紧凑，不增加行高
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(c.surfaceAlt)
                .padding(start = 6.dp, end = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 11.sp, color = c.text, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier
                    .weight(1f)
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.key) {
                            Key.Enter -> {
                                onChange(filter.copy(pattern = draft))
                                editing = false
                                true
                            }
                            Key.Escape -> {
                                draft = filter.pattern
                                editing = false
                                true
                            }
                            else -> false
                        }
                    },
            )
            // 内嵌按钮组：确定 / 取消 / 清空
            InlineAction("确定", color = c.accent) {
                onChange(filter.copy(pattern = draft))
                editing = false
            }
            InlineAction("取消", color = c.textDim) {
                draft = filter.pattern
                editing = false
            }
            InlineAction("清空", color = c.textDim) {
                draft = ""
            }
        }
    }
}

/** 输入框内嵌文字按钮：轻量、无背景块 */
@Composable
private fun InlineAction(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        text = label,
        color = color,
        fontSize = 10.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(3.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 3.dp),
    )
}
