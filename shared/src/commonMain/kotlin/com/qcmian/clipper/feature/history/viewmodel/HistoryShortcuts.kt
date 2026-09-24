package com.qcmian.clipper.feature.history.viewmodel

import com.qcmian.clipper.core.domain.model.SearchResult
import com.qcmian.clipper.core.settings.ShortcutSpec
import com.qcmian.clipper.core.ui.KeyShortcut

/**
 * 每条历史的快捷键角标：前九个**置顶项**按显示顺序使用 `1`…`9`，其余条目没有快捷键。
 *
 * 数字键按 [results] 的顺序发（也就是面板里的排列顺序，置顶区在最上或最下由 `pinTo` 决定），
 * 因此「第 N 个置顶项」与角标上的数字始终一致。未置顶条目不分配快捷键，
 * 按下数字键固定执行「直接粘贴」——数字只负责挑条目（见 `HistoryKeyboard`）。
 *
 * 角标携带的修饰键来自「快速粘贴」这条可录制槽位（[quickSelect]）：它定义数字键要配合按下的
 * 修饰键（默认 `⌘`），按键解析与角标因此共用同一份绑定，改一处不会漂移。该槽位被清除
 * （`null`）时没有快速粘贴，也就没有角标。
 *
 * 结果同时供界面渲染角标与 [resolveKeyActions] 匹配按键，因此它属于「交互模型」而不是渲染：
 * 它是历史列表与设置的纯函数，放在 viewmodel 层，界面只负责显示。
 */
internal fun shortcutMap(
    results: List<SearchResult>,
    quickSelect: ShortcutSpec?,
): Map<String, List<KeyShortcut>> {
    if (quickSelect == null) return emptyMap()

    val map = mutableMapOf<String, List<KeyShortcut>>()
    var counter = 1
    results.forEach { result ->
        // 数字键只发给置顶项，且最多九个。
        if (!result.meta.isPinned || counter > 9) return@forEach
        map[result.meta.id] = listOf(
            KeyShortcut(
                character = (counter++).toString(),
                control = quickSelect.control,
                option = quickSelect.option,
                shift = quickSelect.shift,
                command = quickSelect.command,
            ),
        )
    }
    return map
}
