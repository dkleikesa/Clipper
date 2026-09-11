package com.qcmian.clipper.core

/**
 * A platform independent view of the system clipboard.
 *
 * `imageBase64` holds the raw encoded image bytes (PNG or JPEG) so that the value can be
 * persisted without depending on a platform image type.
 */
data class ClipboardSnapshot(
    val text: String? = null,
    val imageBase64: String? = null,
    val files: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isNullOrEmpty() && imageBase64 == null && files.isEmpty()
}

/**
 * Bridge to the operating system clipboard.
 *
 * Implementations must invoke [onChange] whenever new content is placed on the clipboard
 * from the outside. Writes performed through [write] are allowed to trigger the listener
 * as well, the repository de-duplicates such round trips.
 */
interface ClipboardPlatform {
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
}

expect fun createClipboardPlatform(): ClipboardPlatform
