package com.qcmian.clipper.devtools.tools.barcode

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.qcmian.clipper.core.util.decodeUtf8OrNull
import kotlin.math.roundToInt

/** 认出来的一条码。 */
internal class BarcodeHit(
    /** 码里装的内容——二维码多半是一段链接或一段文本，一维码是一串数字 / 字符。 */
    val text: String,
    /**
     * 码制：ZXing 报出来的名字，例如 `QR_CODE` / `CODE_128`。
     *
     * 存**原始名字**而不是显示名：这一层不该认识界面文案，显示前过一道 [barcodeFormatLabel]。
     */
    val format: String,
)

/**
 * 一次识别的结果。
 *
 * 「图读不出来」与「图里没有码」是**两件事**，所以分成两支而不是用一个空表代替：前者是这张图
 * 本身用不了（不是图片、或者格式怪到取不出像素），后者是图好好的、只是没有码——用户该做的事
 * 完全不同（换一张图 vs 把码放正、拍清楚）。
 */
internal sealed interface ScanOutcome {
    /** 图认出来了。[hits] 为空是**正常结果**：这张图里没有码。 */
    class Ready(val bitmap: ImageBitmap, val hits: List<BarcodeHit>) : ScanOutcome

    /** 这串字节根本当不了码图使。[message] 是给用户看的一句交代。 */
    class Unreadable(val message: String) : ScanOutcome
}

/** 认码前把长边缩到这么多像素以内。见 [downscaledForScan]。 */
private const val MaxScanLongSide = 1600

/**
 * 从 ARGB 像素里认码——**平台相关**的那一步：JVM 上交给 ZXing（见 `BarcodeScan.jvm.kt`）。
 *
 * 边界刻意收在「像素进、结果出」：图片格式的解码、缩放、取像素都在 commonMain 用 Compose 做完，
 * 平台实现不必认识 PNG / JPEG，也不必碰 Compose 的任何东西。认不出码返回**空表**而不是抛异常
 * ——那是最常见的正常结果（见 [ScanOutcome]）。
 */
internal expect fun scanBarcodePixels(pixels: IntArray, width: Int, height: Int): List<BarcodeHit>

/**
 * 这串字节解出来的图；解不动（不是图片、尺寸大到内存放不下、格式太古怪）时为 `null`。
 *
 * 除了认码，还供「该落在解码页还是编码页」这类**分流**用（见 `BarcodeDevTool.Content`）：判据是
 * 内容本身，不是扩展名——二维码截图可能存成了别的名字。分流那一次解出来的图用不上（认码时会
 * 再解一次），但这只在「从历史里打开一个文件」时走一次，多解一遍换一处简单的状态，值。
 */
internal fun decodeImageOrNull(bytes: ByteArray): ImageBitmap? =
    runCatching { bytes.decodeToImageBitmap() }.getOrNull()

/**
 * 认一张图：把字节解成图 → 按需缩小 → 取 ARGB 像素 → 交给 [scanBarcodePixels]。
 *
 * 返回的 [ScanOutcome.Ready.bitmap] 是**原图**而不是缩小那份：它还要兼作界面上的预览，缩小那份
 * 只为认码而生（见 [downscaledForScan]）。
 *
 * 调用方负责放到后台线程上：取像素是百万次级的循环，ZXing 还要在上面做二值化与多轮扫描。
 */
internal fun scanBarcodeImage(bytes: ByteArray): ScanOutcome {
    val bitmap = decodeImageOrNull(bytes)
        ?: return ScanOutcome.Unreadable("这不是一张能读的图片（认 PNG / JPEG / GIF / WebP / BMP）")
    val target = bitmap.downscaledForScan(MaxScanLongSide)
    val pixels = IntArray(target.width * target.height)
    // 取像素这一步也可能失败（`readPixels` 对个别配置会拒），与解图同一条口径：说一句「读不出来」，
    // 而不是把窗口炸掉。
    return runCatching { target.readPixels(pixels) }.fold(
        onSuccess = {
            // 识别器自己抛出来的意外也收在这里：认不出码是**正常结果**（空表），但平台实现真炸了
            // 也不该把窗口一起带走——对用户来说那同样是「这张图用不了」，只是说得更具体一点。
            val hits = runCatching { scanBarcodePixels(pixels, target.width, target.height) }
                .getOrElse { error ->
                    return@fold ScanOutcome.Unreadable(
                        "识别这张图时出了点问题：${error.message ?: error::class.simpleName.orEmpty()}"
                    )
                }
            // 内容一律过一道字符集修复：Aztec 这类「字节直接塞进去、不声明字符集」的码，识别器会按
            // 规范默认的 ISO-8859-1 解，中文于是成了乱码（见 [repairTextEncoding]）。
            ScanOutcome.Ready(bitmap, hits.map { BarcodeHit(repairTextEncoding(it.text), it.format) })
        },
        onFailure = { ScanOutcome.Unreadable("读不出这张图的像素（尺寸或格式太特殊）") },
    )
}

