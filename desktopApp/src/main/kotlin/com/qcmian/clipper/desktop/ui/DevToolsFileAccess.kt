package com.qcmian.clipper.desktop.ui

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.io.File

/**
 * 开发者工具需要的三件平台活儿：弹原生文件对话框、从拖放里取出文件路径、读剪贴板里的文件路径。
 *
 * 放在这里而不是 `:devTools` 里：两者都要碰 AWT，而工具模块是 commonMain、只该拿到路径
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
