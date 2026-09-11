package com.qcmian.clipper.data.source

import com.qcmian.clipper.data.model.SourceApplication
import com.qcmian.clipper.macos.MacAppIcon
import com.qcmian.clipper.macos.MacApplicationPicker
import com.qcmian.clipper.macos.MacGlobalHotKey
import com.qcmian.clipper.macos.MacLaunchAtLogin
import com.qcmian.clipper.macos.MacTextRecognition
import com.qcmian.clipper.macos.MacWindow
import com.qcmian.clipper.macos.MacWorkspace
import java.awt.GraphicsEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** True when the JVM runs on macOS, where the native integrations below are available. */
internal val isMacOs: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("mac")

private class MacNativeDataSource : NativeDataSource {
    private val iconCache = mutableMapOf<String, String?>()

    override val supportsApplicationInfo: Boolean get() = isMacOs
    override val supportsTextRecognition: Boolean get() = MacTextRecognition.available
    override val supportsGlobalHotKey: Boolean get() = isMacOs
    override val supportsLaunchAtLogin: Boolean get() = isMacOs && MacLaunchAtLogin.isSupported

    override val screenCount: Int
        get() = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.size
        }.getOrDefault(1).coerceAtLeast(1)

    override fun frontmostApplication(): SourceApplication? =
        if (isMacOs) MacWorkspace.frontmostApplication() else null

    override fun frontmostWindowRect(): ScreenRect? =
        if (isMacOs) MacWindow.frontmostWindowRect(MacWorkspace.frontmostPid()) else null

    override fun currentModifierFlags(): Int =
        if (isMacOs) MacWorkspace.currentModifierFlags() else 0

    override fun applicationIcon(bundleId: String?): String? {
        if (!isMacOs || bundleId == null) return null
        return iconCache.getOrPut(bundleId) { MacAppIcon.iconBase64(bundleId) }
    }

    override fun applicationName(bundleId: String): String? =
        if (isMacOs) MacWorkspace.applicationName(bundleId) else null

    override fun pickApplication(): SourceApplication? =
        if (isMacOs) MacApplicationPicker.pick() else null

    override fun openUrl(url: String): Boolean {
        if (isMacOs) return MacWorkspace.openUrl(url)
        return runCatching {
            java.awt.Desktop.getDesktop().browse(java.net.URI(url))
        }.isSuccess
    }

    override suspend fun recognizeText(imageBase64: String): String? {
        if (!MacTextRecognition.available) return null
        // Vision runs synchronously, so keep it off the UI thread.
        return withContext(Dispatchers.Default) { MacTextRecognition.recognize(imageBase64) }
    }

    override fun registerGlobalHotKey(
        shortcut: GlobalShortcut,
        onTrigger: () -> Unit,
    ): GlobalHotKeyHandle? = if (isMacOs) MacGlobalHotKey.register(shortcut, onTrigger) else null

    override fun setLaunchAtLogin(enabled: Boolean) {
        if (!isMacOs) return
        MacLaunchAtLogin.setEnabled(enabled)
    }
}

actual fun createNativeDataSource(): NativeDataSource =
    if (isMacOs) MacNativeDataSource() else UnsupportedNativeDataSource
