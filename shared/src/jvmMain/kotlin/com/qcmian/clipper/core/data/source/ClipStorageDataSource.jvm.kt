package com.qcmian.clipper.core.data.source

import androidx.room3.Room
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
        .build()
}

actual fun createClipStorageDataSource(): ClipStorageDataSource =
    RoomClipStorageDataSource(createDatabase()) {
        val bytes = databaseFile().length()
        // 存储实际为空时返回空字符串。
        if (bytes > 1) formatBytes(bytes) else ""
    }
