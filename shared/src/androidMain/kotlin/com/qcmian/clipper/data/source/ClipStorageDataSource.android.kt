package com.qcmian.clipper.data.source

import android.content.Context
import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.domain.model.AppSettings
import com.qcmian.clipper.util.decodeJsonOrNull
import com.qcmian.clipper.util.encodeJson

private class AndroidClipStorageDataSource(context: Context) : ClipStorageDataSource {
    private val preferences =
        context.getSharedPreferences("clipper", Context.MODE_PRIVATE)

    override fun loadItems(): List<ClipItem> =
        decodeJsonOrNull<List<ClipItem>>(preferences.getString(KEY_ITEMS, null)).orEmpty()

    override fun saveItems(items: List<ClipItem>) {
        preferences.edit().putString(KEY_ITEMS, encodeJson(items)).apply()
    }

    override fun loadSettings(): AppSettings =
        decodeJsonOrNull<AppSettings>(preferences.getString(KEY_SETTINGS, null)) ?: AppSettings()

    override fun saveSettings(settings: AppSettings) {
        preferences.edit().putString(KEY_SETTINGS, encodeJson(settings)).apply()
    }

    private companion object {
        const val KEY_ITEMS = "clipper.history"
        const val KEY_SETTINGS = "clipper.settings"
    }
}

private class InMemoryClipStorageDataSource : ClipStorageDataSource {
    private var items: List<ClipItem> = emptyList()
    private var settings: AppSettings = AppSettings()

    override fun loadItems(): List<ClipItem> = items
    override fun saveItems(items: List<ClipItem>) {
        this.items = items
    }

    override fun loadSettings(): AppSettings = settings
    override fun saveSettings(settings: AppSettings) {
        this.settings = settings
    }
}

actual fun createClipStorageDataSource(): ClipStorageDataSource =
    ClipperAndroid.appContext?.let { AndroidClipStorageDataSource(it) } ?: InMemoryClipStorageDataSource()
