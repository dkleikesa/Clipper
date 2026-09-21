package com.qcmian.clipper.core.domain.usecase

import com.qcmian.clipper.core.domain.repository.ClipboardRepository

/**
 * 应用「退出时清空历史」偏好，并确保待写的偏好已经落地。
 *
 * 是挂起函数：清空要等数据库写完（单表时代它只是一次内存替换 + 异步落盘，现在是一次真实的
 * 删除）。调用方决定等多久——退出路径不该被一次大删除无限拖住。
 */
class HandleQuitUseCase(
    private val repository: ClipboardRepository,
    private val clearHistory: ClearHistoryUseCase,
) {
    suspend operator fun invoke() {
        if (repository.settings.value.clearOnQuit) {
            clearHistory(all = false)
        }
        repository.flush()
    }
}
