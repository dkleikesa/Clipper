package com.qcmian.clipper.macos

/**
 * Port of Maccy's `LaunchAtLogin` package, implemented with `SMAppService` (macOS 13+).
 *
 * `SMAppService.mainAppService` registers the running application bundle as a login item.
 * When the JVM is not started from a bundle (for example `./gradlew run` during development)
 * registration fails; every failure is swallowed so the preference simply stays inert.
 */
object MacLaunchAtLogin {
    private val available: Boolean by lazy {
        MacNative.loadFramework(SERVICE_MANAGEMENT_FRAMEWORK) &&
            MacNative.clazz("SMAppService") != null
    }

    /** `true` when the platform exposes `SMAppService`. */
    val isSupported: Boolean get() = available

    fun setEnabled(enabled: Boolean): Boolean {
        if (!available) return false

        val clazz = MacNative.clazz("SMAppService") ?: return false
        val service = MacNative.send(clazz, "mainAppService") ?: return false
        return if (enabled) {
            MacNative.sendBool(service, "registerAndReturnError:", null)
        } else {
            MacNative.sendBool(service, "unregisterAndReturnError:", null)
        }
    }

    private const val SERVICE_MANAGEMENT_FRAMEWORK =
        "/System/Library/Frameworks/ServiceManagement.framework/ServiceManagement"
}
