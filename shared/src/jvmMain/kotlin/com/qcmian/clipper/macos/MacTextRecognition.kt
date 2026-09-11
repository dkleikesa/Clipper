package com.qcmian.clipper.macos

import com.qcmian.clipper.util.decodeBase64
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer

private interface ImageIOLibrary : Library {
    fun CGImageSourceCreateWithData(data: Pointer, options: Pointer?): Pointer?
    fun CGImageSourceCreateImageAtIndex(source: Pointer, index: Long, options: Pointer?): Pointer?
}

private interface CoreFoundationLibrary : Library {
    fun CFDataCreate(allocator: Pointer?, bytes: Pointer, length: Long): Pointer?
    fun CFRelease(value: Pointer?)
}

/**
 * 基于 Vision 框架的 `HistoryItem.performTextRecognition()` 移植。
 *
 * 刻意使用同步的 `performRequests:error:` 路径：这样可以避免为完成回调构建
 * Objective-C block——JNA 无法表达它。
 */
object MacTextRecognition {
    private val imageIO: ImageIOLibrary? = runCatching {
        Native.load("/System/Library/Frameworks/ImageIO.framework/ImageIO", ImageIOLibrary::class.java)
    }.getOrNull()

    private val coreFoundation: CoreFoundationLibrary? = runCatching {
        Native.load(
            "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
            CoreFoundationLibrary::class.java,
        )
    }.getOrNull()

    private val visionLoaded: Boolean =
        MacNative.loadFramework("/System/Library/Frameworks/Vision.framework/Vision")

    val available: Boolean get() = imageIO != null && coreFoundation != null && visionLoaded

    fun recognize(imageBase64: String): String? {
        val imageIO = this.imageIO ?: return null
        val coreFoundation = this.coreFoundation ?: return null
        if (!visionLoaded) return null

        val bytes = decodeBase64(imageBase64) ?: return null
        if (bytes.isEmpty()) return null

        val buffer = Memory(bytes.size.toLong())
        buffer.write(0, bytes, 0, bytes.size)
        val data = coreFoundation.CFDataCreate(null, buffer, bytes.size.toLong()) ?: return null

        var source: Pointer? = null
        var image: Pointer? = null
        try {
            source = imageIO.CGImageSourceCreateWithData(data, null) ?: return null
            image = imageIO.CGImageSourceCreateImageAtIndex(source, 0L, null) ?: return null
            return recognizeImage(image)
        } finally {
            image?.let { coreFoundation.CFRelease(it) }
            source?.let { coreFoundation.CFRelease(it) }
            coreFoundation.CFRelease(data)
        }
    }

    private fun recognizeImage(cgImage: Pointer): String? {
        val requestClass = MacNative.clazz("VNRecognizeTextRequest") ?: return null
        val request = MacNative.send(MacNative.send(requestClass, "alloc"), "init") ?: return null

        // VNRequestTextRecognitionLevelFast == 0
        MacNative.send(request, "setRecognitionLevel:", 0L)

        val dictionaryClass = MacNative.clazz("NSDictionary") ?: return null
        val options = MacNative.send(dictionaryClass, "dictionary")

        val handlerClass = MacNative.clazz("VNImageRequestHandler") ?: return null
        val handler = MacNative.send(
            MacNative.send(handlerClass, "alloc"),
            "initWithCGImage:options:",
            cgImage,
            options,
        ) ?: return null

        val arrayClass = MacNative.clazz("NSArray") ?: return null
        val requests = MacNative.send(arrayClass, "arrayWithObject:", request) ?: return null

        if (!MacNative.sendBool(handler, "performRequests:error:", requests, null)) return null

        val results = MacNative.send(request, "results") ?: return null
        val lines = MacNative.array(results).mapNotNull { observation ->
            val candidates = MacNative.send(observation, "topCandidates:", 1L) ?: return@mapNotNull null
            val best = MacNative.array(candidates).firstOrNull() ?: return@mapNotNull null
            MacNative.string(MacNative.send(best, "string"))?.trim()?.takeIf { it.isNotEmpty() }
        }

        return lines.joinToString("\n").ifBlank { null }
    }
}
