package com.qcmian.clipper.domain.repository

import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.domain.model.ClipboardSnapshot
import com.qcmian.clipper.domain.model.SourceApplication
import com.qcmian.clipper.settings.AppSettings
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

    /** 替换整份历史；列表在持久化前会重新排序并按上限裁剪。 */
    fun setItems(items: List<ClipItem>)

    /** 替换偏好设置，并把平台侧设置同步下去。 */
    fun setSettings(settings: AppSettings)

    /** 显示（或清除）临时状态消息。 */
    fun setStatusMessage(message: String?)
}

/**
 * 数据层中负责触达宿主的那部分：系统剪贴板、来源应用、应用图标与文字识别。
 *
 * 从 [ClipboardRepository] 中拆出来，使那些从不接触这些能力的用例——排序、清空历史、
 * 更新偏好——不必依赖一个二十个成员的接口。
 */
interface ClipboardPlatform {
    /** 已持久化历史的近似大小，平台无法给出时为 `null`。 */
    val storageSize: String?

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

    /** 清空系统剪贴板。 */
    fun clearSystemClipboard()

    /** 尽力向此前聚焦的应用「按一次粘贴」。 */
    fun paste(): Boolean

    fun applicationIcon(bundleId: String?): String?

    fun applicationName(bundleId: String): String?

    fun pickApplication(): SourceApplication?

    fun openUrl(url: String): Boolean

    /** 在编码后的图片中识别出的文字，用作图片条目的标题。 */
    suspend fun recognizeText(imageBase64: String): String?

    /** 最近一次复制来源的应用，平台无法判断时为 `null`。 */
    fun currentSourceApplication(): SourceApplication?
}
