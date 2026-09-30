package com.qcmian.clipper.feature.history.state

import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.action.ClipAction
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.ShortcutSlot

/**
 * 页脚中提供的动作。
 *
 * 它属于状态层（而非 UI 层）：[ClipboardUiAction.RunFooter] 需要携带它，界面只是负责渲染。
 */
enum class FooterAction { CLEAR, PREFERENCES, QUIT }

/**
 * 用户（或宿主）可以要求历史界面做的一切。UI 只会发送这些动作；
 * `ClipboardViewModel` 是唯一对它们做出反应的地方。
 */
sealed interface ClipboardUiAction {

    // ------------------------------------------------------------------ 搜索
    data class UpdateQuery(val value: String) : ClipboardUiAction
    data object ClearSearch : ClipboardUiAction
    data object CopySearchQuery : ClipboardUiAction

    /**
     * 对**正文**再搜一次当前查询（从库中分批读取）。
     *
     * 由滚动列表末尾的入口触发：默认搜索只覆盖标题，用户在那里表达「这些还不够」。
     */
    data object RunDeepSearch : ClipboardUiAction

    // ------------------------------------------------------------------ 导航
    /** `MouseMovedViewModifier`：鼠标移动会结束键盘导航。 */
    data object PointerMoved : ClipboardUiAction
    /**
     * 鼠标划到某一行上。
     *
     * [selectionModifierHeld] 是「此刻按住 `⌘` 或 `⇧`」：按住时划过的行不能改写选中集，
     * 否则那一次点击的基准会被鼠标自己换掉，变成空操作。
     */
    data class HoverHistory(
        val index: Int,
        val selectionModifierHeld: Boolean = false,
    ) : ClipboardUiAction
    data class HoverFooter(val index: Int) : ClipboardUiAction
    data class MoveNext(val allowCycle: Boolean = false) : ClipboardUiAction
    data object MovePrevious : ClipboardUiAction
    data object MoveToFirst : ClipboardUiAction
    data object MoveToLast : ClipboardUiAction

    // ------------------------------------------------------------------ 选中
    /** 无修饰键点击：折叠为单选（光标、锚点、选中集一起落到 [index]）。 */
    data class SelectOnly(val index: Int) : ClipboardUiAction

    /** `⇧`点击 / `⇧↑↓`：从锚点**连续选中**到 [index]。 */
    data class SelectRange(val index: Int) : ClipboardUiAction

    /** `⌘`点击：切换 [index] 的选中状态。 */
    data class ToggleSelection(val index: Int) : ClipboardUiAction

    /** `Esc`：清空多选，退回单选。 */
    data object ClearSelection : ClipboardUiAction

    // ------------------------------------------------------------------ 激活
    /**
     * 对**当前选中集**执行 [action]。
     *
     * 动作由触发它的那条快捷键直接给出（见 `ClipAction.slot`）：激活的四种按法是四条独立绑定，
     * 这里不再表达「按了哪些修饰键」，含义也就不需要第二次解析。
     *
     * 不带下标：作用对象由 `ClipboardUiState.selectedIds` 唯一决定。
     */
    data class Activate(val action: ClipAction) : ClipboardUiAction

    /** `⌘1`…`⌘9`：动作已经解析完毕（快速粘贴固定是粘贴）。 */
    data class ActivateShortcut(val index: Int, val action: ClipAction) : ClipboardUiAction

    /** 复制选中集（右键菜单里的「复制」）：与 `Activate(ClipAction.COPY)` 同义。 */
    data object CopySelection : ClipboardUiAction

    /** 粘贴选中集（右键菜单里的「粘贴」）。 */
    data object PasteSelection : ClipboardUiAction

    data class RunFooter(val action: FooterAction) : ClipboardUiAction

    /** `Esc`：清空搜索并关闭面板，与 `KeyChord.close` 完全一致。 */
    data object Escape : ClipboardUiAction

    // ------------------------------------------------------------------ 条目操作
    data object TogglePinSelected : ClipboardUiAction
    data object DeleteSelected : ClipboardUiAction
    data object TogglePreview : ClipboardUiAction
    /** 暂停 / 恢复记录（可录制的 `pause` 快捷键）。 */
    data object ToggleRecordingPause : ClipboardUiAction
    data object CopyExtractedText : ClipboardUiAction
    data class SetPreviewWidth(val width: Int) : ClipboardUiAction

