package com.qcmian.clipper.core.platform.macos

import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.sun.jna.Pointer

/**
 * `NSPasteboard.generalPasteboard` 的轻量访问。
 *
 * macOS 没有第三方可用的剪贴板变更通知，所有剪贴板管理器都靠轮询；但轮询应当
 * 只读 `changeCount`（一个 int）做快速比对，内容本身只有在变化时才值得读。
 *
 * 读与写都**逐类型搬运原始字节**：富文本（HTML / RTF 等）与图片因此在进出剪贴板时都不被改写
 * ——「复制什么格式、粘贴还是什么格式」由这一层保证。
 */
object MacPasteboard {
    init {
        // `NSPasteboard` 在 AppKit 里。剪贴板这条路已不再经 AWT 初始化（见 `MacClipboardDataSource`），
        // 没有别的东西会替我们加载它——不加载的话 [available] 会一直是 `false`，整条链路静默失效。
        MacNative.loadFramework("/System/Library/Frameworks/AppKit.framework/AppKit")
    }

    /** 运行时是否能找到 `NSPasteboard`；不可用时（非 macOS 或运行时缺失）走降级路径。 */
    val available: Boolean get() = MacNative.clazz("NSPasteboard") != null

    /**
     * 承载纯文本的类型全集。
     *
     * **读取时**用它在内容里挑出文本表示（见 `MacClipboardDataSource.readText`）。这些类型的
     * 字节编码并不统一（UTF-8、UTF-16、传统 Mac 编码），因此**解码不交给系统**：
     * `NSPasteboard.stringForType:` 实测会把 UTF-16 解错（`你好` 得到 `` `O}Y ``）。
     */
    val TEXT_TYPES: Set<String> = setOf(
        "public.utf8-plain-text",
        "public.utf8-tab-separated-values-text",
        "public.utf16-plain-text",
        // 实测 Finder 复制文件时用它放文件名列表（实测字节是 `a.txt\nb.txt\n…` 的 UTF-16LE）。
        "public.utf16-external-plain-text",
        "public.utf8-external-plain-text",
        "public.text",
        "public.plain-text",
        "NSStringPboardType",
    )

    /**
     * [TEXT_TYPES] 里能**直接按 UTF-8 解码**的那些。
     *
     * 写入时只有它们走 `setString:forType:`——那个方法收 `NSString`，而 UTF-16 的字节按 UTF-8
     * 解出来是乱码（`你好` → `` `O}Y ``），`initWithUTF8String:` 还会在 NUL 处截断，于是这条
     * 表示就被写坏了。其余文本类型一律走 `setData:forType:`，字节原样落进去。
     */
    val UTF8_TEXT_TYPES: Set<String> = setOf(
        "public.utf8-plain-text",
        "public.utf8-tab-separated-values-text",
        "public.utf8-external-plain-text",
    )

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
     * 类型，逐个 item 读 `dataForType:` 才拿得到真实载荷。
     * `pasteboardItems` 读不到时退化为直接在粘贴板上读 `types` + `dataForType:`。
     *
     * 返回值只做「把字节安全拷出来」这一件事：`NSData.bytes` 是裸指针，只在当前
     * autorelease pool 内有效，因此 [MacNative.nsDataBytes] 会立即拷贝。类型过滤
     * （哪些与纯文本 / 图片 / 文件重叠、哪些属于动态或忽略类型）由数据源层负责。
     *
     * @param keepBytes 该类型的字节是否值得拷出来。返回 `false` 时**只收集类型名、不调
     *   `dataForType:`**：那个调用会真的把内容生成并拷出来，而值不值得留存是个纯字符串判断。
     *   名字必须留下——`ClipboardSnapshot.types` 要靠它识别「密码管理器声明的内容」；密码
     *   管理器声明的类型因此**连读都不读**，而不是读进来再丢。
     */
    fun readContents(keepBytes: (String) -> Boolean = { true }): List<ClipboardContent> =
        MacNative.autoreleasePool {
            val pasteboard = generalPasteboard() ?: return@autoreleasePool emptyList()
            val items = MacNative.array(MacNative.send(pasteboard, "pasteboardItems"))

            if (items.isEmpty()) {
                val types = MacNative.array(MacNative.send(pasteboard, "types"))
                types.mapNotNull { type ->
                    val typeName = MacNative.string(type) ?: return@mapNotNull null
                    val data = if (keepBytes(typeName)) {
                        MacNative.send(pasteboard, "dataForType:", type)
                    } else {
                        null
                    }
                    ClipboardContent(typeName, MacNative.nsDataBytes(data))
                }
            } else {
                val contents = ArrayList<ClipboardContent>()
                // 记下每个类型属于第几个 item：写回时要靠它还原「同类型出现多次」的结构。
                for ((index, item) in items.withIndex()) {
                    val types = MacNative.array(MacNative.send(item, "types"))
                    for (type in types) {
                        val typeName = MacNative.string(type) ?: continue
                        val data = if (keepBytes(typeName)) {
                            MacNative.send(item, "dataForType:", type)
                        } else {
                            null
                        }
                        contents += ClipboardContent(
                            type = typeName,
                            value = MacNative.nsDataBytes(data),
                            itemIndex = index,
                        )
                    }
                }
                contents
            }
        }

