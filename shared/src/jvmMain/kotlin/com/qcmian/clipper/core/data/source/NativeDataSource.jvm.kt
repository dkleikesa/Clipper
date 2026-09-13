package com.qcmian.clipper.core.data.source

import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.SourceApplication
import com.qcmian.clipper.core.platform.macos.MacAppIcon
import com.qcmian.clipper.core.platform.macos.MacApplicationPicker
import com.qcmian.clipper.core.platform.macos.MacLaunchAtLogin
import com.qcmian.clipper.core.platform.macos.MacTextRecognition
import com.qcmian.clipper.core.platform.macos.MacWorkspace
import java.awt.GraphicsEnvironment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** JVM 是否运行在 macOS 上，下面的原生集成只在那里可用。 */
internal val isMacOs: Boolean =
    System.getProperty("os.name").orEmpty().lowercase().contains("mac")

private class MacNativeDataSource : NativeDataSource {
    private val iconCache = mutableMapOf<String, String?>()

    override val supportsApplicationInfo: Boolean get() = isMacOs
    override val supportsTextRecognition: Boolean get() = MacTextRecognition.available
    override val supportsLaunchAtLogin: Boolean get() = isMacOs && MacLaunchAtLogin.isSupported

    override val screenCount: Int
        get() = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.size
        }.getOrDefault(1).coerceAtLeast(1)

    override fun frontmostApplication(): SourceApplication? =
        if (isMacOs) MacWorkspace.frontmostApplication() else null

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

    override suspend fun recognizeText(image: ClipImage): String? {
        if (!MacTextRecognition.available) return null
        // Vision 是同步执行的，因此把它放到 UI 线程之外。
        return withContext(Dispatchers.Default) { MacTextRecognition.recognize(image) }
    }

    override fun setLaunchAtLogin(enabled: Boolean) {
        if (!isMacOs) return
        MacLaunchAtLogin.setEnabled(enabled)
    }
}

actual fun createNativeDataSource(): NativeDataSource =
    if (isMacOs) MacNativeDataSource() else UnsupportedNativeDataSource
