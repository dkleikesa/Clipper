package com.qcmian.clipper.core.data.source

import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.SourceApplication
import com.qcmian.clipper.core.settings.ShortcutSpec

/**
 * 可选的原生能力。每一项都有安全的默认实现，因此没有等价能力的平台只需报告
 * 「不可用」，而不是直接失败。
 *
 * 只能存在于某个 JVM 平台上的集成——例如基于 Carbon 的全局热键——刻意放在该平台源集
 * （`com.qcmian.clipper.core.platform.macos`）里，而不在这里，这样这个公共契约永远不会描述诸如
 * 虚拟键码这类只属于 macOS 的概念。
 */
interface NativeDataSource {
    /** [frontmostApplication] 是否能返回有用的结果。 */
    val supportsApplicationInfo: Boolean get() = false

    /** [recognizeText] 是否能返回有用的结果。 */
    val supportsTextRecognition: Boolean get() = false

    /** [setLaunchAtLogin] 是否能做任何事。 */
    val supportsLaunchAtLogin: Boolean get() = false

    /**
     * 宿主是否能注册系统级热键（`⌘⇧C` 那类在别的应用里也生效的快捷键）。
     *
     * 不能的平台既不要去注册，也不该拦住用户录制——那只是这些快捷键没有系统级效果。
     */
    val supportsGlobalHotKeys: Boolean get() = false

    /** 弹窗可以锚定的屏幕数量；至少为 1。 */
    val screenCount: Int get() = 1

    /** 此前处于最前的应用，也就是用户复制内容的来源应用。 */
    fun frontmostApplication(): SourceApplication? = null

    /**
     * 处理事件触发时按下的修饰键，对应 `NSApp.currentEvent.modifierFlags`。
     * 平台无法给出时返回 `0`。
     */
    fun currentModifierFlags(): Int = 0

    /** 应用图标的 base64 编码 PNG，按 bundle id 查找。 */
    fun applicationIcon(bundleId: String?): String? = null

    /**
     * 试注册一次 [shortcut]，判断它是否已被系统或其它应用占用。
     *
     * 这是唯一可靠的判断方式：平台没有「这个组合被谁注册了」的查询接口。注册随即注销，
     * 不会真的留下这个热键。平台无法判断时返回 `true`——无从判断，也不该拦住用户。
     */
    fun isGlobalShortcutAvailable(shortcut: ShortcutSpec): Boolean = true

    /** 在图片中识别出的文字，用作图片条目的标题。 */
    suspend fun recognizeText(image: ClipImage): String? = null

    /**
 * 将应用注册 / 注销为开机自启项， 包。
     * 尽力而为：没有等价能力的平台什么都不做。
     */
    fun setLaunchAtLogin(enabled: Boolean) {}
}

expect fun createNativeDataSource(): NativeDataSource

/** 各平台共用的空实现，用于不覆盖某项能力的情况。 */
internal object UnsupportedNativeDataSource : NativeDataSource
