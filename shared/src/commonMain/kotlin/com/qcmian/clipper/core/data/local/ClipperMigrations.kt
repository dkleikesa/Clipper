package com.qcmian.clipper.core.data.local

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * `1 → 2`：`clip_meta` 增加 `pinnedAt`（置顶区的固定顺序，见 [ClipMetaEntity.pinnedAt]）。
 *
 * 三步都省不掉：
 * - 加列时的 `DEFAULT 0` 必须与实体上的 `@ColumnInfo(defaultValue = "0")` 一致，否则迁移之后
 *   Room 拿实际表结构与期望结构比对会失败（那会让应用直接起不来，比丢数据更难查）；
 * - 索引名必须与 Room 生成的 `index_clip_meta_pinned_pinnedAt` 完全一致；
 * - 回填用 `lastCopiedAt`：旧库的置顶区本来就是按它排序的，拿它当戳正好把**当前看到的顺序
 *   原样冻结下来**。若一律填 0，旧置顶项会全挤在同一个值上，先后只能听 rowid 的。
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override suspend fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "ALTER TABLE `clip_meta` ADD COLUMN `pinnedAt` INTEGER NOT NULL DEFAULT 0",
        )
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_clip_meta_pinned_pinnedAt` " +
                "ON `clip_meta` (`pinned`, `pinnedAt`)",
        )
        connection.execSQL("UPDATE `clip_meta` SET `pinnedAt` = `lastCopiedAt` WHERE `pinned` = 1")
    }
}

/**
 * 数据库版本迁移表：**改 schema 就必须在这里补一条**。
 *
 * 各平台的构建器都挂着 `fallbackToDestructiveMigration()`，它的语义是「找不到迁移路径就把
 * 整库删掉重建」——静默、无提示，而且发生在界面加载之前，用户眼里就是「历史自己没了」。
 * 2026-09-25 已经因此清掉过一次真实历史：新增 `pinnedAt` 时只改了实体、没写迁移，用户什么都
 * 没点，只是启动了一次应用，历史 + 置顶 + 全部偏好设置一起没了，且无法恢复。
 *
 * 所以规矩是：**改 schema → 版本号 +1 → 这里补迁移 → 重新导出 schema json**。
 * 破坏性重建只留给真正没有迁移路径的情况（例如运行在比数据更旧的代码上）。
 */
internal val CLIPPER_MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
