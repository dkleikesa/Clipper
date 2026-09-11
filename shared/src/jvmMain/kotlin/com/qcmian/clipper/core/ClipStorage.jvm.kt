package com.qcmian.clipper.core

import com.qcmian.clipper.model.ClipItem
import com.qcmian.clipper.settings.AppSettings
import java.io.File

/**
 * File based storage living in `~/.clipper`, mirroring Maccy's application support
 * directory layout.
 */
private class JvmClipStorage : ClipStorage {
    private val directory = File(System.getProperty("user.home") ?: ".", ".clipper")
    private val itemsFile = File(directory, "history.json")
    private val settingsFile = File(directory, "settings.json")

    override fun loadItems(): List<ClipItem> = decodeJsonOrNull<List<ClipItem>>(read(itemsFile)).orEmpty()

    override fun saveItems(items: List<ClipItem>) {
        write(itemsFile, encodeJson(items))
    }

    override fun loadSettings(): AppSettings =
        decodeJsonOrNull<AppSettings>(read(settingsFile)) ?: AppSettings()

    override fun saveSettings(settings: AppSettings) {
        write(settingsFile, encodeJson(settings))
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

actual fun createClipStorage(): ClipStorage = JvmClipStorage()
