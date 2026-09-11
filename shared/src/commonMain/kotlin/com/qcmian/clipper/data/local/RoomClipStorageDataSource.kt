package com.qcmian.clipper.data.local

import com.qcmian.clipper.data.source.ClipStorageDataSource
import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.settings.AppSettings
import com.qcmian.clipper.util.decodeJsonOrNull
import com.qcmian.clipper.util.encodeJson

/**
 * 基于 [ClipperDatabase] 的 [ClipStorageDataSource]，由 Android、iOS 与桌面端共用。
 *
 * @param sizeOfDatabase 宿主如何测量数据库文件大小；无法测量的平台保持 `null`，
 *   此时偏好设置界面会隐藏占用大小。
 */
internal class RoomClipStorageDataSource(
    private val database: ClipperDatabase,
    private val sizeOfDatabase: () -> String? = { null },
) : ClipStorageDataSource {
    private val history = database.clipHistoryDao()
    private val preferences = database.appSettingsDao()

    override suspend fun loadItems(): List<ClipItem> =
        history.load().map { it.toModel() }

    override suspend fun saveItems(items: List<ClipItem>) {
        history.replaceAll(items.map { it.toEntity() })
    }

    override suspend fun loadSettings(): AppSettings =
        decodeJsonOrNull<AppSettings>(preferences.load()) ?: AppSettings()

    override suspend fun saveSettings(settings: AppSettings) {
        preferences.save(
            AppSettingsEntity(AppSettingsEntity.SINGLE_ROW_ID, encodeJson(settings)),
        )
    }

    // 注意：参数不能与该方法同名，否则这里的调用会解析成方法自身。
    override fun storageSize(): String? = sizeOfDatabase()
}
