package com.qcmian.clipper.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.ui.icons.ClipperIcon
import com.qcmian.clipper.ui.icons.ClipperIconKind

/** Footer with the actions Maccy exposes: clear, clear all, preferences and about. */
@Composable
fun FooterView(
    itemCount: Int,
    paused: Boolean,
    onClear: () -> Unit,
    onClearAll: () -> Unit,
    onPreferences: () -> Unit,
    onAbout: () -> Unit,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme

    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(
            color = colors.outline.copy(alpha = 0.5f),
            modifier = Modifier.padding(horizontal = 6.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FooterButton(text = "Clear", onClick = onClear)
            FooterButton(text = "Clear all", onClick = onClearAll)
            FooterButton(text = "Preferences…", onClick = onPreferences)
            FooterButton(text = "About", onClick = onAbout)

            Spacer(Modifier.weight(1f))

            TextButton(
                onClick = onTogglePause,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) {
                ClipperIcon(
                    if (paused) ClipperIconKind.PLAY else ClipperIconKind.PAUSE,
                    size = 12.dp,
                    tint = if (paused) colors.primary else colors.onSurfaceVariant,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = if (paused) "Paused" else "Live",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (paused) colors.primary else colors.onSurfaceVariant,
                )
            }

            Spacer(Modifier.width(4.dp))

            Text(
                text = if (itemCount == 1) "1 item" else "$itemCount items",
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}

@Composable
private fun FooterButton(text: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}
