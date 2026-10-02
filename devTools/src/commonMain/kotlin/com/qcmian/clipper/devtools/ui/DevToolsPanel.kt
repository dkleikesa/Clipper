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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.settings.DevToolsSidebar
import com.qcmian.clipper.core.ui.components.ClipperTitleBar
import com.qcmian.clipper.core.ui.components.HoverTooltip
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
import kotlinx.coroutines.delay

/** 够放下一行工具名。 */
private val SidebarWidth = 216.dp

/** 图标栏宽度：32dp 的图标槽 + 两侧各 8dp。 */
private val RailWidth = 48.dp

/** 与剪贴板面板里那条提示保持一致。 */
private const val STATUS_DURATION_MILLIS = 1_600L

/**
 * 开发者工具主面板：左边是工具清单，右边是当前工具的界面。
 *
 * 它自己不认识任何具体工具——工具清单、分组、推荐顺序都来自 [registry]，界面则交给
 * [DevTool.Content]。
 *
 * **窗口的纵向空间分成三层，各司其职**：
 *  - 顶部一条 [ClipperTitleBar]：侧边栏开关 + **当前工具名** + 关闭。工具名只出现在这里，
 *    工具内部因此不再需要一行标题。
 *  - 中间整块给工具（工具栏 + 编辑区），由工具自己排。
 *  - 底部一条 [ToolStatusBar]：这段内容是什么（识别类型、来源）、有多大（行数 / 字符数），
 *    外加一闪而过的提示。它**只在内容区下面**，不横跨侧边栏——侧边栏因此一直铺到窗口底边。
 *
 * 「动作在上、状态在下」这条分工是刻意的：工具栏那一行的高度由按钮决定，把字符数摆进去省不下
 * 任何空间，反而让右端随着识别结果忽宽忽窄。
 *
 * @param item 打开时带过来的那条剪贴板记录；`null` 表示这次没有输入（用户从侧边栏点进来，或历史
 *   为空）。工具拿到的是整条记录，按需取正文 / 图片 / 附加表示。
 * @param onClose 关闭窗口（由宿主决定窗口存亡，见 `ClipperDevToolsWindow`）。
 * @param sidebar 左侧工具清单的形态（展开 / 图标栏 / 隐藏）。由宿主持有并持久化。
 * @param onSidebarChange 用户切换了侧边栏形态。
 * @param onPickFileToOpen 弹「打开」对话框并返回路径；`null` 表示取消。工具那边用
 *   `readTextFileOrNull` 读内容——面板不碰文件内容。
 * @param onPickFileToSave 弹「保存」对话框并返回路径；`null` 表示取消。
 * @param onDroppedFilePaths 从一次拖放里取出文件路径；由宿主解析平台载荷（见 [DevToolHost]）。
 * @param titleBarDragModifier 「按住标题栏拖动窗口」的手势，由宿主注入。
 */
