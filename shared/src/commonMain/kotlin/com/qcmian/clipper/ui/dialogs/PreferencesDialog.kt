package com.qcmian.clipper.ui.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.qcmian.clipper.settings.AppSettings
import com.qcmian.clipper.settings.HighlightMatch
import com.qcmian.clipper.settings.PinPosition
import com.qcmian.clipper.settings.SearchMode
import com.qcmian.clipper.settings.SortBy
import com.qcmian.clipper.ui.icons.ClipperIcon
import com.qcmian.clipper.ui.icons.ClipperIconKind
import kotlin.math.roundToInt

/** The counterpart of Maccy's preferences window, condensed into a single dialog. */
@Composable
fun PreferencesDialog(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onClearUnpinned: () -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = colors.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.width(460.dp).heightIn(max = 620.dp),
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Preferences", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(colors.surfaceVariant.copy(alpha = 0.6f))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        ClipperIcon(ClipperIconKind.CLEAR, size = 12.dp)
                    }
                }

                HorizontalDivider(color = colors.outline.copy(alpha = 0.4f))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    SectionTitle("Behavior")
                    SwitchRow(
                        title = "Paste automatically",
                        description = "Paste the selected item into the previous application.",
                        checked = settings.pasteByDefault,
                    ) { value -> onSettingsChange { it.copy(pasteByDefault = value) } }
                    SwitchRow(
                        title = "Paste without formatting",
                        description = "Strip rich text when pasting.",
                        checked = settings.removeFormattingByDefault,
                    ) { value -> onSettingsChange { it.copy(removeFormattingByDefault = value) } }

                    SectionTitle("History")
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("History size", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${settings.historySize} unpinned items",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Slider(
                            value = settings.historySize.toFloat(),
                            onValueChange = { value ->
                                val size = value.roundToInt().coerceAtLeast(10)
                                onSettingsChange { it.copy(historySize = size) }
                            },
                            valueRange = 10f..1000f,
                            modifier = Modifier.width(220.dp),
                        )
                    }
                    ChipBlock(
                        title = "Sort by",
                        values = SortBy.entries,
                        selected = settings.sortBy,
                        label = { it.label },
                        onSelect = { value -> onSettingsChange { it.copy(sortBy = value) } },
                    )
                    ChipBlock(
                        title = "Pinned items position",
                        values = PinPosition.entries,
                        selected = settings.pinTo,
                        label = { it.label },
                        onSelect = { value -> onSettingsChange { it.copy(pinTo = value) } },
                    )
                    SwitchRow(
                        title = "Clear the system clipboard too",
                        description = "Also wipe the OS clipboard when clearing the history.",
                        checked = settings.clearSystemClipboard,
                    ) { value -> onSettingsChange { it.copy(clearSystemClipboard = value) } }

                    SectionTitle("Search")
                    ChipBlock(
                        title = "Search mode",
                        values = SearchMode.entries,
                        selected = settings.searchMode,
                        label = { it.label },
                        onSelect = { value -> onSettingsChange { it.copy(searchMode = value) } },
                    )
                    ChipBlock(
                        title = "Highlight matches",
                        values = HighlightMatch.entries,
                        selected = settings.highlightMatch,
                        label = { it.label },
                        onSelect = { value -> onSettingsChange { it.copy(highlightMatch = value) } },
                    )

                    SectionTitle("Appearance")
                    SwitchRow(
                        title = "Show title",
                        checked = settings.showTitle,
                    ) { value -> onSettingsChange { it.copy(showTitle = value) } }
                    SwitchRow(
                        title = "Show footer",
                        checked = settings.showFooter,
                    ) { value -> onSettingsChange { it.copy(showFooter = value) } }
                    SwitchRow(
                        title = "Show hex color swatch",
                        description = "Render a swatch for copied colors such as #0A84FF.",
                        checked = settings.showHexColorSwatch,
                    ) { value -> onSettingsChange { it.copy(showHexColorSwatch = value) } }

                    SectionTitle("Ignore")
                    SwitchRow(
                        title = "Pause capturing new copies",
                        checked = settings.ignoreEvents,
                    ) { value ->
                        onSettingsChange { it.copy(ignoreEvents = value, ignoreOnlyNextEvent = false) }
                    }
                    SwitchRow(
                        title = "Ignore only the next copy",
                        checked = settings.ignoreOnlyNextEvent,
                        enabled = settings.ignoreEvents,
                    ) { value -> onSettingsChange { it.copy(ignoreOnlyNextEvent = value) } }

                    var regexpText by remember {
                        mutableStateOf(settings.ignoredRegexp.joinToString(", "))
                    }
                    OutlinedTextField(
                        value = regexpText,
                        onValueChange = { text ->
                            regexpText = text
                            val patterns = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            onSettingsChange { it.copy(ignoredRegexp = patterns) }
                        },
                        label = { Text("Ignore regular expressions") },
                        supportingText = { Text("Comma separated. Matching copies are not recorded.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )

                    SectionTitle("Data")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onClearUnpinned) { Text("Clear unpinned") }
                        TextButton(onClick = onClearAll) { Text("Clear everything") }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
    )
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    description: String? = null,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) colors.onSurface else colors.onSurfaceVariant,
            )
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun <T> ChipBlock(
    title: String,
    values: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            values.forEach { value ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = {
                        Text(label(value), style = MaterialTheme.typography.labelMedium)
                    },
                )
            }
        }
    }
}
