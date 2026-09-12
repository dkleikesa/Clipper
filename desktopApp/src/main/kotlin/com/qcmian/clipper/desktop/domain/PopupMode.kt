package com.qcmian.clipper.desktop.domain

import com.qcmian.clipper.core.settings.ShortcutSpec

/** 切换、打开中（尚未决定）或循环。 */
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

/** 呼出快捷键要求的修饰键掩码：循环只应在该组合完整按住时持续。 */
internal fun nsModifierMask(spec: ShortcutSpec): Int =
    (NS_SHIFT_MASK.takeIf { spec.shift } ?: 0) or
        (NS_CONTROL_MASK.takeIf { spec.control } ?: 0) or
        (NS_OPTION_MASK.takeIf { spec.option } ?: 0) or
        (NS_COMMAND_MASK.takeIf { spec.command } ?: 0)

/** 按住呼出快捷键多久后才开始循环（轻按一下就松开不会进入循环）。 */
internal const val CYCLE_START_DELAY_MILLIS = 500L

/** 循环模式下相邻两条之间的间隔。 */
internal const val CYCLE_INTERVAL_MILLIS = 120L

/** 检测修饰键是否松开的轮询间隔。 */
internal const val MODIFIER_POLL_MILLIS = 30L

/** 面板刚显示后忽略那一次短暂失焦的时长（对应 `FloatingPanel.resignKey` 的时序）。 */
internal const val FOCUS_GRACE_MILLIS = 250L

/**
 * 点击菜单栏图标会让面板先失焦。这段时间内的失焦由 [PopupMode] 的托盘切换逻辑接管，
 * 不再当作「用户点了别处」而单独收起。
 */
internal const val TRAY_CLICK_GRACE_MILLIS = 250L
