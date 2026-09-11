package com.qcmian.clipper.domain.usecase

import com.qcmian.clipper.domain.repository.ClipboardRepository
import com.qcmian.clipper.settings.AppSettings

/**
 * 应用一次偏好变更及其隐含的全部副作用。对应 Maccy 的 `Defaults` 观察者：
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
            items = items.map { it.copy(title = it.generateTitle(updated.showSpecialSymbols)) }
        }
        if (previous.saveText != updated.saveText ||
            previous.saveImages != updated.saveImages ||
            previous.saveFiles != updated.saveFiles
        ) {
            items = items.filterNot { item ->
                (!updated.saveText && item.text != null && item.imageBase64 == null && item.files.isEmpty()) ||
                    (!updated.saveImages && item.imageBase64 != null && item.text.isNullOrBlank() && item.files.isEmpty()) ||
                    (!updated.saveFiles && item.files.isNotEmpty() && item.text.isNullOrBlank() && item.imageBase64 == null)
            }
        }

        val layoutChanged = updated.historySize != previous.historySize ||
            updated.sortBy != previous.sortBy ||
            updated.pinTo != previous.pinTo

        if (items != original || layoutChanged) {
            repository.setItems(items)
        }
    }
}
