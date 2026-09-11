package com.qcmian.clipper.domain.model

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
    /**
     * The application the content was copied from. Maccy fills this in through
     * `NSWorkspace.frontmostApplication`; platforms without an equivalent leave it `null`
     * and the preview simply hides the "应用:" line.
     */
    val application: SourceApplication? = null,
) {
    val isPinned: Boolean get() = pin != null
    val isUnpinned: Boolean get() = pin == null

    /**
     * Text used for previews and for search. Port of `HistoryItem.previewableText`:
     * images carry no text representation, so their title stays empty until text
     * recognition fills it in.
     */
    val previewableText: String
        get() = when {
            files.isNotEmpty() -> files.joinToString("\n")
            !text.isNullOrBlank() -> text
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
     * Builds the one-line title displayed in the list. Port of `HistoryItem.generateTitle()`,
     * including the `showSpecialSymbols` preference: when enabled, leading/trailing spaces
     * become `·` and newlines/tabs become `⏎`/`⇥`.
     */
    fun generateTitle(showSpecialSymbols: Boolean = true): String {
        val raw = previewableText.take(MAX_TITLE_LENGTH).removingUnsafeTitleScalars()
        if (!showSpecialSymbols) return raw.trim()

        return raw
            .replace(Regex("^ +")) { "·".repeat(it.value.length) }
            .replace(Regex(" +$")) { "·".repeat(it.value.length) }
            .replace("\n", "\u23ce")
            .replace("\t", "\u21e5")
    }

    companion object {
        const val MAX_TITLE_LENGTH = 1_000
    }
}

enum class ClipKind { TEXT, LINK, COLOR, IMAGE, FILE }

/**
 * Unicode scalars that hang CoreText's line truncation on macOS 26, see Maccy #1520.
 *
 * U+FFFC OBJECT REPLACEMENT CHARACTER is the placeholder for an inline attachment, so rich
 * text with embedded images carries one per attachment in its plain text flavour. Two or
 * more of them next to non-Latin text send the typesetter into an infinite loop when the
 * title is laid out with a single line and middle truncation.
 */
private val UNSAFE_TITLE_SCALARS = setOf('\uFFFC')

/** Port of `String.removingScalarsUnsafeForTitleLayout()`, filtered per scalar. */
fun String.removingUnsafeTitleScalars(): String =
    if (none { it in UNSAFE_TITLE_SCALARS }) this else filterNot { it in UNSAFE_TITLE_SCALARS }

private val HEX_COLOR_PATTERN = Regex("^#([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")
private val LINK_PATTERN = Regex(
    "^(https?://|ftp://|mailto:|file://|www\\.)\\S+$",
    RegexOption.IGNORE_CASE,
)

fun isHexColor(value: String): Boolean = HEX_COLOR_PATTERN.matches(value.trim())

fun isLink(value: String): Boolean = LINK_PATTERN.matches(value.trim())
