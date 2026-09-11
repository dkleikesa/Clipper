package com.qcmian.clipper.di

import com.qcmian.clipper.data.repository.ClipboardRepository
import com.qcmian.clipper.data.repository.DefaultClipboardRepository
import com.qcmian.clipper.data.source.createClipboardDataSource
import com.qcmian.clipper.data.source.createClipStorageDataSource
import com.qcmian.clipper.data.source.createNativeDataSource
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency injection: builds the data layer and the domain use cases once, so the UI
 * layer only ever receives what it needs. Deliberately framework free — no DI library is
 * required for an application this size.
 */
class AppContainer(
    /** Scope for the repository's debounced writes and the use case background work. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    val repository: ClipboardRepository = DefaultClipboardRepository(
        clipboard = createClipboardDataSource(),
        storage = createClipStorageDataSource(),
        native = createNativeDataSource(),
        scope = scope,
    )

    val captureClipboard = CaptureClipboardUseCase(repository, scope)
    val selectClip = SelectClipUseCase(repository, scope)
    val availablePins = AvailablePinsUseCase(repository)
    val togglePin = TogglePinUseCase(repository, availablePins)
    val updatePin = UpdatePinUseCase(repository)
    val updateTitle = UpdateTitleUseCase(repository)
    val updateContent = UpdateContentUseCase(repository)
    val deleteClip = DeleteClipUseCase(repository)
    val clearHistory = ClearHistoryUseCase(repository)
    val updateSettings = UpdateSettingsUseCase(repository)
    val copySearchQuery = CopySearchQueryUseCase(repository)
    val copyExtractedText = CopyExtractedTextUseCase(repository)
    val handleQuit = HandleQuitUseCase(repository, clearHistory)
}
