package com.qcmian.clipper.desktop

import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.domain.model.SourceApplication
import com.qcmian.clipper.core.domain.model.toMeta
import com.qcmian.clipper.core.domain.model.toPayload
import com.qcmian.clipper.core.domain.repository.ClipboardRepository
import com.qcmian.clipper.di.AppContainer
import com.qcmian.clipper.host.cli.CliServer
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.UUID
import javax.imageio.ImageIO
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * 开发用：只跑数据层 + CLI 服务端，**不起任何窗口**。
 *
 * 存在的理由：验证 CLI 需要有一个活着的服务端，而正常入口会拉起菜单栏应用——那既慢，
 * 又会往用户真实的 `~/.clipper/clipper.db` 里写东西（`copy` 会记一次「被复制」、
 * `delete` 会真的删）。这里配合 Gradle 任务里覆盖的 `user.home`，
 * 让整套东西跑在一个一次性的目录里。
 *
 * 运行：`./gradlew :desktopApp:devCliServer`
 * （数据落在 `desktopApp/build/devhome/`，CLI 侧加同样一条 `-Duser.home` 就能连上）
 *
 * 灌的数据刻意覆盖全部四种类型，因为要验证的正是「不同类型的输出形态」这件事：
 * 图片（带识别结果，验证 `ocr` 与落盘 `path`）、文件（验证 `exists`）、
 * 富文本（验证 `attachments` 与提取出的 `text`）、以及普通文本。
 */
fun main() {
    val container = AppContainer()
    container.repository.start()

    runBlocking {
        delay(600)
        // 已经有数据就不重复灌——服务端会被反复重启，重复的种子数据会污染 `list` 的结果。
        if (container.repository.loadedCount() == 0) {
            seed(container)
            delay(300)
        }
    }

    val server = CliServer(container)
    server.start()

    println("[dev] home   = ${System.getProperty("user.home")}")
    println("[dev] socket = ${server.socketPath}")
    println("[dev] 条目数  = ${container.repository.loadedCount()}")
    println("[dev] Ctrl+C 退出")

    // 一直待着，直到被信号终止。
    Thread.currentThread().join()
}

private fun ClipboardRepository.loadedCount(): Int = pinned.value.size + unpinned.value.size

private suspend fun seed(container: AppContainer) {
    val repo = container.repository

    // 1. 普通文本
    val textItem = item(
        text = "git commit -m \"fix: 修正 CLI 输出对齐\"",
        app = SourceApplication("Terminal", "com.apple.Terminal"),
        minutesAgo = 3,
        copies = 4,
    )
    repo.insert(textItem.toMeta(), textItem.toPayload())

    // 2. 超长文本：验证 title 截断与 titleTruncated
    val longText = buildString {
        repeat(40) { append("line %02d: 这是一段用于验证预览截断的长文本".format(it + 1)).append('\n') }
    }
    val longItem = item(
        text = longText,
        app = SourceApplication("Visual Studio Code", "com.microsoft.VSCode"),
        minutesAgo = 12,
        copies = 2,
    )
    repo.insert(longItem.toMeta(), longItem.toPayload())

    // 3. 图片 + 识别文字。识别结果要另外走一次 `updateRecognizedText`——真实路径就是这么来的
    //    （见 `CaptureClipboardUseCase`），`toMeta()` 不会自己去看 `ClipItem.recognizedText`。
    val imageItem = item(image = ClipImage(samplePng()), minutesAgo = 25)
    repo.insert(imageItem.toMeta(), imageItem.toPayload())
    repo.updateRecognizedText(
        id = imageItem.id,
        fullText = "识别出来的完整原文：这是一张截图里的文字。",
        title = "识别出来的完整原文：这是一张截图里的文字。",
    )

    // 4. 文件：一条存在的、一条不存在的，验证 `exists` 是查出来的而不是假设为真
    val dir = Files.createTempDirectory("clipper-dev-seed")
    val existingFile = dir.resolve("report.pdf")
    Files.writeString(existingFile, "%PDF-1.4 dev seed")
    val fileItem = item(
        files = listOf(existingFile.toString(), dir.resolve("已删除的文件.txt").toString()),
        app = SourceApplication("Finder", "com.apple.finder"),
        minutesAgo = 40,
    )
    repo.insert(fileItem.toMeta(), fileItem.toPayload())

    // 5. 富文本：正文与 HTML 附加表示同时存在，验证 `text` 取自可读文字而不是标签源码
    val richItem = item(
        text = "Clipper 是一个 macOS 剪贴板历史管理器。",
        contents = listOf(
            ClipboardContent(
                type = "public.html",
                value = "<html><body><b>Clipper</b> 是一个 macOS 剪贴板历史管理器。</body></html>"
                    .toByteArray(),
            ),
            ClipboardContent(type = "public.rtf", value = "{\\rtf1 Clipper}".toByteArray()),
        ),
        app = SourceApplication("Safari", "com.apple.Safari"),
        minutesAgo = 55,
        copies = 7,
    )
    repo.insert(richItem.toMeta(), richItem.toPayload())

    // 6. 只写了 HTML、没有纯文本的条目：`text` 必须退回到从标签里提取出的文字，
    //    否则它在输出里会是一片空白（`ClipItem.previewableText` 专门处理了这种情况）。
    val htmlOnlyItem = item(
        contents = listOf(
            ClipboardContent(
                type = "public.html",
                value = "<html><body>只有 HTML，没有纯文本表示。</body></html>".toByteArray(),
            ),
        ),
        minutesAgo = 70,
    )
    repo.insert(htmlOnlyItem.toMeta(), htmlOnlyItem.toPayload())
}

private fun item(
    text: String? = null,
    image: ClipImage? = null,
    files: List<String> = emptyList(),
    contents: List<ClipboardContent> = emptyList(),
    app: SourceApplication? = null,
    minutesAgo: Long = 0,
    copies: Int = 1,
): ClipItem {
    val copiedAt = System.currentTimeMillis() - minutesAgo * 60_000
    return ClipItem(
        id = UUID.randomUUID().toString(),
        text = text,
        image = image,
        files = files,
        contents = contents,
        firstCopiedAt = copiedAt,
        lastCopiedAt = copiedAt,
        numberOfCopies = copies,
        application = app,
    )
}

/** 一张小 PNG，用来验证图片条目的落盘与扩展名判断（魔数嗅探）。 */
private fun samplePng(): ByteArray {
    val image = BufferedImage(240, 80, BufferedImage.TYPE_INT_RGB)
    val graphics = image.createGraphics()
    try {
        graphics.color = Color(30, 90, 200)
        graphics.fillRect(0, 0, 240, 80)
        graphics.color = Color.WHITE
        graphics.drawString("clipper", 24, 46)
    } finally {
        graphics.dispose()
    }
    val output = ByteArrayOutputStream()
    ImageIO.write(image, "png", output)
    return output.toByteArray()
}
