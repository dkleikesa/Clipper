package com.qcmian.clipper.core

import com.qcmian.clipper.model.ClipItem
import com.qcmian.clipper.settings.AppSettings

/** Persistence for the clipboard history and the user preferences. */
interface ClipStorage {
    fun loadItems(): List<ClipItem>
    fun saveItems(items: List<ClipItem>)
    fun loadSettings(): AppSettings
    fun saveSettings(settings: AppSettings)

    /**
     * Human readable size of the persisted history, shown in the storage preferences the
     * same way Maccy shows `Storage.size`. `null` when the platform cannot tell.
     */
    fun storageSize(): String? = null
}

expect fun createClipStorage(): ClipStorage
