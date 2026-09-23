package com.qcmian.clipper.feature.history.state

import androidx.compose.runtime.Immutable
import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.model.SearchResult
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.feature.preferences.state.ShortcutRecording

/** 当前屏幕上显示的是哪个模态框（如果有）。 */
enum class ClipboardDialog { PREFERENCES }

/**
 * 「全文搜索」的状态机。它只在**有查询**时才有意义——没有查询词就没有要找的东西。
 *
 * 列表默认只搜标题（那在元数据里、已在内存），而正文留在库里、只能一批批读出来。因此它是
 * 用户显式触发的第二次搜索，入口固定在滚动列表的末尾：用户滚到那儿，本身就是「这些还不够」
 * 的表达。
 */
enum class DeepSearchState {
    /** 还没搜过：列表末尾显示触发入口。 */
    AVAILABLE,

    /** 正在分批读取正文并匹配。 */
    RUNNING,

    /** 搜完了（或到了预算上限）：入口换成结果说明。 */
    DONE,
}

/** 「清除历史」的二次确认，包含将要清除的内容。 */
data class ClearConfirmation(
    val message: String,
    val comment: String? = null,
    val all: Boolean,
    val hidePanel: Boolean,
)

/**
 * 历史界面的完整不可变描述。
 *
 * UI 只渲染这个对象，除此之外什么都不读；每一次用户交互都以 [ClipboardUiAction] 的形式回传。
 * 它是该界面的唯一数据源，由 `ClipboardViewModel` 生成。
 *
 * **注意这里装的是「全部元数据」而不是完整条目**：[results] 是按当前排序的全量元数据，
 * 图片字节在 [images] 里按视口懒加载，完整正文在 [previewItem] 里按选中项懒加载。因此
 * 历史有 10 万条还是 200 条，这个对象的大小差别只在元数据（每条几百字节）。
 */
