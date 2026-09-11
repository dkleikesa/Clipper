package com.qcmian.clipper.data.source

import com.qcmian.clipper.domain.model.ClipboardSnapshot
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
 * 浏览器剪贴板支持。
 *
 * Clipboard API 是异步的，且只在安全上下文中可用，因此在页面打开期间轮询粘贴板。
 * 浏览器还要求文档处于聚焦状态 `readText` 才会 resolve；失败会被静默忽略。
 */
private class WebClipboardDataSource : ClipboardDataSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var listener: ((ClipboardSnapshot) -> Unit)? = null
    private var pollJob: Job? = null
    private var lastText: String? = null

    override var pollIntervalMillis: Long = 800L

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
                delay(pollIntervalMillis)
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
        listener?.invoke(ClipboardSnapshot(text = text, types = listOf("text/plain")))
    }
}

actual fun createClipboardDataSource(): ClipboardDataSource = WebClipboardDataSource()
