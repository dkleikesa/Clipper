package com.qcmian.clipper.core

import android.content.Context
import com.qcmian.clipper.model.ClipItem
import com.qcmian.clipper.settings.AppSettings

private class AndroidClipStorage(context: Context) : ClipStorage {
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

private class InMemoryClipStorage : ClipStorage {
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

actual fun createClipStorage(): ClipStorage =
    ClipperAndroid.appContext?.let { AndroidClipStorage(it) } ?: InMemoryClipStorage()
