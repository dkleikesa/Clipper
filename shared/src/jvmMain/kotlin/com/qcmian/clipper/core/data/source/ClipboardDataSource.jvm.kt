package com.qcmian.clipper.core.data.source

import java.awt.Image
import java.awt.Robot
import java.awt.Toolkit
import java.awt.datatransfer.Clipboard
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipboardSnapshot
import com.qcmian.clipper.core.platform.macos.MacKeyboard
import com.qcmian.clipper.core.platform.macos.MacPasteboard
import com.qcmian.clipper.core.settings.AppSettings

/**
 * 基于 AWT 的桌面端（JVM）剪贴板支持。
 *
 * 剪贴板由定时器轮询，而不依赖所有权通知——
 * 后者在不同桌面环境下行为并不一致。
 *
 * 轮询刻意跑在 [Dispatchers.IO] 而不是 `Dispatchers.Swing`。这条循环里全是阻塞式原生调用
 * （JNA 读 `NSPasteboard`，内容变化时还要读整幅图片并做 PNG 编码），放在 EDT 上会阻塞界面；
 * 而且 `SwingDispatcher` 每次 `delay()` 都新建一个 `javax.swing.Timer`，
 * 于是每 500ms 就要唤醒 Swing 的 `TimerQueue` 线程、再向 EDT 投递一个事件。
 *
 * 写入方向（[write]）仍由调用方线程执行，不受影响。
 */
private class JvmClipboardDataSource : ClipboardDataSource {
    private val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 轮询协程与 [start] / [stop] 分属不同线程，读写都需要可见性保证。
     */
    @Volatile
    private var listener: ((ClipboardSnapshot) -> Unit)? = null

    private var pollJob: Job? = null

    /** 同上：轮询协程与 [clear] 分属不同线程。 */
    @Volatile
    private var lastFingerprint: String? = null

    /** macOS 上 `NSPasteboard.changeCount` 的上次读数；`-1` 表示未知（退化为全量读取）。 */
    @Volatile
    private var lastChangeCount = -1L

    override var pollIntervalMillis: Long = ClipboardDataSource.DEFAULT_POLL_INTERVAL_MILLIS

    override fun write(snapshot: ClipboardSnapshot): Boolean {
        val text = snapshot.text
        val image = snapshot.image
            ?.let { runCatching { ImageIO.read(it.toByteArray().inputStream()) }.getOrNull() }
        val files = snapshot.files.map { File(it) }.filter { it.exists() }

        if (text == null && image == null && files.isEmpty() && snapshot.contents.isEmpty()) return false

        // 这里刻意*不*刷新指纹：轮询循环会观察到这次写入，仓库随后把选中的条目合并一次
        // （次数 +1、`lastCopiedAt` 前移）并排到最前面，行为与系统剪贴板的观察者预期一致。
        val written = runCatching {
            clipboard.setContents(ClipTransferable(text, image, files), null)
        }.isSuccess
        // AWT 只写了 text / image / file 三种，这里把 HTML / RTF 等额外类型原样补回剪贴板，
        // 目标应用才会优先取富格式而非纯文本。
        if (written && isMacOs() && MacPasteboard.available && snapshot.contents.isNotEmpty()) {
            MacPasteboard.writeAdditionalTypes(snapshot.contents)
        }
        return written
    }