    /**
     * 清空剪贴板并把 [contents] 写进去；任一条失败即返回 `false`。
     *
     * 按 [ClipboardContent.itemIndex] **分组构造 `NSPasteboardItem`**，再用 `writeObjects:`
     * 一次性写入。不能直接对粘贴板调 `setData:forType:`：那是单 item 的旧 API，同类型出现多次时
     * （复制多个文件、多张图片）后写的会覆盖先写的，粘出来只剩最后一个。
     *
     * 每个 item 内部，**UTF-8 的纯文本**走 `setString:forType:`（对方读到的一定是字符串）、
     * 其余一律走 `setData:forType:`，字节原样落进去——**没有任何中间层改写它们**，这正是
     * 「粘贴还是原格式」的来源。UTF-16 等非 UTF-8 文本同样走 `setData:`，见 [UTF8_TEXT_TYPES]。
     *
     * 剪贴板写入是**复制语义**：内容一写进去就归系统所有，不像 X11 需要持有者进程存活，
     * 因此这里不需要 `owner`。
     */
    fun write(contents: List<ClipboardContent>): Boolean = MacNative.autoreleasePool {
        val pasteboard = generalPasteboard() ?: return@autoreleasePool false
        val itemClass = MacNative.clazz("NSPasteboardItem") ?: return@autoreleasePool false
        val items = MacNative.send(MacNative.clazz("NSMutableArray"), "array")
            ?: return@autoreleasePool false

        MacNative.sendLong(pasteboard, "clearContents")

        var allOk = true
        for (group in contents.groupBy { it.itemIndex }.toSortedMap().values) {
            val item = MacNative.send(MacNative.send(itemClass, "alloc"), "init") ?: continue
            for (content in group) {
                val typeName = MacNative.nsString(content.type) ?: continue
                val written = try {
                    if (content.type in UTF8_TEXT_TYPES) {
                        writeString(item, content.value, typeName)
                    } else {
                        writeData(item, content.value, typeName)
                    }
                } finally {
                    MacNative.send(typeName, "release")
                }
                if (!written) allOk = false
            }
            MacNative.send(items, "addObject:", item)
            MacNative.send(item, "release")
        }

        MacNative.sendBool(pasteboard, "writeObjects:", items) && allOk
    }

    /** 只对 [UTF8_TEXT_TYPES] 调用——[bytes] 必须真的是 UTF-8 字节。 */
    private fun writeString(item: Pointer, bytes: ByteArray?, typeName: Pointer): Boolean {
        val text = bytes?.decodeToString() ?: return false
        val value = MacNative.nsString(text) ?: return false
        return try {
            MacNative.sendBool(item, "setString:forType:", value, typeName)
        } finally {
            MacNative.send(value, "release")
        }
    }

    private fun writeData(item: Pointer, bytes: ByteArray?, typeName: Pointer): Boolean {
        val value = bytes?.let { MacNative.data(it) } ?: return false
        return try {
            MacNative.sendBool(item, "setData:forType:", value, typeName)
        } finally {
            MacNative.send(value, "release")
        }
    }
}
