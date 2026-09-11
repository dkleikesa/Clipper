package com.qcmian.clipper.domain.usecase

import com.qcmian.clipper.data.model.ClipItem
import com.qcmian.clipper.data.repository.ClipboardRepository
import com.qcmian.clipper.settings.AppSettings

/** Port of `History.togglePin`: pins an item with a free shortcut or unpins it. */
class TogglePinUseCase(
    private val repository: ClipboardRepository,
    private val availablePins: AvailablePinsUseCase,
) {
    operator fun invoke(item: ClipItem) {
        val items = repository.items.value
        val index = items.indexOfFirst { it.id == item.id }
        if (index < 0) return

        val updated = items.toMutableList()
        updated[index] = item.copy(pin = if (item.isPinned) null else randomAvailablePin())
        repository.setItems(updated)
    }

    private fun randomAvailablePin(): String = availablePins().randomOrNull().orEmpty()
}

/** Port of `PinsSettingsPane`: lets the user re-assign the key of a pinned item. */
class UpdatePinUseCase(private val repository: ClipboardRepository) {
    operator fun invoke(item: ClipItem, pin: String?) = repository.replace(item) { it.copy(pin = pin) }
}

/** Port of `PinsSettingsPane`'s alias column, which edits `HistoryItem.title`. */
class UpdateTitleUseCase(private val repository: ClipboardRepository) {
    operator fun invoke(item: ClipItem, title: String) = repository.replace(item) { it.copy(title = title) }
}

/** Port of `PinValueView.updateItemContent()`: replaces the plain text representation. */
class UpdateContentUseCase(private val repository: ClipboardRepository) {
    operator fun invoke(item: ClipItem, text: String) {
        // Only plain text entries expose an editable content field.
        val hasPlainText = item.text != null && item.imageBase64 == null && item.files.isEmpty()
        if (!hasPlainText) return
        repository.replace(item) { it.copy(text = text) }
    }
}

/** Removes a single entry. */
class DeleteClipUseCase(private val repository: ClipboardRepository) {
    operator fun invoke(item: ClipItem) {
        val items = repository.items.value
        if (items.none { it.id == item.id }) return
        repository.setItems(items.filterNot { it.id == item.id })
    }
}

/**
 * Port of Maccy's "Clear" (unpinned only) and "Clear all". Pinned items survive a plain
 * clear, and the system clipboard is emptied when the preference asks for it.
 */
class ClearHistoryUseCase(private val repository: ClipboardRepository) {
    operator fun invoke(all: Boolean) {
        val items = repository.items.value
        val remaining = if (all) emptyList() else items.filter { it.isPinned }
        repository.setItems(remaining)
        if (repository.settings.value.clearSystemClipboard) repository.clearSystemClipboard()
    }
}

/**
 * Port of `HistoryItem.supportedPins`: the characters reserved by the recordable
 * `delete` / `pin` / `togglePreview` shortcuts are removed from the pool, so a custom
 * shortcut can never collide with the generated pin of a pinned item.
 */
class AvailablePinsUseCase(private val repository: ClipboardRepository) {
    operator fun invoke(excluding: ClipItem? = null): List<String> {
        val assigned = repository.items.value
            .mapNotNull { if (it.id == excluding?.id) null else it.pin }
            .toSet()
        return supportedPins(repository.settings.value).filter { it !in assigned }
    }

    private fun supportedPins(settings: AppSettings): List<String> {
        val reserved = setOf(
            settings.deleteShortcut.character,
            settings.pinShortcut.character,
            settings.togglePreviewShortcut.character,
        ).mapTo(mutableSetOf()) { it.lowercase() }
        return PIN_CHARACTERS.map { it.toString() }.filterNot { it.lowercase() in reserved }
    }

    private companion object {
        /** `a`, `q`, `v`, `w`, `z` are reserved for shortcuts, see Maccy. */
        const val PIN_CHARACTERS = "bcdefghijklmnoprstuxy"
    }
}

/** Shared "replace one item and persist" helper. */
private inline fun ClipboardRepository.replace(
    item: ClipItem,
    transform: (ClipItem) -> ClipItem,
) {
    val items = items.value
    val index = items.indexOfFirst { it.id == item.id }
    if (index < 0) return
    setItems(items.toMutableList().also { it[index] = transform(it[index]) })
}
