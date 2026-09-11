package com.qcmian.clipper.di

import com.qcmian.clipper.domain.usecase.AvailablePinsUseCase
import com.qcmian.clipper.domain.usecase.CaptureClipboardUseCase
import com.qcmian.clipper.domain.usecase.ClearHistoryUseCase
import com.qcmian.clipper.domain.usecase.CopyExtractedTextUseCase
import com.qcmian.clipper.domain.usecase.CopySearchQueryUseCase
import com.qcmian.clipper.domain.usecase.DeleteClipUseCase
import com.qcmian.clipper.domain.usecase.HandleQuitUseCase
import com.qcmian.clipper.domain.usecase.SelectClipUseCase
import com.qcmian.clipper.domain.usecase.TogglePinUseCase
import com.qcmian.clipper.domain.usecase.UpdateContentUseCase
import com.qcmian.clipper.domain.usecase.UpdatePinUseCase
import com.qcmian.clipper.domain.usecase.UpdateSettingsUseCase
import com.qcmian.clipper.domain.usecase.UpdateTitleUseCase

/**
 * The domain use cases the history screen drives, bundled into a single dependency.
 *
 * The state holder used to take thirteen separate use case parameters; grouping them keeps the
 * constructor about the three things it actually owns — the repository, the platform and the
 * actions — instead of reading like the dependency graph itself. Defined in the DI layer
 * because bundling is a wiring concern, not a domain one.
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
