package com.qcmian.clipper.core.data.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update

/**
 * 历史表的读 / 写入口。
 *
 * 写入走 [applyDiff]：与磁盘上已有行做 diff 后只提交变化的那几行。排序与裁剪由仓库在
 * 加载时重算（见 `DefaultClipboardRepository.normalise`），因此行序不需要落盘，全量重写
 * 从来没有必要——历史里有大体积 BLOB 时，diff 让单次复制的落盘从 O(整份历史) 降到 O(1 行)。
 */
@Dao
abstract class ClipHistoryDao {
    @Query("SELECT * FROM clip_history")
    abstract suspend fun load(): List<ClipItemEntity>

    /** [load] 的轻量版：不读 image 列，用于 diff 对照与全量加载的差别只在 BLOB。 */
    @Query(
        "SELECT id, text, files, firstCopiedAt, lastCopiedAt, numberOfCopies, " +
            "pin, title, application FROM clip_history",
    )
    abstract suspend fun loadLite(): List<ClipItemLite>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(items: List<ClipItemEntity>)

    @Update
    abstract suspend fun updateAll(items: List<ClipItemEntity>)

    @Query("DELETE FROM clip_history WHERE id IN (:ids)")
    abstract suspend fun deleteByIds(ids: List<String>)

    /** 原子地应用一次 diff：新增、更新与删除在同一个事务里落地。 */
    @Transaction
    open suspend fun applyDiff(
        inserts: List<ClipItemEntity>,
        updates: List<ClipItemEntity>,
        deletes: List<String>,
    ) {
        if (inserts.isNotEmpty()) insertAll(inserts)
        if (updates.isNotEmpty()) updateAll(updates)
        if (deletes.isNotEmpty()) deleteByIds(deletes)
    }
}

/**
 * 历史行除 image 外各列的投影。给 diff 用：比较时不必把历史里的所有图片 BLOB 拉出磁盘。
 */
data class ClipItemLite(
    val id: String,
    val text: String?,
    val files: String,
    val firstCopiedAt: Long,
    val lastCopiedAt: Long,
    val numberOfCopies: Int,
    val pin: String?,
    val title: String,
    val application: String?,
)

/** 单行偏好设置表的读 / 写入口。 */
@Dao
interface AppSettingsDao {
    @Query("SELECT payload FROM app_settings WHERE id = 0")
    suspend fun load(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: AppSettingsEntity)
}