/**
 * 长边超过 [maxSide] 时等比缩到 [maxSide]；没超过就原样返回。
 *
 * 手机拍的照片动辄 4000×3000：光取像素就要走 1200 万个 int（约 48MB），ZXing 还得在这么大一张图
 * 上做二值化与逐行扫描。认码不需要这么多像素——长边收到 1600 对最小的码也够，代价是几十毫秒。
 */
private fun ImageBitmap.downscaledForScan(maxSide: Int): ImageBitmap {
    val longSide = maxOf(width, height)
    if (longSide <= maxSide) return this
    val ratio = maxSide.toFloat() / longSide
    val targetWidth = maxOf(1, (width * ratio).roundToInt())
    val targetHeight = maxOf(1, (height * ratio).roundToInt())
    val target = ImageBitmap(targetWidth, targetHeight)
    CanvasDrawScope().draw(
        density = Density(1f, 1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(target),
        size = Size(targetWidth.toFloat(), targetHeight.toFloat()),
    ) {
        // 缩小必须**带插值**（默认那档在缩小时直接丢像素）：一维码的细条会断线、二维码的模块会
        // 时有时无，ZXing 就栽在这一步上。
        drawImage(
            image = this@downscaledForScan,
            dstSize = IntSize(targetWidth, targetHeight),
            filterQuality = FilterQuality.Medium,
        )
    }
    return target
}

/**
 * 修「没声明字符集的 UTF-8 被按 ISO-8859-1 解出来」的那种乱码。
 *
 * 码里存的是**字节**，字符集要么显式声明（ECI），要么按各自规范的默认值——Aztec 与一维码的默认
 * 是 ISO-8859-1。而 Aztec 的编码器（qrose 那份是 ZXing 编码器的移植，ZXing 自己也一样）把中文按
 * UTF-8 写进去却**不声明** ECI，于是识别器老老实实按 ISO-8859-1 解出一个字符一个字节的西欧字母：
 * `Base64 URL 条码(解码页面先留白占位)` 会变成 `Base64 URL æ¡ç (è§£ç …`。字节是对的，只是解错了。
 *
 * 判据两条，缺一不可：
 *  - **每个字符都在 0x00–0xFF 之间**——说明它确实是一个字节一个字符解出来的，才有「把字符还原成
 *    字节」这一步可言；
 *  - 还原出来的字节拼起来是**合法 UTF-8**（严格判，见 `decodeUtf8OrNull`）——合法 UTF-8 是很难
 *    凑巧撞上的（随机二进制不行），所以真正的 ISO-8859-1 文本（`café`）不会被误改：那个 `é` 单独
 *    成不了一个合法的 UTF-8 序列，原样返回。
 *
 * 两处都满足才替换，否则原样返回——这个函数宁可什么都不做，也不该把一段本来正确的文本改坏。
 */
internal fun repairTextEncoding(text: String): String {
    if (text.isEmpty()) return text
    val bytes = ByteArray(text.length)
    for (index in text.indices) {
        val code = text[index].code
        if (code > 0xFF) return text
        bytes[index] = code.toByte()
    }
    return decodeUtf8OrNull(bytes) ?: text
}

/**
 * ZXing 报出来的码制名 → 界面上显示的名字。
 *
 * 本工具**编得出**的那几种直接取 [BarcodeFormat.title]：同一个码制在生成与解码两处不会各叫各的
 * （「QR」与「QR Code」）。认得出但编不了的那几种（Data Matrix、MaxiCode、RSS…）在这里补上名字
 * ——它们在物流与工业标签上很常见，比原样显示库内部的名字强。其余照原样显示。
 */
internal fun barcodeFormatLabel(format: String): String = when (format) {
    "QR_CODE" -> BarcodeFormat.Qr.title
    "AZTEC" -> BarcodeFormat.Aztec.title
    "PDF_417" -> BarcodeFormat.Pdf417.title
    "CODE_128" -> BarcodeFormat.Code128.title
    "CODE_39" -> BarcodeFormat.Code39.title
    "CODE_93" -> BarcodeFormat.Code93.title
    "EAN_13" -> BarcodeFormat.Ean13.title
    "EAN_8" -> BarcodeFormat.Ean8.title
    "UPC_A" -> BarcodeFormat.UpcA.title
    "UPC_E" -> BarcodeFormat.UpcE.title
    "ITF" -> BarcodeFormat.Itf.title
    "CODABAR" -> BarcodeFormat.Codabar.title
    "DATA_MATRIX" -> "Data Matrix"
    "MAXICODE" -> "MaxiCode"
    "RSS_14" -> "RSS-14"
    "RSS_EXPANDED" -> "RSS Expanded"
    "UPC_EAN_EXTENSION" -> "UPC/EAN 附加码"
    else -> format
}
