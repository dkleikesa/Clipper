package com.qcmian.clipper.core.platform.macos

/**
 * `NSPasteboard.generalPasteboard` 的极轻量访问。
 *
 * macOS 没有第三方可用的剪贴板变更通知，所有剪贴板管理器都靠轮询；但轮询应当
 * 只读 `changeCount`（一个 int）做快速比对，内容本身只有在变化时才值得读。
 */
object MacPasteboard {
    /** `NSPasteboard.generalPasteboard.changeCount`；原生层不可用时返回 `-1`（视为未知）。 */
    fun changeCount(): Long {
        val pasteboard = MacNative.send(MacNative.clazz("NSPasteboard"), "generalPasteboard") ?: return -1
        return MacNative.sendLong(pasteboard, "changeCount")
    }
}
