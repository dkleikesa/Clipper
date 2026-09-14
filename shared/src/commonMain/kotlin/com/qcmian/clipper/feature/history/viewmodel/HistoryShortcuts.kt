package com.qcmian.clipper.feature.history.viewmodel

import com.qcmian.clipper.core.domain.model.SearchResult
import com.qcmian.clipper.core.ui.KeyShortcut
import com.qcmian.clipper.core.ui.keyShortcuts

/**
 * 每条历史的快捷键角标：置顶项使用分配到的字母，前九个未置顶项使用 `1`…`9`。
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
        val item = result.item
        val character = when {
            item.isPinned -> item.pin
            counter <= 9 -> (counter++).toString()
            else -> null
        } ?: return@forEach
        map[item.id] = keyShortcuts(character.uppercase(), pasteByDefault)
    }
    return map
}
