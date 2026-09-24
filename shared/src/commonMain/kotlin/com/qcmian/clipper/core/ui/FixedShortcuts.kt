package com.qcmian.clipper.core.ui

import com.qcmian.clipper.core.domain.action.ClipAction
import com.qcmian.clipper.core.domain.action.modifierFlagsOf
import com.qcmian.clipper.core.settings.AppSettings

/**
 * 固定快捷键分组，只影响设置页里的呈现顺序与小标题。
 */
enum class FixedShortcutGroup(val title: String) {
    NAVIGATION("列表导航"),
    ACTIVATION("激活与选择"),
    POPUP("呼出与窗口"),
}

/**
 * 一条**不可录制**的固定快捷键。
 *
 * 这些按键不是「可替换的命令」，而是面板的交互方式本身（方向键移动、`⇧` 连续选中、
 * `⌘1`…`⌘9` 角标、`⏎` 激活）：允许改它们只会造出一个「能配但没人配」的伪功能，
 * 真正需要的是把它们的含义讲清楚。因此它们不进 [com.qcmian.clipper.core.settings.ShortcutSlot]，
 * 只在设置页里做成一张只读速查表。
 *
 * **这张表必须与 `HistoryKeyboard.resolveKeyActions` 的 `when` 分支保持一致**：那份 `when`
 * 就是这些按键的真正实现，也同时是 `ShortcutValidation` 里两个「面板保留」集合的来源。
 * 改动那张 `when` 时，这三处要一并回来核对。
 *
 * @param keys 已拼好的键位文本（多个等价组合用 ` / ` 连接），直接渲染进键位胶囊。
 *   符号与 `KeyShortcut.label` 同源（`⌘⌥⌃⇧`、`⏎`、`⎋`、`⇞⇟`），因此速查表的键位与
 *   可录制行的键位看起来是同一套。
 */
data class FixedShortcut(
    val title: String,
    val keys: String,
    val group: FixedShortcutGroup,
)

/**
 * 全部固定快捷键。
 *
 * [settings] 会参与其中三条的构造：「`⏎` + 修饰键」的含义由 `defaultAction` 按
 * `pasteByDefault` / `removeFormattingByDefault` 推导（见 [modifierFlagsOf]），
 * 呼出键那一条则要显示用户当前录制的键位——两者都不能写成常量。
 */
fun fixedShortcuts(settings: AppSettings): List<FixedShortcut> = buildList {
    // ---------------------------------------------------------------- 列表导航
    add(FixedShortcut("选中上一条 / 下一条", "↑ / ↓", FixedShortcutGroup.NAVIGATION))
    add(FixedShortcut("连续选中上 / 下若干条", "⇧↑ / ⇧↓", FixedShortcutGroup.NAVIGATION))
    add(FixedShortcut("跳到第一条", "⌘↑ / ⌥↑ / ⇞ / ⌃⌥P", FixedShortcutGroup.NAVIGATION))
    add(FixedShortcut("跳到最后一条", "⌘↓ / ⌥↓ / ⇟ / ⌃⌥N", FixedShortcutGroup.NAVIGATION))

    // ---------------------------------------------------------------- 激活与选择
    add(FixedShortcut("激活选中项", "⏎", FixedShortcutGroup.ACTIVATION))
    // 「⏎ + 修饰键」：三种动作各自对应的组合随偏好变化，这里按当前偏好现算。
    addAll(enterVariants(settings))
    add(FixedShortcut("快速激活前 9 个置顶项", "⌘1 … ⌘9", FixedShortcutGroup.ACTIVATION))
    add(FixedShortcut("全选", "⌘A", FixedShortcutGroup.ACTIVATION))

    // ---------------------------------------------------------------- 呼出与窗口
    // 「按住循环」是面板最不为人知的一处交互（见 `GlobalHotKeyController` 的持有会话）：
    // 呼出键连按或按住会在面板里逐条下选，整组键松开时才激活选中项。
    // 呼出键被清除时这一条没有键位可显示，干脆不出现。
    settings.popupShortcut?.let {
        add(FixedShortcut("连按 / 按住呼出键下选，松手激活", it.label, FixedShortcutGroup.POPUP))
    }
    add(FixedShortcut("清空搜索 / 关闭面板", "⎋", FixedShortcutGroup.POPUP))
    add(FixedShortcut("打开设置", "⌘,", FixedShortcutGroup.POPUP))
}

/**
 * `⏎` 加修饰键所对应的三个动作。
 *
 * 标签由 [modifierFlagsOf] 从 `defaultAction` 反推（与按键匹配共用同一份规则，
 * 正反两个方向不会漂移），因此「自动粘贴 / 粘贴时去除格式」一改，速查表当场跟着变。
 * 理论上三个动作总能各自找到组合，`mapNotNull` 只是防御。
 */
private fun enterVariants(settings: AppSettings): List<FixedShortcut> =
    listOf(
        ClipAction.COPY to "复制",
        ClipAction.PASTE to "粘贴",
        ClipAction.PASTE_WITHOUT_FORMATTING to "粘贴并去除格式",
    ).mapNotNull { (action, title) ->
        modifierFlagsOf(action, settings)
            .takeIf { it.isNotEmpty() }
            ?.let { FixedShortcut(title, "$it⏎", FixedShortcutGroup.ACTIVATION) }
    }
