package com.qcmian.clipper.core.domain.usecase

import com.qcmian.clipper.core.domain.repository.ClipboardRepository
import com.qcmian.clipper.core.settings.AppSettings

/**
 * 应用一次偏好变更及其隐含的全部副作用。 观察者：
 * 在「特殊符号」开关变化时重建标题、丢弃不再收集的内容类型，
 * 并在存储偏好变化时重新排序 / 裁剪。
 */
class UpdateSettingsUseCase(private val repository: ClipboardRepository) {
    operator fun invoke(transform: (AppSettings) -> AppSettings) {
        val previous = repository.settings.value
        val updated = transform(previous)
        if (updated == previous) return

        // 先发布新的偏好，使下面的不变量处理使用新值。
        repository.setSettings(updated)

        var items = repository.items.value
        val original = items

        if (previous.showSpecialSymbols != updated.showSpecialSymbols) {
            // 只有标题由正文 / 文件派生的条目才需要重算（判据与 `previewableText` 的分支一致）。
            // 纯图片条目的 `title` 是识别原文，`previewableText` 在这里只是回退到它；重算会把
            // 原文里的真换行换成显示用的 `⏎`，用户一开关就再也复制不出换行了。
            items = items.map { item ->
                val fromContent = item.files.isNotEmpty() || !item.text.isNullOrBlank()
                if (fromContent) item.copy(title = item.generateTitle(updated.showSpecialSymbols)) else item
            }
        }
        if (previous.saveText != updated.saveText ||
            previous.saveImages != updated.saveImages ||
            previous.saveFiles != updated.saveFiles
        ) {
            items = items.filterNot { item ->
                (!updated.saveText && item.text != null && item.image == null && item.files.isEmpty()) ||
                    (!updated.saveImages && item.image != null && item.text.isNullOrBlank() && item.files.isEmpty()) ||
                    (!updated.saveFiles && item.files.isNotEmpty() && item.text.isNullOrBlank() && item.image == null)
            }
        }

        val layoutChanged = updated.historyMaxCount != previous.historyMaxCount ||
            updated.sortBy != previous.sortBy ||
            updated.pinTo != previous.pinTo

        if (items != original || layoutChanged) {
            repository.setItems(items)
        }
    }
}
