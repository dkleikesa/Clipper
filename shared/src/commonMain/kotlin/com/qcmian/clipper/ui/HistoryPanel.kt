package com.qcmian.clipper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.ClipAction
import com.qcmian.clipper.core.ClipboardRepository
import com.qcmian.clipper.core.SearchResult
import com.qcmian.clipper.core.currentTimeMillis
import com.qcmian.clipper.core.defaultAction
import com.qcmian.clipper.settings.PinPosition
import com.qcmian.clipper.ui.components.ClipRow
import com.qcmian.clipper.ui.components.FooterView
import com.qcmian.clipper.ui.components.SearchField
import com.qcmian.clipper.ui.dialogs.AboutDialog
import com.qcmian.clipper.ui.dialogs.ConfirmDialog
import com.qcmian.clipper.ui.dialogs.PreferencesDialog
import com.qcmian.clipper.ui.icons.ClipperIcon
import com.qcmian.clipper.ui.icons.ClipperIconKind
import kotlinx.coroutines.delay

/**
 * The main panel: header with the search field, the history list and the footer.
 * Port of Maccy's `ContentView` / `HistoryListView` with the same keyboard-first flow.
 */
@Composable
fun HistoryPanel(
    repository: ClipboardRepository,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val settings = repository.settings
    val results = repository.searchResults

    val searchFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    var selectedIndex by remember { mutableStateOf(0) }
    var showPreferences by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf<ConfirmRequest?>(null) }
    var now by remember { mutableStateOf(currentTimeMillis()) }

    val rows = buildRows(results, settings.pinTo)
    val shortcuts = shortcutMap(results)

    LaunchedEffect(Unit) { searchFocusRequester.requestFocus() }

    LaunchedEffect(Unit) {
        while (true) {
            delay(20_000)
            now = currentTimeMillis()
        }
    }

    LaunchedEffect(repository.searchQuery, results.size) {
        if (selectedIndex > results.lastIndex) selectedIndex = results.lastIndex.coerceAtLeast(0)
    }

    LaunchedEffect(selectedIndex, rows.size) {
        val target = rows.indexOfFirst { it is PanelRow.Entry && it.index == selectedIndex }
        if (target >= 0) listState.animateScrollToItem(target)
    }

    LaunchedEffect(repository.statusMessage) {
        if (repository.statusMessage == null) return@LaunchedEffect
        delay(1_600)
        repository.dismissStatus()
    }

    fun moveSelection(delta: Int) {
        if (results.isEmpty()) return
        selectedIndex = (selectedIndex + delta).coerceIn(0, results.lastIndex)
    }

    fun activate(index: Int, action: ClipAction) {
        results.getOrNull(index)?.let { repository.select(it.item, action) }
    }

    fun requestClear(all: Boolean) {
        confirmation = ConfirmRequest(
            message = if (all) {
                "This will remove every item, including the pinned ones. You can't undo this action."
            } else {
                "This will remove all unpinned items. You can't undo this action."
            },
        ) { if (all) repository.clearAll() else repository.clear() }
    }

    val keyHandler: (KeyEvent) -> Boolean = { event ->
        if (event.type != KeyEventType.KeyDown) {
            false
        } else {
            val meta = event.isMetaPressed || event.isCtrlPressed
            when {
                event.key == Key.DirectionDown -> {
                    moveSelection(1)
                    true
                }

                event.key == Key.DirectionUp -> {
                    moveSelection(-1)
                    true
                }

                event.key == Key.Enter -> {
                    activate(
                        selectedIndex,
                        defaultAction(settings, event.isShiftPressed, event.isAltPressed, meta),
                    )
                    true
                }

                event.key == Key.Escape -> {
                    if (repository.searchQuery.isNotEmpty()) {
                        repository.clearSearch()
                        true
                    } else {
                        false
                    }
                }

                // Backspace only deletes an item while the search field is empty, so it
                // keeps working as "edit the query" the rest of the time.
                event.key == Key.Delete ||
                    (event.key == Key.Backspace && repository.searchQuery.isEmpty()) -> {
                    when {
                        meta && event.isShiftPressed -> requestClear(all = true)
                        meta -> requestClear(all = false)
                        else -> results.getOrNull(selectedIndex)?.let { repository.delete(it.item) }
                    }
                    true
                }

                event.key == Key.P && (meta || event.isAltPressed) -> {
                    results.getOrNull(selectedIndex)?.let { repository.togglePin(it.item) }
                    true
                }

                event.key == Key.Comma && meta -> {
                    showPreferences = true
                    true
                }

                else -> {
                    val code = event.utf16CodePoint
                    if (repository.searchQuery.isEmpty() && code in '1'.code..'9'.code) {
                        val index = code - '1'.code
                        if (index <= results.lastIndex) {
                            activate(
                                index,
                                defaultAction(settings, event.isShiftPressed, event.isAltPressed, meta),
                            )
                            true
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
            }
        }
    }

    Surface(color = colors.background, modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .onPreviewKeyEvent(keyHandler),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (settings.showTitle) {
                        Text(
                            text = "Clipper",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                    }
                    SearchField(
                        query = repository.searchQuery,
                        onQueryChange = repository::updateSearchQuery,
                        focusRequester = searchFocusRequester,
                        modifier = Modifier.weight(1f),
                    )
                }

                if (settings.ignoreEvents) {
                    PausedBanner(
                        onlyNext = settings.ignoreOnlyNextEvent,
                        onResume = { repository.updateSettings { it.copy(ignoreEvents = false, ignoreOnlyNextEvent = false) } },
                    )
                }

                HorizontalDivider(
                    color = colors.outline.copy(alpha = 0.4f),
                    modifier = Modifier.padding(horizontal = 6.dp),
                )

                Box(Modifier.weight(1f)) {
                    if (rows.isEmpty()) {
                        EmptyState(searching = repository.searchQuery.isNotEmpty())
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 5.dp, vertical = 5.dp),
                        ) {
                            items(rows) { row ->
                                when (row) {
                                    is PanelRow.Separator -> HorizontalDivider(
                                        color = colors.outline.copy(alpha = 0.35f),
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                    )

                                    is PanelRow.Entry -> ClipRow(
                                        item = row.result.item,
                                        ranges = row.result.ranges,
                                        selected = row.index == selectedIndex,
                                        shortcut = shortcuts[row.result.item.id],
                                        highlight = settings.highlightMatch,
                                        showColorSwatch = settings.showHexColorSwatch,
                                        now = now,
                                        onClick = {
                                            repository.select(
                                                row.result.item,
                                                defaultAction(settings, false, false, false),
                                            )
                                        },
                                        onTogglePin = { repository.togglePin(row.result.item) },
                                        onDelete = { repository.delete(row.result.item) },
                                    )
                                }
                            }
                        }
                    }
                }

                if (settings.showFooter) {
                    FooterView(
                        itemCount = results.size,
                        paused = settings.ignoreEvents,
                        onClear = { requestClear(all = false) },
                        onClearAll = { requestClear(all = true) },
                        onPreferences = { showPreferences = true },
                        onAbout = { showAbout = true },
                        onTogglePause = {
                            repository.updateSettings {
                                it.copy(ignoreEvents = !it.ignoreEvents, ignoreOnlyNextEvent = false)
                            }
                        },
                    )
                }
            }

            repository.statusMessage?.let { message ->
                Box(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = colors.onSurface,
                        contentColor = colors.surface,
                        tonalElevation = 4.dp,
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }

    if (showPreferences && confirmation == null) {
        PreferencesDialog(
            settings = settings,
            onSettingsChange = repository::updateSettings,
            onClearUnpinned = { requestClear(all = false) },
            onClearAll = { requestClear(all = true) },
            onDismiss = { showPreferences = false },
        )
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

    confirmation?.let { request ->
        ConfirmDialog(
            message = request.message,
            onConfirm = {
                request.onConfirm()
                confirmation = null
            },
            onDismiss = { confirmation = null },
        )
    }
}

@Composable
private fun PausedBanner(onlyNext: Boolean, onResume: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(colors.primary.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ClipperIcon(ClipperIconKind.PAUSE, size = 12.dp, tint = colors.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (onlyNext) {
                "Paused — the next copy will be ignored"
            } else {
                "Paused — new copies are not recorded"
            },
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onResume).padding(horizontal = 6.dp, vertical = 2.dp)) {
            Text("Resume", style = MaterialTheme.typography.labelMedium, color = colors.primary)
        }
    }
}

@Composable
private fun EmptyState(searching: Boolean) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ClipperIcon(
            if (searching) ClipperIconKind.SEARCH else ClipperIconKind.COPY,
            size = 30.dp,
            tint = colors.onSurfaceVariant.copy(alpha = 0.5f),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (searching) "No matching items" else "Your clipboard history is empty",
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (searching) {
                "Try a different query or switch the search mode in Preferences."
            } else {
                "Copy something anywhere and it will show up here."
            },
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant.copy(alpha = 0.75f),
            textAlign = TextAlign.Center,
        )
    }
}

