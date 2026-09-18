package com.qcmian.clipper.core.domain.repository

import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.model.SourceApplication
import com.qcmian.clipper.core.settings.AppSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 剪贴板历史与用户偏好的唯一数据源。
 *
 * 这是数据层中用于*读写历史*的入口：它负责持久化，并以 [StateFlow] 暴露内存中的状态。
 * 宿主能力——写系统剪贴板、解析应用图标、文字识别——放在 [ClipboardPlatform] 中，
 * 这样使用方只需依赖自己实际用到的那一半。它刻意不包含任何 UI 状态，也不依赖 Compose。
 */
interface ClipboardRepository {
    /** 历史记录，已按当前的排序 / 置顶偏好排好序。 */
    val items: StateFlow<List<ClipItem>>

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

    /** 立即把待写状态写入存储，而不等待防抖。 */
    fun flush()

    /**
     * 与 [flush] 类似，但会挂起直到写入完成。即将终止进程的宿主会用它，
     * 以免丢失最后一次变更。
     */
    suspend fun flushNow()

    /**
     * 落盘当前状态并关闭底层存储。SQLite 会在最后一个连接关闭时把 WAL 合并回主库，
     * 并删除 `-wal` / `-shm` 临时文件。进程退出路径应优先用本方法而非 [flushNow]；
     * 关闭后的读写会被安全忽略，宿主后续的 [flush] 不需要感知。
     */
    suspend fun close()

    /** 替换整份历史；列表在持久化前会重新排序并按上限裁剪。 */
    fun setItems(items: List<ClipItem>)

    /** 替换偏好设置，并把平台侧设置同步下去。 */
    fun setSettings(settings: AppSettings)

    /** 显示（或清除）临时状态消息。 */
    fun setStatusMessage(message: String?)

    /**
     * 存储占用可能已经变化（回收 / 压紧完成）时自增。
     *
     * 「存储文件大小」是每次现读的，因此界面需要这样一个信号才会重新读一次——否则 `VACUUM`
     * 之后设置页里的数字会停在旧值上。
     */
    val storageRevision: StateFlow<Int>

    /**
     * 彻底压紧数据库文件（SQLite `VACUUM`）：空闲页与页内碎片一起回收。
     *
     * 非阻塞：真正的工作在 IO 上跑，同一时刻只会有一个，重复调用会被合并。用在「用户刚清掉
     * 一批数据」与「进程即将退出」这两个时机。
     */
    fun compactStorage()

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
