package com.qcmian.clipper.data.source

import android.content.Context
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.qcmian.clipper.data.local.ClipperDatabase
import com.qcmian.clipper.data.local.RoomClipStorageDataSource
import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.settings.AppSettings

private const val DATABASE_NAME = "clipper.db"

/** 在应用的私有 databases 目录中打开唯一的 Room 数据库。 */
private fun createDatabase(context: Context): ClipperDatabase =
    Room.databaseBuilder<ClipperDatabase>(
        name = context.getDatabasePath(DATABASE_NAME).absolutePath,
    )
        .setDriver(BundledSQLiteDriver())
        .build()

/** 供预览使用；预览没有 Application，无法定位数据库文件。 */
private class InMemoryClipStorageDataSource : ClipStorageDataSource {
    private var items: List<ClipItem> = emptyList()
    private var settings: AppSettings = AppSettings()

    override suspend fun loadItems(): List<ClipItem> = items

    override suspend fun saveItems(items: List<ClipItem>) {
        this.items = items
    }

    override suspend fun loadSettings(): AppSettings = settings

    override suspend fun saveSettings(settings: AppSettings) {
        this.settings = settings
    }
}

actual fun createClipStorageDataSource(): ClipStorageDataSource =
    ClipperAndroid.appContext
        ?.let { RoomClipStorageDataSource(createDatabase(it)) }
        ?: InMemoryClipStorageDataSource()
