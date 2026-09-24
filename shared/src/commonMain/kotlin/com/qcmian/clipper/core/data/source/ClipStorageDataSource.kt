package com.qcmian.clipper.core.data.source

import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.model.ClipPayload
import com.qcmian.clipper.core.domain.model.ClipText
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.settings.SortBy
import com.qcmian.clipper.core.settings.SortOrder

/**
 * 剪贴板历史与用户偏好的持久化。
 *
 * 与拆分后的数据模型对齐：
 *
 * - **读**分成「元数据」与「载荷」两条路径。列表、搜索、排序只碰元数据；只有预览与
 *   「写回剪贴板」才按 id 取一份载荷。因此历史里有多少张图片都不影响列表的内存占用。
 * - **写**按 id 精确到列。上层不再需要「先把整份历史读进内存，改一条，再全量写回」。
 *
 * 这些成员都是挂起函数，以便实现在各平台都能使用 Room：在非 Android 平台上，Room 的 DAO
 * 函数必须是挂起的，而调用方（仓库）本就运行在 IO 作用域里。
 */
interface ClipStorageDataSource {

    /**
     * 打开后的基础配置：应用 PRAGMA（`page_size` / `mmap_size` / `cache_size` /
     * `auto_vacuum`），并清理一次孤儿载荷。加载历史之前调用一次即可。
     */
    suspend fun initialise()

    // -----------------------------------------------------------------------------------
    // 元数据：读
    // -----------------------------------------------------------------------------------

    /** 未置顶条目数。列表用它判断还有没有下一批，设置页用它显示「当前条数」。 */
    suspend fun countUnpinned(): Int

    /** 置顶区块。置顶数量少，一次取全，不分页。 */
    suspend fun loadPinned(): List<ClipMeta>

    /** 未置顶条目的分页。排序在 SQL 里完成，因此只读回这一页的元数据。 */
    suspend fun loadUnpinned(by: SortBy, order: SortOrder, limit: Int, offset: Int): List<ClipMeta>

    /** 单条元数据；条目不存在时为 `null`。 */
    suspend fun loadMeta(id: String): ClipMeta?

    /** 内容摘要相同的条目 id；把去重从「扫描整份历史」降成一次等值查询。 */
    suspend fun findIdByContentKey(contentKey: String): String?

    /** 历史里最大的 `lastCopiedAt`；没有任何条目时为 `0`。 */
    suspend fun maxLastCopiedAt(): Long

    /** 现有置顶里最大的 `pinnedAt`；没有任何置顶时为 `0`。批量置顶据此发出严格递增的时间戳。 */
    suspend fun maxPinnedAt(): Long

    /**
     * 标题为空的条目 id；启动时用它回填历史遗留的空标题（见
     * `DefaultClipboardRepository.backfillEmptyTitles`）。
     */
    suspend fun emptyTitleIds(): List<String>

    /** 超出上限的未置顶 id，按「最后一次复制」从新到旧数。 */
    suspend fun overflowIds(maxCount: Int): List<String>

    // -----------------------------------------------------------------------------------
    // 载荷
    // -----------------------------------------------------------------------------------

    /** 单条载荷；条目不存在或本来就没有载荷时为 `null`。 */
    suspend fun loadPayload(id: String): ClipPayload?

    /**
     * 一批条目的正文，供**全文搜索**使用。
     *
     * 与 [loadPayload] 有三点不同，每一点都是为「扫过整份历史」这个场景定的：
     *
     * - **按批取**。正文可能几十 KB，一次装进来会把常驻内存推到几百 MB；调用方读一批、
     *   匹配一批、丢掉一批。
     * - **不投影图片与附加表示**。那两样是全文搜索完全用不到的 BLOB，多读一次就是白花
     *   几 MB 的 IO。
     * - **同一个 `id` 可能出现两次**。用户复制的正文与图片识别出的原文是两段独立的文本，
     *   各有各的匹配分数与高亮区间；合并成一个串会同时污染位置分和高亮。由调用方按 id
     *   取更好的那一条。
     */
    suspend fun loadTexts(ids: List<String>): List<ClipText>

    // -----------------------------------------------------------------------------------
    // 写
    // -----------------------------------------------------------------------------------

    /** 元数据与载荷在同一事务里落盘，因此不会出现「列表里有、点开是空的」半条记录。 */
    suspend fun insert(meta: ClipMeta, payload: ClipPayload?)

    /** 重复复制：只更新统计列。 */
    suspend fun updateStats(id: String, numberOfCopies: Int, lastCopiedAt: Long)

    /** 改写标题；[fromRecognition] 表示这次改写是否来自图片文字识别。 */
    suspend fun updateTitle(id: String, title: String, fromRecognition: Boolean)

    /**
     * 图片文字识别完成：**完整原文**写进载荷，标题更新为它的前一段。
     *
     * 两件事一起做：标题供列表渲染与搜索（按行宽截断），完整原文供「复制图片文字」。
     * 它和 [updateTitle] 的区别是前者会连带把「标题来自识别」这一标记置位。
     */
    suspend fun updateRecognizedText(id: String, fullText: String, title: String)

    /**
     * 切换一条的置顶状态；[pinnedAt] 是**置顶那一刻**的时间戳，决定它在置顶区的先后
     * （取消置顶时无意义，存储层会清零）。
     *
     * 时间戳由调用方给而不是这里现取：一次批量置顶的几条必须拿到彼此不同的值，才分得出先后。
     */
    suspend fun updatePinned(id: String, pinned: Boolean, pinnedAt: Long)

    suspend fun delete(ids: List<String>)

    /** 「清除」：只删未置顶项，保留置顶。 */
    suspend fun deleteAllUnpinned()

    suspend fun deleteAll()

    /** 清理元数据里已经不存在的载荷行，返回删除条数。 */
    suspend fun deleteOrphanPayloads(): Int

    // -----------------------------------------------------------------------------------
    // 偏好
    // -----------------------------------------------------------------------------------

    suspend fun loadSettings(): AppSettings

    suspend fun saveSettings(settings: AppSettings)

    // -----------------------------------------------------------------------------------
    // 存储维护
    // -----------------------------------------------------------------------------------

    /**
     * 已持久化历史占用的字节数（数据库文件大小）；平台无法测量时返回 `null`。
     *
     * 给的是原始字节而不是格式化后的文本：怎么显示（MB / GB、保留几位小数）由界面决定。
     */
    fun storageBytes(): Long? = null

    /**
     * 空闲页达到 [minFreeBytes] 时才回收（SQLite `PRAGMA incremental_vacuum`）。
     *
     * 只把文件尾部的页搬到空闲页位置再截断，不重建索引、不重排页内数据，代价与**要回收的页数**
     * 成正比而不是与整库大小成正比。它为「删了不少、但不到清空」的场合准备，因此带阈值。
     *
     * 需要数据库处于 `auto_vacuum = INCREMENTAL`，由 [initialise] 负责确保。
     */
    suspend fun reclaimFreePages(minFreeBytes: Long) {}

    /**
     * 关闭底层存储。最后一个连接关闭时，SQLite 会把 `-wal` / `-shm` 合并回主库并删除它们，
     * 下次启动不再需要恢复。仅进程退出前调用一次；关闭后的读写会被安全忽略，因此退出路径
     * 后续的落盘不需要感知。
     */
    fun close() {}
}

expect fun createClipStorageDataSource(): ClipStorageDataSource
