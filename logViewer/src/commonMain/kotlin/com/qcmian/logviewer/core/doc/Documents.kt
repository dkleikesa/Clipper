package com.qcmian.logviewer.core.doc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.withLock

/* ==================== 编码（F1-3） ==================== */

enum class TextEncoding(val zh: String) {
    UTF8("UTF-8"),
    UTF16LE("UTF-16 LE"),
    UTF16BE("UTF-16 BE"),
    GBK("GBK / 中文 ANSI"),
}

/* ==================== 文档源 ==================== */

/**
 * 文档源：全内存加载。
 * 行索引 0-based；原始行号 = index + 1。line() 为同步内存访问。
 */
interface DocumentSource {
    val name: String
    val lineCount: Int
    val sizeBytes: Long
    val encodingName: String
    val fileUri: String

    /** 最长行字符数（横向滚动条预扫描：未渲染行也能撑出正确滚动范围；0=未知） */
    val maxLineChars: Int get() = 0

    fun line(index: Int): String
    fun close() {}
}

/** 行偏移（紧凑结构：全文 + starts/lengths 两个 IntArray） */
class LineOffsets(val starts: IntArray, val lengths: IntArray) {
    val size: Int get() = starts.size
}

/** 扫描文本行偏移（\r\n 剔除；indexOf intrinsic） */
fun scanLineOffsets(text: String): LineOffsets {
    var starts = IntArray(64)
    var lengths = IntArray(64)
    var count = 0
    val n = text.length
    var i = 0
    while (i <= n) {
        val nl = if (i < n) text.indexOf('\n', i) else -1
        val end = if (nl < 0) n else nl
        if (count == starts.size) {
            starts = starts.copyOf(count * 2)
            lengths = lengths.copyOf(count * 2)
        }
        var e = end
        if (e > i && text[e - 1] == '\r') e--
        starts[count] = i
        lengths[count] = e - i
        count++
        if (nl < 0) break
        i = nl + 1
    }
    return LineOffsets(starts.copyOf(count), lengths.copyOf(count))
}

/**
 * 全内存文档（紧凑结构）：
 * - 全文保存在单个 String（Latin-1 内容在 JVM compact strings 下 ≈ 原始文件大小）
 * - 行 = (起始偏移, 长度) 两个 IntArray，不产生百万级 String 对象
 * - 过滤/搜索走全文 indexOf 快路径（引擎侧）
 */
class InMemoryDocument(
    override val name: String,
    override val fileUri: String,
    fullText: String,
    override val encodingName: String,
    override val sizeBytes: Long,
    precomputed: LineOffsets? = null,
) : DocumentSource {
    /** 全文（含换行符；过滤/搜索快路径使用） */
    val text: String = fullText
    private val starts: IntArray
    private val lengths: IntArray
    override val lineCount: Int

    init {
        if (precomputed != null) {
            starts = precomputed.starts
            lengths = precomputed.lengths
            lineCount = precomputed.size
        } else {
            val lo = scanLineOffsets(fullText)
            starts = lo.starts
            lengths = lo.lengths
            lineCount = lo.size
        }
    }

    override fun line(index: Int): String =
        if (index in 0 until lineCount) text.substring(starts[index], starts[index] + lengths[index]) else ""

    fun lineStart(i: Int): Int = starts[i]
    fun lineLength(i: Int): Int = lengths[i]

    /** 最长行字符数（O(N) 偏移差分，横向滚动条预扫描；惰性一次） */
    override val maxLineChars: Int by lazy(LazyThreadSafetyMode.NONE) {
        var m = 0
        for (i in lengths.indices) {
            val l = lengths[i]
            if (l > m) m = l
        }
        m
    }

    /** 字符位置 → 行号（二分：最后一个 starts[k] <= pos） */
    fun lineOfPos(pos: Int): Int {
        var lo = 0
        var hi = lineCount - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= pos) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    private var lowerTextCache: String? = null
    private val lowerMutex = kotlinx.coroutines.sync.Mutex()

    /**
     * ASCII 小写全文（忽略大小写过滤的快路径用）：
     * 只折叠 A-Z（长度严格不变 → 行偏移可复用），首次访问时并行构建并缓存。
     */
    suspend fun lowerText(onProgress: (Float) -> Unit = {}): String {
        lowerTextCache?.let { return it }
        lowerMutex.withLock {
            lowerTextCache?.let { return it }
            val t = parallelAsciiLowercase(text, onProgress)
            lowerTextCache = t
            return t
        }
    }
}

