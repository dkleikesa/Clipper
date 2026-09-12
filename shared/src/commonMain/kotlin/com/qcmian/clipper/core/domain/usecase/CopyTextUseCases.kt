package com.qcmian.clipper.core.domain.usecase

import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.repository.ClipboardPlatform

/**
 * 在没有选中任何条目时，
 * 按回车会把输入框里的查询词本身复制到剪贴板。
 */
class CopySearchQueryUseCase(private val platform: ClipboardPlatform) {
    operator fun invoke(query: String): Boolean {
        if (query.isEmpty()) return false
        return platform.writeClipboard(ClipboardSnapshot(text = query))
    }
}

/**
 * 对应 `ToolbarView` 的 `text.viewfinder` 动作：把图片中识别出的文字（即条目标题）
 * 放回剪贴板。
 */
class CopyExtractedTextUseCase(private val platform: ClipboardPlatform) {
    operator fun invoke(item: ClipItem): Boolean {
        val text = item.title.trim()
        if (item.imageBase64 == null || text.isEmpty()) return false
        return platform.writeClipboard(ClipboardSnapshot(text = text))
    }
}
