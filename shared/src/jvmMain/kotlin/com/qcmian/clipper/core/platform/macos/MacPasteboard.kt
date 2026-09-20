package com.qcmian.clipper.core.platform.macos

import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.sun.jna.Pointer

/**
 * `NSPasteboard.generalPasteboard` 的极轻量访问。
 *
 * macOS 没有第三方可用的剪贴板变更通知，所有剪贴板管理器都靠轮询；但轮询应当
 * 只读 `changeCount`（一个 int）做快速比对，内容本身只有在变化时才值得读。
 *
 * [readContents] 只有在 [changeCount] 变化时才调用，逐类型读出原始字节，
 * 让富文本（HTML/RTF 等）不至于在「只存纯文本」的路径里丢失。
 */
object MacPasteboard {
    /** 运行时是否能找到 `NSPasteboard`；不可用时（非 macOS 或运行时缺失）走降级路径。 */
    val available: Boolean get() = MacNative.clazz("NSPasteboard") != null

    private fun generalPasteboard(): Pointer? =
        MacNative.send(MacNative.clazz("NSPasteboard"), "generalPasteboard")

    /** `NSPasteboard.generalPasteboard.changeCount`；原生层不可用时返回 `-1`（视为未知）。 */
    fun changeCount(): Long {
        val pasteboard = generalPasteboard() ?: return -1
        return MacNative.sendLong(pasteboard, "changeCount")
    }

    /**
     * 读出剪贴板上每一种类型的原始字节。
     *
     * 优先走 `pasteboardItems`：`NSPasteboard.types` 会列出「声明了但当前条目里并不存在」的
     * 类型（见 Maccy #241），逐个 item 读 `dataForType:` 才拿得到真实载荷。
     * `pasteboardItems` 读不到时退化为直接在粘贴板上读 `types` + `dataForType:`。
     *
     * 返回值只做「把字节安全拷出来」这一件事：`NSData.bytes` 是裸指针，只在当前
     * autorelease pool 内有效，因此 [MacNative.nsDataBytes] 会立即拷贝。类型过滤
     * （哪些与纯文本 / 图片 / 文件重叠、哪些属于动态或忽略类型）由数据源层负责。
     */
    fun readContents(): List<ClipboardContent> = MacNative.autoreleasePool {
        val pasteboard = generalPasteboard() ?: return@autoreleasePool emptyList()
        val items = MacNative.array(MacNative.send(pasteboard, "pasteboardItems"))

        if (items.isEmpty()) {
            val types = MacNative.array(MacNative.send(pasteboard, "types"))
            types.mapNotNull { type ->
                val typeName = MacNative.string(type) ?: return@mapNotNull null
                val data = MacNative.send(pasteboard, "dataForType:", type)
                ClipboardContent(typeName, MacNative.nsDataBytes(data))
            }
        } else {
            val contents = ArrayList<ClipboardContent>()
            for (item in items) {
                val types = MacNative.array(MacNative.send(item, "types"))
                for (type in types) {
                    val typeName = MacNative.string(type) ?: continue
                    val data = MacNative.send(item, "dataForType:", type)
                    contents += ClipboardContent(typeName, MacNative.nsDataBytes(data))
                }
            }
            contents
        }
    }

    /**
     * 把额外的原始类型追加写进剪贴板，不触碰已有的内容。
     *
     * 用于「写回」方向：文本 / 图片 / 文件仍由 AWT 写入（已验证可靠），
     * 这里补上 HTML / RTF 等额外类型，让目标应用优先取富格式。
     */
    fun writeAdditionalTypes(contents: List<ClipboardContent>): Boolean =
        MacNative.autoreleasePool {
            val pasteboard = generalPasteboard() ?: return@autoreleasePool false
            var allOk = true
            for (content in contents) {
                val bytes = content.toByteArray() ?: continue
                val data = MacNative.data(bytes) ?: continue
                val type = MacNative.nsString(content.type) ?: continue
                if (!MacNative.sendBool(pasteboard, "setData:forType:", data, type)) allOk = false
            }
            allOk
        }
}
