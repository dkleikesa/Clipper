package com.qcmian.clipper.core.data.source

import java.nio.charset.Charset
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.domain.model.FILE_URL_CONTENT_TYPE
import com.qcmian.clipper.core.domain.model.toClipImage
import com.qcmian.clipper.core.platform.macos.MacKeyboard
import com.qcmian.clipper.core.platform.macos.MacNative
import com.qcmian.clipper.core.platform.macos.MacPasteboard
import com.qcmian.clipper.core.settings.AppSettings
import com.qcmian.clipper.core.util.imageFormatOf

/** 纯文本类型的现代标准名；剪贴板上的纯文本表示几乎都是它。 */
private const val PLAIN_TEXT_TYPE = "public.utf8-plain-text"

/** 文件 URL 类型；类型名定义在领域层（[FILE_URL_CONTENT_TYPE]），这里只是平台侧的短名。 */
private const val FILE_URL_TYPE = FILE_URL_CONTENT_TYPE

/**
 * 文件图标类型。
 *
 * Finder 一类应用复制文件时会在粘贴板上声明它（实测约 830 KB）。**不保留**：它不是用户复制的
 * 内容，目标应用会自己生成图标，而收下它会让每次「复制文件」都白存几百 KB。
 */
private const val ICON_CONTENT_TYPE = "com.apple.icns"

/**
 * macOS 剪贴板支持，**整条链路都经 `NSPasteboard` 原生 API**。
 *
 * 为什么不再用 AWT：`java.awt.datatransfer` 只看得见 text / image / file 三种表示，拿不到
 * HTML / RTF 的原始字节；更要命的是它读图片时**先解码成像素、再按 PNG 重新编码**——用户复制的
 * 原图因此被改写。「复制什么格式、粘贴还是什么格式」要求逐类型搬运原始字节，AWT 的表达力
 * 做不到，所以整条链路改走原生。
 *
 * 由轮询驱动：macOS 没有可用的剪贴板变更通知，所有剪贴板工具都靠轮询。轮询只读 `changeCount`
 * （一个整数）做快速比对，内容本身仅在变化时才读。
 *
 * 轮询刻意跑在 [Dispatchers.IO] 而不是 `Dispatchers.Swing`：这条循环里全是阻塞式原生调用
 * （JNA 读 `NSPasteboard`），放在 EDT 上会阻塞界面；而且 `SwingDispatcher` 每次 `delay()` 都
 * 新建一个 `javax.swing.Timer`，于是每 500ms 就要唤醒 Swing 的 `TimerQueue` 线程。
 *
 * **本类只针对 macOS**；其他平台的实现后续再补。
 */