@Immutable
data class ClipboardUiState(
    val settings: AppSettings = AppSettings(),
    /** 用户输入的内容，每敲一个键就更新。 */
    val query: String = "",
    /** 实际应用到历史上的查询词，按 节流。 */
    val appliedQuery: String = "",
    /**
     * 全文搜索的状态，以及它**此刻在列表里**补了多少条。
     *
     * 两条一起看：前者决定末尾那个入口显示什么，后者决定它说什么。它们只由
     * `ClipboardViewModel` 维护，界面只渲染——入口不自己记「点过没有」。
     *
     * 条数是「实际多出来的行数」，不是「搜索当时的命中数」：其后的删除或筛选变化会让两者
     * 不等，而入口上写着的数字必须与列表对得上。
     */
    val deepSearch: DeepSearchState = DeepSearchState.AVAILABLE,
    val deepSearchHits: Int = 0,
    /** 按 [appliedQuery] 过滤后的内容，已按显示顺序排列。 */
    val results: List<SearchResult> = emptyList(),
    /**
     * 已经取回来的图片，键是条目 id。
     *
     * 只在有图片的行**进入组合**时才装填（`LazyColumn` 只组合可见项），并随窗口一起清理，
     * 因此它的大小与视口相关，与历史里的图片总数无关。
     */
    val images: Map<String, ClipImage> = emptyMap(),
    /**
     * 当前选中条目的完整内容（含载荷）。
     *
     * [selectedMeta] 只有元数据，渲染预览面板需要真正的正文 / 图片，因此由状态持有者按 id
     * 异步补齐。条目还在路上（或本来就没有载荷）时为 `null`。
     */
    val previewItem: ClipItem? = null,
    val historySelection: Int = 0,
    /** 由历史列表持有高亮时为 `-1`。 */
    val footerSelection: Int = -1,
    /**
     * `⇧`（连续选中）的锚点。
     *
     * 单选时等于光标；`⇧`点击 / `⇧↑↓` 把锚点到光标之间的**整段**设为选中集，因此按住 `⇧`
     * 一路上下移动时会原路伸缩，而不是每按一次就从光标重新往外铺。
     */
    val selectionAnchor: Int = 0,
    /**
     * 多选（`⌘`点击 / `⌘A`）的选中集。
     *
     * 存的是**条目 id 而不是下标**：排序、筛选、新复制都会让下标指向别的条目，而 id 不会。
     * 空集表示「还没建立过选中集」，此时由光标行兜底（见 [selectedEntries]）。
     */
    val selectedIds: Set<String> = emptySet(),
    /**
     * 每当选中项因「非悬停」原因变化（键盘导航、新查询结果、面板重新打开）时自增。
     *
     * 界面的「把选中行滚进可视区」效果只跟这个令牌走：悬停同样会更新 [historySelection]，
     * 但不该带着列表滚动，因此悬停路径不递增它。
     */
    val historyScrollToken: Int = 0,
    val previewOpen: Boolean = false,
    val statusMessage: String? = null,
    /** 数据库文件占用的字节数；设置页用它显示「数据库 x MB」，平台测不到时为 `null`。 */
    val storageBytes: Long? = null,
    /** 未置顶条目的条数，与「历史上限」是同一个口径。 */
    val historyCount: Int = 0,
    val screenCount: Int = 1,
    val supportsLaunchAtLogin: Boolean = false,
    /** 宿主是否能做图片文字识别；为假时偏好设置里的识别开关会被禁用。 */
    val supportsTextRecognition: Boolean = false,
    /** 宿主是否能退出应用，决定是否多出一行「退出」页脚。 */
    val showQuit: Boolean = false,
    val dialog: ClipboardDialog? = null,
    val confirmation: ClearConfirmation? = null,
    /**
     * 偏好设置里正在录制的快捷键。
     *
     * 宿主必须知道这件事：系统级热键（呼出面板）由 Carbon 派发，**不看焦点**，录制期间照旧会
     * 触发原动作——表现为「一边录快捷键、一边把面板切走 / 选中某一条」。界面内那几类快捷键
     * 不受影响：对话框是场景里的一层，它拿到焦点后按键根本不会派发到面板（见
     * `CanvasLayersComposeScene.processKeyEvent`）。
     *
     * 状态机在 `ShortcutRecorder` 里，这里只是它的投影：设置页渲染录制态与失败原因，宿主读
     * [isRecordingShortcut]。
     */
    val shortcutRecording: ShortcutRecording = ShortcutRecording(),
    /** 每当搜索框需要重新获得焦点时自增。 */
    val focusRequestToken: Int = 0,
) {
    /**
     * 固定的置顶区块，放在滚动区之外。
     *
     * 用 `by lazy` 按状态实例缓存：状态是不可变的，只有 `copy` 才会重算，因此界面在重组中
     * （拖动窗口尺寸时每帧都会发生）反复读它也只付一次 O(n) 的代价，调用方不必自己 `remember`。
     */
    val pinnedEntries: List<IndexedValue<SearchResult>> by lazy {
        results.withIndex().filter { it.value.meta.isPinned }
    }

    /** 置顶区块下方（或上方）可滚动的历史。缓存方式同 [pinnedEntries]。 */
    val unpinnedEntries: List<IndexedValue<SearchResult>> by lazy {
        results.withIndex().filterNot { it.value.meta.isPinned }
    }

    val selectedResult: SearchResult? get() = results.getOrNull(historySelection)

    /** 当前选中条目的元数据；置顶 / 删除 / 预览都按 id 作用在它上面。 */
    val selectedMeta: ClipMeta? get() = selectedResult?.meta

    /** 与 [results] 一一对应的 id（按显示顺序）。选中集按下标取值时读它，不必每次 `map` 一遍全量。 */
    val resultIds: List<String> by lazy { results.map { it.meta.id } }

    /**
     * 要激活的条目，**按列表顺序**——连续粘贴的先后顺序就是它。
     *
     * 选中集为空时退回光标行：面板刚打开、结果刚刷新这些时刻还没有「显式的选中集」，
     * 但语义上就是「当前这一条」。
     */
    val selectedEntries: List<SearchResult> by lazy {
        if (selectedIds.isEmpty()) listOfNotNull(selectedResult) else results.filter { it.meta.id in selectedIds }
    }

    /** 待写回剪贴板的 id，顺序同 [selectedEntries]。 */
    val selectedMetaIds: List<String> get() = selectedEntries.map { it.meta.id }

    /** 选中条数。 */
    val selectionCount: Int get() = selectedEntries.size

    /** `> 1` 表示处于多选态：鼠标悬停不再改变选中集，`Esc` 先退回单选。 */
    val isMultiSelect: Boolean get() = selectionCount > 1

    /**
     * 选中集是否**全部**已置顶。
     *
     * 右键菜单据此决定显示「置顶」还是「取消置顶」——它与
     * `ClipboardViewModel.togglePinSelected` 里「只要有一条没置顶就整批置顶」是同一个判据的两面，
     * 因此菜单上写的动作一定就是点下去会发生的那个。
     */
    val isSelectionAllPinned: Boolean
        get() = selectedEntries.isNotEmpty() && selectedEntries.all { it.meta.isPinned }

    /** 该行是否被选中（选中集为空时只有光标那一行算选中）。 */
    fun isRowSelected(index: Int): Boolean {
        val result = results.getOrNull(index) ?: return false
        return if (selectedIds.isEmpty()) index == historySelection else result.meta.id in selectedIds
    }

    val isHistoryHighlighted: Boolean get() = footerSelection < 0

    /** 搜索框开启即总是显示。 */
    val searchVisible: Boolean
        get() = settings.showSearch

    /** 有对话框弹出时为 `true`，此时桌面端面板不得自动隐藏。 */
    val isModalOpen: Boolean get() = dialog != null || confirmation != null

    /** 设置页正在录制快捷键；宿主据此让出系统级热键。 */
    val isRecordingShortcut: Boolean get() = shortcutRecording.isActive

    /** `AppDelegate.isStatusItemDisabled`：已暂停记录新的复制。 */
    val isStatusItemDisabled: Boolean
        get() = settings.ignoreEvents
}
