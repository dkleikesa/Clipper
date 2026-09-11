package com.qcmian.clipper.data.source

import com.qcmian.clipper.data.model.ClipItem
import com.qcmian.clipper.settings.AppSettings
import com.qcmian.clipper.util.decodeJsonOrNull
import com.qcmian.clipper.util.encodeJson
import com.qcmian.clipper.util.formatBytes
import java.io.File

/**
 * File based storage living in `~/.clipper`, mirroring Maccy's application support
 * directory layout.
 */
private class JvmClipStorageDataSource : ClipStorageDataSource {
    private val directory = File(System.getProperty("user.home") ?: ".", ".clipper")
    private val itemsFile = File(directory, "history.json")
    private val settingsFile = File(directory, "settings.json")

    override fun loadItems(): List<ClipItem> =
        decodeJsonOrNull<List<ClipItem>>(read(itemsFile)).orEmpty()

    override fun saveItems(items: List<ClipItem>) {
        write(itemsFile, encodeJson(items))
    }

    override fun loadSettings(): AppSettings =
        decodeJsonOrNull<AppSettings>(read(settingsFile)) ?: AppSettings()

    override fun saveSettings(settings: AppSettings) {
        write(settingsFile, encodeJson(settings))
    }

    override fun storageSize(): String? {
        val bytes = runCatching { itemsFile.length() }.getOrNull() ?: return null
        // Maccy returns an empty string when the store is effectively empty.
        return if (bytes > 1) formatBytes(bytes) else ""
    }

    private fun read(file: File): String? =
        runCatching { if (file.isFile) file.readText() else null }.getOrNull()

    private fun write(file: File, content: String) {
        runCatching {
            directory.mkdirs()
            file.writeText(content)
        }
    }
}

actual fun createClipStorageDataSource(): ClipStorageDataSource = JvmClipStorageDataSource()
