package com.qcmian.clipper.core.platform.macos

import com.qcmian.clipper.core.domain.model.SourceApplication
import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Pointer

/**
 * 依赖的两个 `NSWorkspace` 查询：最前应用，
 * 以及按 bundle id 定位的应用的 URL。
 */
object MacWorkspace {
    private val loaded: Boolean = run {
        val foundation = MacNative.loadFramework("/System/Library/Frameworks/Foundation.framework/Foundation")
        val appKit = MacNative.loadFramework("/System/Library/Frameworks/AppKit.framework/AppKit")
        foundation || appKit
    }

    /**
     * 本进程 pid。
     *
     * 公开出来是因为「合成的 ⌘V 该投给谁」需要知道「自己是谁」：开发者工具窗口是一个正常的
     * 编辑面，它自己在最前时粘贴应当落在它的光标处，而不是回头找上一次那个外部应用
     * （见 `MacKeyboard.pasteTargetPid`）。
     */
    val ownPid: Long = runCatching { ProcessHandle.current().pid() }.getOrDefault(-1L)

    /** `NSWorkspace.shared.frontmostApplication`。 */
    fun frontmostApplication(): SourceApplication? {
        if (!loaded) return null
        val application = MacNative.send(sharedWorkspace(), "frontmostApplication") ?: return null
        // Clipper 自身处于最前时产生的复制不归属任何应用，
        // 等价于给粘贴板打上「来自本应用」标记的效果。
        if (MacNative.sendLong(application, "processIdentifier") == ownPid) return null

        val name = MacNative.string(MacNative.send(application, "localizedName"))?.trim()
        if (name.isNullOrEmpty()) return null
        val bundleId = MacNative.string(MacNative.send(application, "bundleIdentifier"))
        return SourceApplication(name = name, bundleId = bundleId)
    }

    /** `NSWorkspace.shared.frontmostApplication.processIdentifier`。 */
    private fun frontmostPid(): Long {
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
     * `NSApplication.setActivationPolicy:`：控制应用要不要出现在 Dock（以及 ⌘Tab）里。
     *
     * 本应用默认是菜单栏应用（`LSUIElement` → `NSApplicationActivationPolicyAccessory`），
     * 平时不占 Dock。开发者工具窗口出现时临时切到 `Regular`——它是一个正经的编辑面，在 Dock
     * 里露个图标既方便切回、也让「正在编辑」这件事有个系统层面的落点；窗口收起后再切回
     * `Accessory`（否则图标会一直挂着）。
     *
     * **必须在 AppKit 主线程上调用**，而 AWT 的 EDT 不是 AppKit 主线程（见 [MacStatusItem]），
     * 因此经 `performSelectorOnMainThread:` 派发：目标方法读 [pendingDockVisible] 再落地。
     * 不等待（`waitUntilDone` 为假）——调用方在窗口的显隐副作用里，阻塞没有意义。
     */
    fun setDockIconVisible(visible: Boolean) {
        if (!loaded) return
        pendingDockVisible = visible
        val target = dockTargetObject() ?: return
        MacNative.send(
            target,
            "performSelectorOnMainThread:withObject:waitUntilDone:",
            MacNative.selector(DOCK_SELECTOR),
            null,
            0.toByte(),
        )
    }

    /** 动态建出来的派发目标类名；与 [MacStatusItem] 各用各的，互不干扰。 */
    private const val DOCK_TARGET_CLASS = "ClipperDockPolicyTarget"

    /** AppKit 主线程上执行的选择器。 */
    private const val DOCK_SELECTOR = "applyDockPolicy:"

    /** `NSApplicationActivationPolicyRegular`：常规应用，Dock 里出现图标。 */
    private const val POLICY_REGULAR = 0L

    /** `NSApplicationActivationPolicyAccessory`：只在菜单栏里存在，不占 Dock。 */
    private const val POLICY_ACCESSORY = 1L

    /** 最近一次请求的目标状态；主线程上的目标方法读它。 */
    @Volatile
    private var pendingDockVisible: Boolean? = null

    /**
     * 主线程回调：把待定状态落成真正的 `setActivationPolicy:`。
     *
     * 函数指针会被写进运行时建的类，必须保活（同 [MacStatusItem] 的 `applyCallback`）。
     */
    private val dockApplyCallback: Callback = object : DockApplyCallback {
        override fun apply(self: Pointer?, command: Pointer?, argument: Pointer?) {
            val visible = pendingDockVisible ?: return
            val application = MacNative.send(MacNative.clazz("NSApplication"), "sharedApplication") ?: return
            MacNative.sendBool(
                application,
                "setActivationPolicy:",
                if (visible) POLICY_REGULAR else POLICY_ACCESSORY,
            )
        }
    }

    private interface DockApplyCallback : Callback {
        fun apply(self: Pointer?, command: Pointer?, argument: Pointer?)
    }

    private var dockTarget: Pointer? = null

    /** 建出（或复用）派发用的动态类实例；任一步失败返回 `null`，静默降级。 */
    private fun dockTargetObject(): Pointer? {
        dockTarget?.let { return it }
        val implementation = runCatching { CallbackReference.getFunctionPointer(dockApplyCallback) }.getOrNull()
            ?: return null
        val targetClass = MacNative.clazz(DOCK_TARGET_CLASS)
            ?: MacNative.allocateClass("NSObject", DOCK_TARGET_CLASS)?.also {
                MacNative.addMethod(it, DOCK_SELECTOR, implementation, "v@:@")
                MacNative.registerClass(it)
            }
            ?: return null
        val target = MacNative.send(MacNative.send(targetClass, "alloc"), "init")
        dockTarget = target
        return target
    }

    /**
     * 系统外观是否为深色；原生层不可用时返回 `null`。
     *
     * 读取 `NSUserDefaults` 的 `AppleInterfaceStyle`：深色时为 `"Dark"`，浅色时该键不存在。
     * Compose 的 `isSystemInDarkTheme()` 在桌面端不会实时跟随系统外观变化，
     * 因此「跟随系统」模式由轮询此值驱动。
     */
    fun isSystemAppearanceDark(): Boolean? {
        if (!loaded) return null
        val defaults = MacNative.send(MacNative.clazz("NSUserDefaults"), "standardUserDefaults")
            ?: return null
        val style = MacNative.send(defaults, "stringForKey:", APPLE_INTERFACE_STYLE_KEY)
            ?: return false
        return MacNative.string(style)?.equals("Dark", ignoreCase = true)
    }

    /**
     * `NSEvent.modifierFlags`，即当前正在处理的事件的修饰键。
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

    private fun sharedWorkspace(): Pointer? {
        val clazz = MacNative.clazz("NSWorkspace") ?: return null
        return MacNative.send(clazz, "sharedWorkspace")
    }

    /** `NSApplicationActivateAllWindows`。 */
    private const val ACTIVATE_ALL_WINDOWS = 1L shl 0

    /** `NSApplicationActivateIgnoringOtherApps`。 */
    private const val ACTIVATE_IGNORING_OTHER_APPS = 1L shl 1

    /** `AppleInterfaceStyle` 的 `NSString` 常量，只在类加载时分配一次（每次轮询 alloc 会缓慢泄漏）。 */
    private val APPLE_INTERFACE_STYLE_KEY = MacNative.nsString("AppleInterfaceStyle")
}
