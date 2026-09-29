package com.qcmian.clipper.core.ui.components

/**
 * 把图片字节转成 Compose 的解码器认得的格式（PNG）。
 *
 * **只在直解失败后调用**：捕获层原样保存剪贴板给出的字节（macOS 上常见的规范图片类型是
 * TIFF，而 Skia 不支持 TIFF），PNG / JPEG 这类解码器认得的格式根本不会走到这里。
 * 转码结果落在 [ImageCache] 里，同一张图只转一次。
 *
 * 返回 `null` 表示转不了——格式连平台的解码器也不认（例如 JVM 的 `ImageIO` 默认不支持
 * HEIC）。此时该图片只是无法预览，字节本身仍然完好，写回与导出都不受影响。
 */
internal expect fun transcodeForDisplay(bytes: ByteArray): ByteArray?