/** ASCII 小写（长度严格不变；优先 JVM intrinsic lowercase，变长时退回逐字符折叠） */
fun asciiLowercase(s: String): String {
    val lower = s.lowercase()   // JVM intrinsic，快 5×
    if (lower.length == s.length) return lower
    // 罕见：含变长字符（如 İ→i̇）；逐字符折叠保证等长
    val sb = StringBuilder(s.length)
    for (i in s.indices) {
        val c = s[i]
        sb.append(if (c in 'A'..'Z') c + 32 else c)
    }
    return sb.toString()
}

/** 并行分段 ASCII 小写：分区写共享 CharArray（零拼接拷贝） */
suspend fun parallelAsciiLowercase(text: String, onProgress: (Float) -> Unit = {}): String {
    if (text.length < (1 shl 20)) return asciiLowercase(text)
    val n = minOf(cpuCores().coerceAtLeast(1), 16)
    val segLen = (text.length + n - 1) / n
    val out = CharArray(text.length)
    val dispatcher = Dispatchers.Default.limitedParallelism(n)
    val jobs = (0 until n).map { k ->
        CoroutineScope(dispatcher).async {
            val from = k * segLen
            val to = minOf(text.length, from + segLen)
            if (from >= to) return@async
            val seg = text.substring(from, to)
            val lower = seg.lowercase()
            if (lower.length == seg.length) {
                for (i in lower.indices) out[from + i] = lower[i]
            } else {
                // 变长兜底：等长折叠
                for (i in seg.indices) {
                    val c = seg[i]
                    out[from + i] = if (c in 'A'..'Z') c + 32 else c
                }
            }
        }
    }
    // 逐段 await：每段完成推进进度（构建 1GB 全文 ~2s，无反馈会被感知为卡死）
    var done = 0
    jobs.forEach {
        it.await()
        done++
        onProgress(done.toFloat() / n)
    }
    return out.concatToString()
}

/* ==================== 行切分（旧接口兼容：粘贴导入等） ==================== */

fun splitLines(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lo = scanLineOffsets(text)
    return (0 until lo.size).map { text.substring(lo.starts[it], lo.starts[it] + lo.lengths[it]) }
}

/* ==================== 编码检测与解码 ==================== */

object EncodingDetector {
    /** BOM 检测，返回 null 表示无 BOM */
    fun detectByBom(bytes: ByteArray): TextEncoding? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() -> TextEncoding.UTF8
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> TextEncoding.UTF16LE
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> TextEncoding.UTF16BE
        else -> null
    }

    /** 严格 UTF-8 验证（采样） */
    fun looksLikeUtf8(bytes: ByteArray): Boolean {
        var i = 0
        val n = bytes.size
        while (i < n) {
            val b = bytes[i].toInt() and 0xFF
            val len = when {
                b < 0x80 -> 1
                b in 0xC2..0xDF -> 2
                b in 0xE0..0xEF -> 3
                b in 0xF0..0xF4 -> 4
                else -> return false
            }
            if (i + len > n) return true // 采样截断，视为通过
            for (k in 1 until len) {
                val cb = bytes[i + k].toInt() and 0xFF
                if (cb !in 0x80..0xBF) return false
            }
            i += len
        }
        return true
    }

    /**
     * 自动检测（无用户指定时）：
     * BOM → 严格 UTF-8 → GBK（平台支持时）→ null（需要用户手动选择）
     */
    fun autoDetect(head: ByteArray): TextEncoding? {
        detectByBom(head)?.let { return it }
        if (looksLikeUtf8(head)) return TextEncoding.UTF8
        if (decodeGbk(head) != null) return TextEncoding.GBK
        return null
    }
}