    data class TogglePin(val meta: ClipMeta) : ClipboardUiAction

    /**
     * 有图片的行进入组合：请求把这一条的图片取回来。
     *
     * `LazyColumn` 只组合可见项，因此这个动作天然只对视口内的行触发——列表里有多少张图片
     * 都不会一次性全进内存。
     */
    data class RequestImage(val id: String) : ClipboardUiAction

    // ------------------------------------------------------------------ 偏好设置
    data class UpdateSettings(val transform: (AppSettings) -> AppSettings) : ClipboardUiAction

    // ------------------------------------------------------------------ 对话框
    data object ShowPreferences : ClipboardUiAction
    data object DismissPreferences : ClipboardUiAction

    /**
     * 偏好设置开始录制某个槽位的快捷键。
     *
     * 宿主据此停掉系统级热键：录制期间按下的组合属于录制器，不该同时触发原动作
     * （见 `ClipboardUiState.shortcutRecording`）。
     */
    data class StartShortcutRecording(val slot: ShortcutSlot) : ClipboardUiAction

    /**
     * 结束录制（再点一次那条快捷键、录制成功由录制器自己收尾，这一条用于对话框离开屏幕）。
     *
     * 不收回它系统级热键会一直哑着。
     */
    data object CancelShortcutRecording : ClipboardUiAction
    data class RequestClear(val all: Boolean, val hidePanel: Boolean = true) : ClipboardUiAction
    data object ConfirmClear : ClipboardUiAction
    data object DismissClear : ClipboardUiAction

    // ------------------------------------------------------------------ 开发者工具
    /**
     * 用**当前选中条目**的内容打开开发者工具窗口。
     *
     * 内容刻意不随动作携带：正文只留在库里，要按条目 id 现取（见
     * `ClipboardViewModel.openDevTools`）——列表里流动的元数据里没有它。
     */
    data object OpenDevTools : ClipboardUiAction

    /** 关闭开发者工具窗口（窗口标题栏的关闭按钮）。 */
    data object CloseDevTools : ClipboardUiAction

    /**
     * 打开 / 关闭开发者工具窗口（`⇧⌘D` 这条系统级快捷键）。
     *
     * 与 [OpenDevTools] 分开，是因为它要能**从后台**触发：宿主在面板没有焦点时也会发它，此时
     * 开与关得由当前状态决定，不能只表达「打开」。
     */
    data object ToggleDevTools : ClipboardUiAction

    // ------------------------------------------------------------------ 数据库加密
    /**
     * 设置页的「数据库加密」开关被切换：[enabled] 是用户想要的目标状态。
     *
     * 它只打开密码框，真正的换钥发生在用户确认口令之后（见 [ConfirmDatabaseEncryption]）：
     * 加密涉及整库重写，不能在拨动开关的同一下发生。
     */
    data class RequestDatabaseEncryption(val enabled: Boolean) : ClipboardUiAction

    /**
     * 密码框确认：[passphrase] 是用户输入的口令——开启时是**新**口令，关闭时是**当前**口令
     * （先核对身份，再解密）。
     */
    data class ConfirmDatabaseEncryption(val passphrase: String) : ClipboardUiAction

    /** 关闭密码框（取消 / `Esc`）。 */
    data object DismissDatabaseEncryption : ClipboardUiAction

    // ------------------------------------------------------------------ 宿主驱动
    /** 全局热键打开了面板（`Popup.handleFirstKeyDown`）。 */
    data object Opened : ClipboardUiAction
    /** 面板打开时全局热键再次按下（`PopupState.cycle`）。 */
    data object Cycle : ClipboardUiAction
    /** 循环模式下修饰键松开（`Popup.handleFlagsChanged`）。 */
    data object Accept : ClipboardUiAction
    /**
     * 宿主隐藏了面板（`FloatingPanel.close`）。
     *
     * 不再顺带收起预览：预览开关是用户的选择、已随设置持久化，见 `AppSettings.previewOpen`。
     *
     * 界面会把内容区归位到第一条（`ClipboardUiState.listResetToken`）：这一跳在窗口消失的
     * 同时发生，看不见，下次打开时列表已经在顶部，不会再有「刚显示就滑一下」的闪动。
     */
    data object Hidden : ClipboardUiAction
}
