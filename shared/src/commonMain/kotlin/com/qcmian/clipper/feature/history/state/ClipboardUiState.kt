package com.qcmian.clipper.feature.history.state

import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.SearchResult
import com.qcmian.clipper.core.settings.AppSettings

/** 当前屏幕上显示的是哪个模态框（如果有）。 */
enum class ClipboardDialog { PREFERENCES }

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
 */
data class ClipboardUiState(
    val settings: AppSettings = AppSettings(),
    /** 用户输入的内容，每敲一个键就更新。 */
    val query: String = "",
 /** 实际应用到历史上的查询词，按 节流。 */
    val appliedQuery: String = "",
    /** 按 [appliedQuery] 过滤后的历史，已按显示顺序排列。 */
    val results: List<SearchResult> = emptyList(),
    val historySelection: Int = 0,
    /** 由历史列表持有高亮时为 `-1`。 */
    val footerSelection: Int = -1,
    /**
     * 每当选中项因「非悬停」原因变化（键盘导航、新查询结果、面板重新打开）时自增。
     *
     * 界面的「把选中行滚进可视区」效果只跟这个令牌走：悬停同样会更新 [historySelection]，
     * 但不该带着列表滚动，因此悬停路径不递增它。
     */
    val historyScrollToken: Int = 0,
    val previewOpen: Boolean = false,
    val statusMessage: String? = null,
    val storageSize: String? = null,
    /** 未置顶条目占用的近似字节数，与「历史上限」比较的是同一个口径。 */
    val historyBytes: Long = 0L,
    val screenCount: Int = 1,
    val supportsLaunchAtLogin: Boolean = false,
    val supportsApplicationInfo: Boolean = false,
    /** 宿主是否能做图片文字识别；为假时偏好设置里的识别开关会被禁用。 */
    val supportsTextRecognition: Boolean = false,
    /** 宿主是否能退出应用，决定是否多出一行「退出」页脚。 */
    val showQuit: Boolean = false,
    val dialog: ClipboardDialog? = null,
    val confirmation: ClearConfirmation? = null,
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
        results.withIndex().filter { it.value.item.isPinned }
    }

    /** 置顶区块下方（或上方）可滚动的历史。缓存方式同 [pinnedEntries]。 */
    val unpinnedEntries: List<IndexedValue<SearchResult>> by lazy {
        results.withIndex().filterNot { it.value.item.isPinned }
    }

    /** 置顶项，供偏好设置编辑。缓存方式同 [pinnedEntries]。 */
    val pinnedItems: List<ClipItem> by lazy { results.map { it.item }.filter { it.isPinned } }

    val selectedResult: SearchResult? get() = results.getOrNull(historySelection)

    val selectedItem: ClipItem? get() = selectedResult?.item

    val isHistoryHighlighted: Boolean get() = footerSelection < 0

    /** 对应 `AppState.searchVisible`；搜索框开启即总是显示。 */
    val searchVisible: Boolean
        get() = settings.showSearch

    /** 有对话框弹出时为 `true`，此时桌面端面板不得自动隐藏。 */
    val isModalOpen: Boolean get() = dialog != null || confirmation != null

    /** `AppDelegate.isStatusItemDisabled`：已暂停，或者根本没有在记录任何内容。 */
    val isStatusItemDisabled: Boolean
        get() = settings.ignoreEvents ||
            (!settings.saveText && !settings.saveImages && !settings.saveFiles)
}