    override fun start(onChange: (ClipboardSnapshot) -> Unit) {
        listener = onChange
        lastFingerprint = fingerprint(readSnapshot())
        if (isMacOs()) lastChangeCount = MacPasteboard.changeCount()
        pollJob = scope.launch {
            while (isActive) {
                delay(pollIntervalMillis)
                // macOS 上先读 `changeCount`（一个 int）做快速比对：变化即一次复制，
                // 内容相同也算——重复制交给捕获层合并并累加次数。
                // 本应用自己写入的选中走的也是这条路：仓库随后把它合并一次、排到最前。
                if (isMacOs()) {
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
                }
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
     * macOS 上优先走 [MacKeyboard]：⌘ 直接写在 `V` 的按下 / 抬起事件上，
     * 不依赖「⌘ 是不是已经作为一个独立事件生效」（[Robot] 只能靠后者，偶尔会漏掉修饰键，
     * 于是目标应用收到一个裸的 `v`）。
     *
     * 其余平台，以及框架 / 符号不可用时，退回 [Robot]：按下 Ctrl（macOS 上是 ⌘）与 `V`，
     * 再逆序放开。
     */
    override fun paste(): Boolean {
        if (isMacOs() && MacKeyboard.available) return MacKeyboard.sendCommandKey()
        return runCatching {
            val robot = Robot()
            val modifier = if (isMacOs()) KeyEvent.VK_META else KeyEvent.VK_CONTROL
            robot.keyPress(modifier)
            robot.keyPress(KeyEvent.VK_V)
            robot.keyRelease(KeyEvent.VK_V)
            robot.keyRelease(modifier)
            true
        }.getOrDefault(false)
    }

    /** 向此前聚焦的应用按一次裸回车；与 [paste] 同一条投递路径，只是事件上不带 `⌘`。 */
    override fun pressReturn(): Boolean {
        if (isMacOs() && MacKeyboard.available) return MacKeyboard.sendReturn()
        return runCatching {
            val robot = Robot()
            robot.keyPress(KeyEvent.VK_ENTER)
            robot.keyRelease(KeyEvent.VK_ENTER)
            true
        }.getOrDefault(false)
    }

    private fun readSnapshot(): ClipboardSnapshot {
        val flavors = runCatching { clipboard.availableDataFlavors.toList() }.getOrDefault(emptyList())

        // macOS 上额外用原生 API 读出全部类型的原始字节：AWT 只暴露 text / image / file 三种，
        // HTML、RTF、PDF 等富格式会在这里被保留下来，而不是随纯文本一起丢掉。
        val nativeAvailable = isMacOs() && MacPasteboard.available
        val nativeContents = if (nativeAvailable) MacPasteboard.readContents() else emptyList()

        val text = readText(flavors)
        val image = readImage(flavors)
        val files = readFiles(flavors)

        // 原生路径用 UTI 标识类型（让「始终忽略」的 transient / concealed / autoGenerated
        // 真正生效）；AWT 路径只能给出它自己的 mime 类型。
        val types = if (nativeAvailable) nativeContents.map { it.type } else flavors.map { it.mimeType }

        // contents 保留 text / image / files 覆盖不到、也不属于动态 / 内部 / 忽略标记的原始类型。
        val contents = if (nativeAvailable) nativeContents.filter { keepAsContent(it.type) } else emptyList()

        return ClipboardSnapshot(text = text, image = image, files = files, types = types, contents = contents)
    }

    /** 该类型是否应作为「额外内容」保留——即没有被 text / image / files 单独建模。 */
    private fun keepAsContent(type: String): Boolean =
        type !in TEXT_TYPES &&
            type !in IMAGE_TYPES &&
            type !in FILE_TYPES &&
            !type.startsWith(DYNAMIC_TYPE_PREFIX) &&
            !type.startsWith(OLE_SOURCE_PREFIX) &&
            type !in AppSettings.ALWAYS_IGNORED_PASTEBOARD_TYPES

    private fun readText(flavors: List<DataFlavor>): String? {
        val flavor = flavors.firstOrNull { it == DataFlavor.stringFlavor }
            ?: flavors.firstOrNull { it.isFlavorTextType }
            ?: return null
        return runCatching { clipboard.getData(flavor) as? String }.getOrNull()
    }

    private fun readImage(flavors: List<DataFlavor>): ClipImage? {
        if (flavors.none { it == DataFlavor.imageFlavor }) return null
        val image = runCatching { clipboard.getData(DataFlavor.imageFlavor) as? Image }.getOrNull() ?: return null
        return runCatching {
            val output = ByteArrayOutputStream()
            ImageIO.write(toBufferedImage(image), "png", output)
            ClipImage(output.toByteArray())
        }.getOrNull()
    }

    private fun readFiles(flavors: List<DataFlavor>): List<String> {
        if (flavors.none { it == DataFlavor.javaFileListFlavor }) return emptyList()
        @Suppress("UNCHECKED_CAST")
        val files = runCatching {
            clipboard.getData(DataFlavor.javaFileListFlavor) as? List<File>
        }.getOrNull()
        return files.orEmpty().map { it.absolutePath }
    }

    private fun toBufferedImage(image: Image): BufferedImage {
        if (image is BufferedImage) return image
        val width = image.getWidth(null).coerceAtLeast(1)
        val height = image.getHeight(null).coerceAtLeast(1)
        val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = buffered.createGraphics()
        try {
            graphics.drawImage(image, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return buffered
    }

    private fun isMacOs(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("mac")

    private companion object {
        /** 已被 [text] 单独建模的纯文本类型。 */
        private val TEXT_TYPES = setOf(
            "public.utf8-plain-text",
            "public.utf8-tab-separated-values-text",
            "public.utf16-plain-text",
            "public.utf8-external-plain-text",
            "public.text",
            "public.plain-text",
            "NSStringPboardType",
        )

        /** 已被 [image] 单独建模的图片类型。 */
        private val IMAGE_TYPES = setOf(
            "public.png",
            "public.tiff",
            "public.jpeg",
            "public.heic",
            "com.apple.icns",
        )

        /** 已被 [files] 单独建模的文件类型。 */
        private val FILE_TYPES = setOf("public.file-url")

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

/** 携带该条目拥有的每一种表示，使其能粘贴进任何应用。 */
private class ClipTransferable(
    private val text: String?,
    private val image: BufferedImage?,
    private val files: List<File>,
) : Transferable {
    override fun getTransferDataFlavors(): Array<DataFlavor> = buildList {
        if (image != null) add(DataFlavor.imageFlavor)
        if (text != null) add(DataFlavor.stringFlavor)
        if (files.isNotEmpty()) add(DataFlavor.javaFileListFlavor)
    }.toTypedArray()

    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean =
        getTransferDataFlavors().any { it == flavor }

    override fun getTransferData(flavor: DataFlavor): Any = when {
        flavor == DataFlavor.imageFlavor && image != null -> image
        flavor == DataFlavor.stringFlavor && text != null -> text
        flavor == DataFlavor.javaFileListFlavor && files.isNotEmpty() -> files
        else -> throw UnsupportedFlavorException(flavor)
    }
}

actual fun createClipboardDataSource(): ClipboardDataSource = JvmClipboardDataSource()
