package com.qcmian.clipper.data.source

import com.qcmian.clipper.domain.model.ClipboardSnapshot
import com.qcmian.clipper.util.decodeBase64
import com.qcmian.clipper.util.encodeBase64
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
 * iOS clipboard support. `UIPasteboard` has no change notification, so the pasteboard is
 * polled through its `changeCount`, the same way Maccy polls the macOS pasteboard.
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

        // `lastChangeCount` is intentionally not refreshed so the polling loop observes the
        // write and the repository moves the selected item back to the top.
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
        // iOS exposes the real pasteboard type identifiers, the same strings Maccy matches on.
        val types = pasteboard.pasteboardTypes.orEmpty().mapNotNull { it as? String }
        return ClipboardSnapshot(text = text, imageBase64 = imageBase64, files = files, types = types)
    }
}

actual fun createClipboardDataSource(): ClipboardDataSource = IosClipboardDataSource()
