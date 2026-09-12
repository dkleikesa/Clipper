package com.qcmian.clipper.di

import com.qcmian.clipper.core.domain.usecase.AvailablePinsUseCase
import com.qcmian.clipper.core.domain.usecase.CaptureClipboardUseCase
import com.qcmian.clipper.core.domain.usecase.ClearHistoryUseCase
import com.qcmian.clipper.core.domain.usecase.CopyExtractedTextUseCase
import com.qcmian.clipper.core.domain.usecase.CopySearchQueryUseCase
import com.qcmian.clipper.core.domain.usecase.DeleteClipUseCase
import com.qcmian.clipper.core.domain.usecase.HandleQuitUseCase
import com.qcmian.clipper.core.domain.usecase.SelectClipUseCase
import com.qcmian.clipper.core.domain.usecase.TogglePinUseCase
import com.qcmian.clipper.core.domain.usecase.UpdateContentUseCase
import com.qcmian.clipper.core.domain.usecase.UpdatePinUseCase
import com.qcmian.clipper.core.domain.usecase.UpdateSettingsUseCase
import com.qcmian.clipper.core.domain.usecase.UpdateTitleUseCase

/**
 * 历史界面所驱动的领域用例，打包成单个依赖。
 *
 * 状态持有者过去要接收十三个独立的用例参数；把它们分组后，构造函数只关心它真正拥有的三样
 * 东西——仓库、平台与动作——而不是把整张依赖图摊开。定义在 DI 层，因为「打包」是装配问题，
 * 不是领域问题。
 */
data class ClipboardUseCases(
    val captureClipboard: CaptureClipboardUseCase,
    val selectClip: SelectClipUseCase,
    val togglePin: TogglePinUseCase,
    val updatePin: UpdatePinUseCase,
    val updateTitle: UpdateTitleUseCase,
    val updateContent: UpdateContentUseCase,
    val deleteClip: DeleteClipUseCase,
    val clearHistory: ClearHistoryUseCase,
    val updateSettings: UpdateSettingsUseCase,
    val availablePins: AvailablePinsUseCase,
    val copySearchQuery: CopySearchQueryUseCase,
    val copyExtractedText: CopyExtractedTextUseCase,
    val handleQuit: HandleQuitUseCase,
)
