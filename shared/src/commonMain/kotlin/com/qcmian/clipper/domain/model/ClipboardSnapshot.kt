package com.qcmian.clipper.domain.model

/**
 * A platform independent view of the system clipboard.
 *
 * `imageBase64` holds the raw encoded image bytes (PNG or JPEG) so that the value can be
 * persisted without depending on a platform image type.
 *
 * [types] carries the pasteboard type identifiers the clipboard advertised, so the
 * "ignored pasteboard types" preference can be honoured the way Maccy does it.
 */
data class ClipboardSnapshot(
    val text: String? = null,
    val imageBase64: String? = null,
    val files: List<String> = emptyList(),
    val types: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isNullOrEmpty() && imageBase64 == null && files.isEmpty()
}
