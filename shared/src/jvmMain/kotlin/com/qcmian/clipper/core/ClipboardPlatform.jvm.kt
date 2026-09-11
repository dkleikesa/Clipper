package com.qcmian.clipper.core

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing

/**
 * Desktop (JVM) clipboard support built on AWT.
 *
 * Like Maccy, the clipboard is inspected on a timer instead of relying on ownership
 * notifications, which behave inconsistently across desktop environments.
 */
private class JvmClipboardPlatform : ClipboardPlatform {
    private val clipboard: Clipboard = Toolkit.getDefaultToolkit().systemClipboard
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Swing)

    private var listener: ((ClipboardSnapshot) -> Unit)? = null
    private var pollJob: Job? = null
    private var lastFingerprint: String? = null

    override var pollIntervalMillis: Long = ClipboardPlatform.DEFAULT_POLL_INTERVAL_MILLIS

    override val supportsFiles: Boolean get() = true

    override fun write(snapshot: ClipboardSnapshot): Boolean {
        val text = snapshot.text
        val image = snapshot.imageBase64
            ?.let { decodeBase64(it) }
            ?.let { bytes -> runCatching { ImageIO.read(bytes.inputStream()) }.getOrNull() }
        val files = snapshot.files.map { File(it) }.filter { it.exists() }

        if (text == null && image == null && files.isEmpty()) return false

        // The fingerprint is intentionally *not* refreshed here: the polling loop will
        // observe the write and the repository will move the selected item to the top,
        // which is exactly what Maccy does.
        return runCatching {
            clipboard.setContents(ClipTransferable(text, image, files), null)
        }.isSuccess
    }

    override fun clear() {
        runCatching { clipboard.setContents(null, null) }
        lastFingerprint = null
    }

    override fun start(onChange: (ClipboardSnapshot) -> Unit) {
        listener = onChange
        lastFingerprint = fingerprint(readSnapshot())
        pollJob = scope.launch {
            while (isActive) {
                delay(pollIntervalMillis)
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

    override fun paste(): Boolean = runCatching {
        val robot = Robot()
        val modifier = if (isMacOs()) KeyEvent.VK_META else KeyEvent.VK_CONTROL
        robot.keyPress(modifier)
        robot.keyPress(KeyEvent.VK_V)
        robot.keyRelease(KeyEvent.VK_V)
        robot.keyRelease(modifier)
        true
    }.getOrDefault(false)

    private fun readSnapshot(): ClipboardSnapshot {
        val flavors = runCatching { clipboard.availableDataFlavors.toList() }.getOrDefault(emptyList())
        if (flavors.isEmpty()) return ClipboardSnapshot()

        val text = readText(flavors)
        val imageBase64 = readImage(flavors)
        val files = readFiles(flavors)
        // AWT only exposes its own mime types, not the native pasteboard types, but that is
        // still enough for the "ignored pasteboard types" preference to be useful.
        val types = flavors.map { it.mimeType }
        return ClipboardSnapshot(text = text, imageBase64 = imageBase64, files = files, types = types)
    }

    private fun readText(flavors: List<DataFlavor>): String? {
        val flavor = flavors.firstOrNull { it == DataFlavor.stringFlavor }
            ?: flavors.firstOrNull { it.isFlavorTextType }
            ?: return null
        return runCatching { clipboard.getData(flavor) as? String }.getOrNull()
    }

    private fun readImage(flavors: List<DataFlavor>): String? {
        if (flavors.none { it == DataFlavor.imageFlavor }) return null
        val image = runCatching { clipboard.getData(DataFlavor.imageFlavor) as? Image }.getOrNull() ?: return null
        return runCatching {
            val output = ByteArrayOutputStream()
            ImageIO.write(toBufferedImage(image), "png", output)
            encodeBase64(output.toByteArray())
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
        fun fingerprint(snapshot: ClipboardSnapshot): String = buildString {
            append(snapshot.text.orEmpty())
            append('\u0000')
            append(snapshot.imageBase64?.length ?: 0)
            append('\u0000')
            append(snapshot.files.joinToString("\u0001"))
        }
    }
}

/** Carries every representation the item has, so pasting into any app works. */
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

actual fun createClipboardPlatform(): ClipboardPlatform = JvmClipboardPlatform()
