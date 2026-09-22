package com.qcmian.clipper.core.data.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import com.qcmian.clipper.core.settings.SortBy
import com.qcmian.clipper.core.settings.SortOrder

/**
 * 历史两张表（`clip_meta` + `clip_payload`）的唯一入口。
 *
 * 一个 DAO 同时管两张表，是为了让「写一条历史」这类跨表动作能落在一个 `@Transaction` 里：
 * 拆成两个 DAO 就只能在上层手工开事务，而那层拿不到 Room 的事务句柄。
 *
 * 元数据表的读写被设计成「窄行 + 全覆盖索引」：
 *
 * - **分页永远走索引**：4 个排序字段各有一个 `(pinned, <字段>)` 联合索引。`ORDER BY` 必须
 *   写死才能用上它们，所以下面有 8 个固定语句，由 [pageUnpinned] 分发。
 * - **排序只写一个列**：`ORDER BY a, b` 需要 `(pinned, a, b)` 这样的复合索引才走得动，
 *   多一个 tiebreaker 就多一份索引体积；而 SQLite 的索引内天然按 rowid 排序，同值行的顺序
 *   本来就是稳定的，不需要自己在 SQL 里再指定一次（实测加了 `, id` 反而退化成临时 B 树）。
 * - **写操作按列精确定位**：统计与置顶各一条 `UPDATE`，不重写整行，更不碰载荷表。
 *
 * 载荷表只在「按 id 取一条」与「整条增删」两条路径上被触达。
 */
@Dao
abstract class ClipHistoryDao {

    // -----------------------------------------------------------------------------------
    // 元数据：读
    // -----------------------------------------------------------------------------------

    /** 未置顶条目数。列表用它判断是否还有下一批，设置页用它显示「当前条数」。 */
    @Query("SELECT COUNT(*) FROM clip_meta WHERE pinned = 0")
    abstract suspend fun countUnpinned(): Int

    /** 全部条目数（含置顶）。只用于「这个库是不是还没有用户数据」这类判断。 */
    @Query("SELECT COUNT(*) FROM clip_meta")
    abstract suspend fun countAll(): Int

    @Query("SELECT * FROM clip_meta WHERE id = :id")
    abstract suspend fun loadMeta(id: String): ClipMetaEntity?

    /** 内容摘要相同的条目 id；把去重从「扫描整份历史」降成一次等值查询。 */
    @Query("SELECT id FROM clip_meta WHERE contentKey = :contentKey LIMIT 1")
    abstract suspend fun findIdByContentKey(contentKey: String): String?

    /**
     * 置顶区块：置顶是用户的显式选择、数量通常很少，因此不分页，一次取全。
     *
     * 固定按 `lastCopiedAt` 降序——置顶的语义是「固定在那」，不该被筛选栏的排序方式左右。
     */
    @Query("SELECT * FROM clip_meta WHERE pinned = 1 ORDER BY lastCopiedAt DESC")
    abstract suspend fun loadPinned(): List<ClipMetaEntity>

    /**
     * 超出上限的未置顶条目的 id：按「最后一次复制」从新到旧数，第 [maxCount] 条之后的全部丢弃。
     *
     * 裁剪口径从「按当前排序取前 N」改成「按时间保留最近 N」——用户设上限的意图是
     * 「只留最近的这些」，前者会随排序方式变化丢掉不同的行。
     */
    @Query(
        "SELECT id FROM clip_meta WHERE pinned = 0 " +
            "ORDER BY lastCopiedAt DESC LIMIT -1 OFFSET :maxCount",
    )
    abstract suspend fun overflowIds(maxCount: Int): List<String>

    // -----------------------------------------------------------------------------------
    // 元数据：分页（4 个排序字段 × 2 个方向）
    // -----------------------------------------------------------------------------------

    @Query("SELECT * FROM clip_meta WHERE pinned = 0 ORDER BY lastCopiedAt DESC LIMIT :limit OFFSET :offset")
    abstract suspend fun pageByLastCopiedDesc(limit: Int, offset: Int): List<ClipMetaEntity>

