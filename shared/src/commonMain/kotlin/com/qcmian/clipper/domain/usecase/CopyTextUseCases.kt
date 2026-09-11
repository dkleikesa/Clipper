package com.qcmian.clipper.domain.usecase

import com.qcmian.clipper.data.model.ClipItem
import com.qcmian.clipper.data.model.ClipboardSnapshot
import com.qcmian.clipper.data.repository.ClipboardRepository

/**
 * Port of `Clipboard.copyInMaccy(history.searchQuery)`: when nothing is selected, pressing
 * Return copies the typed query itself onto the clipboard.
 */
class CopySearchQueryUseCase(private val repository: ClipboardRepository) {
    operator fun invoke(query: String): Boolean {
        if (query.isEmpty()) return false
        return repository.writeClipboard(ClipboardSnapshot(text = query))
    }
}

/**
 * Port of `ToolbarView`'s `text.viewfinder` action: puts the text recognised inside an
 * image (the item title) back onto the clipboard.
 */
class CopyExtractedTextUseCase(private val repository: ClipboardRepository) {
    operator fun invoke(item: ClipItem): Boolean {
        val text = item.title.trim()
        if (item.imageBase64 == null || text.isEmpty()) return false
        return repository.writeClipboard(ClipboardSnapshot(text = text))
    }
}
