package com.qcmian.clipper.core.data.source

import androidx.room3.Room
import androidx.room3.RoomDatabase
import com.qcmian.clipper.core.data.local.CLIPPER_MIGRATIONS
import com.qcmian.clipper.core.data.local.ClipperDatabase
import com.qcmian.clipper.core.data.local.RoomClipStorageDataSource

/**
 * 位于 `~/.clipper` 的数据库，应用支持目录布局。
 */
private fun createDatabase(): ClipperDatabase {
    val file = clipperDatabaseFile()
    file.parentFile?.mkdirs()
    return Room.databaseBuilder<ClipperDatabase>(name = file.absolutePath)
        // 有迁移就迁移（见 `CLIPPER_MIGRATIONS`）；找不到路径就让开库失败，**绝不碰数据**。
        // 这里刻意不挂 `fallbackToDestructiveMigration()`：它的语义是「静默删库重建」，
        // 发生在界面加载之前，用户眼里就是「历史自己没了」，且不可恢复——2026-09-25
        // 已经因此丢过一次真实历史。缺迁移时「打不开」反而可救：数据完好，补个迁移发一版就行。
        .addMigrations(*CLIPPER_MIGRATIONS)
        // SQLCipher 驱动：口令由 `DatabaseKey` 现给（每次建连接都读一次），因此换钥之后新开的
        // 连接自动用新口令，不必重建 Room。明文库下它给 `null`，落盘的仍是标准 SQLite。
        .setDriver(SqlCipherDriver(passphrase = DatabaseKey::current))
        // 回滚日志（TRUNCATE）代替 WAL：没有 -wal / -shm，全部数据都在主库文件里，
        // 读写也不再需要 wal-index。代价是每事务多一两次 fsync、写期间读被阻塞——
        // 历史持久化已改为增量写入（单次事务只有几 KB），这点代价可以忽略。
        // Room 用单连接跑 TRUNCATE，与桌面端的单使用方模型正好吻合。
        //
        // 对加密还有一层意义：加密一个明文库要求连接**不在 WAL 模式**（见 SQLite3MultipleCiphers
        // 的 `PRAGMA rekey` 说明），TRUNCATE 天然满足。
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .build()
}

actual fun createClipStorageDataSource(): ClipStorageDataSource =
    RoomClipStorageDataSource(
        database = createDatabase(),
        // 存储实际为空时（只有一个空页头）当作「没有大小」。
        databaseBytes = { clipperDatabaseFile().length().takeIf { it > 1 } },
        // 会话密钥：桌面端有能力加密，换钥成功后由存储层回写。
        sessionKey = DatabaseKey,
        // 换钥前先备份文件，失败即回滚。
        backup = DatabaseFileBackup,
    )
