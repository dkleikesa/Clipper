package com.qcmian.clipper.core.domain.usecase

import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.repository.ClipboardRepository

/**
 * 置顶条目，或取消置顶。
 *
 * 只改写 `pinned` 一列。单表时代这里要「取整份历史 → 换掉一条 → 全量写回」，而其中的
 * 图片 BLOB 会被跟着重写一遍——这正是拆表要解决的问题。
 */
class TogglePinUseCase(
    private val repository: ClipboardRepository,
) {
    suspend operator fun invoke(meta: ClipMeta) {
        repository.setPinned(meta.id, !meta.isPinned)
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
