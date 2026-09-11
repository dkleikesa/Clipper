package com.qcmian.clipper.macos

import com.qcmian.clipper.domain.model.SourceApplication
import com.sun.jna.Pointer

/**
 * 对应 Maccy 依赖的两个 `NSWorkspace` 查询：最前应用，
 * 以及按 bundle id 定位的应用的 URL。
 */
object MacWorkspace {
    private val loaded: Boolean = run {
        val foundation = MacNative.loadFramework("/System/Library/Frameworks/Foundation.framework/Foundation")
        val appKit = MacNative.loadFramework("/System/Library/Frameworks/AppKit.framework/AppKit")
        foundation || appKit
    }

    private val ownPid: Long = runCatching { ProcessHandle.current().pid() }.getOrDefault(-1L)

    /** `NSWorkspace.shared.frontmostApplication`。 */
    fun frontmostApplication(): SourceApplication? {
        if (!loaded) return null
        val application = MacNative.send(sharedWorkspace(), "frontmostApplication") ?: return null
        // Clipper 自身处于最前时产生的复制不归属任何应用，
        // 这正是 Maccy 那边用 `fromMaccy` 粘贴板标记达到的效果。
        if (MacNative.sendLong(application, "processIdentifier") == ownPid) return null

        val name = MacNative.string(MacNative.send(application, "localizedName"))?.trim()
        if (name.isNullOrEmpty()) return null
        val bundleId = MacNative.string(MacNative.send(application, "bundleIdentifier"))
        return SourceApplication(name = name, bundleId = bundleId)
    }

    /** `NSWorkspace.shared.frontmostApplication.processIdentifier`。 */
    fun frontmostPid(): Long {
        if (!loaded) return -1L
        val application = MacNative.send(sharedWorkspace(), "frontmostApplication") ?: return -1L
        return MacNative.sendLong(application, "processIdentifier")
    }

    /** 同 [frontmostPid]，但最前的正是本应用时返回 `-1`，便于记住「此前聚焦的外部应用」。 */
    fun frontmostExternalPid(): Long {
        val pid = frontmostPid()
        return if (pid == ownPid) -1L else pid
    }

    /**
     * `NSRunningApplication.currentApplication.activate(options:)`：把本应用带到前台，
     * 使面板成为 key window。这样键盘输入能到达面板，点击窗口外部也会触发失焦收起
     * （`FloatingPanel.makeKeyAndOrderFront`）。
     */
    fun activateSelf(): Boolean = activateApplication(currentApplication())

    /**
     * `NSRunningApplication.activate(options:)`：把指定 pid 的应用带回前台。
     * 面板合成粘贴或关闭之前调用它，使焦点（以及随后的 ⌘V）落回此前聚焦的应用。
     */
    fun activate(pid: Long): Boolean {
        if (pid <= 0) return false
        val clazz = MacNative.clazz("NSRunningApplication") ?: return false
        val application = MacNative.send(clazz, "runningApplicationWithProcessIdentifier:", pid.toInt())
        return activateApplication(application)
    }

    private fun currentApplication(): Pointer? {
        val clazz = MacNative.clazz("NSRunningApplication") ?: return null
        return MacNative.send(clazz, "currentApplication")
    }

    private fun activateApplication(application: Pointer?): Boolean {
        if (!loaded || application == null) return false
        // `NSApplicationActivateAllWindows` | `NSApplicationActivateIgnoringOtherApps`。
        return MacNative.sendBool(application, "activateWithOptions:", ACTIVATE_ALL_WINDOWS or ACTIVATE_IGNORING_OTHER_APPS)
    }

    /**
     * `NSEvent.modifierFlags`，即当前正在处理的事件的修饰键。
     * 对应 `AppDelegate.performStatusItemClick` 中对 `NSApp.currentEvent.modifierFlags` 的检查。
     */
    fun currentModifierFlags(): Int {
        if (!loaded) return 0
        val clazz = MacNative.clazz("NSEvent") ?: return 0
        return MacNative.sendLong(clazz, "modifierFlags").toInt()
    }

    /** `NSWorkspace.shared.urlForApplication(withBundleIdentifier:)`，以路径形式返回。 */
    fun applicationPath(bundleId: String): String? {
        if (!loaded) return null
        val workspace = sharedWorkspace() ?: return null
        val identifier = MacNative.nsString(bundleId) ?: return null
        val url = MacNative.send(workspace, "URLForApplicationWithBundleIdentifier:", identifier) ?: return null
        return MacNative.string(MacNative.send(url, "path"))
    }

    /**
     * `NSWorkspace.applicationName(at:)`，通过应用包路径解析，
     * 这样忽略列表就能显示 `Safari` 而不是 `com.apple.Safari`。
     */
    fun applicationName(bundleId: String): String? {
        val path = applicationPath(bundleId) ?: return null
        return java.io.File(path).name.removeSuffix(".app").ifBlank { null }
    }

    /** `NSWorkspace.open(_:)`，用于跟随「关于」对话框中的链接。 */
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

    /** `NSApplicationActivateAllWindows`。 */
    private const val ACTIVATE_ALL_WINDOWS = 1L shl 0

    /** `NSApplicationActivateIgnoringOtherApps`。 */
    private const val ACTIVATE_IGNORING_OTHER_APPS = 1L shl 1
}