    @Query("SELECT * FROM clip_meta WHERE pinned = 0 ORDER BY lastCopiedAt ASC LIMIT :limit OFFSET :offset")
    abstract suspend fun pageByLastCopiedAsc(limit: Int, offset: Int): List<ClipMetaEntity>

    @Query("SELECT * FROM clip_meta WHERE pinned = 0 ORDER BY firstCopiedAt DESC LIMIT :limit OFFSET :offset")
    abstract suspend fun pageByFirstCopiedDesc(limit: Int, offset: Int): List<ClipMetaEntity>

    @Query("SELECT * FROM clip_meta WHERE pinned = 0 ORDER BY firstCopiedAt ASC LIMIT :limit OFFSET :offset")
    abstract suspend fun pageByFirstCopiedAsc(limit: Int, offset: Int): List<ClipMetaEntity>

    @Query("SELECT * FROM clip_meta WHERE pinned = 0 ORDER BY numberOfCopies DESC LIMIT :limit OFFSET :offset")
    abstract suspend fun pageByCopiesDesc(limit: Int, offset: Int): List<ClipMetaEntity>

    @Query("SELECT * FROM clip_meta WHERE pinned = 0 ORDER BY numberOfCopies ASC LIMIT :limit OFFSET :offset")
    abstract suspend fun pageByCopiesAsc(limit: Int, offset: Int): List<ClipMetaEntity>

    @Query("SELECT * FROM clip_meta WHERE pinned = 0 ORDER BY payloadBytes DESC LIMIT :limit OFFSET :offset")
    abstract suspend fun pageBySizeDesc(limit: Int, offset: Int): List<ClipMetaEntity>

    @Query("SELECT * FROM clip_meta WHERE pinned = 0 ORDER BY payloadBytes ASC LIMIT :limit OFFSET :offset")
    abstract suspend fun pageBySizeAsc(limit: Int, offset: Int): List<ClipMetaEntity>

    /** 按偏好分发到上面八个固定语句之一——`ORDER BY` 写死才用得上索引。 */
    suspend fun pageUnpinned(
        by: SortBy,
        order: SortOrder,
        limit: Int,
        offset: Int,
    ): List<ClipMetaEntity> {
        val descending = order == SortOrder.DESCENDING
        return when (by) {
            SortBy.LAST_COPIED_AT ->
                if (descending) pageByLastCopiedDesc(limit, offset) else pageByLastCopiedAsc(limit, offset)

            SortBy.FIRST_COPIED_AT ->
                if (descending) pageByFirstCopiedDesc(limit, offset) else pageByFirstCopiedAsc(limit, offset)

            SortBy.NUMBER_OF_COPIES ->
                if (descending) pageByCopiesDesc(limit, offset) else pageByCopiesAsc(limit, offset)

            SortBy.FILE_SIZE ->
                if (descending) pageBySizeDesc(limit, offset) else pageBySizeAsc(limit, offset)
        }
    }

    // -----------------------------------------------------------------------------------
    // 载荷
    // -----------------------------------------------------------------------------------

    @Query("SELECT * FROM clip_payload WHERE id = :id")
    abstract suspend fun loadPayload(id: String): ClipPayloadEntity?

    // -----------------------------------------------------------------------------------
    // 写
    // -----------------------------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertMetas(rows: List<ClipMetaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertPayloads(rows: List<ClipPayloadEntity>)

    /**
     * 写入一条新历史。元数据与载荷在同一个事务里落地，因此不会出现
     * 「列表里有、点开是空的」的半条记录。
     *
     * [payload] 为 `null` 表示这条记录没有任何载荷（例如只携带文件路径），此时只写元数据。
     */
    @Transaction
    open suspend fun insert(meta: ClipMetaEntity, payload: ClipPayloadEntity?) {
        insertMetas(listOf(meta))
        if (payload != null) insertPayloads(listOf(payload))
    }