internal class MacClipboardDataSource : ClipboardDataSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 轮询协程与 [start] / [stop] 分属不同线程，读写都需要可见性保证。 */
    @Volatile
    private var listener: ((ClipboardSnapshot) -> Unit)? = null

    private var pollJob: Job? = null

    /** 同上：轮询协程与 [stop] 分属不同线程。 */
    @Volatile
    private var lastFingerprint: String? = null

    /** `NSPasteboard.changeCount` 的上次读数；`-1` 表示未知（退化为全量读取）。 */
    @Volatile
    private var lastChangeCount = -1L

    override var pollIntervalMillis: Long = ClipboardDataSource.DEFAULT_POLL_INTERVAL_MILLIS

    /**
     * 把快照逐类型搬进系统剪贴板，**不改写任何字节**。
     *
     * 与旧实现（AWT 写 text / image / file，再由原生补写附加类型）的关键差别：没有中间层。
     * 图片按它自己的原始格式（PNG / TIFF / …）写入，而不是先解码成像素、再编回某个格式。
     */
    override fun write(snapshot: ClipboardSnapshot): Boolean {
        // 快照自带原始表示时**逐类型原样写回**——这是「复制什么格式、粘贴还是什么格式」的终点；
        // 否则它是一份合成快照（复制搜索词、多条合并、去格式复制），从那几个便利字段生成内容。
        val contents = snapshot.contents.ifEmpty { snapshot.synthesizedContents() }
        if (contents.isEmpty()) return false
        return MacPasteboard.write(contents)
    }

    override fun start(onChange: (ClipboardSnapshot) -> Unit) {
        listener = onChange
        lastFingerprint = fingerprint(readSnapshot())
        lastChangeCount = MacPasteboard.changeCount()
        pollJob = scope.launch {
            while (isActive) {
                delay(pollIntervalMillis)
                // 先读 `changeCount`（一个 int）做快速比对：变化即一次复制，内容相同也算
                // ——重复制交给捕获层合并并累加次数。本应用自己写入的选中走的也是这条路：
                // 仓库随后把它合并一次、排到最前。
                val changeCount = MacPasteboard.changeCount()
                if (changeCount >= 0) {
                    if (changeCount == lastChangeCount) continue
                    lastChangeCount = changeCount
                    val snapshot = readSnapshot()
                    if (snapshot.isEmpty) continue
                    lastFingerprint = fingerprint(snapshot)
                    listener?.invoke(snapshot)
                    continue
                }
                // changeCount 读不到（返回 -1）时退化为内容指纹比对。
                val snapshot = readSnapshot()
                if (snapshot.isEmpty) continue
                val current = fingerprint(snapshot)
                if (current == lastFingerprint) continue
                lastFingerprint = current
                listener?.invoke(snapshot)
            }
        }
    }

    override fun stop() {
        pollJob?.cancel()
        pollJob = null
        listener = null
    }

    /**
     * 尽力向此前聚焦的应用按一次「粘贴」。
     *
     * `⌘` 直接写在 `V` 的按下 / 抬起事件上，不依赖「⌘ 是不是已经作为一个独立事件生效」。
     * 原生键盘桥不可用（框架 / 符号缺失）时返回 `false`——调用方据此提示「本平台不支持粘贴」，
     * 而不是静默地什么都没发生。
     */
    override fun paste(): Boolean = MacKeyboard.available && MacKeyboard.sendCommandKey()

    /** 向此前聚焦的应用按一次裸回车；与 [paste] 同一条投递路径，只是事件上不带 `⌘`。 */
    override fun pressReturn(): Boolean = MacKeyboard.available && MacKeyboard.sendReturn()

    private fun readSnapshot(): ClipboardSnapshot {
        // 原生一次读出全部类型：后续每个字段都从它派生。不值得留存的类型（动态标记、密码
        // 管理器声明的类型等）只收集名字、不读字节——那些字节本来就要丢，读它只是白拷一遍。
        val raw = MacPasteboard.readContents(keepBytes = ::keepAsContent)
        return ClipboardSnapshot(
            // 逐类型保留原始表示（只滤掉安全底线与无留存价值的动态标记，见 [keepAsContent]）。
            // 被跳过字节的类型一定也通不过 `keepAsContent`（两者是同一个判断），因此这一步顺带
            // 把「只记了名字」的条目筛掉。
            contents = raw.filter { keepAsContent(it.type) },
            // 以下三项都是**派生便利视图**，供领域层派生标题 / 类型 / 判空；不参与存储与写回。
            text = readText(raw),
            image = raw.toClipImage(),
            files = readFiles(raw),
            // 全部类型名（含被滤掉的那些）：捕获层据此识别「密码管理器声明的内容」。
            types = raw.map { it.type },
        )
    }

    /**
     * 纯文本。
     *
     * **不能把解码交给系统**（`NSPasteboard.stringForType:`）：实测它对
     * `public.utf16-plain-text` 返回的是把 UTF-16 字节当单字节字符解释出来的串（`你好` 得到
     * `` `O}Y ``）。因此这里自己按类型解——先找 UTF-8 表示（系统几乎总会写一份，也是唯一能
     * 直接按 UTF-8 解码的），没有才按各类型自己的编码解。
     */
    internal fun readText(contents: List<ClipboardContent>): String? {
        contents.firstOrNull { it.type in MacPasteboard.UTF8_TEXT_TYPES }?.value?.let { return it.decodeToString() }

        val fallback = contents.firstOrNull { it.type in MacPasteboard.TEXT_TYPES } ?: return null
        val bytes = fallback.value ?: return null
        return decodeText(fallback.type, bytes)
    }

    /**
     * 文件：`public.file-url` 的字节是 `file:///…`，交给 `NSURL` 解析成路径。
     *
     * 不手写 percent-decoding：空格、中文、`#`、`?` 这些字符的转义规则不少，
     * 而 `NSURL` 已经处理好，并且系统给的 URL 也会随系统版本变化。
     */
    private fun readFiles(contents: List<ClipboardContent>): List<String> =
        contents.filter { it.type == FILE_URL_TYPE }
            .mapNotNull { it.value?.decodeToString() }
            .mapNotNull(::pathFromFileUrl)

    /**
     * 该类型是否值得保留原始字节。
     *
     * 这个判断**同时决定读不读**：`readContents` 拿它当 `keepBytes`，判 `false` 的类型连
     * `dataForType:` 都不调（见 [MacPasteboard.readContents]）。
     *
     * **文本 / 图片 / 文件都不排除**——它们同样是剪贴板上的原始表示，要原样保存、原样写回。
     * 剩下三类排除各有理由：
     * - 安全底线：密码管理器声明的 transient / concealed / autoGenerated，任何情况下都不记录；
     * - 无留存价值：`dyn.*` 与 OLE 源是提供方每次变化的内部标记。它们还**必须**排除——留在
     *   `contentKeyOf` 的输入里会让同一份内容每次算出不同的摘要，去重直接失效；
     * - 文件图标（[ICON_CONTENT_TYPE]）：实测 Finder 复制一个普通文件时，粘贴板在
     *   `NSPasteboard.types` 上声明了约 830 KB 的 icns，外加一整套 TIFF / BMP / 8BPS / PNG
     *   等图标图像（合计约 11 MB）。这些**都不在 `pasteboardItems` 里**，正常路径下本来就
     *   读不到——这条排除守的是「万一它们成了真实载荷」以及退化路径（`pasteboardItems` 为空
     *   时改从 `types` 读）。留着它们体积大，又会把「复制文件」变成「存了一张图」。
     */
    internal fun keepAsContent(type: String): Boolean =
        type != ICON_CONTENT_TYPE &&
            !type.startsWith(DYNAMIC_TYPE_PREFIX) &&
            !type.startsWith(OLE_SOURCE_PREFIX) &&
            type !in AppSettings.ALWAYS_IGNORED_PASTEBOARD_TYPES

    private companion object {
        /** 动态类型前缀；其内容随提供方每次变化，没有留存价值。 */
        private const val DYNAMIC_TYPE_PREFIX = "dyn."

        /** 微软 OLE 对象源前缀；与 Word 书签 / 交叉引用相关，属内部实现细节。 */
        private const val OLE_SOURCE_PREFIX = "com.microsoft.ole.source."

        fun fingerprint(snapshot: ClipboardSnapshot): String = buildString {
            append(snapshot.text.orEmpty())
            append('\u0000')
            append(snapshot.image?.size ?: 0)
            append('\u0000')
            append(snapshot.files.joinToString("\u0001"))
            append('\u0000')
            append(snapshot.contents.joinToString("\u0001") { "${it.type}:${it.size}:${it.hashCode()}" })
        }
    }
}

