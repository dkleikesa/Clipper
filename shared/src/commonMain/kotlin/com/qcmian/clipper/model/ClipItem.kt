package com.qcmian.clipper.model

import kotlinx.serialization.Serializable

/**
 * A single entry of the clipboard history.
 *
 * Mirrors Maccy's `HistoryItem`: it keeps every representation that was found on the
 * system clipboard (plain text, an encoded image and/or a list of files) together with
 * the bookkeeping fields used for de-duplication and sorting.
 */
@Serializable
data class ClipItem(
    val id: String,
    val text: String? = null,
    /** Raw (PNG/JPEG) image bytes, base64 encoded so the item stays serializable. */
    val imageBase64: String? = null,
    val files: List<String> = emptyList(),
    val firstCopiedAt: Long = 0L,
    val lastCopiedAt: Long = 0L,
    val numberOfCopies: Int = 1,
    val pin: String? = null,
    val title: String = "",
) {
    val isPinned: Boolean get() = pin != null
    val isUnpinned: Boolean get() = pin == null

    /** Text used for previews and for search. */
    val previewableText: String
        get() = when {
            files.isNotEmpty() -> files.joinToString("\n")
            !text.isNullOrBlank() -> text
            imageBase64 != null -> title.ifBlank { "Image" }
            else -> title
        }

    val kind: ClipKind
        get() = when {
            imageBase64 != null && text.isNullOrBlank() && files.isEmpty() -> ClipKind.IMAGE
            files.isNotEmpty() -> ClipKind.FILE
            text != null && isHexColor(text) -> ClipKind.COLOR
            text != null && isLink(text) -> ClipKind.LINK
            else -> ClipKind.TEXT
        }

    /** `true` when this item already contains everything the [other] item provides. */
    fun supersedes(other: ClipItem): Boolean {
        val hasContent = other.text != null || other.imageBase64 != null || other.files.isNotEmpty()
        if (!hasContent) return false
        return (other.text == null || text == other.text) &&
            (other.imageBase64 == null || imageBase64 == other.imageBase64) &&
            (other.files.isEmpty() || files == other.files)
    }

    /**
     * Builds the one-line title that is displayed in the list, replacing newlines and
     * tabs with visible symbols the same way Maccy does.
     */
    fun generateTitle(): String = previewableText
        .take(MAX_TITLE_LENGTH)
        .replace("\n", "\u23ce")
        .replace("\t", "\u21e5")
        .replace(Regex(" +"), " ")
        .trim()

    companion object {
        const val MAX_TITLE_LENGTH = 1_000
    }
}

enum class ClipKind { TEXT, LINK, COLOR, IMAGE, FILE }

private val HEX_COLOR_PATTERN = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
private val LINK_PATTERN = Regex(
    "^(https?://|ftp://|mailto:|file://|www\\.)\\S+$",
    RegexOption.IGNORE_CASE,
)

fun isHexColor(value: String): Boolean = HEX_COLOR_PATTERN.matches(value.trim())

fun isLink(value: String): Boolean = LINK_PATTERN.matches(value.trim())
