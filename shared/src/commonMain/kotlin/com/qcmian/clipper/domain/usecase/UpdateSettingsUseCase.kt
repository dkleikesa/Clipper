package com.qcmian.clipper.domain.usecase

import com.qcmian.clipper.data.repository.ClipboardRepository
import com.qcmian.clipper.settings.AppSettings

/**
 * Applies a preference change and every side effect it implies. Port of Maccy's
 * `Defaults` observers: rebuilding the titles when the special symbols toggle, dropping the
 * content kinds that are no longer collected, and re-sorting / trimming when the storage
 * preferences change.
 */
class UpdateSettingsUseCase(private val repository: ClipboardRepository) {
    operator fun invoke(transform: (AppSettings) -> AppSettings) {
        val previous = repository.settings.value
        val updated = transform(previous)
        if (updated == previous) return

        // Publish the new preferences first so the invariant pass below uses them.
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
