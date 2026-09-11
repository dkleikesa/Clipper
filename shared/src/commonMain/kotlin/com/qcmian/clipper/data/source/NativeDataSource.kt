package com.qcmian.clipper.data.source

import com.qcmian.clipper.domain.model.SourceApplication

/** A rectangle in screen points, used by the "popup at the application window" position. */
data class ScreenRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * Optional native capabilities. Everything has a safe default so platforms without an
 * equivalent simply report "not available" instead of failing.
 *
 * Integrations that can only exist on one JVM-hosted target — the Carbon global hot key per
 * example — deliberately live in that platform source set (`com.qcmian.clipper.macos`) instead
 * of here, so this common contract never describes a macOS-only concept such as a virtual key
 * code.
 */
interface NativeDataSource {
    /** Whether [frontmostApplication] can return anything useful. */
    val supportsApplicationInfo: Boolean get() = false

    /** Whether [recognizeText] can return anything useful. */
    val supportsTextRecognition: Boolean get() = false

    /** Whether [setLaunchAtLogin] can do anything. */
    val supportsLaunchAtLogin: Boolean get() = false

    /** How many screens the popup can be anchored to; at least one. */
    val screenCount: Int get() = 1

    /** The application that was frontmost, i.e. the one the user copied from. */
    fun frontmostApplication(): SourceApplication? = null

    /**
     * Bounds of the frontmost application's main window, the counterpart of Maccy's
     * `NSRunningApplication.windowFrame`. `null` when the platform cannot tell.
     */
    fun frontmostWindowRect(): ScreenRect? = null

    /**
     * Modifier keys held while the event being handled fired, the counterpart of
     * `NSApp.currentEvent.modifierFlags`. `0` when the platform cannot tell.
     */
    fun currentModifierFlags(): Int = 0

    /** Base64 encoded PNG of an application icon, looked up by bundle id. */
    fun applicationIcon(bundleId: String?): String? = null

    /**
     * Human readable name of an application, resolved from its bundle id. Port of
     * `NSWorkspace.applicationName(at:)`, which `IgnoreApplicationsSettingsView` uses to label
     * the entries of the ignore list.
     */
    fun applicationName(bundleId: String): String? = null

    /**
     * Opens the platform application picker and returns the chosen application. Port of the
     * `.fileImporter(allowedContentTypes: [.application])` Maccy shows next to the ignore list.
     * `null` when the platform has no picker or the user cancelled.
     */
    fun pickApplication(): SourceApplication? = null

    /** Opens [url] with the default handler, used by the links of the About dialog. */
    fun openUrl(url: String): Boolean = false

    /** Text recognised inside an encoded image, used as the title of image entries. */
    suspend fun recognizeText(imageBase64: String): String? = null

    /**
     * Registers or unregisters the application as a login item, the counterpart of Maccy's
     * `LaunchAtLogin` package. Best effort: platforms without an equivalent do nothing.
     */
    fun setLaunchAtLogin(enabled: Boolean) {}
}

expect fun createNativeDataSource(): NativeDataSource

/** Shared no-op used by every platform that does not override a capability. */
internal object UnsupportedNativeDataSource : NativeDataSource
