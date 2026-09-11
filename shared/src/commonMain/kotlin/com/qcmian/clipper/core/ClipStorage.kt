package com.qcmian.clipper.core

import com.qcmian.clipper.model.ClipItem
import com.qcmian.clipper.settings.AppSettings

/** Persistence for the clipboard history and the user preferences. */
interface ClipStorage {
    fun loadItems(): List<ClipItem>
    fun saveItems(items: List<ClipItem>)
    fun loadSettings(): AppSettings
    fun saveSettings(settings: AppSettings)
}

expect fun createClipStorage(): ClipStorage
