package com.qcmian.clipper.feature.history.state

import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.action.ClipAction
import com.qcmian.clipper.core.settings.AppSettings

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
    data object DeleteSearchChar : ClipboardUiAction
    data object DeleteSearchWord : ClipboardUiAction
    data object CopySearchQuery : ClipboardUiAction

    // ------------------------------------------------------------------ 导航
    /** `MouseMovedViewModifier`：鼠标移动会结束键盘导航。 */
    data object PointerMoved : ClipboardUiAction
    data class HoverHistory(val index: Int) : ClipboardUiAction
    data class HoverFooter(val index: Int) : ClipboardUiAction
    data class MoveNext(val allowCycle: Boolean = false) : ClipboardUiAction
    data object MovePrevious : ClipboardUiAction
    data object MoveToFirst : ClipboardUiAction
    data object MoveToLast : ClipboardUiAction

    // ------------------------------------------------------------------ 激活
    /** 回车 / 点击历史行；由修饰键决定实际的 [ClipAction]。 */
    data class Activate(
        val index: Int,
        val shift: Boolean = false,
        val alt: Boolean = false,
        val meta: Boolean = false,
    ) : ClipboardUiAction

    /** `⌘1`…`⌘9` / `⌘<字母>`：动作已经解析完毕。 */
    data class ActivateShortcut(val index: Int, val action: ClipAction) : ClipboardUiAction

    data class RunFooter(val action: FooterAction) : ClipboardUiAction

    /** `Esc`：清空搜索并关闭面板，与 `KeyChord.close` 完全一致。 */
    data object Escape : ClipboardUiAction

    // ------------------------------------------------------------------ 条目操作
    data object TogglePinSelected : ClipboardUiAction
    data object DeleteSelected : ClipboardUiAction
    data object TogglePreview : ClipboardUiAction
    data object CopyExtractedText : ClipboardUiAction
    data class SetPreviewWidth(val width: Int) : ClipboardUiAction

    data class TogglePin(val item: ClipItem) : ClipboardUiAction
    data class DeleteItem(val item: ClipItem) : ClipboardUiAction

    // ------------------------------------------------------------------ 偏好设置
    data class UpdateSettings(val transform: (AppSettings) -> AppSettings) : ClipboardUiAction
    data class UpdatePin(val item: ClipItem, val pin: String?) : ClipboardUiAction
    data class UpdateTitle(val item: ClipItem, val title: String) : ClipboardUiAction
    data class UpdateContent(val item: ClipItem, val text: String) : ClipboardUiAction
    data object PickIgnoredApplication : ClipboardUiAction

    // ------------------------------------------------------------------ 对话框
    data object ShowPreferences : ClipboardUiAction
    data object DismissPreferences : ClipboardUiAction
    data class RequestClear(val all: Boolean, val hidePanel: Boolean = true) : ClipboardUiAction
    data object ConfirmClear : ClipboardUiAction
    data object DismissClear : ClipboardUiAction

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
     */
    data object Hidden : ClipboardUiAction
}
