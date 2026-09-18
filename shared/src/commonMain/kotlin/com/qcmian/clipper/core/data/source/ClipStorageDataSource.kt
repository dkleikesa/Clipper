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
     * 已持久化历史占用的字节数（数据库文件大小）；平台无法测量时返回 `null`。
     *
     * 给的是原始字节而不是格式化后的文本：怎么显示（MB / GB、保留几位小数）由界面决定。
     */
    fun storageBytes(): Long? = null

    /**
     * 丢弃留在文件里的空闲页（SQLite `VACUUM`）：重写整库，把文件收回到实际大小。
     *
     * 成本约等于整库有效数据的一次重写，且期间独占数据库，因此只在「用户刚刚清掉一大批数据」
     * 这类明确想要释放磁盘的时机调用。默认空实现：平台不需要压紧时什么都不做。
     */
    suspend fun compact() {}

    /**
     * 空闲页达到 [minFreeBytes] 时才回收（SQLite `PRAGMA incremental_vacuum`）。
     *
     * 比 [compact] 便宜得多：只把文件尾部的页搬到空闲页位置再截断，不重建索引、不重排页内数据，
     * 代价与**要回收的页数**成正比而不是与整库大小成正比。它为「删了不少、但不到清空」的场合准备，
     * 因此带阈值：空洞太小时不值得为它搬一次页。
     *
     * 需要数据库处于 `auto_vacuum = INCREMENTAL`，实现方负责确保这一点。
     */
    suspend fun reclaimFreePages(minFreeBytes: Long) {}

    /**
     * 关闭底层存储。最后一个连接关闭时，SQLite 会把 WAL 合并回主库并删除
     * `-wal` / `-shm` 临时文件，下次启动不再需要恢复。仅进程退出前调用一次；
     * 关闭后的读写会被安全忽略，因此退出路径后续的落盘不需要感知。
     */
    fun close() {}
}

expect fun createClipStorageDataSource(): ClipStorageDataSource
