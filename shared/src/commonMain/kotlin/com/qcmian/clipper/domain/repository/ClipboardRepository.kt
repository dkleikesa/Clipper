package com.qcmian.clipper.domain.repository

import com.qcmian.clipper.domain.model.AppSettings
import com.qcmian.clipper.domain.model.ClipItem
import com.qcmian.clipper.domain.model.ClipboardSnapshot
import com.qcmian.clipper.domain.model.SourceApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * The single source of truth for the clipboard history and the user preferences.
 *
 * This is the data layer entry point for *reading and writing the history*: it owns
 * persistence and exposes the in-memory state as [StateFlow]. Host capabilities — writing the
 * system clipboard, resolving application icons, text recognition — live in
 * [ClipboardPlatform] instead, so a consumer only depends on the half it actually uses.
 * It deliberately contains no UI state and no Compose dependency.
 */
interface ClipboardRepository {
    /** The history, already ordered by the current sort / pin preferences. */
    val items: StateFlow<List<ClipItem>>

    /** The user preferences. */
    val settings: StateFlow<AppSettings>

    /** A transient message the UI may show, e.g. "pasting is not supported here". */
    val statusMessage: StateFlow<String?>

    /** Every new copy the platform reports, for the domain layer to interpret. */
    val snapshots: Flow<ClipboardSnapshot>

    /** Starts observing the system clipboard. */
    fun start()

    /** Stops observing the system clipboard. */
    fun stop()

    /** Writes the pending state to storage immediately instead of waiting for the debounce. */
    fun flush()

    /** Replaces the history; the list is re-sorted and trimmed before it is persisted. */
    fun setItems(items: List<ClipItem>)

    /** Replaces the preferences and pushes the platform side settings. */
    fun setSettings(settings: AppSettings)

    /** Shows (or clears) the transient status message. */
    fun setStatusMessage(message: String?)
}

/**
 * The part of the data layer that reaches out to the host: the system clipboard, the source
 * application, application icons and text recognition.
 *
 * Split out of [ClipboardRepository] so that the use cases which never touch them — sorting,
 * clearing the history, updating the preferences — do not depend on a twenty member interface.
 */
interface ClipboardPlatform {
    /** Approximate size of the persisted history, `null` when the platform cannot tell. */
    val storageSize: String?

    /** How many screens the "popup screen" preference can point at. */
    val screenCount: Int

    /** Whether the host can register the app as a login item. */
    val supportsLaunchAtLogin: Boolean

    /** Whether the host can tell which application a copy came from. */
    val supportsApplicationInfo: Boolean

    /** Whether image text recognition is available. */
    val supportsTextRecognition: Boolean

    /** Places [snapshot] on the system clipboard. `false` when the platform cannot. */
    fun writeClipboard(snapshot: ClipboardSnapshot): Boolean

    /** Clears the system clipboard. */
    fun clearSystemClipboard()

    /** Best effort "press paste" into the previously focused application. */
    fun paste(): Boolean

    fun applicationIcon(bundleId: String?): String?

    fun applicationName(bundleId: String): String?

    fun pickApplication(): SourceApplication?

    fun openUrl(url: String): Boolean

    /** Text recognised inside an encoded image, used as the title of image entries. */
    suspend fun recognizeText(imageBase64: String): String?

    /** The application the last copy came from, `null` when the platform cannot tell. */
    fun currentSourceApplication(): SourceApplication?
}
