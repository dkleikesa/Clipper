package com.qcmian.clipper.devtools.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.ui.components.ClipperTitleBar
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.devtools.api.DataTypes
import com.qcmian.clipper.devtools.api.DevTool
import com.qcmian.clipper.devtools.api.DevToolGroup
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.DevToolMetadata
import com.qcmian.clipper.devtools.api.devToolText
import com.qcmian.clipper.devtools.registry.DevToolsRegistry
import com.qcmian.clipper.feature.history.ui.components.StatusToast
import kotlinx.coroutines.delay

/** 够放下一行工具名加一行说明。 */
private val SidebarWidth = 216.dp

/** 与剪贴板面板里那条提示保持一致。 */
private const val STATUS_DURATION_MILLIS = 1_600L

/**
 * 开发者工具主面板：左边是工具清单，右边是当前工具的界面。
 *
 * 它自己不认识任何具体工具——工具清单、分组、推荐顺序都来自 [registry]，界面则交给
 * [DevTool.Content]。
 *
 * @param item 打开时带过来的那条剪贴板记录；`null` 表示这次没有输入（用户从侧边栏点进来，或历史
 *   为空）。工具拿到的是整条记录，按需取正文 / 图片 / 附加表示。
 * @param onClose 关闭窗口（由宿主决定窗口存亡，见 `ClipperDevToolsWindow`）。
 * @param titleBarDragModifier 「按住标题栏拖动窗口」的手势，由宿主注入。
 */
@Composable
fun DevToolsPanel(
    registry: DevToolsRegistry,
    item: ClipItem?,
    onClose: () -> Unit,
    onCopyToClipboard: (String) -> Unit,
    modifier: Modifier = Modifier,
    titleBarDragModifier: Modifier = Modifier,
) {
    // 类型探测在这里做，而不是在剪贴板那边：只有面板关心结果——它决定默认打开哪个工具、
    // 顶栏写什么类型。剪贴板只管把记录交过来，因此完全不必知道工具的存在。
    //
    // 探测结果**不进状态、也不交给工具**：它是面板对「这条内容是什么」的判断，只有渲染用得着；
    // 工具能不能吃某段内容由它自己声明的 `acceptedDataTypes` 决定，不需要别人告诉它。
    // 探测的是「工具实际会看到的文本」而不是 `previewText`：文件类条目要读文件内容再判类型，
    // 否则一个 .json 文件只会因为路径被判成纯文本、推不出 JSON 工具（见 `devToolText`）。
    val detected = remember(item) { registry.detectTypes(item?.devToolText().orEmpty()) }

    var selectedId by remember { mutableStateOf<String?>(null) }

    val recommended = remember(registry, detected) {
        registry.rankedTools(detected).filter { tool -> detected.any { it in tool.acceptedDataTypes } }
    }
    val recommendedId = recommended.firstOrNull()?.metadata?.id

    LaunchedEffect(item) {
        selectedId = recommendedId
            ?: selectedId?.takeIf { registry.tool(it) != null }
            ?: registry.tools.firstOrNull()?.metadata?.id
    }

    val tools = registry.tools
    val effectiveSelectedId = selectedId ?: recommendedId ?: tools.firstOrNull()?.metadata?.id
    val groups = remember(tools) { groupTools(tools) }
    val selectedTool = effectiveSelectedId?.let(registry::tool)

    val status = remember { mutableStateOf<String?>(null) }
    // 宿主能力：按 `onCopyToClipboard` 记忆——它一变（宿主换了实现）就重建，其余时候保持稳定，
    // 免得工具界面因为「host 引用变了」而整块重组。
    val host = remember(onCopyToClipboard) {
        object : DevToolHost {
            override fun copyToClipboard(text: String) {
                if (text.isEmpty()) return
                onCopyToClipboard(text)
                status.value = "已复制到剪贴板"
            }

            override fun showStatus(message: String) {
                status.value = message
            }
        }
    }
    LaunchedEffect(status.value) {
        if (status.value == null) return@LaunchedEffect
        delay(STATUS_DURATION_MILLIS)
        status.value = null
    }

    // 圆角 + 描边是**窗口自己的外形**：宿主把窗口设成透明无边框，这里画的才是用户看到的那一块。
    // 与设置窗口同一口径（都是 10dp 圆角 + 40% 描边），两个窗口因此不会一个圆一个方。
    val windowShape = RoundedCornerShape(10.dp)
    Column(
        modifier
            .fillMaxSize()
            .clip(windowShape)
            .background(MaterialTheme.colorScheme.background)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                shape = windowShape,
            ),
    ) {
        ClipperTitleBar(
            title = "开发者工具",
            onClose = onClose,
            closeTooltip = "关闭",
            dragModifier = titleBarDragModifier,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

        Row(Modifier.weight(1f).fillMaxWidth()) {
            ToolSidebar(
                groups = groups,
                selectedId = effectiveSelectedId,
                recommendedId = recommendedId,
                onSelect = { selectedId = it },
                modifier = Modifier.width(SidebarWidth).fillMaxHeight(),
            )
            VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (selectedTool == null) {
                    EmptyTools()
                } else {
                    ToolContent(
                        tool = selectedTool,
                        item = item,
                        type = detected.firstOrNull(),
                        isRecommended = selectedTool.metadata.id == recommendedId,
                        host = host,
                    )
                }
                status.value?.let { message ->
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) {
                        StatusToast(message)
                    }
                }
            }
        }
    }
}