@Composable
fun DevToolsPanel(
    registry: DevToolsRegistry,
    item: ClipItem?,
    onClose: () -> Unit,
    onCopyToClipboard: (String) -> Unit,
    sidebar: DevToolsSidebar = DevToolsSidebar.EXPANDED,
    onSidebarChange: (DevToolsSidebar) -> Unit = {},
    onPickFileToOpen: () -> String? = { null },
    onPickFileToSave: (String) -> String? = { null },
    onDroppedFilePaths: (DragAndDropEvent) -> List<String> = { emptyList() },
    modifier: Modifier = Modifier,
    titleBarDragModifier: Modifier = Modifier,
) {
    // 类型探测在这里做，而不是在剪贴板那边：只有面板关心结果——它决定默认打开哪个工具、状态栏
    // 写什么类型。剪贴板只管把记录交过来，因此完全不必知道工具的存在。
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

    // 「刚刚发生了什么」——一次性的，到点自己消失。
    val status = remember { mutableStateOf<String?>(null) }
    // 「眼前这份内容是什么状态」——工具报告的，一直留着；换了一条剪贴板记录就作废。
    var toolStatus by remember(item) { mutableStateOf<String?>(null) }
    // 宿主能力：按这几个回调一起记忆——它们一变（宿主换了实现）就重建，其余时候保持稳定，
    // 免得工具界面因为「host 引用变了」而整块重组。
    //
    // 文件那一组只把「弹对话框 / 解析拖放」转给宿主：读写文件由工具侧用 kotlinx-io 自己做
    // （见 `readTextFileOrNull`），这里因此不碰文件内容。
    val host = remember(onCopyToClipboard, onPickFileToOpen, onPickFileToSave, onDroppedFilePaths) {
        object : DevToolHost {
            override fun copyToClipboard(text: String) {
                if (text.isEmpty()) return
                onCopyToClipboard(text)
                status.value = "已复制到剪贴板"
            }

            override fun showStatus(message: String) {
                status.value = message
            }

            override fun reportStatus(text: String?) {
                toolStatus = text
            }

            override fun pickFileToOpen(): String? = onPickFileToOpen()

            override fun pickFileToSave(suggestedName: String): String? =
                onPickFileToSave(suggestedName)

            override fun droppedFilePaths(event: DragAndDropEvent): List<String> =
                onDroppedFilePaths(event)
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
            // 工具名当窗口标题：这个窗口的内容就是它，侧边栏里还高亮着同一个词，再在内容区顶部
            // 重复一行是白占地方。没有可用工具时才退回窗口自己的名字。
            title = selectedTool?.metadata?.name ?: "开发者工具",
            onClose = onClose,
            closeTooltip = "关闭",
            dragModifier = titleBarDragModifier,
            leading = { SidebarToggle(sidebar, onSidebarChange) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))

        Row(Modifier.weight(1f).fillMaxWidth()) {
            when (sidebar) {
                DevToolsSidebar.EXPANDED -> {
                    ToolSidebar(
                        groups = groups,
                        selectedId = effectiveSelectedId,
                        recommendedId = recommendedId,
                        onSelect = { selectedId = it },
                        modifier = Modifier.width(SidebarWidth).fillMaxHeight(),
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                }

                DevToolsSidebar.RAIL -> {
                    ToolRail(
                        tools = tools,
                        selectedId = effectiveSelectedId,
                        recommendedId = recommendedId,
                        onSelect = { selectedId = it },
                        modifier = Modifier.width(RailWidth).fillMaxHeight(),
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                }

                DevToolsSidebar.HIDDEN -> Unit
            }

            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (selectedTool == null) {
                        EmptyTools()
                    } else {
                        ToolContent(tool = selectedTool, item = item, host = host)
                    }
                }
                ToolStatusBar(
                    type = detected.firstOrNull(),
                    item = item,
                    message = status.value,
                    toolStatus = toolStatus,
                )
            }
        }
    }
}

/** 标题栏左侧的侧边栏开关：三档循环，图标跟着当前形态走。 */
@Composable
private fun SidebarToggle(sidebar: DevToolsSidebar, onChange: (DevToolsSidebar) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // 提示写在按钮上而不是藏在别处：三档循环只看图标猜不出「再点一下会怎样」。
    HoverTooltip(
        text = "侧边栏：${sidebar.label}（点击切换）",
        positioning = TooltipAnchorPosition.Below,
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (hovered) colors.onSurface.copy(alpha = 0.08f) else Color.Transparent)
                .hoverable(interaction)
                .clickable(interactionSource = interaction, indication = null) {
                    onChange(sidebar.next())
                },
            contentAlignment = Alignment.Center,
        ) {
            ClipperIcon(
                // 已经藏起来时用「向右展开」的图形，其余两档都是「向左收起」——一个图标说不出
                // 三档，但至少能说出「点下去是收还是放」。
                kind = if (sidebar == DevToolsSidebar.HIDDEN) {
                    ClipperIconKind.SIDEBAR_RIGHT
                } else {
                    ClipperIconKind.SIDEBAR_LEFT
                },
                size = 15.dp,
                tint = colors.onSurfaceVariant,
            )
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
    Column(modifier.padding(vertical = 6.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            groups.forEach { (group, tools) ->
                Text(
                    text = group.label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.hintColor,
                    modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 3.dp),
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

/**
 * 一行工具：图标 + 名字，说明挪进悬停提示。
 *
 * 说明曾经直接排在名字下面，结果是 216dp 宽的一列里每一行都被截断（「美化或压缩 XML，非良构
 * 的输入…」）——截断比不显示更糟，它占着一行的高度却给不出完整信息。工具变多之后这一点更明显：
 * 两行一项时 640dp 高的窗口只装得下十来个工具。
 */
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

    HoverTooltip(text = metadata.description, positioning = TooltipAnchorPosition.Above) {
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
                .padding(horizontal = 8.dp, vertical = 7.dp),
        ) {
            ClipperIcon(
                kind = metadata.icon,
                size = 14.dp,
                tint = if (selected) colors.primary else colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(9.dp))
            Text(
                text = metadata.name,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) colors.primary else colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            // 推荐只用一个小圆点标出来。原先是一个「推荐」文字块，和内容区那枚「匹配剪贴板内容」
            // 说的是同一件事，两块实心标签抢的却是同一处注意力。
            if (recommended && !selected) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(colors.primary))
            }
        }
    }
}

/**
 * 图标栏：侧边栏收起后的中间档。
 *
 * 存在的理由是「换工具」这个动作——完全隐藏之后，换一个工具要先展开侧边栏、点一下、再收起来。
 * 图标栏只占 48dp，把这件事压回一次点击。
 */
