package com.qcmian.clipper.di

import com.qcmian.clipper.data.repository.DefaultClipboardRepository
import com.qcmian.clipper.data.source.NativeDataSource
import com.qcmian.clipper.data.source.createClipStorageDataSource
import com.qcmian.clipper.data.source.createClipboardDataSource
import com.qcmian.clipper.data.source.createNativeDataSource
import com.qcmian.clipper.domain.repository.ClipboardPlatform
import com.qcmian.clipper.domain.repository.ClipboardRepository
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
import com.qcmian.clipper.util.ioDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * Manual dependency injection: builds the data layer and the domain use cases once, so the UI
 * layer only ever receives what it needs. Deliberately framework free — no DI library is
 * required for an application this size.
 *
 * A container is expected to live for as long as the application does: Android creates it in
 * its `Application`, desktop / iOS / web create one per process. It must therefore never be
 * created from inside a composable, where a configuration change would discard and rebuild it
 * while the retained `ViewModel` keeps pointing at the old instance.
 */
class AppContainer(
    /** Scope for the repository's debounced writes; pinned to [ioDispatcher] for file IO. */
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
) {
    /**
     * Optional native capabilities. Exposed so hosts (the desktop tray, for example) can reuse
     * the same instance instead of building a second one.
     */
    val native: NativeDataSource = createNativeDataSource()

    /**
     * A single implementation, exposed as two narrow interfaces: the history repository and
     * the host platform. Every consumer depends only on the half it uses.
     */
    private val defaultRepository = DefaultClipboardRepository(
        clipboard = createClipboardDataSource(),
        storage = createClipStorageDataSource(),
        native = native,
        scope = scope,
    )

    val repository: ClipboardRepository = defaultRepository
    val platform: ClipboardPlatform = defaultRepository

    private val availablePinsUseCase = AvailablePinsUseCase(repository)
    private val clearHistoryUseCase = ClearHistoryUseCase(repository, platform)

    val useCases = ClipboardUseCases(
        captureClipboard = CaptureClipboardUseCase(repository, platform),
        selectClip = SelectClipUseCase(repository, platform),
        togglePin = TogglePinUseCase(repository, availablePinsUseCase),
        updatePin = UpdatePinUseCase(repository),
        updateTitle = UpdateTitleUseCase(repository),
        updateContent = UpdateContentUseCase(repository),
        deleteClip = DeleteClipUseCase(repository),
        clearHistory = clearHistoryUseCase,
        updateSettings = UpdateSettingsUseCase(repository),
        availablePins = availablePinsUseCase,
        copySearchQuery = CopySearchQueryUseCase(platform),
        copyExtractedText = CopyExtractedTextUseCase(platform),
        handleQuit = HandleQuitUseCase(repository, clearHistoryUseCase),
    )
}
