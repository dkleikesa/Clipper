package com.qcmian.clipper.feature.preferences.state

import com.qcmian.clipper.core.settings.ShortcutSlot
import com.qcmian.clipper.core.ui.ShortcutProblem

/**
 * 设置页「正在录制某个快捷键」的状态。
 *
 * 它是设置页的一次交互状态（对话框关掉就该忘掉），但仍然放进界面状态里：宿主据此停掉系统级
 * 热键（见 `ClipboardUiState.shortcutRecording`），而宿主只观察状态。
 *
 * @param slot 正在录制的槽位；`null` 表示没有在录制。
 * @param problem 上一次录制被拒绝的原因；开始新一次录制、录制成功、取消时清空。
 */
data class ShortcutRecording(
    val slot: ShortcutSlot? = null,
    val problem: ShortcutProblem? = null,
) {
    /** 是否有槽位正在录制。 */
    val isActive: Boolean get() = slot != null
}
