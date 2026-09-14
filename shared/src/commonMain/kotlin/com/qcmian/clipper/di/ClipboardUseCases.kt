package com.qcmian.clipper.di

import com.qcmian.clipper.core.domain.usecase.AvailablePinsUseCase
import com.qcmian.clipper.core.domain.usecase.CaptureClipboardUseCase
import com.qcmian.clipper.core.domain.usecase.ClearHistoryUseCase
import com.qcmian.clipper.core.domain.usecase.HandleQuitUseCase
import com.qcmian.clipper.core.domain.usecase.SelectClipUseCase
import com.qcmian.clipper.core.domain.usecase.TogglePinUseCase
import com.qcmian.clipper.core.domain.usecase.UpdateSettingsUseCase

/**
 * 历史界面所驱动的**带业务规则**的领域用例，打包成单个依赖。
 *
 * 只收真正有规则的用例；「改个标题」「删一条」这类纯转发操作是
 * [com.qcmian.clipper.core.domain.usecase.HistoryEdits] 里的仓库扩展函数，不必经过这里。
 *
 * 定义在 DI 层，因为「打包」是装配问题，不是领域问题。
 */
data class ClipboardUseCases(
    val captureClipboard: CaptureClipboardUseCase,
    val selectClip: SelectClipUseCase,
    val togglePin: TogglePinUseCase,
    val clearHistory: ClearHistoryUseCase,
    val updateSettings: UpdateSettingsUseCase,
    val availablePins: AvailablePinsUseCase,
    val handleQuit: HandleQuitUseCase,
)
