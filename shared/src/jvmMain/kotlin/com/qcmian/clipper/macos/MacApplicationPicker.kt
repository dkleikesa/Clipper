package com.qcmian.clipper.macos

import com.qcmian.clipper.domain.model.SourceApplication
import java.io.File

/**
 * Port of the `.fileImporter(allowedContentTypes: [.application])` Maccy puts next to its
 * ignore list: an `NSOpenPanel` restricted to application bundles.
 *
 * The panel runs modal on the calling thread, which is the AWT event thread for Compose
 * Desktop, so it is safe to open it straight from a click handler.
 */
object MacApplicationPicker {
    private const val NS_MODAL_RESPONSE_OK = 1L

    private val loaded: Boolean = run {
        MacNative.loadFramework("/System/Library/Frameworks/Foundation.framework/Foundation")
        MacNative.loadFramework("/System/Library/Frameworks/AppKit.framework/AppKit")
    }

    /** Shows the picker and returns the chosen application, or `null` when cancelled. */
    fun pick(): SourceApplication? {
        if (!loaded) return null

        val panel = MacNative.send(MacNative.clazz("NSOpenPanel"), "openPanel") ?: return null
        MacNative.send(panel, "setCanChooseFiles:", true)
        MacNative.send(panel, "setCanChooseDirectories:", false)
        MacNative.send(panel, "setAllowsMultipleSelection:", false)
        MacNative.send(panel, "setResolvesAliases:", true)
        MacNative.nsString("选择要忽略的应用")
            ?.let { MacNative.send(panel, "setMessage:", it) }
        MacNative.stringArray(listOf("app"))
            ?.let { MacNative.send(panel, "setAllowedFileTypes:", it) }

        if (MacNative.sendLong(panel, "runModal") != NS_MODAL_RESPONSE_OK) return null

        val urls = MacNative.send(panel, "URLs") ?: return null
        val url = MacNative.array(urls).firstOrNull() ?: return null
        val path = MacNative.string(MacNative.send(url, "path")) ?: return null
        return fromPath(path)
    }

    /** Reads the bundle identifier and the display name out of the selected `.app`. */
    fun fromPath(path: String): SourceApplication? {
        val bundle = MacNative.send(
            MacNative.clazz("NSBundle"),
            "bundleWithPath:",
            MacNative.nsString(path) ?: return null,
        )
        val bundleId = MacNative.string(MacNative.send(bundle, "bundleIdentifier"))
        val name = File(path).name.removeSuffix(".app")

        if (bundleId.isNullOrBlank() && name.isBlank()) return null
        return SourceApplication(
            name = name.ifBlank { bundleId.orEmpty() },
            bundleId = bundleId,
        )
    }
}