@Composable
private fun ToolRail(
    tools: List<DevTool>,
    selectedId: String?,
    recommendedId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier.padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        var previousGroup: DevToolGroup? = null
        tools.forEach { tool ->
            val metadata = tool.metadata
            // 分组之间拉一条分隔线：图标栏里没有分组标题，不给一点提示就分不出「格式化」到哪儿
            // 结束、「编解码」从哪儿开始。
            if (previousGroup != null && previousGroup != metadata.group) {
                Box(
                    Modifier.padding(vertical = 6.dp).width(20.dp).height(1.dp)
                        .background(colors.outline.copy(alpha = 0.5f))
                )
            }
            previousGroup = metadata.group

            val selected = metadata.id == selectedId
            // 推荐位在图标栏里没有文字可挂，只能点一个小圆点。**必须点在那一枚图标自己身上**：
            // 点在整列末尾的话，它跟到底推荐了哪一个完全没有关系，读起来只会让人以为最后一个
            // 工具被推荐了。
            val recommended = metadata.id == recommendedId && !selected
            val interaction = remember(metadata.id) { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            HoverTooltip(text = metadata.name, positioning = TooltipAnchorPosition.Above) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .size(32.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(
                            when {
                                selected -> colors.primary.copy(alpha = 0.16f)
                                hovered -> colors.onSurface.copy(alpha = 0.06f)
                                else -> Color.Transparent
                            },
                        )
                        .hoverable(interaction)
                        .clickable(interactionSource = interaction, indication = null) {
                            onSelect(metadata.id)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    ClipperIcon(
                        kind = metadata.icon,
                        size = 16.dp,
                        tint = if (selected) colors.primary else colors.onSurfaceVariant,
                    )
                    if (recommended) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp)
                                .size(4.dp).clip(CircleShape).background(colors.primary)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 内容区：只留给工具自己。
 *
 * 这里曾经还要画三行——工具名、一句话说明、以及「来自剪贴板 …… 识别为 JSON」的横幅，加起来
 * 约 82dp。三行里没有一行是动作：工具名与侧边栏高亮的那一项重复，说明是看一次就够的引导文案，
 * 来源横幅在用户刚按下快捷键时是废话。现在它们分别去了标题栏、悬停提示与底部状态栏。
 */
@Composable
private fun ToolContent(
    tool: DevTool,
    item: ClipItem?,
    host: DevToolHost,
) {
    // 有界高度：工具里的编辑区用 `weight` 分配空间，父级必须先给一个确定的高度。
    Box(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
        tool.Content(input = item, host = host)
    }
}

/**
 * 窗口底部的状态栏：左边是「这段内容是什么」，右边是「它有多大」。
 *
 * 只铺在内容区下面（不横跨侧边栏），侧边栏因此一直延伸到窗口底边，读起来是一条完整的竖栏。
 *
 * 临时提示占的是**来源那一段的位置**：它曾经是浮在内容上的一枚气泡（`StatusToast`），正好压在
 * 最后几行代码上。同一块地方轮流显示「这段内容从哪来」和「刚刚做了什么」，两件事不会同时需要。
 */
@Composable
private fun ToolStatusBar(
    /** 最具体的探测结果；没有输入或没匹配上时为 `null`。 */
    type: String?,
    item: ClipItem?,
    message: String?,
    toolStatus: String?,
) {
    val colors = MaterialTheme.colorScheme
    Column {
        HorizontalDivider(color = colors.outline.copy(alpha = 0.5f))
        Row(
            Modifier.fillMaxWidth().height(24.dp).background(colors.surface)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (type != null) {
                Box(Modifier.size(5.dp).clip(CircleShape).background(colors.primary))
                Spacer(Modifier.width(6.dp))
                Text(typeLabel(type), fontSize = 10.sp, color = colors.primary)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = message ?: sourceLabel(item),
                fontSize = 10.sp,
                // 提示用主色，与左边那枚类型圆点一起成为「刚发生的事」；常驻的来源说明是灰的。
                color = if (message != null) colors.primary else MaterialTheme.hintColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (toolStatus != null) {
                Spacer(Modifier.width(12.dp))
                Text(toolStatus, fontSize = 10.sp, color = MaterialTheme.hintColor, maxLines = 1)
            }
        }
    }
}

/** 没有输入时也写一句，免得状态栏左半边空着像是坏了。 */
private fun sourceLabel(item: ClipItem?): String {
    if (item == null) return "未带入剪贴板内容"
    val title = item.title.replace('\n', ' ').trim()
    return if (title.isBlank()) "来自剪贴板" else "来自剪贴板 · $title"
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
