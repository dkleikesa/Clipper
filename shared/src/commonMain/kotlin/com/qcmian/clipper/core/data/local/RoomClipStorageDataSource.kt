package com.qcmian.clipper.core.data.local

import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import com.qcmian.clipper.core.data.source.ClipStorageDataSource
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.settings.AppSettings
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

    /** 关闭后再有读写一律忽略：进程退出路径后续的 `flush()` 不需要感知关闭这件事。 */
    @Volatile
    private var closed = false

    /** [ensureIncrementalVacuum] 是否已确认过（见那里的说明）。 */
    @Volatile
    private var incrementalVacuumReady = false

    override suspend fun loadItems(): List<ClipItem> {
        if (closed) return emptyList()
        return history.load().map { it.toModel() }
    }

    override suspend fun saveItems(items: List<ClipItem>) {
        if (closed) return
        // 与磁盘上的已有行做 diff，只提交变化的行。对照用轻量投影（不含 image BLOB），
        // 因此无论历史多大，diff 本身都只读几 KB 的元数据；单次复制通常只产生一两个
        // 变更行，落盘成本从 O(整份历史) 降到 O(变化行数)。
        val existing = history.loadLite().associateBy { it.id }
        val seen = HashSet<String>(existing.size * 2)
        val inserts = ArrayList<ClipItemEntity>()
        val updates = ArrayList<ClipItemEntity>()
        for (item in items) {
            seen += item.id
            val row = item.toRowLite()
            when (existing[item.id]) {
                null -> inserts += item.toEntity()
                row -> Unit
                else -> updates += item.toEntity()
            }
        }
        val deletes = existing.keys.filterNot { it in seen }
        history.applyDiff(inserts, updates, deletes)
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

    override suspend fun compact() {
        if (closed) return
        // `VACUUM` 不能在事务里执行，因此直接取写连接跑。它重写整个数据库文件：空闲页与页内
        // 碎片一起回收，代价与库的**有效数据量**成正比（不是文件大小）。
        database.useWriterConnection { it.executeSQL("VACUUM") }
    }

    override suspend fun reclaimFreePages(minFreeBytes: Long) {
        if (closed) return
        // 空洞太小就不值得为它搬一次页：`incremental_vacuum` 的代价与回收页数成正比。
        if (freeBytes() < minFreeBytes) return
        ensureIncrementalVacuum()
        // 不带参数即回收全部空闲页：逐页把文件尾截掉，因此是一次「缩多少看有多少空洞」的操作。
        database.useWriterConnection { it.executeSQL("PRAGMA incremental_vacuum") }
    }

    /**
     * 空闲页的字节数（`freelist_count × page_size`），即「已删除、还没还给文件系统」的部分。
     *
     * 它是页级精确值，也是 [reclaimFreePages] 唯一能回收的量；页内碎片（一页里删掉部分行）
     * 不在其中——那部分只有 [compact] 能收。
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
     * 确保 `auto_vacuum = INCREMENTAL`：只有这个模式下 `incremental_vacuum` 才真的搬页，
     * 否则那条 PRAGMA 是**空操作**。
     *
     * 该模式只在数据库尚为空时可以凭 PRAGMA 直接设定，已有内容的库要重建一次才生效——这里
     * 就地重建（历史内容都来自系统剪贴板，可再复制回来），且只在第一次需要回收时做一次。
     */
    private suspend fun ensureIncrementalVacuum() {
        if (incrementalVacuumReady) return
        if (pragmaLong("PRAGMA auto_vacuum") != AUTO_VACUUM_INCREMENTAL) {
            database.useWriterConnection { it.executeSQL("PRAGMA auto_vacuum = INCREMENTAL") }
            database.useWriterConnection { it.executeSQL("VACUUM") }
        }
        incrementalVacuumReady = true
    }

    override fun close() {
        if (closed) return
        closed = true
        database.close()
    }

    private companion object {
        /** `PRAGMA auto_vacuum` 的取值：0 = NONE，1 = FULL，2 = INCREMENTAL。 */
        const val AUTO_VACUUM_INCREMENTAL = 2L
    }
}
