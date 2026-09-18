package com.qcmian.clipper.core.domain.usecase

import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.repository.ClipboardRepository

/** 对应 `History.togglePin`：置顶条目，或取消置顶。 */
class TogglePinUseCase(
    private val repository: ClipboardRepository,
) {
    operator fun invoke(item: ClipItem) {
        val items = repository.items.value
        val index = items.indexOfFirst { it.id == item.id }
        if (index < 0) return

        val updated = items.toMutableList()
        // `pin` 只作为「已置顶」标记，写入一个固定值即可（见 `ClipItem.pin`）。
        updated[index] = item.copy(pin = if (item.isPinned) null else ClipItem.PINNED_MARKER)
        repository.setItems(updated)
    }
}

/** 「清除」（仅未置顶）与「全部清除」。普通清除会保留置顶项。 */
class ClearHistoryUseCase(
    private val repository: ClipboardRepository,
) {
    operator fun invoke(all: Boolean) {
        val items = repository.items.value
        val remaining = if (all) emptyList() else items.filter { it.isPinned }
        repository.setItems(remaining)
        // 清空是用户明确「要释放磁盘」的动作：顺手把文件压紧。此时有效数据最少，
        // 也是 VACUUM 最快的一次（见 `ClipboardRepository.compactStorage`）。
        repository.compactStorage()
    }
}
