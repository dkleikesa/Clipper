package com.qcmian.clipper.core.domain.usecase

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
 * 把图片中识别出的**完整文字**放回剪贴板。返回 `true` 表示确实写入了剪贴板。
 *
 * 入参取的是载荷里的 `recognizedText`，而不是元数据的 `title`——后者按列表行宽截断过，
 * 拿它去「复制图片文字」会把长截图的识别结果永久截掉后半段（那些字符没有别处可存）。
 */
fun ClipboardPlatform.copyExtractedText(recognizedText: String?): Boolean {
    val text = recognizedText?.trim().orEmpty()
    if (text.isEmpty()) return false
    return writeClipboard(ClipboardSnapshot(text = text))
}