/** 侧边栏：按分组排列的工具清单。 */
@Composable
private fun ToolSidebar(
    groups: List<Pair<DevToolGroup, List<DevToolMetadata>>>,
    selectedId: String?,
    recommendedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            groups.forEach { (group, tools) ->
                Text(
                    text = group.label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.hintColor,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
                )
                tools.forEach { metadata ->
                    ToolSidebarItem(
                        metadata = metadata,
                        selected = metadata.id == selectedId,
                        recommended = metadata.id == recommendedId,
                        onClick = { onSelect(metadata.id) },
                    )
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

@Composable
private fun ToolSidebarItem(
    metadata: DevToolMetadata,
    selected: Boolean,
    recommended: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                when {
                    selected -> colors.primary.copy(alpha = 0.16f)
                    hovered -> colors.onSurface.copy(alpha = 0.06f)
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        ClipperIcon(
            kind = metadata.icon,
            size = 14.dp,
            tint = if (selected) colors.primary else colors.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = metadata.name,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) colors.primary else colors.onSurface,
            )
            Text(
                text = metadata.description,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = colors.onSurfaceVariant,
            )
        }
        if (recommended) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "推荐",
                fontSize = 9.sp,
                color = colors.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(colors.primary.copy(alpha = 0.14f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/** 内容区：标题、来源提示与工具界面。 */
@Composable
private fun ToolContent(
    tool: DevTool,
    item: ClipItem?,
    /** 最具体的探测结果；没有输入或没匹配上时为 `null`。 */
    type: String?,
    isRecommended: Boolean,
    host: DevToolHost,
) {
    val colors = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = tool.metadata.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
            if (isRecommended && item != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "匹配剪贴板内容",
                    fontSize = 10.sp,
                    color = colors.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(colors.primary.copy(alpha = 0.14f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Spacer(Modifier.size(2.dp))
        Text(
            text = tool.metadata.description,
            fontSize = 11.sp,
            color = MaterialTheme.hintColor,
        )

        if (item != null) {
            SourceLine(item = item, type = type)
        }

        Spacer(Modifier.size(10.dp))

        // 有界高度：工具里的编辑区用 `weight` 分配空间，父级必须先给一个确定的高度。
        Box(Modifier.weight(1f).fillMaxWidth()) {
            tool.Content(input = item, host = host)
        }
    }
}

/** 「这条输入来自哪一条剪贴板记录」＋ 自动判定的类型。 */
@Composable
private fun SourceLine(item: ClipItem, type: String?) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.onSurface.copy(alpha = 0.05f))
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        ClipperIcon(ClipperIconKind.COPY, size = 11.dp, tint = colors.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "来自剪贴板",
            fontSize = 11.sp,
            color = colors.onSurfaceVariant,
        )
        // 标题只用来提示「这条来自哪条记录」，按单行显示即可（工具要内容自己去读 `item`）。
        // 显式标注类型：`item.title` 跨模块，靠推断的话分析器不认下面那次判空。
        val sourceLabel: String = item.title
        if (sourceLabel.isNotBlank()) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = sourceLabel.replace('\n', ' '),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.hintColor,
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        if (type != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "识别为 ${typeLabel(type)}",
                fontSize = 10.sp,
                color = colors.primary,
            )
        }
    }
}

@Composable
private fun EmptyTools() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("还没有可用的开发者工具", fontSize = 12.sp, color = MaterialTheme.hintColor)
    }
}

private fun groupTools(tools: List<DevTool>): List<Pair<DevToolGroup, List<DevToolMetadata>>> =
    DevToolGroup.entries.mapNotNull { group ->
        val inGroup = tools.map { it.metadata }.filter { it.group == group }
        if (inGroup.isEmpty()) null else group to inGroup
    }

private fun typeLabel(name: String): String = when (name) {
    DataTypes.JSON -> "JSON"
    DataTypes.XML -> "XML"
    DataTypes.URL -> "链接"
    DataTypes.TEXT -> "文本"
    else -> name
}