/**
 * **合成**快照 → 剪贴板内容。
 *
 * 只在快照没有原始表示时使用：「复制搜索词」「多条合并」「去格式复制」这几条路径交给数据源的
 * 就是文本 / 文件路径本身，没有原始字节可搬。文件路径在这里展开成 `file://` URL——剪贴板上
 * 的文件表示本来就是 URL，路径只是库里存的形式。
 */
private fun ClipboardSnapshot.synthesizedContents(): List<ClipboardContent> = buildList {
    text?.let { add(ClipboardContent(PLAIN_TEXT_TYPE, it.encodeToByteArray())) }
    image?.let { imageContentOf(it)?.let(::add) }
    for (path in files) {
        fileUrlOf(path)?.let { add(ClipboardContent(FILE_URL_TYPE, it.encodeToByteArray())) }
    }
}

/** 把图片的原始字节连同它推断出的类型包成一条内容，供**原样**写回。 */
private fun imageContentOf(image: ClipImage): ClipboardContent? {
    val bytes = image.toByteArray()
    val type = imageFormatOf(bytes).uti ?: return null
    return ClipboardContent(type, bytes)
}

/** 路径 → `file://` URL；转义规则交给 `NSURL`。 */
private fun fileUrlOf(path: String): String? = MacNative.autoreleasePool {
    val urlClass = MacNative.clazz("NSURL") ?: return@autoreleasePool null
    val source = MacNative.nsString(path) ?: return@autoreleasePool null
    val url = MacNative.send(urlClass, "fileURLWithPath:", source)
    MacNative.send(source, "release")
    MacNative.string(MacNative.send(url, "absoluteString"))
}

