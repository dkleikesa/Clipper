package com.qcmian.clipper.core.data.local

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor

/**
 * 应用唯一的 Room 数据库：历史拆成「元数据 + 载荷」两张表，另有一张表存偏好设置。
 * Android、iOS 与桌面端都打开同一份 schema；只有文件位置与 SQLite 驱动不同，
 * 而这两者由各自的平台源集提供。
 *
 * 历史为什么拆表见 [ClipMetaEntity] 的说明——一句话：SQLite 的 `UPDATE` 会重写整条记录，
 * 高频改写的窄字段不能和大 BLOB 同行，否则每次「复制次数 + 1」都要重写一遍图片。
 *
 * 版本号从 `1` 开始：应用尚未发布，schema 在开发期几经推翻（单表 → 双表 → 列类型与编码
 * 的调整），没有必要为一版都没发出去的结构留下版本历史。首次发布之后再按正常迁移递增。
 *
 * **每次 +1 都必须同时在 [CLIPPER_MIGRATIONS] 里补一条迁移**：构建器上还挂着
 * `fallbackToDestructiveMigration()`，缺迁移的版本变化会把整库静默删掉重建。
 *
 * `2`：`clip_meta` 增加 `pinnedAt`（置顶区的固定顺序，见 [ClipMetaEntity.pinnedAt]）。
 */
@Database(
    entities = [ClipMetaEntity::class, ClipPayloadEntity::class, AppSettingsEntity::class],
    version = 2,
)
@ConstructedBy(ClipperDatabaseConstructor::class)
abstract class ClipperDatabase : RoomDatabase() {
    abstract fun clipHistoryDao(): ClipHistoryDao

    abstract fun appSettingsDao(): AppSettingsDao
}

/**
 * Room 按目标生成的构造函数。在此声明，是因为构建器位于平台代码中，
 * 需要一个实例化所生成数据库实现的入口。
 *
 * `initialize()` 必须在 expect 侧显式 override：平台编译时由 Room 的 KSP 生成
 * actual 实现，而元数据编译（iOS / IDE 同步）没有 actual，缺了它就过不了编译。
 */
@Suppress("KotlinNoActualForExpect")
expect object ClipperDatabaseConstructor : RoomDatabaseConstructor<ClipperDatabase> {
    override fun initialize(): ClipperDatabase
}
