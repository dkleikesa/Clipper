package com.qcmian.clipper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.ui.KeyShortcut
import com.qcmian.clipper.ui.Popup

/**
 * Port of Maccy's `ListItemView`: one compact row shared by the history list and the footer.
 * It only ever shows the leading accessory, the title (or a thumbnail) and the keyboard
 * shortcut hint — no timestamps, pin markers or delete buttons.
 */
@Composable
fun ListItemRow(
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    shortcut: KeyShortcut? = null,
    /** The source application icon, shown when `showApplicationIcons` is enabled. */
    appIcon: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onHover: (() -> Unit)? = null,
    accessory: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val contentColor = if (isSelected) colors.onPrimary else colors.onSurface
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val clickModifier = onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier

    LaunchedEffect(hovered) {
        if (hovered) onHover?.invoke()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = Popup.itemHeight)
            .clip(RoundedCornerShape(Popup.cornerRadius))
            .background(if (isSelected) colors.primary.copy(alpha = 0.8f) else Color.Transparent)
            .hoverable(interactionSource)
            .then(clickModifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (appIcon != null) {
            Box(Modifier.padding(start = 4.dp, top = 5.dp, bottom = 5.dp)) { appIcon() }
            Spacer(Modifier.width(5.dp))
        } else {
            Spacer(Modifier.width(10.dp))
        }

        if (accessory != null) {
            accessory()
            Spacer(Modifier.width(5.dp))
        }

        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Box(Modifier.weight(1f)) { content() }
            Spacer(Modifier.width(5.dp))
            if (shortcut != null) ShortcutView(shortcut)
            Spacer(Modifier.width(10.dp))
        }
    }
}

/**
 * The row title, matching `ListItemTitleView` (single line, middle truncation).
 *
 * The line height is pinned so the row keeps `Popup.itemHeight` instead of inheriting
 * Material's much taller default line box.
 */
@Composable
fun RowTitle(text: AnnotatedString, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        color = LocalContentColor.current,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
        modifier = modifier,
    )
}

@Composable
fun RowTitle(text: String, modifier: Modifier = Modifier) {
    RowTitle(AnnotatedString(text), modifier)
}

/** Renders `⌥⌘⌫` the same way `KeyboardShortcutView` does. */
@Composable
private fun ShortcutView(shortcut: KeyShortcut) {
    val color = LocalContentColor.current
    Row(
        modifier = Modifier.alpha(0.7f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (shortcut.modifiers.isNotEmpty()) {
            Text(
                text = shortcut.modifiers,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = color,
                maxLines = 1,
            )
        }
        Text(
            text = shortcut.character,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = color,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(12.dp),
        )
    }
}
