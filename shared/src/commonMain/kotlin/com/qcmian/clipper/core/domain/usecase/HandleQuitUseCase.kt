package com.qcmian.clipper.core.domain.usecase

import com.qcmian.clipper.core.domain.repository.ClipboardRepository

/**
 * 应用「退出时清空历史」偏好，并确保防抖的写入已经落地。
 */
class HandleQuitUseCase(
    private val repository: ClipboardRepository,
    private val clearHistory: ClearHistoryUseCase,
) {
    operator fun invoke() {
        if (repository.settings.value.clearOnQuit) {
            clearHistory(all = false)
        }
        repository.flush()
    }
}
