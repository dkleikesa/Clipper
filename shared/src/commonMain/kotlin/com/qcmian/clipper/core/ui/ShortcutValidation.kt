package com.qcmian.clipper.core.ui

import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.ShortcutSlot
import com.qcmian.clipper.core.settings.ShortcutSpec
import com.qcmian.clipper.core.settings.shortcut

/**
 * 录制快捷键时，这条组合不可用的原因。`null`（即没有原因）表示可用。
 *
 * [OCCUPIED] 只能由平台给出——macOS 上没有「这个组合被谁占了」的查询接口，唯一可靠的判断
 * 就是真的注册一次（见 `NativeDataSource.isGlobalShortcutAvailable`）。
 */
enum class ShortcutProblem(val message: String) {
    /** 一个裸键会在面板里吞掉普通输入，在系统里也会抢走别的应用的正键。 */
    NEEDS_MODIFIER("至少要有一个修饰键（⌘ / ⌥ / ⌃ / ⇧）。"),

    /** 同一个组合被两个功能用，按下去只会命中先检查的那个。 */
    DUPLICATE("这个组合已经分配给其它功能了。"),

    /** 面板自己也用这个按键（导航、回车、清空搜索……），它们先于可录制快捷键执行。 */
    RESERVED("面板内置操作占用了这个按键。"),

    /** 已被系统或其它应用注册为全局热键。 */
    OCCUPIED("已被系统或其它应用占用。"),
}

/**
 * 面板自己保留的按键（按字符判定）：方向键、翻页键、回车、Esc。
 *
 * 这些分支在 `HistoryKeyboard.resolveKeyActions` 里只看按键、不看修饰键，因此任何修饰键组合
 * 都命中它们——录给可录制快捷键只会「按下去没反应」。
 */
private val PANEL_NAVIGATION_CHARACTERS = setOf(
    "\u2191", "\u2193", "\u2190", "\u2192",
    "\u2196", "\u2198", "\u21de", "\u21df",
    "\u23ce", "\u2324", "\u238b",
)

/**
 * 面板自己保留的组合（按组合判定）：`⌃⌥N`/`⌃⌥P` 跳到首尾、`⌘,` 打开设置。
 *
 * 与 [PANEL_NAVIGATION_CHARACTERS] 一样，这里列的就是 `HistoryKeyboard` 里可录制快捷键
 * **之前**的那些分支；改动那张 `when` 时要一并回来核对。
 */
private val PANEL_RESERVED_SPECS = listOf(
    ShortcutSpec("N", control = true, option = true),
    ShortcutSpec("P", control = true, option = true),
    ShortcutSpec(",", command = true),
)

/**
 * 条目快捷键（`⌘1`…`⌘9`）用的字符：前九个置顶项固定占用它们的任意修饰键变体。
 */
private const val QUICK_SELECT_CHARACTERS = "123456789"

/**
 * 纯本地的可用性检查：不碰系统、只看这条组合与面板自身的关系。
 *
 * 检查顺序由「先拦最贵的」决定：一个裸键最糟（面板与系统都会被打扰），
 * 其次是重复绑定，最后才是面板内置按键。
 *
 * 系统 / 其它应用的占用不在这里判断——那需要真的去注册一次，交给调用方（见 [ShortcutProblem.OCCUPIED]）。
 */
fun shortcutProblem(
    spec: ShortcutSpec,
    slot: ShortcutSlot,
    settings: AppSettings,
): ShortcutProblem? {
    if (!spec.command && !spec.control && !spec.option && !spec.shift) {
        return ShortcutProblem.NEEDS_MODIFIER
    }

    val duplicated = ShortcutSlot.entries.any { other ->
        other != slot && settings.shortcut(other) == spec
    }
    if (duplicated) return ShortcutProblem.DUPLICATE

    if (spec.character in PANEL_NAVIGATION_CHARACTERS) return ShortcutProblem.RESERVED
    if (spec in PANEL_RESERVED_SPECS) return ShortcutProblem.RESERVED
    // 条目快捷键只认字符，任意修饰键变体都算占用（`⌘1`、`⌥1`、`⌃⇧1` 都是第 1 条）。
    if (spec.character in QUICK_SELECT_CHARACTERS) return ShortcutProblem.RESERVED

    return null
}
