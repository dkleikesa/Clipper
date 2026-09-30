package com.qcmian.clipper.di

import com.qcmian.clipper.core.data.repository.DefaultClipboardRepository
import com.qcmian.clipper.core.data.source.ClipboardDataSource
import com.qcmian.clipper.core.data.source.NativeDataSource
import com.qcmian.clipper.core.data.source.createClipStorageDataSource
import com.qcmian.clipper.core.data.source.createClipboardDataSource
import com.qcmian.clipper.core.data.source.createNativeDataSource
import com.qcmian.clipper.core.domain.repository.ClipboardPlatform
import com.qcmian.clipper.core.domain.repository.ClipboardRepository
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
    /**
     * 通往系统剪贴板的桥接。默认真实实现；测试注入一个**只记账**的实现，避免真去读写
     * 开发机上的粘贴板（那会把当前剪贴板覆盖掉，也会把测试变成「要有人登录图形会话」）。
     */
    clipboardDataSource: ClipboardDataSource = createClipboardDataSource(),
    /**
     * 可选的原生能力。默认取真实实现；测试注入空实现，避免跑到 `SMAppService` 一类的
     * 系统副作用（`setLaunchAtLogin` 会真的注册 / 注销登录项）。
     */
    nativeDataSource: NativeDataSource = createNativeDataSource(),
) {
    /**
     * 可选的原生能力。对外暴露是为了让宿主（例如桌面端托盘）复用同一个实例，
     * 而不必再构建第二个。
     */
    val native: NativeDataSource = nativeDataSource

    /**
     * 同一个实现，以两个窄接口对外暴露：历史仓库与宿主平台。
     * 每个使用方只依赖自己用到的那一半。
     *
     * 存储不受上面两个注入点影响：它始终是真实实现，路径由 `user.home` 决定，因此测试
     * 只要把 `user.home` 指到临时目录，拿到的就是一份真的 Room + SQLCipher 存储。
     */
    private val defaultRepository = DefaultClipboardRepository(
        clipboard = clipboardDataSource,
        storage = createClipStorageDataSource(),
        native = nativeDataSource,
        scope = scope,
    )

    val repository: ClipboardRepository = defaultRepository
    val platform: ClipboardPlatform = defaultRepository

    // 开发者工具**不在这里**：插件表（`:devTools` 模块的 `DevToolsRegistry`）依赖 `:shared` 复用
    // 这里的 UI 原子与主题，方向是单向的，因此它放不进这个依赖图，由桌面端的组合根自己建
    // （见 `main.kt`）。剪贴板这边只把一条 `ClipItem` 放进状态，见 `ClipboardUiState.devToolsItem`。

    private val clearHistoryUseCase = ClearHistoryUseCase(repository)

    val useCases = ClipboardUseCases(
        captureClipboard = CaptureClipboardUseCase(repository, platform),
        selectClip = SelectClipUseCase(repository, platform),
        togglePin = TogglePinUseCase(repository),
        clearHistory = clearHistoryUseCase,
        updateSettings = UpdateSettingsUseCase(repository),
        handleQuit = HandleQuitUseCase(repository, clearHistoryUseCase),
    )
}
