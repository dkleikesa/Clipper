package com.qcmian.clipper.data.source

import com.qcmian.clipper.domain.model.SourceApplication

/** 屏幕坐标系中的一个矩形（点），用于「弹窗位于应用窗口」的定位方式。 */
data class ScreenRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * 可选的原生能力。每一项都有安全的默认实现，因此没有等价能力的平台只需报告
 * 「不可用」，而不是直接失败。
 *
 * 只能存在于某个 JVM 平台上的集成——例如基于 Carbon 的全局热键——刻意放在该平台源集
 * （`com.qcmian.clipper.macos`）里，而不在这里，这样这个公共契约永远不会描述诸如
 * 虚拟键码这类只属于 macOS 的概念。
 */
interface NativeDataSource {
    /** [frontmostApplication] 是否能返回有用的结果。 */
    val supportsApplicationInfo: Boolean get() = false

    /** [recognizeText] 是否能返回有用的结果。 */
    val supportsTextRecognition: Boolean get() = false

    /** [setLaunchAtLogin] 是否能做任何事。 */
    val supportsLaunchAtLogin: Boolean get() = false

    /** 弹窗可以锚定的屏幕数量；至少为 1。 */
    val screenCount: Int get() = 1

    /** 此前处于最前的应用，也就是用户复制内容的来源应用。 */
    fun frontmostApplication(): SourceApplication? = null

    /**
     * 最前应用主窗口的边界，对应 Maccy 的 `NSRunningApplication.windowFrame`。
     * 平台无法给出时返回 `null`。
     */
    fun frontmostWindowRect(): ScreenRect? = null

    /**
     * 处理事件触发时按下的修饰键，对应 `NSApp.currentEvent.modifierFlags`。
     * 平台无法给出时返回 `0`。
     */
    fun currentModifierFlags(): Int = 0

    /** 应用图标的 base64 编码 PNG，按 bundle id 查找。 */
    fun applicationIcon(bundleId: String?): String? = null

    /**
     * 应用的可读名称，按 bundle id 解析。对应 `NSWorkspace.applicationName(at:)`，
     * `IgnoreApplicationsSettingsView` 用它来标注忽略列表的条目。
     */
    fun applicationName(bundleId: String): String? = null

    /**
     * 打开平台的应用选择器并返回用户选择的应用。对应 Maccy 在忽略列表旁展示的
     * `.fileImporter(allowedContentTypes: [.application])`。平台没有选择器或用户取消时返回 `null`。
     */
    fun pickApplication(): SourceApplication? = null

    /** 用默认处理器打开 [url]，用于「关于」对话框里的链接。 */
    fun openUrl(url: String): Boolean = false

    /** 在编码后的图片中识别出的文字，用作图片条目的标题。 */
    suspend fun recognizeText(imageBase64: String): String? = null

    /**
     * 将应用注册 / 注销为开机自启项，对应 Maccy 的 `LaunchAtLogin` 包。
     * 尽力而为：没有等价能力的平台什么都不做。
     */
    fun setLaunchAtLogin(enabled: Boolean) {}
}

expect fun createNativeDataSource(): NativeDataSource

/** 各平台共用的空实现，用于不覆盖某项能力的情况。 */
internal object UnsupportedNativeDataSource : NativeDataSource
