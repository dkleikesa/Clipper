package com.qcmian.clipper.domain.usecase

import com.qcmian.clipper.data.repository.ClipboardRepository

/**
 * Port of `AppDelegate.applicationWillTerminate` plus a synchronous flush: applies the
 * "clear history on quit" preference and makes sure the debounced writes have landed.
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
