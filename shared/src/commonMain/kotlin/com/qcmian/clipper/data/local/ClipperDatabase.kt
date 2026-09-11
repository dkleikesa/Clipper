package com.qcmian.clipper.data.local

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

/**
 * 应用唯一的 Room 数据库：一张表存剪贴板历史，一张表存偏好设置。
 * Android、iOS 与桌面端都打开同一份 schema；只有文件位置与 SQLite 驱动不同，
 * 而这两者由各自的平台源集提供。
 */
@Database(
    entities = [ClipItemEntity::class, AppSettingsEntity::class],
    version = 1,
)
@ConstructedBy(ClipperDatabaseConstructor::class)
abstract class ClipperDatabase : RoomDatabase() {
    abstract fun clipHistoryDao(): ClipHistoryDao

    abstract fun appSettingsDao(): AppSettingsDao
}

/**
 * Room 按目标生成的构造函数。在此声明，是因为构建器位于平台代码中，
 * 需要一个实例化所生成数据库实现的入口。
 */
@Suppress("KotlinNoActualForExpect")
expect object ClipperDatabaseConstructor : RoomDatabaseConstructor<ClipperDatabase>
