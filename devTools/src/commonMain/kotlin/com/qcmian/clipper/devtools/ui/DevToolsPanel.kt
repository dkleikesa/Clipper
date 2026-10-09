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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import com.qcmian.clipper.devtools.api.DevTool
import com.qcmian.clipper.devtools.api.DevToolGroup
import com.qcmian.clipper.devtools.api.DevToolHost
import com.qcmian.clipper.devtools.api.DevToolMetadata
import com.qcmian.clipper.devtools.api.DevToolPasteKey
import com.qcmian.clipper.devtools.api.LocalDevToolPasteKey
import com.qcmian.clipper.devtools.api.devToolText
import com.qcmian.clipper.devtools.registry.DevToolsRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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
 *  - 底部一条 [ToolStatusBar]：工具报告的一行常驻状态（行数 / 字符数 / 命中数…），
 *    外加一闪而过的提示。它**只在内容区下面**，不横跨侧边栏——侧边栏因此一直铺到窗口底边。
 *
 * 「动作在上、状态在下」这条分工是刻意的：工具栏那一行的高度由按钮决定，把字符数摆进去省不下
 * 任何空间，反而让右端随着识别结果忽宽忽窄。
 *
 * @param item 打开时带过来的那条剪贴板记录；`null` 表示这次没有输入（用户从侧边栏点进来，或历史
 *   为空）。工具拿到的是整条记录，按需取正文 / 图片 / 附加表示。
 * @param onClose 关闭窗口（由宿主决定窗口存亡，见 `ClipperDevToolsWindow`）。
 * @param sidebar 左侧工具清单的形态（展开 / 收起）。由宿主持有并持久化。
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
    /** 把一张 PNG 图片写回系统剪贴板；见 [DevToolHost.copyImageToClipboard]。 */
    onCopyImageToClipboard: (ByteArray) -> Unit = {},
    sidebar: DevToolsSidebar = DevToolsSidebar.EXPANDED,
    onSidebarChange: (DevToolsSidebar) -> Unit = {},
    onPickFileToOpen: () -> String? = { null },
    onPickFileToSave: (String) -> String? = { null },
    onDroppedFilePaths: (DragAndDropEvent) -> List<String> = { emptyList() },
    /** 从一次拖放里取出被拖进来的图片字节；见 [DevToolHost.droppedImage]。 */
    onDroppedImage: (DragAndDropEvent) -> ByteArray? = { null },
    /** 剪贴板里若放着文件就给出它们的路径；见 [DevToolHost.clipboardFilePaths]。 */
    onClipboardFilePaths: () -> List<String> = { emptyList() },
    /** 剪贴板里若放着图片就给出它的字节；见 [DevToolHost.clipboardImage]。 */
    onClipboardImage: () -> ByteArray? = { null },
    /**
     * 窗口层接到的**粘贴**按键交给谁；见 [DevToolPasteKey]。
     *
     * 不提供时（离屏渲染、别处的窗口宿主）工具的输入区退回自己那层键盘拦截——那条要求输入区里
     * 有焦点节点，只够兜底。
     */
    pasteKey: DevToolPasteKey? = null,
    modifier: Modifier = Modifier,
    titleBarDragModifier: Modifier = Modifier,
) {
    // 类型探测在这里做，而不是在剪贴板那边：只有面板关心结果——它决定默认打开哪个工具、状态栏
    // 写什么类型。剪贴板只管把记录交过来，因此完全不必知道工具的存在。
    //
    // **它必须跑在后台线程上。** 探测要先把内容取成文本（文件类条目要**读文件**），再把整段喂给
    // JSON / XML 解析器——那些都是整篇全量解析。放在组合里做，等于让主线程去啃一条几兆的记录，
    // 窗口一打开就是死的。这与具体哪个工具无关，所以表现是「打开开发者工具就卡死」而不是某个
    // 工具卡死（`devToolText` 只负责取到文本，真正的大头是 `detectTypes` 里那两个解析器）。
    //
    // 探测结果**不进状态、也不交给工具**：它是面板对「这条内容是什么」的判断，只有渲染用得着；
    // 工具能不能吃某段内容由它自己声明的 `acceptedDataTypes` 决定，不需要别人告诉它。
    // 探测的是「工具实际会看到的文本」而不是 `previewText`：文件类条目要读文件内容再判类型，
    // 否则一个 .json 文件只会因为路径被判成纯文本、推不出 JSON 工具（见 `devToolText`）。
    //
    // `null` 表示**还没探完**：这期间不自动选工具（先按声明顺序显示第一个），切换到推荐工具发生在
    // 结果回来之后——通常快到看不见，内容很大时也只是一个可用的窗口等一下，而不是整个卡住。
    var detected by remember(item) { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(item) {
        detected = null
        val text = withContext(Dispatchers.Default) { item?.devToolText().orEmpty() }
        detected = withContext(Dispatchers.Default) { registry.detectTypes(text) }
    }

    var selectedId by remember { mutableStateOf<String?>(null) }
    // 用户自己点过工具没有（换一条记录就重置）。探测挪到后台之后结果可能晚到，那时用户说不定
    // 早就在用了——不能让它把用户点好的选择顶掉。换一条记录时重新允许自动推荐。
    var pickedByUser by remember(item) { mutableStateOf(false) }

    val recommended = remember(registry, detected) {
        val types = detected.orEmpty()
        registry.rankedTools(types).filter { tool -> types.any { it in tool.acceptedDataTypes } }
    }
    val recommendedId = recommended.firstOrNull()?.metadata?.id

    LaunchedEffect(item, detected, pickedByUser) {
        if (detected == null || pickedByUser) return@LaunchedEffect
        selectedId = recommendedId
            ?: selectedId?.takeIf { registry.tool(it) != null }
            ?: registry.tools.firstOrNull()?.metadata?.id
    }

    val tools = registry.tools
    // 探测没回来之前**什么都不选**。此时还不知道该用哪个工具，先挑第一个画出来、过一会儿再换掉，
    // 等于白付一次「首次渲染」的账——而冷启动时那正是最贵的一笔（字体、文本排版、Skia 都要现初始
    // 化）。空等一两帧，远比把错的那个工具整个渲染一遍划算。
    val effectiveSelectedId = if (detected == null) {
        null
    } else {
        selectedId ?: recommendedId ?: tools.firstOrNull()?.metadata?.id
    }
    val groups = remember(tools) { groupTools(tools) }
    val selectedTool = effectiveSelectedId?.let(registry::tool)

    // 剪贴板内容只交给「吃这一口」的工具，而不是无脑灌进当前选中的那个：
    //  - [DevTool.acceptsAnyInput] 的工具（通用输入、双向编解码）照旧拿原样内容；
    //  - 探测不出类型时（图片、空内容）也照旧交给工具按字节接——条码解码那张图、Base64 编码一张图
    //    都靠这一路；
    //  - 其余（JSON / XML / 时间戳这类只吃一种格式的）只在**命中**时才拿内容。手动切到一个对不上
    //    的工具时输入区留空，而不是硬灌一段它解析不了的内容、再报一堆错。
    val detectedTypes = detected.orEmpty()
    val toolInput = when {
        item == null || selectedTool == null -> null
        selectedTool.acceptsAnyInput || detectedTypes.isEmpty() -> item
        detectedTypes.any { it in selectedTool.acceptedDataTypes } -> item
        else -> null
    }

    // 「刚刚发生了什么」——一次性的，到点自己消失。
    val status = remember { mutableStateOf<String?>(null) }
    // 「眼前这份内容是什么状态」——工具报告的，一直留着；换了一条剪贴板记录就作废。
    //
    // 用 `remember {}` 而不是 `remember(item) {}`：下面那个 host 对象跨重组记忆，构造时把
    // 这几个状态的写入口闭包了进去。这里若跟着 item 换一个新实例，host 手里仍是旧的那一个——
    // 工具往后报告的一切都写进了没人再读的副本，状态栏看上去「不刷新」。重置改由下面那个
    // `LaunchedEffect(item)` 显式完成。
    // 工具报告的状态连**是哪件工具报的**一起记下。
    //
    // 记 owner 是为了换工具时旧值自动失效：`owner != 当前工具` 即不采信。原先写的是「切工具时清空」，
    // 但那与工具的报告存在时序竞争——清空可能落在报告**之后**，把新工具刚报的值抹掉（时间戳工具的
    // 「当前时间」就是这么一直显示不出来的）。归属校验没有这个竞争：值谁报的，谁才算数。
    val reportedStatus = remember { mutableStateOf<Pair<String?, String?>?>(null) }
    // 给 host 读「当前是哪件工具」，免得把 effectiveSelectedId 直接闭包进那个 `remember` 出来的对象
    // （那样它只会拿到构造时的那个工具 id）。
    val currentToolId = rememberUpdatedState(effectiveSelectedId)
    LaunchedEffect(item) {
        reportedStatus.value = null
    }

    // 宿主能力：按这几个回调一起记忆——它们一变（宿主换了实现）就重建，其余时候保持稳定，
    // 免得工具界面因为「host 引用变了」而整块重组。
    //
    // 文件那一组只把「弹对话框 / 解析拖放」转给宿主：读写文件由工具侧用 kotlinx-io 自己做
    // （见 `readTextFileOrNull`），这里因此不碰文件内容。
    val host = remember(
        onCopyToClipboard,
        onCopyImageToClipboard,
        onPickFileToOpen,
        onPickFileToSave,
        onDroppedFilePaths,
        onDroppedImage,
        onClipboardFilePaths,
        onClipboardImage,
    ) {
        object : DevToolHost {
            override fun copyToClipboard(text: String) {
                if (text.isEmpty()) return
                onCopyToClipboard(text)
                status.value = "已复制到剪贴板"
            }

            override fun copyImageToClipboard(png: ByteArray) {
                if (png.isEmpty()) return
                onCopyImageToClipboard(png)
                status.value = "已复制到剪贴板"
            }

            override fun showStatus(message: String) {
                status.value = message
            }

            override fun reportStatus(text: String?) {
                reportedStatus.value = currentToolId.value to text
            }

            override fun pickFileToOpen(): String? = onPickFileToOpen()

            override fun pickFileToSave(suggestedName: String): String? =
                onPickFileToSave(suggestedName)

            override fun droppedFilePaths(event: DragAndDropEvent): List<String> =
                onDroppedFilePaths(event)

            override fun droppedImage(event: DragAndDropEvent): ByteArray? = onDroppedImage(event)

            override fun clipboardFilePaths(): List<String> = onClipboardFilePaths()

            override fun clipboardImage(): ByteArray? = onClipboardImage()
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
                        onSelect = {
                            pickedByUser = true
                            selectedId = it
                        },
                        modifier = Modifier.width(SidebarWidth).fillMaxHeight(),
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                }

                DevToolsSidebar.COLLAPSED -> {
                    ToolRail(
                        tools = tools,
                        selectedId = effectiveSelectedId,
                        recommendedId = recommendedId,
                        onSelect = {
                            pickedByUser = true
                            selectedId = it
                        },
                        modifier = Modifier.width(RailWidth).fillMaxHeight(),
                    )
                    VerticalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
                }
            }

            Column(Modifier.weight(1f).fillMaxHeight()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    // 粘贴入口：工具的输入区靠它把处理函数登记给窗口层（见 `DevToolPasteKey`——
                    // 按键只发给焦点路径上的节点，卡片一上来就没有焦点节点了）。
                    CompositionLocalProvider(LocalDevToolPasteKey provides pasteKey) {
                        when {
                            // 还在认这段内容是什么：先不画工具（见上面 `effectiveSelectedId` 的说明）。
                            detected == null && tools.isNotEmpty() -> Identifying()
                            selectedTool == null -> EmptyTools()
                            else -> ToolContent(tool = selectedTool, item = toolInput, host = host)
                        }
                    }
                }
                // 只采信**当前工具**报的值：别的工具留下的（即便还在）不关这一件的事。
                val toolStatus = reportedStatus.value?.takeIf { it.first == effectiveSelectedId }?.second
                ToolStatusBar(message = status.value, toolStatus = toolStatus)
            }
        }
    }
}

