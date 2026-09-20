package com.qcmian.clipper.core.settings

/**
 * 一条可录制的快捷键所在的槽位。
 *
 * 槽位的元信息集中在这里，是因为它有三个互不相干的消费方，而它们必须始终一致：
 * - 设置页按 [title] 渲染行（声明顺序就是显示顺序，全局的排在前面）、按 [default] 恢复；
 * - 录制校验按 [global] 决定要不要去系统里试注册，并拿其余槽位查重；
 * - 宿主只注册 [global] 的槽位（面板没有焦点时也要生效），其余的由面板在按键时匹配
 *   （见 `HistoryKeyboard`）。
 *
 * [global] 的一处连带影响：全局键由系统派发，面板拿到焦点时它**也会**照发一次，因此那类槽位
 * 在面板里要么被显式接管（呼出键：往下选一条 / 挪窗口），要么得防止它被当成条目快捷键。
 *
 * [spec] 为 `null` 表示**未绑定**（用户在设置页里「清除」的结果），与「用出厂默认值」是两回事：
 * 清除之后那一次交互就没有快捷键了，只有「恢复默认设置」才会把它填回来。
 */
enum class ShortcutSlot(
    /** 设置页里显示的名字。 */
    val title: String,
    /** 是否注册为系统级热键：面板没有焦点时也要生效。 */
    val global: Boolean,
    /** 出厂默认绑定；`null` 表示默认不绑定。 */
    val default: ShortcutSpec?,
) {
    /** 默认 `⇧⌘C`。 */
    POPUP("呼出面板", global = true, default = ShortcutSpec("C", command = true, shift = true)),

    /** 默认 `⌥P`。 */
    PIN("置顶 / 取消置顶", global = false, default = ShortcutSpec("P", option = true)),

    /** 默认 `⌥⌫`。 */
    DELETE("删除选中项", global = false, default = ShortcutSpec("\u232b", option = true)),

    /** 默认 `⌃Space`。 */
    TOGGLE_PREVIEW("显示 / 隐藏预览", global = false, default = ShortcutSpec(" ", control = true)),

    /**
     * 暂停 / 恢复记录，默认 `⌘P`。
     *
     * 只在面板里有焦点时生效（`global = false`）：它切换的是「接下来还记不记录」，
     * 打开面板那一刻正好能看到开关状态与暂停横幅，比在别的应用里盲按更可控。
     */
    PAUSE("暂停 / 恢复记录", global = false, default = ShortcutSpec("P", command = true)),
}

/** 该槽位当前的绑定；`null` 表示未绑定。 */
fun AppSettings.shortcut(slot: ShortcutSlot): ShortcutSpec? = when (slot) {
    ShortcutSlot.POPUP -> popupShortcut
    ShortcutSlot.PAUSE -> pauseShortcut
    ShortcutSlot.PIN -> pinShortcut
    ShortcutSlot.DELETE -> deleteShortcut
    ShortcutSlot.TOGGLE_PREVIEW -> togglePreviewShortcut
}

/** 把 [slot] 的绑定换成 [spec]；[spec] 为 `null` 即清除绑定。 */
fun AppSettings.withShortcut(slot: ShortcutSlot, spec: ShortcutSpec?): AppSettings = when (slot) {
    ShortcutSlot.POPUP -> copy(popupShortcut = spec)
    ShortcutSlot.PAUSE -> copy(pauseShortcut = spec)
    ShortcutSlot.PIN -> copy(pinShortcut = spec)
    ShortcutSlot.DELETE -> copy(deleteShortcut = spec)
    ShortcutSlot.TOGGLE_PREVIEW -> copy(togglePreviewShortcut = spec)
}
