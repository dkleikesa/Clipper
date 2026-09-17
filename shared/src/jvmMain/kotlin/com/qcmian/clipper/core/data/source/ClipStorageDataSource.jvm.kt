package com.qcmian.clipper.core.data.source

import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.qcmian.clipper.core.data.local.ClipperDatabase
import com.qcmian.clipper.core.data.local.RoomClipStorageDataSource
import com.qcmian.clipper.core.util.formatBytes
import java.io.File

private const val DATABASE_NAME = "clipper.db"

/**
 * 位于 `~/.clipper` 的数据库，应用支持目录布局。
 */
private fun databaseFile(): File =
    File(File(System.getProperty("user.home") ?: ".", ".clipper"), DATABASE_NAME)

private fun createDatabase(): ClipperDatabase {
    val file = databaseFile()
    file.parentFile?.mkdirs()
    return Room.databaseBuilder<ClipperDatabase>(name = file.absolutePath)
        .setDriver(BundledSQLiteDriver())
        // 回滚日志（TRUNCATE）代替 WAL：没有 -wal / -shm，全部数据都在主库文件里，
        // 读写也不再需要 wal-index。代价是每事务多一两次 fsync、写期间读被阻塞——
        // 历史持久化已改为增量写入（单次事务只有几 KB），这点代价可以忽略。
        // Room 用单连接跑 TRUNCATE，与桌面端的单使用方模型正好吻合。
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
        .build()
}

actual fun createClipStorageDataSource(): ClipStorageDataSource =
    RoomClipStorageDataSource(createDatabase()) {
        val bytes = databaseFile().length()
        // 存储实际为空时返回空字符串。
        if (bytes > 1) formatBytes(bytes) else ""
    }
