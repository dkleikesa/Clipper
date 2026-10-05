package com.qcmian.clipper.desktop.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Image
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import javax.imageio.ImageIO

/**
 * 开发者工具需要的平台活儿：弹原生文件对话框、从拖放里取出文件路径与图片、读剪贴板里的文件与图片。
 *
 * 放在这里而不是 `:devTools` 里：这些都要碰 AWT，而工具模块是 commonMain、只该拿到路径与字节
 * （见 `DevToolHost`）。读写文件本身不在这里——那一段走 kotlinx-io，留在工具侧。
 */

/** 弹「打开」对话框；取消时返回 `null`。 */
internal fun pickFileToOpen(parent: Frame): String? = pickFile(parent, FileDialog.LOAD, null)

/** 弹「保存」对话框，[suggestedName] 作为预填文件名；取消时返回 `null`。 */
internal fun pickFileToSave(parent: Frame, suggestedName: String): String? =
    pickFile(parent, FileDialog.SAVE, suggestedName)

private fun pickFile(parent: Frame, mode: Int, suggestedName: String?): String? {
    val dialog = FileDialog(parent, if (mode == FileDialog.SAVE) "保存" else "打开", mode)
    suggestedName?.let { dialog.file = it }
    // `isVisible = true` 会起一个嵌套事件循环等用户选完——这正是模态对话框该有的行为，
    // 期间界面照常绘制。取消时 `file` 为 `null`。
    dialog.isVisible = true
    val name = dialog.file ?: return null
    // 用户可能在对话框里直接敲了绝对路径，`File(dir, name)` 对这种情况同样给对结果。
    return File(dialog.directory ?: "", name).absolutePath
}

/**
 * 从一次拖放里取出被拖进来的文件路径。
 *
 * 拖的不是文件（例如拖一段选中的文字）时返回空表，让调用方把事件放过去——那种拖放应当由系统
 * 按「往文本框里拖文字」处理，而不是被工具吞掉。
 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun droppedFilePaths(event: DragAndDropEvent): List<String> = runCatching {
    val data = event.awtTransferable.getTransferData(DataFlavor.javaFileListFlavor)
    (data as? List<*>)?.filterIsInstance<File>()?.map { it.absolutePath }.orEmpty()
}.getOrDefault(emptyList())

/**
 * 系统剪贴板里放着的文件路径；剪贴板里不是文件时返回空表。
 *
 * 走 AWT 而不是 `MacPasteboard`：这条只在开发者工具窗口里用，而那个窗口本身就跑在 AWT/Compose 上，
 * AWT 早已初始化；`javaFileListFlavor` 由 macOS 从粘贴板上的 `public.file-url` 映射而来，这里够用。
 * （**捕获**剪贴板那条主路径仍然绕开 AWT，见 `MacClipboardDataSource`——那是为了不在应用启动早期
 * 就把 AWT 拖起来，与此处的场合不同。）
 */
internal fun clipboardFilePaths(): List<String> = runCatching {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    val data = if (clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor)) {
        clipboard.getData(DataFlavor.javaFileListFlavor)
    } else {
        null
    }
    (data as? List<*>)?.filterIsInstance<File>()?.map { it.absolutePath }.orEmpty()
}.getOrDefault(emptyList())

/**
 * 系统剪贴板里放着的**图片**字节；剪贴板里没有图片时返回 `null`。
 *
 * 与 [clipboardFilePaths] 同一分工（只在开发者工具窗口里用）。两者都问时调用方应当**先问文件**：
 * 在访达里复制一张图片，粘贴板上既有文件 URL、也有图片表示（图标）。
 */
internal fun clipboardImage(): ByteArray? = runCatching {
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    imageBytes(clipboard.availableDataFlavors.toList(), clipboard::getData)
}.getOrNull()

/** 从一次拖放里取出被拖进来的**图片**字节；拖的不是图片时返回 `null`。 */
@OptIn(ExperimentalComposeUiApi::class)
internal fun droppedImage(event: DragAndDropEvent): ByteArray? = runCatching {
    val transferable = event.awtTransferable
    imageBytes(transferable.transferDataFlavors.toList(), transferable::getTransferData)
}.getOrNull()

