package com.qcmian.clipper.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.data.model.ClipItem
import com.qcmian.clipper.data.model.isHexColor
import com.qcmian.clipper.settings.HighlightMatch
import com.qcmian.clipper.ui.KeyShortcut
import com.qcmian.clipper.ui.hexToColor

/**
 * Port of Maccy's `HistoryItemView`. A row is either a colour swatch followed by the title,
 * or the image thumbnail on its own — never both a title and a thumbnail.
 */
@Composable
fun HistoryRow(
    item: ClipItem,
    ranges: List<IntRange>,
    shortcut: KeyShortcut?,
    isSelected: Boolean,
    highlight: HighlightMatch,
    showColorSwatch: Boolean,
    /** Maccy's `imageMaxHeight` preference. */
    maxImageHeight: Dp,
    /** Base64 PNG of the source application icon, `null` when icons are off or unknown. */
    appIconBase64: String?,
    onClick: () -> Unit,
    onHover: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val appIcon = rememberImageBitmap(appIconBase64)
    // Maccy runs `ColorImage.from(item.title)` on every item; requiring the `#` prefix keeps
    // plain three-letter words from being mistaken for a hex colour.
    val swatch = if (showColorSwatch && isHexColor(item.title)) hexToColor(item.title) else null
    val thumbnail = rememberImageBitmap(item.imageBase64)

    ListItemRow(
        isSelected = isSelected,
        shortcut = shortcut,
        onClick = onClick,
        onHover = onHover,
        appIcon = appIcon?.let {
            {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
            }
        },
        accessory = if (swatch != null) ({ ColorSwatch(swatch) }) else null,
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .padding(vertical = 5.dp)
                    .heightIn(max = maxImageHeight)
                    .clip(RoundedCornerShape(2.dp)),
            )
        } else {
            RowTitle(highlightedTitle(item.title, ranges, highlight, isSelected, colors))
        }
    }
}

@Composable
private fun ColorSwatch(color: Color) {
    Box(
        Modifier
            .size(12.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

/**
 * Decodes [encoded] once per payload and keeps the bitmap in [ImageCache], so scrolling a row
 * back into view does not decode the same image again.
 */
@Composable
internal fun rememberImageBitmap(encoded: String?): ImageBitmap? =
    remember(encoded) { encoded?.let(ImageCache::decode) }

/** Port of `HistoryItemDecorator.highlight(_:_:)`. */
private fun highlightedTitle(
    title: String,
    ranges: List<IntRange>,
    highlight: HighlightMatch,
    isSelected: Boolean,
    colors: ColorScheme,
): AnnotatedString {
    if (ranges.isEmpty()) return AnnotatedString(title)

    val style = when (highlight) {
        HighlightMatch.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        HighlightMatch.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        HighlightMatch.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
        HighlightMatch.BACKGROUND -> SpanStyle(
            background = if (isSelected) colors.onPrimary.copy(alpha = 0.30f) else colors.primary.copy(alpha = 0.30f),
            color = if (isSelected) colors.onPrimary else colors.onSurface,
        )
    }

    // Port of `HistoryItemDecorator.highlight`: the attributed title is capped at 500
    // characters, so offsets beyond that are dropped.
    val visible = title.take(HIGHLIGHT_LENGTH)
    return buildAnnotatedString {
        append(visible)
        ranges.forEach { range ->
            val start = range.first.coerceIn(0, visible.length)
            val end = (range.last + 1).coerceIn(start, visible.length)
            if (start < end) addStyle(style, start, end)
        }
    }
}

/** Maccy's `HistoryItemDecorator.highlight` title cap. */
private const val HIGHLIGHT_LENGTH = 500
