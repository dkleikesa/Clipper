package com.qcmian.clipper.core.ui.components

import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * 用 JDK 自带的 `ImageIO` 解码再重编码成 PNG。
 *
 * 选中 `ImageIO` 而不是别的路径，是因为它默认就带 TIFF 插件，而 TIFF 正是这个函数被调用的
 * 唯一常见原因（macOS 粘贴板的规范图片类型）。
 */
internal actual fun transcodeForDisplay(bytes: ByteArray): ByteArray? = runCatching {
    val decoded = ImageIO.read(bytes.inputStream()) ?: return@runCatching null
    val output = ByteArrayOutputStream()
    ImageIO.write(decoded, "png", output)
    output.toByteArray()
}.getOrNull()
