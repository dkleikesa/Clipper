package com.qcmian.clipper.macos

/**
 * 对应 Maccy 的 `LaunchAtLogin` 包，用 `SMAppService`（macOS 13+）实现。
 *
 * `SMAppService.mainAppService` 会把正在运行的应用包注册为登录项。
 * 当 JVM 不是从应用包启动时（例如开发时 `./gradlew run`）注册会失败；
 * 所有失败都被吞掉，因此该偏好只是保持不变、不生效。
 */
object MacLaunchAtLogin {
    private val available: Boolean by lazy {
        MacNative.loadFramework(SERVICE_MANAGEMENT_FRAMEWORK) &&
            MacNative.clazz("SMAppService") != null
    }

    /** 平台是否提供 `SMAppService`。 */
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
