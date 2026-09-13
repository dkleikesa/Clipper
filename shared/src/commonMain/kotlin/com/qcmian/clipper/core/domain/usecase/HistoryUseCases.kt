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

/** 对应 `PinsSettingsPane`：允许用户重新指定置顶项的快捷键。 */
class UpdatePinUseCase(private val repository: ClipboardRepository) {
    operator fun invoke(item: ClipItem, pin: String?) = repository.replace(item) { it.copy(pin = pin) }
}

/** 对应 `PinsSettingsPane` 的别名列，它编辑的是 `HistoryItem.title`。 */
class UpdateTitleUseCase(private val repository: ClipboardRepository) {
    operator fun invoke(item: ClipItem, title: String) = repository.replace(item) { it.copy(title = title) }
}

/** 对应 `PinValueView.updateItemContent()`：替换纯文本表示。 */
class UpdateContentUseCase(private val repository: ClipboardRepository) {
    operator fun invoke(item: ClipItem, text: String) {
        // 只有纯文本条目才提供可编辑的内容字段。
        val hasPlainText = item.text != null && item.image == null && item.files.isEmpty()
        if (!hasPlainText) return
        repository.replace(item) { it.copy(text = text) }
    }
}

/** 删除单条记录。 */
class DeleteClipUseCase(private val repository: ClipboardRepository) {
    operator fun invoke(item: ClipItem) {
        val items = repository.items.value
        if (items.none { it.id == item.id }) return
        repository.setItems(items.filterNot { it.id == item.id })
    }
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

/** 共用的「替换一条记录并持久化」辅助函数。 */
private inline fun ClipboardRepository.replace(
    item: ClipItem,
    transform: (ClipItem) -> ClipItem,
) {
    val items = items.value
    val index = items.indexOfFirst { it.id == item.id }
    if (index < 0) return
    setItems(items.toMutableList().also { it[index] = transform(it[index]) })
}
