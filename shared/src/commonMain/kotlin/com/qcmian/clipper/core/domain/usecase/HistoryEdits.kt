package com.qcmian.clipper.core.domain.usecase

import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.repository.ClipboardPlatform
import com.qcmian.clipper.core.domain.repository.ClipboardRepository

/**
 * 编辑单条历史的操作。
 *
 * 它们各自只有一两条语句、不含任何业务规则，做成用例类只会多一层转发（外加一个装配点）。
 * 因此写成扩展函数：调用点读起来就是「仓库自己的操作」，`ClipboardUseCases` 只保留真正带
 * 规则的用例——记录新复制、激活、清空历史、更新偏好、置顶、退出。
 */

/** 找到 [item] 并就地替换，然后持久化；条目已不存在时什么都不做。 */
fun ClipboardRepository.replace(item: ClipItem, transform: (ClipItem) -> ClipItem) {
    val items = items.value
    val index = items.indexOfFirst { it.id == item.id }
    if (index < 0) return
    setItems(items.toMutableList().also { it[index] = transform(it[index]) })
}

/** 对应 `PinsSettingsPane`：允许用户重新指定置顶项的快捷键。 */
fun ClipboardRepository.updatePin(item: ClipItem, pin: String?) =
    replace(item) { it.copy(pin = pin) }

/** 对应 `PinsSettingsPane` 的别名列，它编辑的是 `HistoryItem.title`。 */
fun ClipboardRepository.updateTitle(item: ClipItem, title: String) =
    replace(item) { it.copy(title = title) }

/** 对应 `PinValueView.updateItemContent()`：替换纯文本表示。 */
fun ClipboardRepository.updateContent(item: ClipItem, text: String) {
    // 只有纯文本条目才提供可编辑的内容字段。
    val hasPlainText = item.text != null && item.image == null && item.files.isEmpty()
    if (!hasPlainText) return
    replace(item) { it.copy(text = text) }
}

/** 删除单条记录。 */
fun ClipboardRepository.deleteClip(item: ClipItem) {
    val items = items.value
    if (items.none { it.id == item.id }) return
    setItems(items.filterNot { it.id == item.id })
}

/**
 * 在没有选中任何条目时，按回车会把输入框里的查询词本身复制到剪贴板。
 *
 * 返回 `true` 表示确实写入了剪贴板（空查询词什么都不做）。
 */
fun ClipboardPlatform.copySearchQuery(query: String): Boolean {
    if (query.isEmpty()) return false
    return writeClipboard(ClipboardSnapshot(text = query))
}

/**
 * 对应 `ToolbarView` 的 `text.viewfinder` 动作：把图片中识别出的文字（即条目标题）
 * 放回剪贴板。返回 `true` 表示确实写入了剪贴板。
 */
fun ClipboardPlatform.copyExtractedText(item: ClipItem): Boolean {
    // 与工具栏按钮同源：只有标题确实来自图片文字识别时才复制，否则会把条目本来的正文
    // 当成「图片里的文字」写回剪贴板。
    if (!item.hasRecognizedText) return false
    return writeClipboard(ClipboardSnapshot(text = item.title.trim()))
}
