package com.qcmian.clipper.core.domain.usecase

import com.qcmian.clipper.core.domain.repository.ClipboardRepository
import com.qcmian.clipper.core.settings.AppSettings

/**
 * 应用一次偏好变更。
 *
 * 相比单表时代，这里少掉了一大块副作用：那时「特殊符号」开关会触发「重建全部条目的标题并把
 * 整份历史写回」。现在库里存的标题是**原文**，`·` / `⏎` / `⇥` 的替换发生在渲染时
 * （见 `titleForDisplay`），因此这个开关只影响界面。
 *
 * 排序、排序方向与历史上限会改变窗口的内容，由 [ClipboardRepository.setSettings] 负责重载。
 */
class UpdateSettingsUseCase(private val repository: ClipboardRepository) {
    operator fun invoke(transform: (AppSettings) -> AppSettings) {
        val previous = repository.settings.value
        val updated = transform(previous)
        if (updated == previous) return
        repository.setSettings(updated)
    }
}
