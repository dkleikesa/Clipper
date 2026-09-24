package com.qcmian.clipper.core.domain.action

import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.ShortcutSlot
import com.qcmian.clipper.core.settings.shortcut

/**
 * 激活一条历史记录时会发生什么：**是否同时粘贴**与**是否去掉格式**这两个维度的四种组合。
 *
 * 四种组合各自是一条可录制的快捷键（见 [slot]），因此按键解析不必再从按下的修饰键去推导含义：
 * 按下哪条绑定就做哪件事。这里把两个维度写成构造参数，[pastes] / [stripsFormatting] 因此不可能与
 * 名字不一致。
 */
enum class ClipAction(val pastes: Boolean, val stripsFormatting: Boolean) {
    /** 写入剪贴板，保留富文本 / 图片 / 文件等全部表示（「激活选中项」）。 */
    COPY(pastes = false, stripsFormatting = false),

    /** 只写纯文本；文件 URL 仍写进去，因此仍然能粘贴文件（「去格式激活」）。 */
    COPY_WITHOUT_FORMATTING(pastes = false, stripsFormatting = true),

    /** 写入剪贴板，并向上一个应用合成一次粘贴（「直接粘贴」）。 */
    PASTE(pastes = true, stripsFormatting = false),

    /** 同上，但只写纯文本（「去格式粘贴」）。 */
    PASTE_WITHOUT_FORMATTING(pastes = true, stripsFormatting = true),
}

/**
 * 触发本动作的可录制槽位。
 *
 * 动作与快捷键一一对应，按键解析、右键菜单的键位提示、设置页的说明都读这一份映射，
 * 因此改绑一条快捷键不需要在别处再改一次。
 */
val ClipAction.slot: ShortcutSlot
    get() = when (this) {
        ClipAction.COPY -> ShortcutSlot.ACTIVATE
        ClipAction.COPY_WITHOUT_FORMATTING -> ShortcutSlot.ACTIVATE_WITHOUT_FORMATTING
        ClipAction.PASTE -> ShortcutSlot.PASTE
        ClipAction.PASTE_WITHOUT_FORMATTING -> ShortcutSlot.PASTE_WITHOUT_FORMATTING
    }

/**
 * 触发本动作的那条快捷键标签（例如 `⌥⏎`）；该槽位被清除（未绑定）时是空串。
 *
 * 右键菜单用它现算「复制 / 粘贴」旁边该显示什么键位：绑定由用户指定，写死 `↵` / `⌥↵`
 * 会在用户改过之后失真。
 */
fun ClipAction.shortcutLabel(settings: AppSettings): String =
    settings.shortcut(slot)?.label.orEmpty()
