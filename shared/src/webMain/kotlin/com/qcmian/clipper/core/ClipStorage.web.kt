package com.qcmian.clipper.core

import com.qcmian.clipper.model.ClipItem
import com.qcmian.clipper.settings.AppSettings
import web.storage.localStorage

private class WebClipStorage : ClipStorage {
    override fun loadItems(): List<ClipItem> =
        decodeJsonOrNull<List<ClipItem>>(read(KEY_ITEMS)).orEmpty()

    override fun saveItems(items: List<ClipItem>) {
        write(KEY_ITEMS, encodeJson(items))
    }

    override fun loadSettings(): AppSettings =
        decodeJsonOrNull<AppSettings>(read(KEY_SETTINGS)) ?: AppSettings()

    override fun saveSettings(settings: AppSettings) {
        write(KEY_SETTINGS, encodeJson(settings))
    }

    private fun read(key: String): String? = runCatching { localStorage.getItem(key) }.getOrNull()

    private fun write(key: String, value: String) {
        runCatching { localStorage.setItem(key, value) }
    }

    private companion object {
        const val KEY_ITEMS = "clipper.history"
        const val KEY_SETTINGS = "clipper.settings"
    }
}

actual fun createClipStorage(): ClipStorage = WebClipStorage()
