package com.qcmian.clipper.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import web.clipboard.readText
import web.clipboard.writeText
import web.navigator.navigator

/**
 * Browser clipboard support.
 *
 * The Clipboard API is asynchronous and only available in a secure context, so the
 * pasteboard is polled while the page is open. Browsers also require the document to be
 * focused for `readText` to resolve; failures are silently ignored.
 */
private class WebClipboardPlatform : ClipboardPlatform {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var listener: ((ClipboardSnapshot) -> Unit)? = null
    private var pollJob: Job? = null
    private var lastText: String? = null

    override val supportsImages: Boolean get() = false
    override val supportsFiles: Boolean get() = false

    override fun write(snapshot: ClipboardSnapshot): Boolean {
        val text = snapshot.text ?: return false
        scope.launch { runCatching { navigator.clipboard.writeText(text) } }
        return true
    }

    override fun clear() {
        lastText = ""
        scope.launch { runCatching { navigator.clipboard.writeText("") } }
    }

    override fun start(onChange: (ClipboardSnapshot) -> Unit) {
        listener = onChange
        pollJob = scope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MILLIS)
                poll()
            }
        }
    }

    override fun stop() {
        pollJob?.cancel()
        pollJob = null
        listener = null
    }

    override fun paste(): Boolean = false

    private suspend fun poll() {
        val text = runCatching { navigator.clipboard.readText() }.getOrNull() ?: return
        if (text.isEmpty() || text == lastText) return
        lastText = text
        listener?.invoke(ClipboardSnapshot(text = text))
    }

    private companion object {
        const val POLL_INTERVAL_MILLIS = 800L
    }
}

actual fun createClipboardPlatform(): ClipboardPlatform = WebClipboardPlatform()
