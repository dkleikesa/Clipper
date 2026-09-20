package com.qcmian.clipper.desktop.domain

import com.qcmian.clipper.core.settings.ShortcutSpec

/**
 * 弹窗当前所处的阶段：一次「按住会话」的投影。
 *
 * - [TOGGLE]：面板已显示，但没有进行中的按住会话；
 * - [OPENING]：会话还在等——等 [CYCLE_START_DELAY_MILLIS] 走完，或者只松了组合键里的一部分、
 *   正挂着等剩下的键（这时不选中也不关窗）；
 * - [CYCLE]：组合键完整按着，正在按 [CYCLE_INTERVAL_MILLIS] 逐条往下走。
 *
 * 「按住」的判定本身由 `DesktopShellViewModel` 的会话时钟决定，不读这个值；
 * 它只用来分辨「托盘呼出的面板、并且当前没有在循环」这种要单独处理的情况。
 */
enum class PopupMode { TOGGLE, OPENING, CYCLE }

/** `NSEvent.ModifierFlags` 的各位。 */
internal const val NS_SHIFT_MASK = 1 shl 17
internal const val NS_CONTROL_MASK = 1 shl 18
internal const val NS_OPTION_MASK = 1 shl 19
internal const val NS_COMMAND_MASK = 1 shl 20

/**
 * Shift / control / option / command。`NSEvent.modifierFlags` 还携带大写锁定与数字键盘，
 * 它们不该让弹窗一直停留在循环模式。
 */
internal const val NS_MODIFIER_MASK = NS_SHIFT_MASK or NS_CONTROL_MASK or NS_OPTION_MASK or NS_COMMAND_MASK

/**
 * 呼出快捷键要求的修饰键掩码：只有这一组键完整按着才继续循环。
 *
 * 完全没有修饰键时退回 `⌘`，与 [com.qcmian.clipper.core.platform.macos.MacGlobalHotKey.register]
 * 注册时用的掩码保持一致——那里也会给裸键补上 `⌘`，否则一个裸键会成为全局热键吞掉别处的输入。
 */
internal fun nsModifierMask(spec: ShortcutSpec): Int {
    val mask =
        (NS_SHIFT_MASK.takeIf { spec.shift } ?: 0) or
            (NS_CONTROL_MASK.takeIf { spec.control } ?: 0) or
            (NS_OPTION_MASK.takeIf { spec.option } ?: 0) or
            (NS_COMMAND_MASK.takeIf { spec.command } ?: 0)
    return mask.takeIf { it != 0 } ?: NS_COMMAND_MASK
}

/** 按住呼出快捷键多久后才开始循环（轻按一下就松开不会进入循环）。 */
internal const val CYCLE_START_DELAY_MILLIS = 500L

/** 循环模式下相邻两条之间的间隔。 */
internal const val CYCLE_INTERVAL_MILLIS = 120L

/**
 * 按住会话里采样组合键状态的间隔。
 *
 * 必须远小于 [CYCLE_INTERVAL_MILLIS]：松掉其中任意一个键最多隔这么久就会被发现，
 * 采样一旦跟着循环节奏走，「松一个键再按回来」就会被整段跳过。
 */
internal const val MODIFIER_POLL_MILLIS = 30L

/** 面板刚显示后忽略那一次短暂失焦的时长。 */
internal const val FOCUS_GRACE_MILLIS = 250L

/**
 * 点击菜单栏图标会让面板先失焦。这段时间内的失焦由 [PopupMode] 的托盘切换逻辑接管，
 * 不再当作「用户点了别处」而单独收起。
 */
internal const val TRAY_CLICK_GRACE_MILLIS = 250L

/**
 * 主窗口标题。面板外点击监视器用它在本地事件里识别「点击落在面板自身」；
 * 必须与 [com.qcmian.clipper.desktop.ui.ClipperWindow] 的 `title` 保持一致。
 */
internal const val PANEL_WINDOW_TITLE = "Clipper"
