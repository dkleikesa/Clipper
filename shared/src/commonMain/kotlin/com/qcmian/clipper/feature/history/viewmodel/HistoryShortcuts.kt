package com.qcmian.clipper.feature.history.viewmodel

import com.qcmian.clipper.core.domain.model.SearchResult
import com.qcmian.clipper.core.ui.KeyShortcut
import com.qcmian.clipper.core.ui.keyShortcuts

/**
 * 每条历史的快捷键角标：前九个**置顶项**按显示顺序使用 `1`…`9`，其余条目没有快捷键。
 *
 * 数字键按 [results] 的顺序发（也就是面板里的排列顺序，置顶区在最上或最下由 `pinTo` 决定），
 * 因此「第 N 个置顶项」与角标上的数字始终一致。未置顶条目不分配快捷键。
 *
 * 每个条目携带 `KeyShortcut.create(character:)` 产生的三个变体。
 *
 * 结果同时供界面渲染角标与 [resolveKeyActions] 匹配按键，因此它属于「交互模型」而不是渲染：
 * 它是历史列表与设置的纯函数，放在 viewmodel 层，界面只负责显示。
 */
internal fun shortcutMap(
    results: List<SearchResult>,
    pasteByDefault: Boolean,
): Map<String, List<KeyShortcut>> {
    val map = mutableMapOf<String, List<KeyShortcut>>()
    var counter = 1
    results.forEach { result ->
        // 数字键只发给置顶项，且最多九个。
        if (!result.item.isPinned || counter > 9) return@forEach
        map[result.item.id] = keyShortcuts((counter++).toString(), pasteByDefault)
    }
    return map
}
