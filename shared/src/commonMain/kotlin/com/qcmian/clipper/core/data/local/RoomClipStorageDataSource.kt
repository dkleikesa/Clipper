package com.qcmian.clipper.core.data.local

import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLiteException
import com.qcmian.clipper.core.data.source.ClipStorageDataSource
import com.qcmian.clipper.core.data.source.EncryptionBackup
import com.qcmian.clipper.core.data.source.SQLCIPHER_SCHEME
import com.qcmian.clipper.core.data.source.SessionKey
import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.model.ClipPayload
import com.qcmian.clipper.core.domain.model.ClipText
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.SortBy
import com.qcmian.clipper.core.settings.SortOrder
import com.qcmian.clipper.core.util.decodeJsonOrNull
import com.qcmian.clipper.core.util.encodeJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 基于 [ClipperDatabase] 的 [ClipStorageDataSource]，由 Android、iOS 与桌面端共用。
 *
 * @param databaseBytes 宿主如何测量数据库文件大小；无法测量的平台保持 `null`，
 *   此时偏好设置界面只显示条数、不显示大小。
 */
internal class RoomClipStorageDataSource(
    private val database: ClipperDatabase,
    private val databaseBytes: () -> Long? = { null },
    /** 会话密钥的同步点；`null` 表示当前平台不支持加密（见 [supportsEncryption]）。 */
    private val sessionKey: SessionKey? = null,
    /** 换钥前后的备份 / 回滚；`null` 表示不做这层保险。 */
    private val backup: EncryptionBackup? = null,
    /**
     * 「这一串是不是当前库的口令」由宿主回答（见 [verifyPassphrase]）；`null` 表示不做这件事，
     * 此时校验一律不通过。
     */
    private val passphraseVerifier: ((String) -> Boolean)? = null,
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

    override val supportsEncryption: Boolean get() = sessionKey != null

    override val isEncrypted: Boolean get() = sessionKey?.encrypted ?: false

    /**
     * `PRAGMA rekey` 一次性把整库按新口令重写：
     *
     * - `null`（空口令）即解密回标准明文库；
     * - 明文库也能直接加密，不需要先导出再重建。
     *
     * 四条顺序都是必须的：
     *
     * - **先收掉残留事务**。`rekey` 内部含一次 `VACUUM`，SQLite 明确拒绝在事务中做这件事
     *   （`cannot VACUUM from within a transaction`）。驱动侧现在会把「提交/回滚失败」留下的
     *   事务真的收掉（见 `SqlCipherDriver.ConnectionState.abandonTransaction`），但这里仍然补一次
     *   回滚：它是这条路径上唯一能确认「连接此刻不在事务里」的判据，而整库重写的代价远高于一句
     *   多余的 `ROLLBACK`。没有事务时 SQLite 会回一句 `no transaction is active`，属正常。
     * - **备份必须在拿到写连接、且回滚之后**。库是 TRUNCATE（回滚日志）模式：写事务进行中，
     *   主库文件里是**半提交**的页，原始页只在 `-journal` 里。若在那时拷文件，`.bak` 本身就是
     *   不一致的——真回滚回去等于把坏快照写回主库。持有写连接保证没有并发写事务，前面的回滚
     *   再保证没有残留事务，此时的一次文件拷贝才是干净的已提交快照。
     * - **`cipher` 必须在 `rekey` 之前**：只写 `rekey` 会沿用连接的旧方案，与驱动建连接时注入的
     *   方案（[SQLCIPHER_SCHEME]）对不上，下次开库就会报 `file is not a database`。
     * - **最后才更新会话口令**：失败时不能把新口令留在内存里，否则下次开库用它必然打不开。
     */
    override suspend fun rekey(newPassphrase: String?): Result<Unit> {
        val session = sessionKey
            ?: return Result.failure(UnsupportedOperationException("当前平台不支持数据库加密"))
        if (closed) return Result.failure(IllegalStateException("数据库已关闭"))

        val snapshot = backup
        // 备份成功与否在写连接的块里才定下来，因此用一个 var 带出块外。
        var backedUp = false
        val result = runCatching {
            val key = newPassphrase ?: ""
            database.useWriterConnection { connection ->
                try {
                    connection.executeSQL("ROLLBACK TRANSACTION")
                } catch (_: SQLiteException) {
                    // 本来就没有事务——正是常态。
                }
                // 到这里连接上既无并发写事务、也无残留事务，文件才是「干净的已提交状态」。
                backedUp = snapshot != null && snapshot.snapshot()
                connection.executeSQL("PRAGMA cipher = '${SQLCIPHER_SCHEME}'")
                connection.executeSQL("PRAGMA rekey = ${sqlLiteral(key)}")
            }
            session.update(newPassphrase)
        }
        if (backedUp) {
            val files = checkNotNull(snapshot)
            if (result.isSuccess) {
                files.discard()
            } else {
                // 回滚文件；连接那边已经不可信，调用方应当提示用户重启（见 `ClipboardViewModel`）。
                files.restore()
            }
        }
        return result
    }

    /**
     * 口令核对本身要另开一条连接去试读库头（见宿主的 `verifyDatabasePassphrase`），是阻塞 IO，
     * 因此挪到 [Dispatchers.Default] 上：调用方在设置窗口那条路径上跑的是合成线程。
     */
    override suspend fun verifyPassphrase(passphrase: String): Boolean {
        if (closed) return false
        val verifier = passphraseVerifier ?: return false
        return withContext(Dispatchers.Default) { verifier(passphrase) }
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

/**
 * 把口令写成 SQL 字符串字面量（单引号翻倍）。
 *
 * `PRAGMA` 语句不支持绑定参数，只能拼字符串，因此这里必须自己转义——否则一个带单引号的
 * 口令就是一次 SQL 注入，且会表现为「设了 A 口令、实际却是另一个」这种极难排查的错。
 */
private fun sqlLiteral(value: String): String = "'" + value.replace("'", "''") + "'"
