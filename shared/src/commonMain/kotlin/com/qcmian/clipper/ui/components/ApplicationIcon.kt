package com.qcmian.clipper.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.qcmian.clipper.util.ioDispatcher
import kotlinx.coroutines.withContext

/**
 * Resolves a base64 icon by bundle id without blocking composition.
 *
 * The platform lookup walks the `.app` bundle (desktop) or the icon cache, so calling it
 * inline in a composable — as the history list used to — performed file IO during every
 * recomposition, once per visible row. The lookup runs on [ioDispatcher] and is re-run only
 * when [bundleId] changes, so scrolling stays off the IO path.
 */
@Composable
fun rememberApplicationIcon(
    load: (String?) -> String?,
    bundleId: String?,
): String? = rememberOffComposition(bundleId) { bundleId?.let(load) }

/**
 * Same as [rememberApplicationIcon] for the display name of an application, used by the
 * "ignored applications" list of the preferences dialog.
 */
@Composable
fun rememberApplicationName(
    load: (String) -> String?,
    bundleId: String,
): String? = rememberOffComposition(bundleId) { load(bundleId) }

/** Runs a blocking lookup on [ioDispatcher], keyed by [key]. */
@Composable
private fun <T> rememberOffComposition(key: Any?, load: () -> T?): T? {
    val value by produceState<T?>(initialValue = null, key) {
        value = withContext(ioDispatcher) { runCatching { load() }.getOrNull() }
    }
    return value
}
