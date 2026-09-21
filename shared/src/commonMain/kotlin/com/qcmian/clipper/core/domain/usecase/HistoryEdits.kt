package com.qcmian.clipper.core.domain.usecase

import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.repository.ClipboardPlatform
import com.qcmian.clipper.core.domain.repository.ClipboardRepository

/**
 * 编辑单条历史的操作。
 *
 * 它们各自只有一两条语句、不含任何业务规则，做成用例类只会多一层转发（外加一个装配点）。
 * 因此写成扩展函数：调用点读起来就是「仓库自己的操作」，`ClipboardUseCases` 只保留真正带
 * 规则的用例——记录新复制、激活、清空历史、更新偏好、置顶、退出。
 *
 * 与单表时代的关键差别：这些操作**都按 id 精确作用**，不再需要「把整份历史读进内存、
 * 改一条、再全量写回」。
 */

/** 删除一条记录。 */
suspend fun ClipboardRepository.deleteClip(id: String) = delete(listOf(id))

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
 * 把图片中识别出的文字（即条目标题）放回剪贴板。返回 `true` 表示确实写入了剪贴板。
 */
fun ClipboardPlatform.copyExtractedText(meta: ClipMeta): Boolean {
    // 与工具栏按钮同源：只有标题确实来自图片文字识别时才复制，否则会把条目本来的正文
    // 当成「图片里的文字」写回剪贴板。
    if (!meta.hasRecognizedText) return false
    return writeClipboard(ClipboardSnapshot(text = meta.title.trim()))
}
