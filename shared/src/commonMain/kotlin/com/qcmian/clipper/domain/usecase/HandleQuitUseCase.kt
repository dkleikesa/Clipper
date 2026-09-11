package com.qcmian.clipper.domain.usecase

import com.qcmian.clipper.domain.repository.ClipboardRepository

/**
 * 对应 `AppDelegate.applicationWillTerminate`，并额外做一次同步落盘：
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
