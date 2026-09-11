package com.qcmian.clipper.domain.usecase

import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.domain.model.ClipboardSnapshot
import com.qcmian.clipper.domain.repository.ClipboardPlatform

/**
 * Port of `Clipboard.copyInMaccy(history.searchQuery)`: when nothing is selected, pressing
 * Return copies the typed query itself onto the clipboard.
 */
class CopySearchQueryUseCase(private val platform: ClipboardPlatform) {
    operator fun invoke(query: String): Boolean {
        if (query.isEmpty()) return false
        return platform.writeClipboard(ClipboardSnapshot(text = query))
    }
}

/**
 * Port of `ToolbarView`'s `text.viewfinder` action: puts the text recognised inside an
 * image (the item title) back onto the clipboard.
 */
class CopyExtractedTextUseCase(private val platform: ClipboardPlatform) {
    operator fun invoke(item: ClipItem): Boolean {
        val text = item.title.trim()
        if (item.imageBase64 == null || text.isEmpty()) return false
        return platform.writeClipboard(ClipboardSnapshot(text = text))
    }
}
