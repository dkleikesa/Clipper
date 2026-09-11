package com.qcmian.clipper.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.decodeBase64
import com.qcmian.clipper.model.ClipItem
import com.qcmian.clipper.model.ClipKind
import com.qcmian.clipper.settings.HighlightMatch
import com.qcmian.clipper.ui.hexToColor
import com.qcmian.clipper.ui.icons.ClipperIcon
import com.qcmian.clipper.ui.icons.ClipperIconKind
import com.qcmian.clipper.util.formatRelativeTime

/** A single history row, the counterpart of Maccy's `HistoryItemView` / `ListItemView`. */
@Composable
fun ClipRow(
    item: ClipItem,
    ranges: List<IntRange>,
    selected: Boolean,
    shortcut: String?,
    highlight: HighlightMatch,
    showColorSwatch: Boolean,
    now: Long,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()

    val background = when {
        selected -> colors.primary
        hovered -> colors.surfaceVariant.copy(alpha = 0.45f)
        else -> Color.Transparent
    }
    val contentColor = if (selected) colors.onPrimary else colors.onSurface

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClipLeadingIcon(item = item, tint = contentColor, showColorSwatch = showColorSwatch)
        Spacer(Modifier.width(8.dp))

        Text(
            text = highlightedTitle(item.title, ranges, highlight, selected, colors),
            style = MaterialTheme.typography.bodyMedium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (item.numberOfCopies > 1) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "×${item.numberOfCopies}",
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.7f),
            )
        }

        if (shortcut != null) {
            Spacer(Modifier.width(6.dp))
            ShortcutBadge(shortcut = shortcut, selected = selected)
        }

        Spacer(Modifier.width(8.dp))
        Box(Modifier.width(30.dp), contentAlignment = Alignment.CenterEnd) {
            Text(
                text = formatRelativeTime(item.lastCopiedAt, now),
                style = MaterialTheme.typography.labelSmall,
                color = contentColor.copy(alpha = 0.6f),
                maxLines = 1,
            )
        }

        // Pin and delete stay visible on every platform: hover does not exist on touch
        // devices, so a hover-only affordance would make them unreachable there.
        val emphasis = if (hovered || selected || item.isPinned) 1f else 0.45f

        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onTogglePin),
            contentAlignment = Alignment.Center,
        ) {
            ClipperIcon(
                if (item.isPinned) ClipperIconKind.STAR_FILLED else ClipperIconKind.STAR,
                size = 12.dp,
                tint = when {
                    selected -> colors.onPrimary.copy(alpha = emphasis)
                    item.isPinned -> colors.primary
                    else -> contentColor.copy(alpha = emphasis)
                },
            )
        }

        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onDelete),
            contentAlignment = Alignment.Center,
        ) {
            ClipperIcon(
                ClipperIconKind.TRASH,
                size = 12.dp,
                tint = contentColor.copy(alpha = emphasis * 0.85f),
            )
        }
    }
}

@Composable
private fun ShortcutBadge(shortcut: String, selected: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(
                if (selected) colors.onPrimary.copy(alpha = 0.25f) else colors.surfaceVariant,
            )
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            text = shortcut,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun ClipLeadingIcon(item: ClipItem, tint: Color, showColorSwatch: Boolean) {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        when (item.kind) {
            ClipKind.IMAGE -> ImageThumbnail(item.imageBase64, tint)
            ClipKind.COLOR -> {
                val swatch = if (showColorSwatch) item.text?.let { hexToColor(it) } else null
                if (swatch != null) {
                    Box(
                        Modifier
                            .size(15.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(swatch),
                    )
                } else {
                    ClipperIcon(ClipperIconKind.SWATCH, size = 15.dp, tint = tint)
                }
            }

            ClipKind.LINK -> ClipperIcon(ClipperIconKind.GLOBE, size = 15.dp, tint = tint)
            ClipKind.FILE -> ClipperIcon(ClipperIconKind.FILE, size = 15.dp, tint = tint)
            ClipKind.TEXT -> ClipperIcon(ClipperIconKind.TEXT, size = 15.dp, tint = tint)
        }
    }
}

@Composable
private fun ImageThumbnail(imageBase64: String?, tint: Color) {
    val bitmap: ImageBitmap? = remember(imageBase64) {
        imageBase64
            ?.let { decodeBase64(it) }
            ?.let { bytes -> runCatching { bytes.decodeToImageBitmap() }.getOrNull() }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(3.dp)),
        )
    } else {
        ClipperIcon(ClipperIconKind.IMAGE, size = 15.dp, tint = tint)
    }
}

private fun highlightedTitle(
    title: String,
    ranges: List<IntRange>,
    highlight: HighlightMatch,
    selected: Boolean,
    colors: ColorScheme,
): AnnotatedString {
    if (ranges.isEmpty()) return AnnotatedString(title)

    val style = when (highlight) {
        HighlightMatch.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        HighlightMatch.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        HighlightMatch.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
        HighlightMatch.BACKGROUND -> SpanStyle(
            background = if (selected) colors.onPrimary.copy(alpha = 0.30f) else colors.primary.copy(alpha = 0.30f),
            color = if (selected) colors.onPrimary else colors.onSurface,
        )
    }

    return buildAnnotatedString {
        append(title)
        ranges.forEach { range ->
            val start = range.first.coerceIn(0, title.length)
            val end = (range.last + 1).coerceIn(start, title.length)
            if (start < end) addStyle(style, start, end)
        }
    }
}
