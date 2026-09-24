package com.qcmian.clipper.core.ui

import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.QUICK_SELECT_DIGITS
import com.qcmian.clipper.core.settings.ShortcutSlot
import com.qcmian.clipper.core.settings.ShortcutSpec
import com.qcmian.clipper.core.settings.hasModifiers
import com.qcmian.clipper.core.settings.occupiedSpecs
import com.qcmian.clipper.core.settings.shortcut

/**
 * 录制快捷键时，这条组合不可用的原因。`null`（即没有原因）表示可用。
 *
 * [OCCUPIED] 只能由平台给出——macOS 上没有「这个组合被谁占了」的查询接口，唯一可靠的判断
 * 就是真的注册一次（见 `NativeDataSource.isGlobalShortcutAvailable`）。
 */
enum class ShortcutProblem(val message: String) {
    /** 单按一个可输入字符会在面板里吞掉普通输入，全局键则会在系统范围内抢键。 */
    NEEDS_MODIFIER("这个按键不能单独使用，请至少按一个修饰键（⌘ / ⌥ / ⌃ / ⇧）。"),

    /** 同一个组合被两个功能用，按下去只会命中先检查的那个。 */
    DUPLICATE("这个组合已经分配给其它功能了。"),

    /** 面板自己也用这个按键（快速粘贴的数字键），它们先于普通快捷键执行。 */
    RESERVED("面板内置操作占用了这个按键。"),

    /** 快速粘贴只认数字键：**只有修饰键有意义**，数字只是占位。 */
    QUICK_SELECT_DIGIT("请按住想要的修饰键，再按一个数字键（数字只是占位）。"),

    /** 已被系统或其它应用注册为全局热键。 */
    OCCUPIED("已被系统或其它应用占用。"),
}

/**
 * 可输入字符（字母 / 数字 / 标点 / 空格）：单按它们会与搜索输入冲突，必须带修饰键。
 *
 * 方向键、回车、`⎋`、功能键等非输入字符不受此限——它们的默认绑定本来就是裸键
 * （`↑` / `↓` / `⏎` / `⎋`），单按不会吞掉打字。
 */
private val TYPING_CHARACTERS: Set<String> = buildSet {
    ('A'..'Z').forEach { add(it.toString()) }
    ('0'..'9').forEach { add(it.toString()) }
    addAll(listOf(",", ".", "/", ";", "'", "[", "]", "\\", "-", "=", "`", " "))
}

/**
 * 纯本地的可用性检查：不碰系统、只看这条组合与面板自身的关系。
 *
 * 检查顺序由「先拦最贵的」决定：一个会吞掉输入的裸键最糟，其次是重复绑定（含派生组合），
 * 最后才是面板内置的数字键。
 *
 * 系统 / 其它应用的占用不在这里判断——那需要真的去注册一次，交给调用方（见 [ShortcutProblem.OCCUPIED]）。
 */
fun shortcutProblem(
    spec: ShortcutSpec,
    slot: ShortcutSlot,
    settings: AppSettings,
): ShortcutProblem? {
    // 全局快捷键再裸也不能没有修饰键；面板内的裸键只在「会吞掉输入」时才拦。
    if (!spec.hasModifiers && (slot.global || spec.character in TYPING_CHARACTERS)) {
        return ShortcutProblem.NEEDS_MODIFIER
    }

    // 快速粘贴只认数字键：它真正要的只是修饰键，数字是占位；录进别键会让人误以为绑定了那个键。
    if (slot == ShortcutSlot.QUICK_SELECT && spec.character !in QUICK_SELECT_DIGITS) {
        return ShortcutProblem.QUICK_SELECT_DIGIT
    }

    // 与其它槽位冲突：按「实际会命中的全部组合」比，而不是只比单条绑定。
    val duplicated = ShortcutSlot.entries.any { other ->
        other != slot && settings.shortcut(other)?.let { otherSpec ->
            spec in other.occupiedSpecs(otherSpec)
        } == true
    }
    if (duplicated) return ShortcutProblem.DUPLICATE

    // 快速粘贴占用的数字键：同一修饰键下任意数字都会被它先接走。
    val quickSelect = settings.shortcut(ShortcutSlot.QUICK_SELECT)
    if (slot != ShortcutSlot.QUICK_SELECT && quickSelect != null &&
        spec.character in QUICK_SELECT_DIGITS && sameModifiers(spec, quickSelect)
    ) {
        return ShortcutProblem.RESERVED
    }

    return null
}

/** 两个组合的修饰键是否一致（忽略字符）。 */
private fun sameModifiers(a: ShortcutSpec, b: ShortcutSpec): Boolean =
    a.control == b.control && a.option == b.option && a.shift == b.shift && a.command == b.command
