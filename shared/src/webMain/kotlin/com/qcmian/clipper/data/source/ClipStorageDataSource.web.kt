package com.qcmian.clipper.data.source

import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.settings.AppSettings
import com.qcmian.clipper.util.decodeJsonOrNull
import com.qcmian.clipper.util.encodeJson
import web.storage.localStorage

/**
 * Web 目标继续使用 `localStorage`：Room 的 Web 支持需要一个由入口点构建的 Web Worker，
 * 而 `androidx.sqlite:sqlite-web` 尚未提供该产物，
 * 因此本次迁移目前只覆盖 Android、iOS 与桌面端。
 */
private class WebClipStorageDataSource : ClipStorageDataSource {
    override suspend fun loadItems(): List<ClipItem> =
        decodeJsonOrNull<List<ClipItem>>(read(KEY_ITEMS)).orEmpty()

    override suspend fun saveItems(items: List<ClipItem>) {
        write(KEY_ITEMS, encodeJson(items))
    }

    override suspend fun loadSettings(): AppSettings =
        decodeJsonOrNull<AppSettings>(read(KEY_SETTINGS)) ?: AppSettings()

    override suspend fun saveSettings(settings: AppSettings) {
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

actual fun createClipStorageDataSource(): ClipStorageDataSource = WebClipStorageDataSource()
