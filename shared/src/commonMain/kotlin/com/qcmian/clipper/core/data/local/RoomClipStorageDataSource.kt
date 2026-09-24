package com.qcmian.clipper.core.data.local

import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import com.qcmian.clipper.core.data.source.ClipStorageDataSource
import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.model.ClipPayload
import com.qcmian.clipper.core.domain.model.ClipText
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.SortBy
import com.qcmian.clipper.core.settings.SortOrder
import com.qcmian.clipper.core.util.decodeJsonOrNull
import com.qcmian.clipper.core.util.encodeJson

/**
 * 基于 [ClipperDatabase] 的 [ClipStorageDataSource]，由 Android、iOS 与桌面端共用。
 *
 * @param databaseBytes 宿主如何测量数据库文件大小；无法测量的平台保持 `null`，
 *   此时偏好设置界面只显示条数、不显示大小。
 */
internal class RoomClipStorageDataSource(
    private val database: ClipperDatabase,
    private val databaseBytes: () -> Long? = { null },
) : ClipStorageDataSource {
    private val history = database.clipHistoryDao()
    private val preferences = database.appSettingsDao()

    /** 关闭后再有读写一律忽略：进程退出路径后续的落盘不需要感知关闭这件事。 */
    @Volatile
    private var closed = false

    /** [initialise] 只跑一次（它含一次可能的 PRAGMA + VACUUM）。 */
    @Volatile
    private var prepared = false

    override suspend fun initialise() {
        if (closed || prepared) return
        prepared = true
        tuneDatabase()
        // 两表一致性由写事务保证，正常路径不会留下孤儿；异常退出（写事务被中断）仍可能残留。
        history.deleteOrphanPayloads()
    }

    override suspend fun countUnpinned(): Int {
        if (closed) return 0
        return history.countUnpinned()
    }

    override suspend fun loadPinned(): List<ClipMeta> {
        if (closed) return emptyList()
        return history.loadPinned().map { it.toModel() }
    }

    override suspend fun loadUnpinned(
        by: SortBy,
        order: SortOrder,
        limit: Int,
        offset: Int,
    ): List<ClipMeta> {
        if (closed) return emptyList()
        return history.pageUnpinned(by, order, limit, offset).map { it.toModel() }
    }

    override suspend fun loadMeta(id: String): ClipMeta? {
        if (closed) return null
        return history.loadMeta(id)?.toModel()
    }

    override suspend fun findIdByContentKey(contentKey: String): String? {
        if (closed) return null
        return history.findIdByContentKey(contentKey)
    }

    override suspend fun overflowIds(maxCount: Int): List<String> {
        if (closed) return emptyList()
        return history.overflowIds(maxCount)
    }

    override suspend fun loadPayload(id: String): ClipPayload? {
        if (closed) return null
        return history.loadPayload(id)?.toModel()
    }

    override suspend fun loadTexts(ids: List<String>): List<ClipText> {
        if (closed || ids.isEmpty()) return emptyList()
        return history.loadPayloadTexts(ids).flatMap { row ->
            listOfNotNull(
                row.text?.takeIf { it.isNotEmpty() }?.let { ClipText(row.id, it) },
                row.recognizedText?.takeIf { it.isNotEmpty() }?.let { ClipText(row.id, it) },
            )
        }
    }

    override suspend fun insert(meta: ClipMeta, payload: ClipPayload?) {
        if (closed) return
        // 没有载荷的条目（例如只带文件路径）就不写载荷行，省一次插入。
        history.insert(meta.toEntity(), payload?.takeIf { !it.isEmpty }?.toEntity(meta.id))
    }

    override suspend fun updateStats(id: String, numberOfCopies: Int, lastCopiedAt: Long) {
        if (closed) return
        history.updateStats(id, numberOfCopies, lastCopiedAt)
    }

    override suspend fun maxLastCopiedAt(): Long {
        if (closed) return 0L
        return history.maxLastCopiedAt() ?: 0L
    }

    override suspend fun maxPinnedAt(): Long {
        if (closed) return 0L
        return history.maxPinnedAt() ?: 0L
    }

    override suspend fun emptyTitleIds(): List<String> {
        if (closed) return emptyList()
        return history.idsWithEmptyTitle()
    }

    override suspend fun updateTitle(id: String, title: String, fromRecognition: Boolean) {
        if (closed) return
        history.updateTitle(id, title, fromRecognition)
    }

    override suspend fun updateRecognizedText(id: String, fullText: String, title: String) {
        if (closed) return
        history.updateRecognizedText(id, fullText, title)
    }

    override suspend fun updatePinned(id: String, pinned: Boolean, pinnedAt: Long) {
        if (closed) return
        // 取消置顶把时间戳清零：留着旧值会让「先取消、再重新置顶」排到过期位置上。
        history.updatePinned(
            id = id,
            pinned = if (pinned) PINNED else UNPINNED,
            pinnedAt = if (pinned) pinnedAt else NOT_PINNED_AT,
        )
    }

    override suspend fun delete(ids: List<String>) {
        if (closed) return
        history.delete(ids)
    }

    override suspend fun deleteAllUnpinned() {
        if (closed) return
        history.deleteAllUnpinned()
    }

    override suspend fun deleteAll() {
        if (closed) return
        history.deleteAll()
    }

    override suspend fun deleteOrphanPayloads(): Int {
        if (closed) return 0
        return history.deleteOrphanPayloads()
    }

    override suspend fun loadSettings(): AppSettings {
        if (closed) return AppSettings()
        return decodeJsonOrNull<AppSettings>(preferences.load()) ?: AppSettings()
    }

    override suspend fun saveSettings(settings: AppSettings) {
        if (closed) return
        preferences.save(
            AppSettingsEntity(AppSettingsEntity.SINGLE_ROW_ID, encodeJson(settings)),
        )
    }

    // 注意：参数不能与该方法同名，否则这里的调用会解析成方法自身。
    override fun storageBytes(): Long? = databaseBytes()

    override suspend fun reclaimFreePages(minFreeBytes: Long) {
        if (closed) return
        // 空洞太小就不值得为它搬一次页：`incremental_vacuum` 的代价与回收页数成正比。
        if (freeBytes() < minFreeBytes) return
        // 不带参数即回收全部空闲页：逐页把文件尾截掉，因此是一次「缩多少看有多少空洞」的操作。
        database.useWriterConnection { it.executeSQL("PRAGMA incremental_vacuum") }
    }

    override fun close() {
        if (closed) return
        closed = true
        database.close()
    }

    /**
     * 空闲页的字节数（`freelist_count × page_size`），即「已删除、还没还给文件系统」的部分。
     *
     * 它是页级精确值，也是 [reclaimFreePages] 唯一能回收的量；页内碎片不在其中。
     */
    private suspend fun freeBytes(): Long =
        pragmaLong("PRAGMA freelist_count") * pragmaLong("PRAGMA page_size")

    /** 读一条只返回单个整数的 PRAGMA（都在库头里，与库大小无关）。 */
    private suspend fun pragmaLong(sql: String): Long =
        database.useReaderConnection { connection ->
            connection.usePrepared(sql) { statement ->
                if (statement.step()) statement.getLong(0) else 0L
            }
        }

    /**
     * 应用 PRAGMA。
     *
     * `mmap_size` 与 `cache_size` 每次打开都可以设：前者让大 BLOB 的读取绕过 `read()`
     * 系统调用，后者避免大 BLOB 反复冲刷页缓存。
     *
     * `page_size` 与 `auto_vacuum` 只对**之后写入**的内容生效，已有内容要靠一次 `VACUUM`
     * 重排。因此只在库事实上是空的时候顺手做掉（此时 `VACUUM` 是瞬时的），非空库不碰——
     * 那会是一次整库重写，代价可能是几分钟。
     */
    private suspend fun tuneDatabase() {
        database.useWriterConnection { connection ->
            connection.executeSQL("PRAGMA mmap_size = $MMAP_SIZE_BYTES")
            connection.executeSQL("PRAGMA cache_size = -$CACHE_SIZE_KB")
        }

        val pageSizeSettled = pragmaLong("PRAGMA page_size") == TARGET_PAGE_SIZE
        val autoVacuumSettled = pragmaLong("PRAGMA auto_vacuum") == AUTO_VACUUM_INCREMENTAL
        if (pageSizeSettled && autoVacuumSettled) return
        // 判据是「库里有没有用户数据」，**不是**页数：Room 建完三张表与索引就已经占了好几页，
        // 按页数判断会让这两个参数在全新库里也永远设不上。
        if (history.countAll() > 0) return

        database.useWriterConnection { connection ->
            connection.executeSQL("PRAGMA page_size = $TARGET_PAGE_SIZE")
            connection.executeSQL("PRAGMA auto_vacuum = INCREMENTAL")
            connection.executeSQL("VACUUM")
        }
    }

    private companion object {
        /** `PRAGMA auto_vacuum` 的取值：0 = NONE，1 = FULL，2 = INCREMENTAL。 */
        const val AUTO_VACUUM_INCREMENTAL = 2L

        /**
         * 16 KB 的页。
         *
         * 页越大，一条大 BLOB 需要的溢出页越少、每页要读的指针开销占比越低；代价是页内
         * 碎片的最小粒度也变粗了——对「窄行元数据 + 巨大载荷」这套结构是划算的。
         */
        const val TARGET_PAGE_SIZE = 16_384L

        /** 256 MB 的 mmap 窗口。 */
        const val MMAP_SIZE_BYTES = 268_435_456L

        /** 负数表示 KiB，即 64 MB 页缓存。 */
        const val CACHE_SIZE_KB = 65_536L
    }
}
