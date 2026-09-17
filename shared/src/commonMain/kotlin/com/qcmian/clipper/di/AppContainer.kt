package com.qcmian.clipper.di

import com.qcmian.clipper.core.data.repository.DefaultClipboardRepository
import com.qcmian.clipper.core.data.source.NativeDataSource
import com.qcmian.clipper.core.data.source.createClipStorageDataSource
import com.qcmian.clipper.core.data.source.createClipboardDataSource
import com.qcmian.clipper.core.data.source.createNativeDataSource
import com.qcmian.clipper.core.domain.repository.ClipboardPlatform
import com.qcmian.clipper.core.domain.repository.ClipboardRepository
import com.qcmian.clipper.core.domain.usecase.AvailablePinsUseCase
import com.qcmian.clipper.core.domain.usecase.CaptureClipboardUseCase
import com.qcmian.clipper.core.domain.usecase.ClearHistoryUseCase
import com.qcmian.clipper.core.domain.usecase.HandleQuitUseCase
import com.qcmian.clipper.core.domain.usecase.SelectClipUseCase
import com.qcmian.clipper.core.domain.usecase.TogglePinUseCase
import com.qcmian.clipper.core.domain.usecase.UpdateSettingsUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 手动依赖注入：一次性构建数据层与领域用例，使 UI 层只拿到自己需要的东西。
 * 刻意不依赖任何框架——这个规模的应用不需要 DI 库。
 *
 * 容器应当与应用的存活时间一致：Android 在它的 `Application` 中创建，
 * desktop / iOS / web 每个进程创建一个。因此绝不能在 composable 内部创建它，
 * 否则一次配置变更就会丢弃并重建它，而被保留的 `ViewModel` 仍指向旧实例。
 */
class AppContainer(
    /** 用于仓库防抖写入的作用域；固定在 [Dispatchers.IO] 上以便做文件 IO。 */
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    /**
     * 可选的原生能力。对外暴露是为了让宿主（例如桌面端托盘）复用同一个实例，
     * 而不必再构建第二个。
     */
    val native: NativeDataSource = createNativeDataSource()

    /**
     * 同一个实现，以两个窄接口对外暴露：历史仓库与宿主平台。
     * 每个使用方只依赖自己用到的那一半。
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
    private val clearHistoryUseCase = ClearHistoryUseCase(repository)

    val useCases = ClipboardUseCases(
        captureClipboard = CaptureClipboardUseCase(repository, platform),
        selectClip = SelectClipUseCase(repository, platform),
        togglePin = TogglePinUseCase(repository, availablePinsUseCase),
        clearHistory = clearHistoryUseCase,
        updateSettings = UpdateSettingsUseCase(repository),
        availablePins = availablePinsUseCase,
        handleQuit = HandleQuitUseCase(repository, clearHistoryUseCase),
    )
}
