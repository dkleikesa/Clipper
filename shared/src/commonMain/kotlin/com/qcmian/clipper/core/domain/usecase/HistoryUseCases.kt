package com.qcmian.clipper.core.domain.usecase

import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.repository.ClipboardPlatform
import com.qcmian.clipper.core.domain.repository.ClipboardRepository
import com.qcmian.clipper.core.settings.AppSettings

/** 对应 `History.togglePin`：用空闲的快捷键置顶条目，或取消置顶。 */
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

/**
 * 「清除」（仅未置顶）与「全部清除」。普通清除会保留置顶项，
 * 当偏好要求时还会清空系统剪贴板。
 */
class ClearHistoryUseCase(
    private val repository: ClipboardRepository,
    private val platform: ClipboardPlatform,
) {
    operator fun invoke(all: Boolean) {
        val items = repository.items.value
        val remaining = if (all) emptyList() else items.filter { it.isPinned }
        repository.setItems(remaining)
        if (repository.settings.value.clearSystemClipboard) platform.clearSystemClipboard()
    }
}

/**
 * 对应 `HistoryItem.supportedPins`：可录制的 `delete` / `pin` / `togglePreview` 快捷键
 * 所用到的字符会从候选池中剔除，因此自定义快捷键永远不会与自动分配的置顶键冲突。
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
 /** `a`、`q`、`v`、`w`、`z` 被快捷键占用。 */
        const val PIN_CHARACTERS = "bcdefghijklmnoprstuxy"
    }
}
