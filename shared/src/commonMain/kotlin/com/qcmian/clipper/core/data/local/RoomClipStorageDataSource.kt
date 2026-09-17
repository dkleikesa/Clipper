package com.qcmian.clipper.core.data.local

import com.qcmian.clipper.core.data.source.ClipStorageDataSource
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.util.decodeJsonOrNull
import com.qcmian.clipper.core.util.encodeJson

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

    /** 关闭后再有读写一律忽略：进程退出路径后续的 `flush()` 不需要感知关闭这件事。 */
    @Volatile
    private var closed = false

    override suspend fun loadItems(): List<ClipItem> {
        if (closed) return emptyList()
        return history.load().map { it.toModel() }
    }

    override suspend fun saveItems(items: List<ClipItem>) {
        if (closed) return
        // 与磁盘上的已有行做 diff，只提交变化的行。对照用轻量投影（不含 image BLOB），
        // 因此无论历史多大，diff 本身都只读几 KB 的元数据；单次复制通常只产生一两个
        // 变更行，落盘成本从 O(整份历史) 降到 O(变化行数)。
        val existing = history.loadLite().associateBy { it.id }
        val seen = HashSet<String>(existing.size * 2)
        val inserts = ArrayList<ClipItemEntity>()
        val updates = ArrayList<ClipItemEntity>()
        for (item in items) {
            seen += item.id
            val row = item.toRowLite()
            when (existing[item.id]) {
                null -> inserts += item.toEntity()
                row -> Unit
                else -> updates += item.toEntity()
            }
        }
        val deletes = existing.keys.filterNot { it in seen }
        history.applyDiff(inserts, updates, deletes)
    }

    override suspend fun loadSettings(): AppSettings {
        if (closed) return AppSettings()
        return decodeJsonOrNull<AppSettings>(preferences.load()) ?: AppSettings()
    }

    override suspend fun saveSettings(settings: AppSettings) {
        if (closed) return
        preferences.save(
            AppSettingsEntity(AppSettingsEntity.SINGLE_ROW_ID, encodeJson(settings)),
        )
    }

    // 注意：参数不能与该方法同名，否则这里的调用会解析成方法自身。
    override fun storageSize(): String? = sizeOfDatabase()

    override fun close() {
        if (closed) return
        closed = true
        database.close()
    }
}
