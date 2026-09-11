package com.qcmian.clipper.data.source

import com.qcmian.clipper.data.model.ClipboardSnapshot

/**
 * Bridge to the operating system clipboard.
 *
 * Implementations must invoke [onChange] whenever new content is placed on the clipboard
 * from the outside. Writes performed through [write] are allowed to trigger the listener
 * as well, the repository de-duplicates such round trips.
 */
interface ClipboardDataSource {
    /**
     * Places [snapshot] on the system clipboard.
     * Returns `false` when the platform cannot represent the content (for example an
     * image only item on Android, where writing images requires a content provider).
     */
    fun write(snapshot: ClipboardSnapshot): Boolean

    /** Clears the system clipboard. */
    fun clear()

    /** Starts observing the clipboard, calling [onChange] for every external copy. */
    fun start(onChange: (ClipboardSnapshot) -> Unit)

    /** Stops observing the clipboard. */
    fun stop()

    /**
     * Best effort "press paste in the previously focused application" action.
     * Returns `true` when the key event was delivered.
     */
    fun paste(): Boolean = false

    /** Whether this platform is able to read images. */
    val supportsImages: Boolean get() = true

    /** Whether this platform is able to read file references. */
    val supportsFiles: Boolean get() = false

    /**
     * How often the clipboard is inspected, in milliseconds. Port of Maccy's
     * `clipboardCheckInterval`. Platforms that rely on change notifications ignore it.
     */
    var pollIntervalMillis: Long
        get() = DEFAULT_POLL_INTERVAL_MILLIS
        set(@Suppress("UNUSED_PARAMETER") value: Long) {}

    companion object {
        /** Maccy's `clipboardCheckInterval` default, 500 ms. */
        const val DEFAULT_POLL_INTERVAL_MILLIS = 500L
    }
}

expect fun createClipboardDataSource(): ClipboardDataSource
