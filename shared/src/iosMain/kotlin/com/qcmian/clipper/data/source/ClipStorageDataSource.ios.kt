package com.qcmian.clipper.data.source

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.qcmian.clipper.data.local.ClipperDatabase
import com.qcmian.clipper.data.local.RoomClipStorageDataSource
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

private const val DATABASE_NAME = "clipper.db"

/** 位于应用 Documents 目录中的数据库文件。 */
@OptIn(ExperimentalForeignApi::class)
private fun databasePath(): String {
    val directory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = true,
        error = null,
    ) ?: error("Cannot locate the documents directory")
    val path = directory.path ?: error("Cannot resolve the documents directory path")
    return "$path/$DATABASE_NAME"
}

actual fun createClipStorageDataSource(): ClipStorageDataSource =
    RoomClipStorageDataSource(
        Room.databaseBuilder<ClipperDatabase>(name = databasePath())
            .setDriver(BundledSQLiteDriver())
            .build(),
    )
