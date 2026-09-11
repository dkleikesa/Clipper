package com.qcmian.clipper.domain.action

import com.qcmian.clipper.settings.AppSettings

/**
 * 激活一条历史记录时会发生什么。对应 Maccy 的 `HistoryItemAction`，包含 `unknown`
 * （不支持的修饰键组合，必须保持面板不变）与 `default`（未按任何修饰键的普通激活）。
 */
enum class ClipAction { DEFAULT, COPY, PASTE, PASTE_WITHOUT_FORMATTING, UNKNOWN }

/**
 * 对应 `HistoryItemAction.init(_:)`：为当前按下的确切修饰键组合解析出动作。
 * [meta] 是 `⌘`（没有该键的平台上是 `Ctrl`）。
 *
 * 偏好项 `pasteByDefault` 与 `removeFormattingByDefault` 都会改变每一种组合的含义，
 * 因此这里完整复现了 Maccy 的十二种情况。
 */
fun defaultAction(settings: AppSettings, shift: Boolean, alt: Boolean, meta: Boolean): ClipAction {
    val paste = settings.pasteByDefault
    val removeFormatting = settings.removeFormattingByDefault

    return when {
        // ⌘
        meta && !alt && !shift -> when {
            !paste -> ClipAction.COPY
            !removeFormatting -> ClipAction.PASTE
            else -> ClipAction.PASTE_WITHOUT_FORMATTING
        }

        // ⌥
        alt && !meta && !shift -> when {
            !paste && !removeFormatting -> ClipAction.PASTE
            !paste && removeFormatting -> ClipAction.PASTE_WITHOUT_FORMATTING
            else -> ClipAction.COPY
        }

        // ⌥⇧
        alt && shift && !meta -> when {
            !paste && !removeFormatting -> ClipAction.PASTE_WITHOUT_FORMATTING
            !paste && removeFormatting -> ClipAction.PASTE
            else -> ClipAction.UNKNOWN
        }

        // ⌘⇧
        meta && shift && !alt -> when {
            paste && !removeFormatting -> ClipAction.PASTE_WITHOUT_FORMATTING
            paste && removeFormatting -> ClipAction.PASTE
            else -> ClipAction.UNKNOWN
        }

        // 完全不按修饰键：`History.select` 按 `removeFormattingByDefault` 处理，
        // 且只有 `pasteByDefault` 开启时才粘贴。
        !shift && !alt && !meta -> ClipAction.DEFAULT

        else -> ClipAction.UNKNOWN
    }
}

/**
 * 对应 `History.select`：只有普通激活与显式的「不带格式粘贴」会去掉格式。
 * 即便开启了 `removeFormattingByDefault`，显式的 `⌘` 复制也从不这么做。
 */
fun ClipAction.removesFormatting(settings: AppSettings): Boolean = when (this) {
    ClipAction.PASTE_WITHOUT_FORMATTING -> true
    ClipAction.DEFAULT -> settings.removeFormattingByDefault
    else -> false
}

/** 对应 `History.select`：该动作是否同时触发一次粘贴。 */
fun ClipAction.pastes(settings: AppSettings): Boolean = when (this) {
    ClipAction.PASTE, ClipAction.PASTE_WITHOUT_FORMATTING -> true
    ClipAction.DEFAULT -> settings.pasteByDefault
    else -> false
}

/**
 * 对应 `HistoryItemAction.modifierFlags`：会触发 [action] 的修饰键组合，
 * 偏好设置窗口用它来解释当前的映射关系。
 */
fun modifierFlagsOf(action: ClipAction, settings: AppSettings): String {
    val paste = settings.pasteByDefault
    val removeFormatting = settings.removeFormattingByDefault

    return when {
        action == ClipAction.COPY && !paste -> "⌘"
        action == ClipAction.COPY && paste -> "⌥"
        action == ClipAction.PASTE && paste && !removeFormatting -> "⌘"
        action == ClipAction.PASTE && !paste && !removeFormatting -> "⌥"
        action == ClipAction.PASTE && !paste && removeFormatting -> "⌥⇧"
        action == ClipAction.PASTE && paste && removeFormatting -> "⌘⇧"
        action == ClipAction.PASTE_WITHOUT_FORMATTING && paste && removeFormatting -> "⌘"
        action == ClipAction.PASTE_WITHOUT_FORMATTING && !paste && removeFormatting -> "⌥"
        action == ClipAction.PASTE_WITHOUT_FORMATTING && !paste && !removeFormatting -> "⌥⇧"
        action == ClipAction.PASTE_WITHOUT_FORMATTING && paste && !removeFormatting -> "⌘⇧"
        else -> ""
    }
}
