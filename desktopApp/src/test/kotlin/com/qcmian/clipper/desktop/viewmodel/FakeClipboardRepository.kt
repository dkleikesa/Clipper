package com.qcmian.clipper.desktop.viewmodel

import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.model.ClipPayload
import com.qcmian.clipper.core.domain.model.ClipText
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.repository.ClipboardRepository
import com.qcmian.clipper.core.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * `WindowGeometryController` 单测的仓库替身。
 *
 * 几何只用到 [settings] 与 [setSettings]；其余成员保持空实现即可——几何这条链若去碰历史或载荷，
 * 说明耦合越界了，而那属于另一个测试的关注点。
 */
internal class FakeClipboardRepository(
    initialSettings: AppSettings = AppSettings(),
) : ClipboardRepository {

    override val settings = MutableStateFlow(initialSettings)
    override val pinned = MutableStateFlow<List<ClipMeta>>(emptyList())
    override val unpinned = MutableStateFlow<List<ClipMeta>>(emptyList())
    override val totalUnpinned = MutableStateFlow(0)
    override val settingsLoaded = MutableStateFlow(true)
    override val statusMessage = MutableStateFlow<String?>(null)
    override val storageRevision = MutableStateFlow(0)
    override val isWritingClipboard = MutableStateFlow(false)
    override val snapshots: Flow<ClipboardSnapshot> = MutableSharedFlow()
    override val clipboardPollIntervalMillis: Int = 500

    /** [setSettings] 的每一次调用，按顺序记下来——几何只应在拖拽收尾时写一次。 */
    val settingsWrites = mutableListOf<AppSettings>()

    override fun setSettings(settings: AppSettings) {
        settingsWrites += settings
        this.settings.value = settings
    }

    override fun start() = Unit
    override fun stop() = Unit
    override fun flush() = Unit
    override suspend fun flushNow() = Unit
    override suspend fun close() = Unit
    override fun setStatusMessage(message: String?) {
        statusMessage.value = message
    }

    override suspend fun <T> withoutCapturing(block: suspend () -> T): T = block()
    override suspend fun recordBatchCopy(ids: List<String>) = Unit
    override suspend fun payload(id: String): ClipPayload? = null
    override suspend fun texts(ids: List<String>): List<ClipText> = emptyList()
    override suspend fun item(id: String): ClipItem? = null
    override suspend fun insert(meta: ClipMeta, payload: ClipPayload?) = Unit
    override suspend fun updateStats(id: String, numberOfCopies: Int, lastCopiedAt: Long) = Unit
    override suspend fun updateRecognizedText(id: String, fullText: String, title: String) = Unit
    override suspend fun meta(id: String): ClipMeta? = null
    override suspend fun findByContentKey(contentKey: String): String? = null
    override suspend fun latestLastCopiedAt(): Long = 0L
    override suspend fun backfillEmptyTitles() = Unit
    override suspend fun setPinned(ids: List<String>, pinned: Boolean) = Unit
    override suspend fun delete(ids: List<String>) = Unit
    override suspend fun clear(all: Boolean) = Unit
    override fun reclaimStorageIfNeeded() = Unit
}
