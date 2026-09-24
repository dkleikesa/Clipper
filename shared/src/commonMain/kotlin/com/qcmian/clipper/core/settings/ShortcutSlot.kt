package com.qcmian.clipper.core.settings

/** 快捷键在设置页里的分组；声明顺序就是显示顺序。 */
enum class ShortcutGroup(val title: String) {
    WINDOW("呼出与窗口"),
    NAVIGATION("列表导航"),
    ACTIVATION("激活与选择"),
    ITEM("条目与记录"),
}

/**
 * 一条可录制的快捷键所在的槽位。
 *
 * 槽位的元信息集中在这里，是因为它有几个互不相干的消费方，而它们必须始终一致：
 * - 设置页按 [group] 分组、按 [title] 渲染行、按 [default] 恢复；
 * - 录制校验按 [global] 决定要不要去系统里试注册，并拿其余槽位（含派生组合）查重；
 * - 宿主只注册 [global] 的槽位（面板没有焦点时也要生效），其余的由面板在按键时匹配
 *   （见 `HistoryKeyboard`）。
 *
 * [spec] 为 `null` 表示**未绑定**（用户在设置页里「清除」的结果），与「用出厂默认值」是两回事：
 * 清除之后那一次交互就没有快捷键了，只有「恢复默认设置」才会把它填回来。
 *
 * 面板内置按键（方向键、`⏎`、`⎋`、`⌘,`、`⌘1…⌘9` 等）过去是写死的、只在设置页里做一张只读
 * 速查表；现在它们同样进这张表，因此设置页只有一种行、一种交互。
 */
enum class ShortcutSlot(
    /** 设置页里显示的名字。 */
    val title: String,
    /** 设置页里的分组。 */
    val group: ShortcutGroup,
    /** 是否注册为系统级热键：面板没有焦点时也要生效。 */
    val global: Boolean,
    /** 出厂默认绑定；`null` 表示默认不绑定。 */
    val default: ShortcutSpec?,
    /** 标题下的灰色小字说明（静态部分）；没有就不显示。 */
    val hint: String? = null,
) {
    // ---------------------------------------------------------------- 呼出与窗口
    /** 默认 `⇧⌘C`。 */
    POPUP("呼出面板", ShortcutGroup.WINDOW, global = true, default = ShortcutSpec("C", command = true, shift = true)),
    /** 默认 `⎋`。 */
    CLOSE("清空搜索 / 关闭面板", ShortcutGroup.WINDOW, global = false, default = ShortcutSpec("\u238b")),
    /** 默认 `⌘,`。 */
    OPEN_SETTINGS("打开设置", ShortcutGroup.WINDOW, global = false, default = ShortcutSpec(",", command = true)),

    // ---------------------------------------------------------------- 列表导航
    /** 默认 `↑`；按住 `⇧` 变成连续选中。 */
    MOVE_PREVIOUS(
        "选中上一条", ShortcutGroup.NAVIGATION, global = false,
        default = ShortcutSpec("\u2191"), hint = "按住 ⇧ 连续选中",
    ),
    /** 默认 `↓`；按住 `⇧` 变成连续选中。 */
    MOVE_NEXT(
        "选中下一条", ShortcutGroup.NAVIGATION, global = false,
        default = ShortcutSpec("\u2193"), hint = "按住 ⇧ 连续选中",
    ),
    /** 默认 `⌘↑`。 */
    MOVE_TO_FIRST("跳到第一条", ShortcutGroup.NAVIGATION, global = false, default = ShortcutSpec("\u2191", command = true)),
    /** 默认 `⌘↓`。 */
    MOVE_TO_LAST("跳到最后一条", ShortcutGroup.NAVIGATION, global = false, default = ShortcutSpec("\u2193", command = true)),

    // ---------------------------------------------------------------- 激活与选择
    /** 默认 `⏎`；含义随修饰键与「默认粘贴 / 去格式」偏好变化（见 `ClipAction.defaultAction`）。 */
    ACTIVATE("激活选中项", ShortcutGroup.ACTIVATION, global = false, default = ShortcutSpec("\u23ce")),
    /** 默认 `⌘1`；配合 `1…9` 激活前九个置顶项，含义同 [ACTIVATE]。 */
    QUICK_SELECT(
        "快速激活置顶项", ShortcutGroup.ACTIVATION, global = false,
        default = ShortcutSpec("1", command = true), hint = "配合数字键 1…9",
    ),
    /** 默认 `⌘A`。 */
    SELECT_ALL("全选", ShortcutGroup.ACTIVATION, global = false, default = ShortcutSpec("A", command = true)),

    // ---------------------------------------------------------------- 条目与记录
    /** 默认 `⌥P`。 */
    PIN("置顶 / 取消置顶", ShortcutGroup.ITEM, global = false, default = ShortcutSpec("P", option = true)),
    /** 默认 `⌥⌫`。 */
    DELETE("删除选中项", ShortcutGroup.ITEM, global = false, default = ShortcutSpec("\u232b", option = true)),
    /** 默认 `⌃Space`。 */
    TOGGLE_PREVIEW("显示 / 隐藏预览", ShortcutGroup.ITEM, global = false, default = ShortcutSpec(" ", control = true)),
    /**
     * 暂停 / 恢复记录，默认 `⌘P`。
     *
     * 只在面板里有焦点时生效（`global = false`）：它切换的是「接下来还记不记录」，
     * 打开面板那一刻正好能看到开关状态与暂停横幅，比在别的应用里盲按更可控。
     */
    PAUSE("暂停 / 恢复记录", ShortcutGroup.ITEM, global = false, default = ShortcutSpec("P", command = true)),
}

