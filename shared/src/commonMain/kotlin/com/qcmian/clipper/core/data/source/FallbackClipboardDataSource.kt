package com.qcmian.clipper.core.data.source

import com.qcmian.clipper.core.domain.model.ClipboardSnapshot

/**
 * 平台剪贴板不可用时使用（例如 Android 的 context 从未初始化）。
 * 让应用保持可用，而不是直接崩溃。
 */
internal class FallbackClipboardDataSource : ClipboardDataSource {
    private var current: ClipboardSnapshot? = null
    private var listener: ((ClipboardSnapshot) -> Unit)? = null

    override val supportsImages: Boolean get() = false

    override fun write(snapshot: ClipboardSnapshot): Boolean {
        current = snapshot
        return true
    }

    override fun clear() {
        current = null
    }

    override fun start(onChange: (ClipboardSnapshot) -> Unit) {
        listener = onChange
    }

    override fun stop() {
        listener = null
    }

    override fun paste(): Boolean = false
}
