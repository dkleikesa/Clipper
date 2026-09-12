package com.qcmian.clipper.core.data.source

import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.util.decodeBase64
import com.qcmian.clipper.core.util.encodeBase64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.Foundation.NSURL
import platform.UIKit.UIImage
import platform.UIKit.UIImagePNGRepresentation
import platform.UIKit.UIPasteboard

/**
 * iOS 剪贴板支持。`UIPasteboard` 没有变更通知，因此通过 `changeCount` 轮询粘贴板，
 * 两者都采用定时器轮询。
 */
private class IosClipboardDataSource : ClipboardDataSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var listener: ((ClipboardSnapshot) -> Unit)? = null
    private var pollJob: Job? = null
    private var lastChangeCount = -1L

    override var pollIntervalMillis: Long = ClipboardDataSource.DEFAULT_POLL_INTERVAL_MILLIS

    override val supportsImages: Boolean get() = true
    override val supportsFiles: Boolean get() = true

    override fun write(snapshot: ClipboardSnapshot): Boolean {
        val pasteboard = UIPasteboard.generalPasteboard
        val image = snapshot.imageBase64
            ?.let { decodeBase64(it) }
            ?.let { UIImage.imageWithData(it.toNSData()) }

        val written = when {
            image != null -> {
                pasteboard.image = image
                true
            }

            !snapshot.text.isNullOrEmpty() -> {
                pasteboard.string = snapshot.text
                true
            }

            snapshot.files.isNotEmpty() -> {
                pasteboard.URLs = snapshot.files.map { NSURL.fileURLWithPath(it) }
                true
            }

            else -> false
        }

        // 这里刻意不刷新 `lastChangeCount`，好让轮询循环观察到这次写入，
        // 从而让仓库把选中的条目重新排到最前面。
        return written
    }

    override fun clear() {
        runCatching { UIPasteboard.generalPasteboard.items = emptyList<Any>() }
        lastChangeCount = -1L
    }

    override fun start(onChange: (ClipboardSnapshot) -> Unit) {
        listener = onChange
        lastChangeCount = UIPasteboard.generalPasteboard.changeCount
        pollJob = scope.launch {
            while (isActive) {
                delay(pollIntervalMillis)
                val pasteboard = UIPasteboard.generalPasteboard
                val changeCount = pasteboard.changeCount
                if (changeCount == lastChangeCount) continue
                lastChangeCount = changeCount
                val snapshot = readSnapshot(pasteboard)
                if (!snapshot.isEmpty) listener?.invoke(snapshot)
            }
        }
    }

    override fun stop() {
        pollJob?.cancel()
        pollJob = null
        listener = null
    }

    override fun paste(): Boolean = false

    private fun readSnapshot(pasteboard: UIPasteboard): ClipboardSnapshot {
        val text = pasteboard.string
        val imageBase64 = pasteboard.image
            ?.let { UIImagePNGRepresentation(it) }
            ?.toByteArray()
            ?.let { encodeBase64(it) }
        val files = pasteboard.URLs.orEmpty().mapNotNull { (it as? NSURL)?.path }
        // iOS 会暴露真正的粘贴板类型标识，与忽略列表匹配的是同一批字符串。
        val types = pasteboard.pasteboardTypes.orEmpty().mapNotNull { it as? String }
        return ClipboardSnapshot(text = text, imageBase64 = imageBase64, files = files, types = types)
    }
}

actual fun createClipboardDataSource(): ClipboardDataSource = IosClipboardDataSource()
