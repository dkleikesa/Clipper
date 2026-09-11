package com.qcmian.clipper.data.source

import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.domain.model.AppSettings
import com.qcmian.clipper.util.decodeJsonOrNull
import com.qcmian.clipper.util.encodeJson
import platform.Foundation.NSUserDefaults

private class IosClipStorageDataSource : ClipStorageDataSource {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun loadItems(): List<ClipItem> =
        decodeJsonOrNull<List<ClipItem>>(defaults.stringForKey(KEY_ITEMS)).orEmpty()

    override fun saveItems(items: List<ClipItem>) {
        defaults.setObject(encodeJson(items), forKey = KEY_ITEMS)
    }

    override fun loadSettings(): AppSettings =
        decodeJsonOrNull<AppSettings>(defaults.stringForKey(KEY_SETTINGS)) ?: AppSettings()

    override fun saveSettings(settings: AppSettings) {
        defaults.setObject(encodeJson(settings), forKey = KEY_SETTINGS)
    }

    private companion object {
        const val KEY_ITEMS = "clipper.history"
        const val KEY_SETTINGS = "clipper.settings"
    }
}

actual fun createClipStorageDataSource(): ClipStorageDataSource = IosClipStorageDataSource()
