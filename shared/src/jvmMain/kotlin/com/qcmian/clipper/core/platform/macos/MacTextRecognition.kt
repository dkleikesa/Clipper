package com.qcmian.clipper.core.platform.macos

import com.qcmian.clipper.core.domain.model.ClipImage
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

    /** 优先语言：简体中文在前，繁体与英文兜底。 */
    private val PREFERRED_LANGUAGES = listOf("zh-Hans", "zh-Hant", "en-US")

    fun recognize(image: ClipImage): String? = MacNative.autoreleasePool { recognizeInPool(image) }

    private fun recognizeInPool(image: ClipImage): String? {
        val imageIO = this.imageIO ?: return null
        val coreFoundation = this.coreFoundation ?: return null
        if (!visionLoaded) return null

        val bytes = image.toByteArray()
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

        try {
            // VNRequestTextRecognitionLevelAccurate == 0，Fast == 1——**不是反过来**。
            //
            // 两档覆盖的语言差得很远（本机实测）：
            //   Accurate：30 种，含 zh-Hans / zh-Hant / ja-JP / ko-KR …
            //   Fast    ：只有 en-US / fr-FR / it-IT / de-DE / es-ES / pt-BR
            // 用 Fast 识别中文不会报错，而是把汉字硬认成拉丁字母（`你好，世界` → `l/J,l},`），
            // 看起来就像编码坏掉的乱码。语言求交也必须在这一档设定之后进行。
            MacNative.send(request, "setRecognitionLevel:", 0L)
            applyRecognitionLanguages(request)

            val dictionaryClass = MacNative.clazz("NSDictionary") ?: return null
            val options = MacNative.send(dictionaryClass, "dictionary")

            val handlerClass = MacNative.clazz("VNImageRequestHandler") ?: return null
            val handler = MacNative.send(
                MacNative.send(handlerClass, "alloc"),
                "initWithCGImage:options:",
                cgImage,
                options,
            ) ?: return null

            try {
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
            } finally {
                // `alloc` 出来的对象由本方法持有，autorelease pool 管不到它，必须显式释放。
                MacNative.send(handler, "release")
            }
        } finally {
            MacNative.send(request, "release")
        }
    }

    /**
     * 下发优先识别语言。
     *
     * Vision 在不设置 `recognitionLanguages` 时只用默认语言（英文），中文画面因此什么都识别不出来
     * ——这是「同一张图有的识别得出、有的识别不出」的主要来源。这里按中文优先、英文兜底的顺序，
     * 先与系统实际支持的列表求交再下发：传一个不受支持的语言代码会让 ObjC 抛异常，在 JVM 里
     * 等同于直接崩掉进程，所以不能盲传。
     */
    private fun applyRecognitionLanguages(request: Pointer) {
        val supported = supportedRecognitionLanguages(request)
        val wanted = PREFERRED_LANGUAGES.filter { it in supported }
        if (wanted.isNotEmpty()) {
            MacNative.send(request, "setRecognitionLanguages:", MacNative.stringArray(wanted))
            return
        }

        // 拿不到支持列表（或这一档里没有我们关心的语言）时退到系统自动判断，与系统「实时文本」
        // 的做法一致；`respondsToSelector:` 让不支持该属性的旧系统保持原样。
        enableAutomaticLanguageDetection(request)
    }

    /** `setAutomaticallyDetectsLanguage:`，macOS 13 起才有。 */
    private fun enableAutomaticLanguageDetection(request: Pointer) {
        val selector = MacNative.selector("setAutomaticallyDetectsLanguage:") ?: return
        if (!MacNative.sendBool(request, "respondsToSelector:", selector)) return
        MacNative.send(request, "setAutomaticallyDetectsLanguage:", 1)
    }

    /** `supportedRecognitionLanguagesAndReturnError:`；查询不到时返回空列表。 */
    private fun supportedRecognitionLanguages(request: Pointer): List<String> {
        val selector = MacNative.selector("supportedRecognitionLanguagesAndReturnError:") ?: return emptyList()
        if (!MacNative.sendBool(request, "respondsToSelector:", selector)) return emptyList()

        // 该方法要求一个可写的 `NSError **` 槽位，JNA 的 `Memory` 正好是一块指针大小的内存。
        // 错误内容用不上：查不到时返回空列表，调用方于是退回系统默认语言。
        val error = Memory(8)
        val array = MacNative.send(request, "supportedRecognitionLanguagesAndReturnError:", error)
            ?: return emptyList()
        return MacNative.array(array).mapNotNull { MacNative.string(it) }
    }
}