/** UTF-16 纯文本。 */
private const val UTF16_TEXT_TYPE = "public.utf16-plain-text"

/** 同上，只是名字里多一个 `external`；实测 Finder 复制文件时用它放文件名列表。 */
private const val UTF16_EXTERNAL_TEXT_TYPE = "public.utf16-external-plain-text"

/** 传统 Mac 编码的纯文本；繁体中文环境下是 Big5。 */
private const val TRADITIONAL_TEXT_TYPE = "com.apple.traditional-mac-plain-text"

/** 按类型解纯文本；解不出来时返回 `null`。 */
internal fun decodeText(type: String, bytes: ByteArray): String? = runCatching {
    when (type) {
        UTF16_TEXT_TYPE, UTF16_EXTERNAL_TEXT_TYPE -> decodeUtf16(bytes)
        TRADITIONAL_TEXT_TYPE -> String(bytes, Charset.forName("Big5"))
        else -> bytes.decodeToString()
    }
}.getOrNull()

/**
 * 解 UTF-16 字节。
 *
 * 有 BOM 就按 BOM 走：实测 Finder 放文件名列表用的 `public.utf16-external-plain-text` 是
 * `ff fe` 开头的小端。没有 BOM 时按**小端**——实测系统写出的 `public.utf16-plain-text` 就是
 * 小端（`你好` 的字节是 `60 4F 7D 59`）。
 */
internal fun decodeUtf16(bytes: ByteArray): String {
    val hasBom = bytes.size >= 2 &&
        ((bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) ||
            (bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()))
    val littleEndian = !hasBom || bytes[0] == 0xFF.toByte()
    val offset = if (hasBom) 2 else 0
    val charset = Charset.forName(if (littleEndian) "UTF-16LE" else "UTF-16BE")
    return String(bytes, offset, bytes.size - offset, charset)
}

/**
 * `file://` URL → 路径。
 *
 * **必须交给 `NSURL`**：Finder 复制文件时写的是「文件引用 URL」（`file:///.file/id=…`，按
 * inode 而不是路径指向文件），只有 `NSURL` 认得这种形式——`java.net.URI` 会把它当成一个字面
 * 路径 `/.file/id=…`（实测如此）。
 *
 * 代价是 `NSURL.path` 顺带把符号链接也解析掉：`/var/…` 变成 `/private/var/…`、`/tmp/…`
 * 变成 `/private/tmp/…`。两者指向同一个文件，因此不影响打开、去重与写回。
 */
private fun pathFromFileUrl(url: String): String? = MacNative.autoreleasePool {
    val urlClass = MacNative.clazz("NSURL") ?: return@autoreleasePool null
    val source = MacNative.nsString(url) ?: return@autoreleasePool null
    val parsed = MacNative.send(urlClass, "URLWithString:", source)
    MacNative.send(source, "release")
    MacNative.string(MacNative.send(parsed, "path"))
}

actual fun createClipboardDataSource(): ClipboardDataSource = MacClipboardDataSource()