/**
 * 从一个粘贴板 / 拖放载荷里取图片字节；这里没有图片时返回 `null`。
 *
 * 一个粘贴板上可能同时躺着**好几条**图片表示（实测 macOS 上「复制图片」会给 `image/tiff` 与
 * `image/png` 两条，顺序还由写的一方定），所以这里排了先后，每一步都有它挡掉的东西：
 *
 *  1. **显示得出来的那几种具体格式**（[previewableImageTypes]）：原样搬字节——与剪贴板捕获那条
 *     主链路（`MacClipboardDataSource`：逐类型搬运原始字节）同一个取舍，用户复制的是什么格式，
 *     Base64 出来的就是什么格式；卡片也预览得出缩略图。挡掉的是 TIFF 一类**界面里根本画不出来**
 *     （且往往是未压缩、特别大）的表示。
 *  2. **`imageFlavor`（像素）**：只给得出像素时才解码一次、编成 PNG。这一路本来就没有「原来的
 *     格式」可言，而 PNG 无损、各处都认。
 *  3. 剩下任何 `image/…` 表示：照原样搬走。宁可 Base64 出来是一份用不上的格式，也别让用户按了粘贴
 *     却什么都没发生。
 *
 * 粘贴板与拖放载荷都从这两个口子取（[flavors] + [read]），而不是收一个 `Transferable`：AWT 的
 * `Clipboard` 并不实现那个接口，两者只有这两个方法长得一样。
 *
 * 做成 `internal` 是为了能单测：真粘贴板与真拖放事件都造不出来，而「哪条表示优先、读不出来怎么
 * 往下走」正是这里最容易写错、又最难在界面上看出来的部分（见 `DevToolsImageTest`）。
 */
internal fun imageBytes(flavors: List<DataFlavor>, read: (DataFlavor) -> Any?): ByteArray? {
    for (flavor in flavors) {
        // `imageFlavor` 不算：它给的是像素，不是一份现成的图片文件。
        if (flavor == DataFlavor.imageFlavor || !flavor.isPreviewableImage) continue
        readImageBytes(flavor, read)?.let { if (it.isNotEmpty()) return it }
    }

    flavors.firstOrNull { it == DataFlavor.imageFlavor }?.let { flavor ->
        val image = runCatching { read(flavor) as? Image }.getOrNull()
        image?.let { return pngBytes(it) }
    }

    for (flavor in flavors) {
        if (flavor == DataFlavor.imageFlavor || !flavor.isImageMimeType) continue
        readImageBytes(flavor, read)?.let { if (it.isNotEmpty()) return it }
    }
    return null
}

/** 读一条图片表示，拿到它的原始字节；这条表示读不出来时返回 `null`（由调用方继续往下找）。 */
private fun readImageBytes(flavor: DataFlavor, read: (DataFlavor) -> Any?): ByteArray? = runCatching {
    when (val data = read(flavor)) {
        is InputStream -> data.use { it.readBytes() }
        is ByteArray -> data
        else -> null
    }
}.getOrNull()

/**
 * 界面（Skia）画得出来、各家也都认的图片格式。
 *
 * 认不出来的（TIFF、HEIC、RAW…）宁可走「解码成像素再编 PNG」那一路：卡片能给出缩略图，Base64
 * 的结果也是别人打得开的。
 */
private val previewableImageTypes = setOf(
    "image/png",
    "image/jpeg",
    "image/jpg",
    "image/gif",
    "image/webp",
    "image/bmp",
)

/** 类型名形如 `image/png` 的表示。`imageFlavor` 也叫 `image/x-java-image`，由调用方另行排除。 */
private val DataFlavor.isImageMimeType: Boolean
    get() = mimeType.startsWith("image/", ignoreCase = true)

/** 这条表示的格式在 [previewableImageTypes] 里（`mimeType` 可能带参数，先掐掉）。 */
private val DataFlavor.isPreviewableImage: Boolean
    get() = mimeType.substringBefore(';').trim().lowercase() in previewableImageTypes

/** 把一张 AWT 图片编成 PNG 字节；尺寸拿不到或编不出来时返回 `null`。 */
private fun pngBytes(image: Image): ByteArray? {
    val width = image.getWidth(null)
    val height = image.getHeight(null)
    if (width <= 0 || height <= 0) return null
    val buffered = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = buffered.createGraphics()
    try {
        graphics.drawImage(image, 0, 0, null)
    } finally {
        graphics.dispose()
    }
    val output = ByteArrayOutputStream()
    return if (ImageIO.write(buffered, "png", output)) output.toByteArray() else null
}