private data class ConfirmRequest(val message: String, val onConfirm: () -> Unit)

private sealed interface PanelRow {
    data class Entry(val index: Int, val result: SearchResult) : PanelRow
    data object Separator : PanelRow
}

private fun buildRows(results: List<SearchResult>, pinTo: PinPosition): List<PanelRow> {
    if (results.isEmpty()) return emptyList()

    val pinned = results.withIndex().filter { it.value.item.isPinned }
    val unpinned = results.withIndex().filterNot { it.value.item.isPinned }
    val ordered = if (pinTo == PinPosition.TOP) pinned + unpinned else unpinned + pinned

    val rows = ordered.map<IndexedValue<SearchResult>, PanelRow> { PanelRow.Entry(it.index, it.value) }.toMutableList()
    if (pinned.isNotEmpty() && unpinned.isNotEmpty()) {
        val insertAt = if (pinTo == PinPosition.TOP) pinned.size else unpinned.size
        rows.add(insertAt, PanelRow.Separator)
    }
    return rows
}

/** Pinned items use their assigned letter, the first nine unpinned items use `1`…`9`. */
private fun shortcutMap(results: List<SearchResult>): Map<String, String> {
    val map = mutableMapOf<String, String>()
    var counter = 1
    results.forEach { result ->
        val item = result.item
        if (item.isPinned) {
            item.pin?.let { map[item.id] = it }
        } else if (counter <= 9) {
            map[item.id] = counter.toString()
            counter++
        }
    }
    return map
}
