package com.qcmian.clipper.core.testutil

import com.qcmian.clipper.core.data.local.ClipperDatabase
import com.qcmian.clipper.core.data.local.RoomClipStorageDataSource
import com.qcmian.clipper.core.data.local.openInMemoryDatabase
import com.qcmian.clipper.core.data.repository.DefaultClipboardRepository
import com.qcmian.clipper.core.data.source.EncryptionBackup
import com.qcmian.clipper.core.data.source.SessionKey
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.usecase.CaptureClipboardUseCase
import com.qcmian.clipper.core.domain.usecase.ClearHistoryUseCase
import com.qcmian.clipper.core.domain.usecase.HandleQuitUseCase
import com.qcmian.clipper.core.domain.usecase.SelectClipUseCase
import com.qcmian.clipper.core.domain.usecase.TogglePinUseCase
import com.qcmian.clipper.core.domain.usecase.UpdateSettingsUseCase
import com.qcmian.clipper.di.ClipboardUseCases
import com.qcmian.clipper.testing.RecordingClipboard
import com.qcmian.clipper.testing.RecordingNative
import com.qcmian.clipper.testing.seed
import com.qcmian.clipper.testing.seedText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A 层（in-process 全链路）的夹具：**真 Room + 真 SqlCipher 驱动**，只把「系统」换掉。
 *
 * 替换的只有两条触达宿主的支路——剪贴板与原生能力（[RecordingClipboard] / [RecordingNative]）。
 * 存储是真实现（内存里的 `ClipperDatabase`，同一个驱动、同一份 schema），因此排序、去重、
 * 淘汰、清空、载荷读取走的都是线上那套 SQL 与映射，而不是镜像。
 *
 * 与 B 层（`CliEndToEndTest`）的分工：那边跨真 socket 验 CLI 的字节契约；这里不碰进程边界，
 * 直接驱动用例与 `ClipboardViewModel`，验的是**业务规则**（F2/F3/F4）。
 *
 * @param scope 仓库的异步作用域。默认自带一个（`Dispatchers.IO`）；传 `runTest` 的
 *   `backgroundScope` 可让异步工作落在测试调度器上。
 */
class InProcessCluster(
    val clipboard: RecordingClipboard = RecordingClipboard(),
    val native: RecordingNative = RecordingNative(),
    scope: CoroutineScope? = null,
    database: ClipperDatabase = openInMemoryDatabase(),
    /** 会话密钥；非空即「这个平台支持加密」，与线上装配口径一致（见 `createClipStorageDataSource`）。 */
    sessionKey: SessionKey? = null,
    /** 换钥前后可选的备份 / 回滚。 */
    backup: EncryptionBackup? = null,
    /** 「这一串是不是当前库的口令」由宿主回答（见 `ClipStorageDataSource.verifyPassphrase`）。 */
    passphraseVerifier: ((String) -> Boolean)? = null,
) : AutoCloseable {

    private val ownsScope = scope == null
    val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val storage = RoomClipStorageDataSource(
        database = database,
        databaseBytes = { null },
        sessionKey = sessionKey,
        backup = backup,
        passphraseVerifier = passphraseVerifier,
    )

    val repository = DefaultClipboardRepository(clipboard, storage, native, this.scope)

    /** 与 `AppContainer` 同构的用例装配（不含开发者工具那条支路）。 */
    val useCases: ClipboardUseCases by lazy {
        val clear = ClearHistoryUseCase(repository)
        ClipboardUseCases(
            captureClipboard = CaptureClipboardUseCase(repository, repository),
            selectClip = SelectClipUseCase(repository, repository),
            togglePin = TogglePinUseCase(repository),
            clearHistory = clear,
            updateSettings = UpdateSettingsUseCase(repository),
            handleQuit = HandleQuitUseCase(repository, clear),
        )
    }

    /** 置顶 + 未置顶的条目总数。 */
    val historySize: Int get() = repository.pinned.value.size + repository.unpinned.value.size

    /**
     * 起仓库并等它就绪：偏好从存储读出，且剪贴板监听已真正接上——此前的 [RecordingClipboard.emit]
     * 会因为还没有回调而被静默丢弃。
     */
    suspend fun start(timeoutMillis: Long = START_TIMEOUT_MILLIS) {
        repository.start()
        awaitSettings(timeoutMillis)
        clipboard.awaitListening(timeoutMillis)
    }

    /** 偏好已从存储载入（此前的 `settings` 是内存默认值）。 */
    suspend fun awaitSettings(timeoutMillis: Long = START_TIMEOUT_MILLIS) {
        withTimeout(timeoutMillis) { repository.settingsLoaded.first { it } }
    }

    /** 轮询等待一个条件成立；超时即失败（比固定 `delay` 稳，也比盲等快）。 */
    suspend fun await(timeoutMillis: Long = AWAIT_TIMEOUT_MILLIS, condition: () -> Boolean) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(POLL_INTERVAL_MILLIS)
        }
    }

    suspend fun awaitHistorySize(count: Int, timeoutMillis: Long = AWAIT_TIMEOUT_MILLIS) =
        await(timeoutMillis) { historySize == count }

    /** 给后台协程一点时间把已经发出的快照处理完，用于「断言它**没有**被记录」这类场合。 */
    suspend fun settle(millis: Long = SETTLE_MILLIS) = delay(millis)

    // -------------------------------------------------------------------------------------
    // 造数据
    // -------------------------------------------------------------------------------------

    /** 走仓库的公开写入路径落一条完整条目，因此口径与真实捕获一致。 */
    suspend fun seed(item: ClipItem) = repository.seed(item)

    /** 一条普通文本条目；`lastCopiedAt` 显式给出，便于断言排序。 */
    suspend fun seedText(id: String, text: String, lastCopiedAt: Long, copies: Int = 1) =
        repository.seedText(id, text, lastCopiedAt, copies)

    /**
     * 走退出路径关库：先把待写偏好落盘（`flushNow`）再关连接。
     *
     * 「改设置 → 重启后保持」这类用例必须用它——[close] 只关连接，防抖中的那次写入会丢。
     */
    /**
     * 走退出路径关库：先停掉后台工作，再把待写偏好落盘（`flushNow`）并关连接。
     *
     * **顺序不能反**。[repository.close] 的最后一步是 `storage.close()`；若此时还有查询在跑
     * （例如刚改过排序、触发了一次重新分页），它会在连接关闭之后抛
     * `SQLiteException: database connection closed`。这个异常没有接收者，会被
     * kotlinx-coroutines-test 记账，砸到**后面某一个**测试上——表现是那个测试报
     * `UncaughtExceptionsBeforeTest`，而且每次失败的用例都不一样，从失败者身上根本看不出原因。
     */
    suspend fun shutdown() {
        if (ownsScope) scope.coroutineContext.job.cancelAndJoin()
        repository.close()
    }

    override fun close() {
        repository.stop()
        if (ownsScope) runBlocking { scope.coroutineContext.job.cancelAndJoin() }
        storage.close()
    }

    private companion object {
        const val START_TIMEOUT_MILLIS = 15_000L
        const val AWAIT_TIMEOUT_MILLIS = 10_000L
        const val POLL_INTERVAL_MILLIS = 5L
        const val SETTLE_MILLIS = 250L
    }
}