    /** 重复复制：只改统计列。 */
    @Query("UPDATE clip_meta SET numberOfCopies = :copies, lastCopiedAt = :lastCopiedAt WHERE id = :id")
    abstract suspend fun updateStats(id: String, copies: Int, lastCopiedAt: Long)

    /**
     * 改写标题。[fromRecognition] 同时决定「标题是否来自图片识别」这一标记——它决定
     * 工具栏要不要给出「复制图片文字」，以及渲染时要不要压平换行。
     */
    @Query("UPDATE clip_meta SET title = :title, hasRecognizedText = :fromRecognition WHERE id = :id")
    abstract suspend fun updateTitle(id: String, title: String, fromRecognition: Boolean)

    /**
     * 历史里最大的 `lastCopiedAt`；没有任何条目时为 `null`。
     *
     * 新条目的时间戳要严格大于它——墙钟精度只有毫秒，快速连续复制时两个快照可能拿到同一个
     * 时间戳，那样列表顺序就会随机。
     */
    @Query("SELECT MAX(lastCopiedAt) FROM clip_meta")
    abstract suspend fun maxLastCopiedAt(): Long?

    /**
     * 标题为空的条目 id。
     *
     * 只服务于启动时的**回填**：`title` 是写入时派生好落盘的，因此新增的标题来源（从 HTML
     * 附加表示里提取文字）只对新复制生效，旧条目仍是空标题——它们在列表里是一行空白、
     * 搜什么都搜不到。没有索引，但这是一次窄列全扫，且回填之后通常长期为空。
     */
    @Query("SELECT id FROM clip_meta WHERE title = ''")
    abstract suspend fun idsWithEmptyTitle(): List<String>

    @Query("UPDATE clip_meta SET pinned = :pinned WHERE id = :id")
    abstract suspend fun updatePinned(id: String, pinned: Int)

    /** 仅供本类的事务方法内部使用，外部请用 [delete]。 */
    @Query("DELETE FROM clip_meta WHERE id IN (:ids)")
    abstract suspend fun deleteMetas(ids: List<String>)

    /** 仅供本类的事务方法内部使用。 */
    @Query("DELETE FROM clip_payload WHERE id IN (:ids)")
    abstract suspend fun deletePayloads(ids: List<String>)

    @Transaction
    open suspend fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        deleteMetas(ids)
        deletePayloads(ids)
    }

    /** 仅供本类的事务方法内部使用。 */
    @Query("SELECT id FROM clip_meta WHERE pinned = 0")
    abstract suspend fun unpinnedIds(): List<String>

    /** 仅供本类的事务方法内部使用。 */
    @Query("DELETE FROM clip_meta")
    abstract suspend fun deleteAllMetas()

    /** 仅供本类的事务方法内部使用。 */
    @Query("DELETE FROM clip_payload")
    abstract suspend fun deleteAllPayloads()

    @Transaction
    open suspend fun deleteAll() {
        deleteAllMetas()
        deleteAllPayloads()
    }

    @Transaction
    open suspend fun deleteAllUnpinned() {
        // 载荷没有「置顶」的概念，只能先按元数据筛出要删的 id。
        val ids = unpinnedIds()
        if (ids.isNotEmpty()) deletePayloads(ids)
        deleteUnpinnedMetas()
    }

    /** 仅供本类的事务方法内部使用。 */
    @Query("DELETE FROM clip_meta WHERE pinned = 0")
    abstract suspend fun deleteUnpinnedMetas()

    /**
     * 清掉元数据里已经不存在的载荷行，返回删除条数。
     *
     * 两表的一致性由上面的 `@Transaction` 保证，正常路径不会留下孤儿；异常退出（写事务被
     * 中断）仍可能残留，因此启动时兜一次。
     */
    @Query("DELETE FROM clip_payload WHERE id NOT IN (SELECT id FROM clip_meta)")
    abstract suspend fun deleteOrphanPayloads(): Int
}

/** 单行偏好设置表的读 / 写入口。 */
@Dao
interface AppSettingsDao {
    @Query("SELECT payload FROM app_settings WHERE id = 0")
    suspend fun load(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: AppSettingsEntity)
}
