package com.qcmian.clipper.desktop.domain

/** 对应 Maccy 的 `PopupState`：切换、打开中（尚未决定）或循环。 */
enum class PopupMode { TOGGLE, OPENING, CYCLE }

/** 状态项点击用到的 `NSEvent.ModifierFlags` 位。 */
internal const val NS_SHIFT_MASK = 1 shl 17
internal const val NS_OPTION_MASK = 1 shl 19

/**
 * Shift / control / option / command。`NSEvent.modifierFlags` 还携带大写锁定与数字键盘，
 * 它们不该让弹窗一直停留在循环模式。
 */
internal const val NS_MODIFIER_MASK = NS_SHIFT_MASK or (1 shl 18) or NS_OPTION_MASK or (1 shl 20)

/** 按住呼出快捷键多久后才开始循环（轻按一下就松开不会进入循环）。 */
internal const val CYCLE_START_DELAY_MILLIS = 500L

/** 循环模式下相邻两条之间的间隔。 */
internal const val CYCLE_INTERVAL_MILLIS = 120L

/** 检测修饰键是否松开的轮询间隔。 */
internal const val MODIFIER_POLL_MILLIS = 30L

/** 面板刚显示后忽略那一次短暂失焦的时长（对应 `FloatingPanel.resignKey` 的时序）。 */
internal const val FOCUS_GRACE_MILLIS = 250L
