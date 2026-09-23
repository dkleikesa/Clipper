package com.qcmian.clipper.core.domain.usecase

import com.qcmian.clipper.core.domain.repository.ClipboardRepository

/**
 * 置顶一批条目，或取消置顶。
 *
 * 传的是**目标状态**而不是「翻转」：多选里可能一半已置顶、一半没有，让每条各自取反会把两边
 * 都翻一遍。方向由调用方先定（见 `ClipboardViewModel.togglePinSelected`）。
 */
class TogglePinUseCase(
    private val repository: ClipboardRepository,
) {
    suspend operator fun invoke(ids: List<String>, pinned: Boolean) {
        if (ids.isEmpty()) return
        repository.setPinned(ids, pinned)
    }
}

/** 「清除」（仅未置顶）与「全部清除」。普通清除会保留置顶项。 */
class ClearHistoryUseCase(
    private val repository: ClipboardRepository,
) {
    suspend operator fun invoke(all: Boolean) {
        repository.clear(all)
        // 清空是用户明确「要释放磁盘」的动作，但这里仍然只做一次便宜的空闲页回收：
        // 图片入库之后整库 `VACUUM` 要重写几 GB（分钟级、需要等量临时空间、期间独占写锁），
        // 不适合挂在一个用户操作上——双表结构下它本来也不再是必需的维护手段。
        repository.reclaimStorageIfNeeded()
    }
}
