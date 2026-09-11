package com.qcmian.clipper.data.local

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction

/**
 * 历史表的读 / 写入口。
 *
 * 用抽象类（而非接口）是为了让 [replaceAll] 能作为带方法体的 `@Transaction` 方法，
 * 从而保证「删除全部再插入新列表」是原子的。
 */
@Dao
abstract class ClipHistoryDao {
    @Query("SELECT * FROM clip_history")
    abstract suspend fun load(): List<ClipItemEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(items: List<ClipItemEntity>)

    @Query("DELETE FROM clip_history")
    abstract suspend fun deleteAll()

    /** 原子地替换整份历史。 */
    @Transaction
    open suspend fun replaceAll(items: List<ClipItemEntity>) {
        deleteAll()
        insertAll(items)
    }
}

/** 单行偏好设置表的读 / 写入口。 */
@Dao
interface AppSettingsDao {
    @Query("SELECT payload FROM app_settings WHERE id = 0")
    suspend fun load(): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(row: AppSettingsEntity)
}
