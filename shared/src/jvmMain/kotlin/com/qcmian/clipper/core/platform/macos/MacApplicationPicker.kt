package com.qcmian.clipper.core.platform.macos

import com.qcmian.clipper.core.domain.model.SourceApplication
import java.io.File

/**
 * 放在忽略列表旁的 `.fileImporter(allowedContentTypes: [.application])`：
 * 一个限制只能选择应用包的 `NSOpenPanel`。
 *
 * 该面板在调用线程上以模态方式运行，对 Compose Desktop 来说就是 AWT 事件线程，
 * 因此直接从点击处理器里打开也是安全的。
 */
object MacApplicationPicker {
    private const val NS_MODAL_RESPONSE_OK = 1L

    private val loaded: Boolean = run {
        MacNative.loadFramework("/System/Library/Frameworks/Foundation.framework/Foundation")
        MacNative.loadFramework("/System/Library/Frameworks/AppKit.framework/AppKit")
    }

    /** 显示选择器并返回所选应用；取消时返回 `null`。 */
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

    /** 从选中的 `.app` 中读出 bundle 标识符与显示名。 */
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
