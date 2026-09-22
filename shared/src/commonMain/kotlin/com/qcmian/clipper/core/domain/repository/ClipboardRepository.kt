package com.qcmian.clipper.core.domain.repository

import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.model.ClipPayload
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.model.SourceApplication
import com.qcmian.clipper.core.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 剪贴板历史与用户偏好的唯一数据源。
 *
 * **内存里只有元数据，载荷按需。** 它被拆成两层暴露：
 *
 * - [pinned] / [unpinned]：全部元数据（[ClipMeta]——id、标题、类型、时间戳与计数，
 *   **不含图片字节、正文与富文本**），已按当前排序排好、一次取回。
 * - [payload]：载荷按 id 单条取，只有预览面板与「写回剪贴板」会用到。
 *
 * 这样历史的常驻内存只与「条数 × 每条几百字节」成正比（一万条约 6 MB），而真正的量级
 * ——图片与长正文——永远不进内存；同时排序下推给了 SQL（见 `ClipHistoryDao`），
 * 不再有「每次复制都全量重排十万条」。
 *
 * 宿主能力——写系统剪贴板、解析应用图标、文字识别——放在 [ClipboardPlatform] 中，
 * 这样使用方只需依赖自己实际用到的那一半。它刻意不包含任何 UI 状态，也不依赖 Compose。
 */
interface ClipboardRepository {

    /** 已加载的置顶条目。置顶是用户的显式选择、数量少，因此一次取全、不参与分页。 */
    val pinned: StateFlow<List<ClipMeta>>

    /** 已加载的未置顶条目：按当前排序，**从第一条开始的连续前缀**。 */
    val unpinned: StateFlow<List<ClipMeta>>

    /** 未置顶条目总数。界面用它判断还有没有下一批。 */
    val totalUnpinned: StateFlow<Int>

    /** 用户偏好。 */
    val settings: StateFlow<AppSettings>

    /** 持久化的偏好已加载完成；此前的 [settings] 是内存默认值。 */
    val settingsLoaded: StateFlow<Boolean>

    /** 可供 UI 显示的临时消息，例如「此平台不支持粘贴」。 */
    val statusMessage: StateFlow<String?>

    /** 平台上报的每一次新复制，供领域层解读。 */
    val snapshots: Flow<ClipboardSnapshot>

    /** 开始监听系统剪贴板。 */
    fun start()

    /** 停止监听系统剪贴板。 */
    fun stop()

    /** 把待写的偏好落盘，而不等待防抖。历史条目本身就是即时落盘的。 */
    fun flush()

    /**
     * 与 [flush] 类似，但会挂起直到写入完成。即将终止进程的宿主会用它，
     * 以免丢失最后一次变更。
     */
    suspend fun flushNow()

    /**
     * 把待写状态落盘并关闭底层存储。SQLite 会在最后一个连接关闭时把 `-wal` / `-shm`
     * 合并回主库并删除它们，下次启动不再需要恢复。进程退出路径应优先用本方法。
     */
    suspend fun close()

    /** 替换偏好设置，并把平台侧设置同步下去。 */
    fun setSettings(settings: AppSettings)

    /** 显示（或清除）临时状态消息。 */
    fun setStatusMessage(message: String?)

    // -----------------------------------------------------------------------------------
    // 载荷
    // -----------------------------------------------------------------------------------

    /** 按 id 取载荷；条目不存在或本来就没有载荷时为 `null`。 */
    suspend fun payload(id: String): ClipPayload?

    /** 按 id 取完整条目（元数据 + 载荷）；条目不存在时为 `null`。 */
    suspend fun item(id: String): ClipItem?

    // -----------------------------------------------------------------------------------
    // 按 id 的细粒度写操作
    // -----------------------------------------------------------------------------------

    /** 写入一条新历史。元数据与载荷在同一事务里落盘。 */
    suspend fun insert(meta: ClipMeta, payload: ClipPayload?)

    /** 重复复制：只更新统计列，不重写标题、更不碰载荷。 */
    suspend fun updateStats(id: String, numberOfCopies: Int, lastCopiedAt: Long)

    /** 改写标题；[fromRecognition] 表示这次改写是否来自图片文字识别。 */
    suspend fun updateTitle(id: String, title: String, fromRecognition: Boolean)

    /** 单条元数据；条目不存在时为 `null`。 */
    suspend fun meta(id: String): ClipMeta?

    /** 内容摘要相同的条目 id；把去重从「扫描整份历史」降成一次等值查询。 */
    suspend fun findByContentKey(contentKey: String): String?

    /** 历史里最大的 `lastCopiedAt`；没有任何条目时为 `0`。 */
    suspend fun latestLastCopiedAt(): Long

    /**
     * 给历史遗留的空标题补上标题。
     *
     * `title` 是**写入时**派生好落盘的，因此新增的标题来源（从 HTML 附加表示里提取文字）
     * 只对新复制生效——旧条目仍是空标题，在列表里是一行空白、搜什么都搜不到。启动时后台跑
     * 一次即可：没有空标题时，它只是一次窄列全扫。
     */
    suspend fun backfillEmptyTitles()

    suspend fun setPinned(id: String, pinned: Boolean)

    suspend fun delete(ids: List<String>)

    /** [all] 为 `true` 时连置顶项一起清空；否则只清未置顶项。 */
    suspend fun clear(all: Boolean)

    /**
     * 存储占用可能已经变化（空闲页回收完成）时自增。
     *
     * 「存储文件大小」是每次现读的，因此界面需要这样一个信号才会重新读一次——否则回收之后
     * 设置页里的数字会停在旧值上。
     */
    val storageRevision: StateFlow<Int>

    /**
     * 空闲页够多时才真正回收（`PRAGMA incremental_vacuum`）。
     *
     * 非阻塞，且内部自带阈值判断，因此可以在每次有记录被丢弃后调用：热路径上只多一次整数加法，
     * 真正的查询与搬页发生在累计删除量够大时。
     */
    fun reclaimStorageIfNeeded()
}

/**
 * 数据层中负责触达宿主的那部分：系统剪贴板、来源应用、应用图标与文字识别。
 *
 * 从 [ClipboardRepository] 中拆出来，使那些从不接触这些能力的用例——排序、清空历史、
 * 更新偏好——不必依赖一个二十个成员的接口。
 */
interface ClipboardPlatform {
    /** 已持久化历史占用的字节数（数据库文件大小）；平台无法测量时为 `null`。 */
    val storageBytes: Long?

    /** 「弹窗屏幕」偏好可以指向的屏幕数量。 */
    val screenCount: Int

    /** 宿主是否能把应用注册为开机自启项。 */
    val supportsLaunchAtLogin: Boolean

    /** 宿主是否能判断一次复制来自哪个应用。 */
    val supportsApplicationInfo: Boolean

    /** 是否能进行图片文字识别。 */
    val supportsTextRecognition: Boolean

    /** 把 [snapshot] 放入系统剪贴板。平台不支持时返回 `false`。 */
    fun writeClipboard(snapshot: ClipboardSnapshot): Boolean

    /** 尽力向此前聚焦的应用「按一次粘贴」。 */
    fun paste(): Boolean

    fun applicationIcon(bundleId: String?): String?

    /** 在图片中识别出的文字，用作图片条目的标题。 */
    suspend fun recognizeText(image: ClipImage): String?

    /** 最近一次复制来源的应用，平台无法判断时为 `null`。 */
    fun currentSourceApplication(): SourceApplication?
}