/** 标题栏左侧的侧边栏开关：两档循环，图标跟着当前形态走。 */
@Composable
private fun SidebarToggle(sidebar: DevToolsSidebar, onChange: (DevToolsSidebar) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // 提示写在按钮上而不是藏在别处：只看图标未必猜得出「再点一下会怎样」。
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
                // 收起时用「向右展开」的图形，展开时用「向左收起」——图标说的是「点下去会怎样」。
                kind = if (sidebar == DevToolsSidebar.COLLAPSED) {
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
                    // 11sp：与应用里「次要文字」同一档（预览面板的字段名、右键菜单的快捷键都是 11）。
                    fontSize = 11.sp,
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
                // 竖向 8dp 跟着变大的字号一起长：13sp 的字挤在 7dp 里显得局促。
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            ClipperIcon(
                kind = metadata.icon,
                // 16dp：与图标栏（收起后的形态）取齐，两边看着是同一套图标；工具图标里有指纹这种
                // 细节多的图形，14dp 下有些笔画会糊掉。
                size = 16.dp,
                tint = if (selected) colors.primary else colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = metadata.name,
                // 13sp：与应用正文同一档（历史列表、页脚行、搜索框都是 13）。工具名是这一列里
                // 唯一要读的内容，不该比别处小一号。
                fontSize = 13.sp,
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
 * 图标栏：侧边栏收起后的形态。
 *
 * 存在的理由是「换工具」这个动作——收起时若把清单整条藏掉，换个工具就得先展开、点一下、再收起。
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
 * 来源横幅在用户刚按下快捷键时是废话。现在它们分别去了标题栏与悬停提示（来源那句连同底部状态栏
 * 那一段后来也一并删了）。
 */
@Composable
private fun ToolContent(
    tool: DevTool,
    item: ClipItem?,
    host: DevToolHost,
) {
    // 有界高度：工具里的编辑区用 `weight` 分配空间，父级必须先给一个确定的高度。
    // 内边距比原先各多 2dp：操作栏放大之后，内容贴着窗口边缘会显得更挤。
    Box(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
        tool.Content(input = item, host = host)
    }
}

/**
 * 窗口底部的状态栏：一行**状态**文字。
 *
 * 只铺在内容区下面（不横跨侧边栏），侧边栏因此一直延伸到窗口底边，读起来是一条完整的竖栏。
 *
 * 一行里两样东西，优先级：临时提示（刚刚发生了什么）＞ 工具报告的常驻状态（行数 / 字符数 /
 * 命中数…）。没有临时提示时，常驻状态占整行左段；有临时提示时它退到右端，短信息仍读得到。
 *
 * 这里曾经还有几样东西，均已删除：左端的「这段内容从哪来」（来自剪贴板 / 来自文件 · 路径 / 文本
 * 输入…）——内容来源对眼下要办的事没有信息量，文件路径更是与文件页上那行路径重复；类型圆点与
 * 类型名——那是面板把内容认成了什么，用户已经在对应工具里、侧边栏也高亮着；右端的代码框引擎
 * 开关——那是面板自身的技术选项，与工具状态无关。
 */
@Composable
private fun ToolStatusBar(
    message: String?,
    /** 工具报告的常驻状态；没有时为 `null`。 */
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
            Text(
                text = (message ?: toolStatus).orEmpty(),
                fontSize = 10.sp,
                // 临时提示用主色（「刚刚发生的事」）；常驻内容一律用次级色。
                color = if (message != null) colors.primary else MaterialTheme.hintColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (message != null && toolStatus != null) {
                Spacer(Modifier.width(12.dp))
                Text(toolStatus, fontSize = 10.sp, color = MaterialTheme.hintColor, maxLines = 1)
            }
        }
    }
}

@Composable
private fun EmptyTools() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("还没有可用的开发者工具", fontSize = 12.sp, color = MaterialTheme.hintColor)
    }
}

/** 类型探测还没回来时的占位。它顶多闪一两帧，绝大多数情况下快到看不见。 */
@Composable
private fun Identifying() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("正在识别内容类型…", fontSize = 12.sp, color = MaterialTheme.hintColor)
    }
}

private fun groupTools(tools: List<DevTool>): List<Pair<DevToolGroup, List<DevToolMetadata>>> =
    DevToolGroup.entries.mapNotNull { group ->
        val inGroup = tools.map { it.metadata }.filter { it.group == group }
        if (inGroup.isEmpty()) null else group to inGroup
    }


