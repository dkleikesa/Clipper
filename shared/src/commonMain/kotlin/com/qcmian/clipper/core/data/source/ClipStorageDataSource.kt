package com.qcmian.clipper.core.data.source

import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.settings.AppSettings

/**
 * 剪贴板历史与用户偏好的持久化。
 *
 * 这些成员都是挂起函数，以便实现在各平台都能使用 Room：在非 Android 平台上，Room 的 DAO
 * 函数必须是挂起的，而调用方（仓库）本就运行在 IO 作用域里。
 */
interface ClipStorageDataSource {
    suspend fun loadItems(): List<ClipItem>

    suspend fun saveItems(items: List<ClipItem>)

    suspend fun loadSettings(): AppSettings

    suspend fun saveSettings(settings: AppSettings)

    /**
 * 已持久化历史的可读大小，按 方式显示在存储偏好中。
     * 平台无法给出时返回 `null`。
     */
    fun storageSize(): String? = null
}

expect fun createClipStorageDataSource(): ClipStorageDataSource