/** 快速激活（[ShortcutSlot.QUICK_SELECT]）使用的数字键。 */
const val QUICK_SELECT_DIGITS = "123456789"

/** 该槽位当前的绑定；`null` 表示未绑定。 */
fun AppSettings.shortcut(slot: ShortcutSlot): ShortcutSpec? = when (slot) {
    ShortcutSlot.POPUP -> popupShortcut
    ShortcutSlot.CLOSE -> closeShortcut
    ShortcutSlot.OPEN_SETTINGS -> openSettingsShortcut
    ShortcutSlot.MOVE_PREVIOUS -> movePreviousShortcut
    ShortcutSlot.MOVE_NEXT -> moveNextShortcut
    ShortcutSlot.MOVE_TO_FIRST -> moveToFirstShortcut
    ShortcutSlot.MOVE_TO_LAST -> moveToLastShortcut
    ShortcutSlot.ACTIVATE -> activateShortcut
    ShortcutSlot.QUICK_SELECT -> quickSelectShortcut
    ShortcutSlot.SELECT_ALL -> selectAllShortcut
    ShortcutSlot.PIN -> pinShortcut
    ShortcutSlot.DELETE -> deleteShortcut
    ShortcutSlot.TOGGLE_PREVIEW -> togglePreviewShortcut
    ShortcutSlot.PAUSE -> pauseShortcut
}

/** 把 [slot] 的绑定换成 [spec]；[spec] 为 `null` 即清除绑定。 */
fun AppSettings.withShortcut(slot: ShortcutSlot, spec: ShortcutSpec?): AppSettings = when (slot) {
    ShortcutSlot.POPUP -> copy(popupShortcut = spec)
    ShortcutSlot.CLOSE -> copy(closeShortcut = spec)
    ShortcutSlot.OPEN_SETTINGS -> copy(openSettingsShortcut = spec)
    ShortcutSlot.MOVE_PREVIOUS -> copy(movePreviousShortcut = spec)
    ShortcutSlot.MOVE_NEXT -> copy(moveNextShortcut = spec)
    ShortcutSlot.MOVE_TO_FIRST -> copy(moveToFirstShortcut = spec)
    ShortcutSlot.MOVE_TO_LAST -> copy(moveToLastShortcut = spec)
    ShortcutSlot.ACTIVATE -> copy(activateShortcut = spec)
    ShortcutSlot.QUICK_SELECT -> copy(quickSelectShortcut = spec)
    ShortcutSlot.SELECT_ALL -> copy(selectAllShortcut = spec)
    ShortcutSlot.PIN -> copy(pinShortcut = spec)
    ShortcutSlot.DELETE -> copy(deleteShortcut = spec)
    ShortcutSlot.TOGGLE_PREVIEW -> copy(togglePreviewShortcut = spec)
    ShortcutSlot.PAUSE -> copy(pauseShortcut = spec)
}

/**
 * 「激活键 + 修饰键」会额外命中的四种组合：`⌘` / `⌥` / `⌥⇧` / `⌘⇧`。
 *
 * 只关心键位（字符占位，稍后被替换成真正的激活键），含义随「默认粘贴 / 去格式」偏好变化，
 * 与 `ClipAction.defaultAction` 的映射表同源。
 */
private val ACTIVATE_MODIFIER_SPECS = listOf(
    ShortcutSpec(" ", command = true),
    ShortcutSpec(" ", option = true),
    ShortcutSpec(" ", shift = true, option = true),
    ShortcutSpec(" ", shift = true, command = true),
)

/**
 * 该槽位在实际按键解析中会命中的**全部**组合，含派生变体：
 * - [ShortcutSlot.MOVE_NEXT] / [ShortcutSlot.MOVE_PREVIOUS] 额外占用「再加一个 `⇧`」的组合
 *   （连续选中）；
 * - [ShortcutSlot.ACTIVATE] 额外占用四种修饰键组合。
 *
 * 录制查重按这张表而不是按单条绑定：用户把别的功能录成 `⌘⏎` 时，它和激活键的派生变体
 * 同样会打架，必须在录制时就拦住。
 */
fun ShortcutSlot.occupiedSpecs(spec: ShortcutSpec): List<ShortcutSpec> = when (this) {
    ShortcutSlot.MOVE_NEXT, ShortcutSlot.MOVE_PREVIOUS ->
        if (spec.hasModifiers) listOf(spec) else listOf(spec, spec.copy(shift = true))

    ShortcutSlot.ACTIVATE -> listOf(spec) + spec.activationVariants()

    else -> listOf(spec)
}

/** 激活键的派生组合（`⌘` / `⌥` / `⌥⇧` / `⌘⇧`）；绑带修饰键时为空。 */
fun ShortcutSpec.activationVariants(): List<ShortcutSpec> =
    if (hasModifiers) {
        emptyList()
    } else {
        ACTIVATE_MODIFIER_SPECS.map { combo ->
            copy(
                control = combo.control,
                option = combo.option,
                shift = combo.shift,
                command = combo.command,
            )
        }
    }

/** 该绑定是否带了至少一个修饰键。 */
val ShortcutSpec.hasModifiers: Boolean get() = control || option || shift || command
