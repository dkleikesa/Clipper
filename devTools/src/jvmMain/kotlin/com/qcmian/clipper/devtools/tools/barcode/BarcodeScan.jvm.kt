package com.qcmian.clipper.devtools.tools.barcode

import com.google.zxing.BarcodeFormat as ZxingFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.multi.GenericMultipleBarcodeReader
import java.util.EnumSet

/**
 * 认码：ZXing。
 *
 * 三条与「认得出、认得准」有关的设置（见 [ScanHints]）：码制给**全部**、`TRY_HARDER` 打开、
 * 连反色的码也认。
 *
 * **多码**走 `GenericMultipleBarcodeReader`：它先整图读一次，**读到之后**才按象限递归去找其余
 * 几个——所以一张图只有一个码时，多的只是几次注定失败的裁剪扫描；反过来若只用单码读法，一张图
 * 里有三个码就只会给出其中一个（实测：`BarcodeScanTest` 里那条多码用例）。
 *
 * 与 [scanBarcodePixels] 的契约一致：认不出就返回**空表**。ZXing 的「图里没有码」是
 * `NotFoundException`，它不是异常情况；真出了别的运行时异常也不该把窗口炸掉。
 */
internal actual fun scanBarcodePixels(pixels: IntArray, width: Int, height: Int): List<BarcodeHit> {
    if (width <= 0 || height <= 0 || pixels.size < width * height) return emptyList()
    val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, height, pixels)))
    val reader = MultiFormatReader()
    val results = runCatching {
        GenericMultipleBarcodeReader(reader).decodeMultiple(image, ScanHints)
    }.getOrNull().orEmpty()
    return results.map { BarcodeHit(text = it.text.orEmpty(), format = it.barcodeFormat.name) }
}

/**
 * 交给 ZXing 的那几档设置。
 *
 *  - `POSSIBLE_FORMATS` 给**全部**码制：本工具只编十二种，但用户拿来的图里可能是 Data Matrix、
 *    MaxiCode、RSS——能认出来比认得少强。用 `EnumSet` 是 ZXing 自己的惯例（它按集合查表）。
 *  - `TRY_HARDER`：为一张照片多花点时间（多扫几行、正着认不出再转 90° 认），比让用户「重拍一张」
 *    便宜。
 *  - `ALSO_INVERTED`：连**黑底白码**一起认——深色主题下的截图、反色打印的标签都属于这种。
 *
 * `CHARACTER_SET` 刻意**不设**：ZXing 默认按码里的内容自己判（QR 的 ECI、一维码的默认字符集），
 * 钉死 UTF-8 反而会把 Shift-JIS 一类非 UTF-8 的码读成乱码。
 */
private val ScanHints: Map<DecodeHintType, Any> = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to EnumSet.allOf(ZxingFormat::class.java),
    DecodeHintType.TRY_HARDER to true,
    DecodeHintType.ALSO_INVERTED to true,
)
