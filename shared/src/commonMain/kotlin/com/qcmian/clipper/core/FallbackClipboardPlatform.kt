package com.qcmian.clipper.core

/**
 * Used when a platform clipboard is unavailable (for example when the Android context
 * was never initialised). Keeps the application functional instead of crashing.
 */
internal class FallbackClipboardPlatform : ClipboardPlatform {
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
