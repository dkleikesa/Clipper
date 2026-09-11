package com.qcmian.clipper.macos

import com.qcmian.clipper.model.SourceApplication
import com.sun.jna.Pointer

/**
 * Port of the two `NSWorkspace` queries Maccy relies on: the frontmost application and the
 * URL of an application identified by its bundle id.
 */
object MacWorkspace {
    private val loaded: Boolean = run {
        val foundation = MacNative.loadFramework("/System/Library/Frameworks/Foundation.framework/Foundation")
        val appKit = MacNative.loadFramework("/System/Library/Frameworks/AppKit.framework/AppKit")
        foundation || appKit
    }

    private val ownPid: Long = runCatching { ProcessHandle.current().pid() }.getOrDefault(-1L)

    /** `NSWorkspace.shared.frontmostApplication`. */
    fun frontmostApplication(): SourceApplication? {
        if (!loaded) return null
        val application = MacNative.send(sharedWorkspace(), "frontmostApplication") ?: return null
        // Copies made while Clipper itself is frontmost are not attributed to an application,
        // which is what Maccy's `fromMaccy` pasteboard marker achieves on its side.
        if (MacNative.sendLong(application, "processIdentifier") == ownPid) return null

        val name = MacNative.string(MacNative.send(application, "localizedName"))?.trim()
        if (name.isNullOrEmpty()) return null
        val bundleId = MacNative.string(MacNative.send(application, "bundleIdentifier"))
        return SourceApplication(name = name, bundleId = bundleId)
    }

    /** `NSWorkspace.shared.frontmostApplication.processIdentifier`. */
    fun frontmostPid(): Long {
        if (!loaded) return -1L
        val application = MacNative.send(sharedWorkspace(), "frontmostApplication") ?: return -1L
        return MacNative.sendLong(application, "processIdentifier")
    }

    /**
     * `NSEvent.modifierFlags`, the flags of the event currently being handled. Port of the
     * `NSApp.currentEvent.modifierFlags` check `AppDelegate.performStatusItemClick` performs.
     */
    fun currentModifierFlags(): Int {
        if (!loaded) return 0
        val clazz = MacNative.clazz("NSEvent") ?: return 0
        return MacNative.sendLong(clazz, "modifierFlags").toInt()
    }

    /** `NSWorkspace.shared.urlForApplication(withBundleIdentifier:)`, returned as a path. */
    fun applicationPath(bundleId: String): String? {
        if (!loaded) return null
        val workspace = sharedWorkspace() ?: return null
        val identifier = MacNative.nsString(bundleId) ?: return null
        val url = MacNative.send(workspace, "URLForApplicationWithBundleIdentifier:", identifier) ?: return null
        return MacNative.string(MacNative.send(url, "path"))
    }

    /**
     * `NSWorkspace.applicationName(at:)`, resolved through the bundle path so that the ignore
     * list can show `Safari` instead of `com.apple.Safari`.
     */
    fun applicationName(bundleId: String): String? {
        val path = applicationPath(bundleId) ?: return null
        return java.io.File(path).name.removeSuffix(".app").ifBlank { null }
    }

    /** `NSWorkspace.open(_:)`, used to follow the About dialog links. */
    fun openUrl(url: String): Boolean {
        if (!loaded) return false
        val workspace = sharedWorkspace() ?: return false
        val string = MacNative.nsString(url) ?: return false
        val nsUrl = MacNative.send(MacNative.clazz("NSURL"), "URLWithString:", string) ?: return false
        return MacNative.sendBool(workspace, "openURL:", nsUrl)
    }

    private fun sharedWorkspace(): Pointer? {
        val clazz = MacNative.clazz("NSWorkspace") ?: return null
        return MacNative.send(clazz, "sharedWorkspace")
    }
}