/** 解码；失败返回 null（编码不匹配或平台不支持）。支持 [offset, offset+length) 范围（分段解码零拷贝） */
fun decodeBytes(
    bytes: ByteArray,
    encoding: TextEncoding,
    strict: Boolean = false,
    offset: Int = 0,
    length: Int = bytes.size - offset,
): String? = when (encoding) {
    TextEncoding.UTF8 ->
        if (strict) {
            runCatching { bytes.decodeToString(offset, offset + length, throwOnInvalidSequence = true) }.getOrNull()
        } else {
            bytes.decodeToString(offset, offset + length)
        }
    TextEncoding.UTF16LE -> decodeUtf16(bytes, littleEndian = true, strict, offset, length)
    TextEncoding.UTF16BE -> decodeUtf16(bytes, littleEndian = false, strict, offset, length)
    TextEncoding.GBK -> decodeGbk(bytes, strict, offset, length)
}

private fun decodeUtf16(
    bytes: ByteArray,
    littleEndian: Boolean,
    strict: Boolean,
    offset: Int = 0,
    length: Int = bytes.size - offset,
): String? {
    // 跳过 BOM（仅首块）
    var start = offset
    if (offset == 0 && bytes.size >= 2) {
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF
        if (b0 == 0xFF && b1 == 0xFE && littleEndian) start = 2
        if (b0 == 0xFE && b1 == 0xFF && !littleEndian) start = 2
    }
    val end = offset + length
    if (end - start < 2) return ""
    val cnt = (end - start) / 2
    val sb = StringBuilder(cnt)
    var i = start
    while (i + 1 < end) {
        val c = if (littleEndian) {
            ((bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)).toChar()
        } else {
            (((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)).toChar()
        }
        sb.append(c)
        i += 2
    }
    return sb.toString()
}

/** GBK 解码平台实现（JVM/Android/iOS），其余平台返回 null */
expect fun decodeGbk(bytes: ByteArray, strict: Boolean = false, offset: Int = 0, length: Int = bytes.size - offset): String?

/** CPU 核数（并行解码用） */
expect fun cpuCores(): Int

/* ==================== 加载结果 ==================== */

sealed class LoadResult {
    data object NeedEncodingChoice : LoadResult()       // 需用户手动选择编码
    data class Ok(val document: DocumentSource) : LoadResult()
    data class Failed(val message: String) : LoadResult()
}

/**
 * 加载文档（紧凑结构 + 并行解码）：
 * 1. 全量读字节（顺序 IO）
 * 2. 按换行符对齐切段（多字节安全：\n 不会出现在 UTF-8/GBK 多字节序列中间）
 * 3. 多协程并行：段解码 + 段内行偏移扫描
 * 4. 拼接全文 + 合并偏移 → InMemoryDocument（全文 + IntArray 偏移）
 */
suspend fun loadDocument(
    name: String,
    fileUri: String,
    source: kotlinx.io.Source,
    size: Long,
    preferredEncoding: TextEncoding?,
    onProgress: (Float) -> Unit = {},
): LoadResult {
    if (size == 0L) {
        return LoadResult.Ok(InMemoryDocument(name, fileUri, "", "UTF-8", 0))
    }

    // 1) 全量读（进度 5%→25% 按已读字节实时回调；冷缓存 1GB 读盘 1~2s 是主要等待段）
    onProgress(0.05f)
    val bytes = ByteArray(size.toInt())
    source.use { src ->
        var off = 0
        var lastP = 0.05f
        while (off < bytes.size) {
            val n = src.readAtMostTo(bytes, off, bytes.size)
            if (n <= 0) break
            off += n
            val p = 0.05f + 0.20f * (off.toFloat() / bytes.size)
            if (p - lastP >= 0.02f) {
                onProgress(p)
                lastP = p
            }
        }
    }

    // 2) 编码检测
    val headLen = minOf(bytes.size, 64 * 1024)
    val encoding = preferredEncoding ?: EncodingDetector.autoDetect(bytes.copyOf(headLen))
        ?: return LoadResult.NeedEncodingChoice

    // JVM 单 String 上限预检（解码后字符数上限）
    val maxChars = if (encoding == TextEncoding.UTF8) size else size / 2
    if (maxChars > Int.MAX_VALUE - 8) {
        return LoadResult.Failed("文件解码后超过单字符串上限（约 21 亿字符），暂不支持")
    }
    onProgress(0.2f)

    // 3) 分段（UTF-16 走单段：字节级 \n 查找不安全）
    val isUtf16 = encoding == TextEncoding.UTF16LE || encoding == TextEncoding.UTF16BE
    val nSeg = if (isUtf16) 1 else minOf(cpuCores().coerceAtLeast(1), 16)
    val bounds = segmentBounds(bytes, nSeg)

    // 4) 并行解码 + 段内偏移扫描
    onProgress(0.3f)
    val dispatcher = Dispatchers.Default.limitedParallelism(nSeg)
    val segments = (0 until nSeg).map { k ->
        CoroutineScope(dispatcher).async {
            val from = bounds[k]
            val to = bounds[k + 1]
            if (from >= to) return@async null
            val segText = decodeRange(bytes, from, to - from, encoding)
            segText to scanLineOffsets(segText)
        }
    }
    // 逐段 await：每段完成即推进 30%→70%（并行解码的真实进度）
    var segDone = 0
    val results = segments.map { seg ->
        seg.await().also {
            segDone++
            onProgress(0.3f + 0.4f * (segDone.toFloat() / nSeg))
        }
    }

    // 5) 拼接全文 + 合并偏移
    var totalChars = 0
    var totalLines = 0
    results.forEach { r -> if (r != null) { totalChars += r.first.length; totalLines += r.second.size } }
    val sb = StringBuilder(totalChars)
    val starts = IntArray(totalLines)
    val lengths = IntArray(totalLines)
    var charBase = 0
    var li = 0
    for (r in results) {
        if (r == null) continue
        sb.append(r.first)
        val seg = r.second
        for (k in 0 until seg.size) {
            starts[li] = seg.starts[k] + charBase
            lengths[li] = seg.lengths[k]
            li++
        }
        charBase += r.first.length
    }
    onProgress(0.9f)
    return LoadResult.Ok(
        InMemoryDocument(name, fileUri, sb.toString(), encoding.zh, size, LineOffsets(starts, lengths)),
    )
}

/** 分段边界：按换行符对齐（每段为完整行的集合；\n 不出现在 UTF-8/GBK 多字节序列中间） */
private fun segmentBounds(bytes: ByteArray, nSeg: Int): IntArray {
    val bounds = IntArray(nSeg + 1)
    bounds[0] = 0
    bounds[nSeg] = bytes.size
    for (k in 1 until nSeg) {
        var b = ((bytes.size.toLong() * k) / nSeg).toInt()
        while (b < bytes.size && bytes[b] != '\n'.code.toByte()) b++
        if (b < bytes.size) b++ // \n 之后
        bounds[k] = b
    }
    // 单调性兜底
    for (k in 1..nSeg) {
        if (bounds[k] < bounds[k - 1]) bounds[k] = bounds[k - 1]
    }
    return bounds
}

/** 解码 [offset, offset+length) 段（段边界由换行符对齐保证字符安全） */
private fun decodeRange(bytes: ByteArray, offset: Int, length: Int, encoding: TextEncoding): String {
    if (length <= 0) return ""
    return decodeBytes(bytes, encoding, strict = false, offset = offset, length = length)
        ?: bytes.decodeToString(offset, offset + length)
}
