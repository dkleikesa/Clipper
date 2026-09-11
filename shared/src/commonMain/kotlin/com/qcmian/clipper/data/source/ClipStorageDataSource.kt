package com.qcmian.clipper.data.source

import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.domain.model.AppSettings

/** Persistence for the clipboard history and the user preferences. */
interface ClipStorageDataSource {
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

expect fun createClipStorageDataSource(): ClipStorageDataSource
