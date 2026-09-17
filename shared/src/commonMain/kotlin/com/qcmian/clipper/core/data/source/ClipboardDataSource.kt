package com.qcmian.clipper.core.data.source

import com.qcmian.clipper.core.domain.model.ClipboardSnapshot

/**
 * 通往操作系统剪贴板的桥接。
 *
 * 当外部把新内容放入剪贴板时，实现必须调用 [onChange]。通过 [write] 执行的写入也允许触发
 * 监听器，仓库会对这类往返去重。
 */
interface ClipboardDataSource {
    /**
     * 把 [snapshot] 放入系统剪贴板。
     * 平台无法表示该内容时返回 `false`（例如 Android 上只有图片的条目，
     * 写图片需要 content provider）。
     */
    fun write(snapshot: ClipboardSnapshot): Boolean

    /** 开始监听剪贴板，对每一次外部复制调用 [onChange]。 */
    fun start(onChange: (ClipboardSnapshot) -> Unit)

    /** 停止监听剪贴板。 */
    fun stop()

    /**
     * 尽力向此前聚焦的应用「按一次粘贴」。
     * 按键事件已送达时返回 `true`。
     */
    fun paste(): Boolean = false

    /**
 * 检查剪贴板的频率，单位毫秒。。
     * 依赖变更通知的平台会忽略它。
     */
    var pollIntervalMillis: Long
        get() = DEFAULT_POLL_INTERVAL_MILLIS
        set(@Suppress("UNUSED_PARAMETER") value: Long) {}

    companion object {
 /** 默认值，500 毫秒。 */
        const val DEFAULT_POLL_INTERVAL_MILLIS = 500L
    }
}

expect fun createClipboardDataSource(): ClipboardDataSource
