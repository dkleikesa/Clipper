package com.qcmian.clipper.devtools.tools.barcode

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import io.github.alexzhirkevich.qrose.ImageFormat
import io.github.alexzhirkevich.qrose.QroseEncoders
import io.github.alexzhirkevich.qrose.matrix.Aztec
import io.github.alexzhirkevich.qrose.matrix.Matrix2D
import io.github.alexzhirkevich.qrose.matrix.MatrixBarcodePainter
import io.github.alexzhirkevich.qrose.matrix.PDF417
import io.github.alexzhirkevich.qrose.matrix.QR
import io.github.alexzhirkevich.qrose.matrix.qr.QrErrorCorrection
import io.github.alexzhirkevich.qrose.oned.BarcodePainter
import io.github.alexzhirkevich.qrose.oned.BarcodePathBuilder
import io.github.alexzhirkevich.qrose.oned.BarcodeType
import io.github.alexzhirkevich.qrose.toByteArray
import kotlin.math.roundToInt

/**
 * 一维码两侧各留多少个模块的静区。规范要求 10 倍窄条宽度，这里照做。
 *
 * 一维码的静区比二维码更要紧：条与条挨得太近时，扫描器会把相邻条码或边框的墨线当成码的一部分。
 */
private const val LinearQuietZoneModules = 10

/**
 * 能生成的码制：三种二维码 + 九种一维码。
 *
 * 两族的差别是**编码结果**：二维码编成一格一格的模块矩阵（[Matrix2D]），一维码编成一条条的
 * 条空序列（`BooleanArray`）——所以各自的 painter 不同（[MatrixBarcodePainter] 与
 * [BarcodePainter]），[encodeBarcode] 按 [linearType] 分流。除这一处分流外，两者在界面上的
 * 呈现完全一样（黑白、静区、导出 PNG）。
 *
 * @param inputHint 这类码吃什么样的输入、**最多装多少**：输入框的占位提示用它，编码失败时那句
 *   「要求：…」也用它。长度上限必须写在这里——各码制差别极大（EAN/UPC 只收固定位数且校验位要
 *   对、Code 39/93 封顶 80 字符、Code 128 没有硬上限但编码器会自己崩、二维码看字节容量），不写
 *   清楚用户只能靠报错去猜（每个上限都实测过，见 `BarcodeFormatTest`）。
 *
 *   失败时的第一行正是它，所以**容量类错误也解释得通**：「上限 1273–2953 字节」摆在最上面，
 *   下面才是编码器那句 `Data too long`。
 *
 *   写描述时踩过的三个坑，都实测过：
 *   - **二维码只写一个字符数会被误读成上限**：同一个符号能装多少字符随内容浮动（数字 4 位/字符、
 *     字母 5 位、汉字 8 位/字节），Aztec 纯数字能到 3748、纯汉字只有 624。所以先给字节上限，
 *     再给各字符集的锚点。
 *   - **EAN/UPC 的「或 13 位」是有条件的**：13 位那一档要求校验位**正确**（算错直接报
 *     `Contents do not pass checksum`），不是照单全收。
 *   - **Code 128 的「不设上限」只是没有长度检查**：编码器是递归实现，实测 6000 字符可用，
 *     7000 崩 `StackOverflowError`、8192 崩校验和 Int 溢出——上限由栈深度决定，不是个定数，
 *     因此只给一个稳妥值。
 * @param linearType 一维码的码制；`null` 表示二维码。
 * @param quietZone 二维码的静区，单位是码元个数（依各自规范取值）。
 * @param rowHeightRatio 二维码的行高与码元宽之比；只有堆叠式的 PDF417 不是 1（标准值 3）。
 */
internal enum class BarcodeFormat(
    val title: String,
    /** 导出时的建议文件名。 */
    val fileName: String,
    val inputHint: String,
    val linearType: BarcodeType? = null,
    val quietZone: Int = 0,
    val rowHeightRatio: Float = 1f,
) {
    // ---------------------------------------------------------------- 二维码（模块矩阵）
    // 三种都收**任意文本**（中文按 UTF-8 字节存），所以第一句一律写「任意文本」——这里说的是
    // **能吃什么输入**，容量与用法跟着写在后面；「较长的文本」那种写法既没说清字符集，又像个
    // 用法建议，和另两行不是一类东西。
    //
    // 容量写法：**先字节、再字符锚点**。同一个符号能装多少「字符」随内容变——数字按 4 位/字符、
    // 字母 5 位、汉字走二进制 8 位/字节，所以纯数字能装的字符数远超汉字（Aztec 实测 3748 vs 624）。
    // 只写一个汉字数会被读成「字符上限」，用户拿数字内容一试就以为提示在骗人。
    // QR 的锚点是**区间**：它随纠错等级整体缩放，而级别是用户在工具栏上可选的。
    //
    // 三者的差别在**字符集声明**，不在「收不收中文」：PDF417 遇到非 ASCII 会先发 ECI 26（UTF-8）
    // 再按 UTF-8 字节编码，QR / Aztec 只把 UTF-8 字节塞进去。用 ZXing 解码我们导出的 PNG 实测：
    // PDF417（含 emoji 🎉）与 QR 的中文都能原样解回，Aztec 会被按 ISO-8859-1 解成乱码——字节是
    // 对的，只是没声明；认 UTF-8 的扫描器（手机基本都认）不受影响，严格按 Latin-1 解的才难看。
    Qr("QR", "qrcode.png", "任意文本；上限 1273–2953 字节（随纠错等级；数字 3100–7100 位、字母 1900–4300 个、汉字 420–980 个）", quietZone = 4),
    // 这里原本还有 Data Matrix（工业与物流标签常用），被去掉了：qrose 的实现对非 ASCII 会走
    // Base256，而那里写的是 `c.code and 0xFF`——每个字符只留**低字节**。于是「中文」编出来的码
    // 扫回去是乱码，而且不报错、不提示（用 ZXing 往返验证过：`中文测试` → `-KÕ`）。要重新加回来，
    // 得先绕开这个坑（自己按 UTF-8 编码后走字节模式，或换一个编码器）。
    Aztec("Aztec", "aztec.png", "任意文本；上限 1872 字节（数字约 3700、字母约 3000、汉字约 620 个）", quietZone = 1),
    Pdf417("PDF417", "pdf417.png", "任意文本；上限 1032 字节（数字约 2500、字母约 1700、汉字约 340 个）", quietZone = 2, rowHeightRatio = 3f),

    // ---------------------------------------------------------------- 一维码（条空序列）
    // 「不设上限」的两个里，Code 128 其实有暗礁：编码器用递归走位，6000 字符还行，7000 就崩在
    // 栈溢出、8192 崩在校验和 Int 溢出（见 `BarcodeFormatTest` 钉住的稳妥值）。它没有长度检查，
    // 所以这句写的是**实测可用范围**而不是库的规则——而且这个边界随栈深度浮动（6000~8000 之间
    // 时好时坏），所以语气是「建议」而不是「上限」：5000 以内实测稳妥，再多不保证。
    Code128("Code 128", "code128.png", "任意 ASCII 文本；不设长度上限，建议 5000 字符以内（再多可能因编码器递归过深而失败）", linearType = BarcodeType.Code128),
    // Code 39/93 的 80 是**扩展后**的长度：字母表之外的字符（小写、冒号一类）得拆成两个字符编码，
    // 因此写小写实际只有 40 个额度——这就是「折半」的由来。
    Code39("Code 39", "code39.png", "大写字母、数字与 - . $ / + % 和空格；最多 80 字符（小写等字符走扩展模式，一个占两个额度）", linearType = BarcodeType.Code39),
    Code93("Code 93", "code93.png", "大写字母、数字与 - . $ / + % 和空格；最多 80 字符（小写等字符走扩展模式，一个占两个额度）", linearType = BarcodeType.Code93),
    // EAN/UPC 的后一档（13/12/8/8 位）不是「也能收」，而是**校验位必须算对**：算错报
    // `Contents do not pass checksum`，所以那句「或 N 位」后面得跟上这个条件。
    Ean13("EAN-13", "ean13.png", "12 位数字（自动补校验位）或 13 位（校验位须正确）", linearType = BarcodeType.EAN13),
    Ean8("EAN-8", "ean8.png", "7 位数字（自动补校验位）或 8 位（校验位须正确）", linearType = BarcodeType.EAN8),
    UpcA("UPC-A", "upca.png", "11 位数字（自动补校验位）或 12 位（校验位须正确）", linearType = BarcodeType.UPCA),
    UpcE("UPC-E", "upce.png", "7 位数字（首位须为 0 或 1，自动补校验位）或 8 位（校验位须正确）", linearType = BarcodeType.UPCE),
    Itf("ITF", "itf.png", "偶数位数字，最多 80 位", linearType = BarcodeType.ITF),
    // 起止符：不写就自动补 A；自己写了就必须首尾成对（只写头一个会报 Invalid start/end guards）。
    // 允许的起止符是 A~D（个别读码器习惯写 T/N/*/E，这里也认，会映射到 A~D）。
    Codabar("Codabar", "codabar.png", "数字与 - $ : / . +；不设长度上限，起止符可省（自动补 A），写了须首尾成对", linearType = BarcodeType.Codabar),
}

/**
 * QR 可选的纠错等级。
 *
 * 只有 QR 在界面上暴露它：另外两种二维码的纠错参数形态各不相同（Aztec 是百分比、PDF417 是
 * 0..8 档），拼成一个控件只会让人以为它们是同一种东西；那两种一律用编码器的默认值
 * （Aztec 33%、PDF417 自动）。
 */
internal enum class QrErrorLevel(
    val title: String,
    val hint: String,
    val level: QrErrorCorrection,
) {
    Low("低", "约 7% 面积可损坏，容量最大", QrErrorCorrection.L),
    Medium("中", "约 15% 面积可损坏（默认）", QrErrorCorrection.M),
    MediumHigh("较高", "约 25% 面积可损坏", QrErrorCorrection.Q),
    High("高", "约 30% 面积可损坏，容量最小", QrErrorCorrection.H),
}

/** 一次编码的结果：能直接画的 painter，以及给状态栏用的「多大」。 */
internal class EncodedCode(
    val painter: Painter,
    /** 二维码是「25×25」（模块数），一维码是「95 模块」（条空格数）。 */
    val sizeLabel: String,
)

/**
 * 把文本编成 [format] 的码；编不出来（长度 / 字符集不合该码制、内容超出容量）时抛异常。
 *
 * 纯函数、不碰 Compose 状态：调用方负责放到后台线程，并接住异常（见 `BarcodeDevTool`）。
 */
internal fun encodeBarcode(format: BarcodeFormat, data: String, level: QrErrorLevel): EncodedCode {
    val linear = format.linearType
    if (linear != null) {
        // 一维码：编码器直接给出条空序列，静区交给 builder（见 [linearBarcodeBuilder]）。
        val code = linear.encoder.encode(data)
        val painter = BarcodePainter(
            code = code,
            brush = SolidColor(Color.Black),
            builder = linearBarcodeBuilder(LinearQuietZoneModules),
        )
        return EncodedCode(painter, "${code.size} 模块")
    }

    val matrix = when (format) {
        BarcodeFormat.Qr -> QroseEncoders.QR(level.level).encode(data)
        BarcodeFormat.Aztec -> QroseEncoders.Aztec().encode(data)
        BarcodeFormat.Pdf417 -> QroseEncoders.PDF417().encode(data)
        else -> error("${format.title} 是一维码，不该走到这条路")
    }
    return EncodedCode(matrixPainter(format, matrix), "${matrix.width}×${matrix.height}")
}

/**
 * 把模块矩阵画成 painter。
 *
 * **颜色固定为黑码白底**，不跟主题走：码要能被别的设备扫，浅色主题下白底没问题，深色主题下
 * 若把底色改成深色、码改成浅色，不少扫描器会认不出。静区靠 `quietZone` 让出来（白色背景会
 * 铺满整块画布，包括静区），因此导出的 PNG 也带着白边。
 */
private fun matrixPainter(format: BarcodeFormat, matrix: Matrix2D): MatrixBarcodePainter =
    MatrixBarcodePainter(
        matrix = matrix,
        brush = SolidColor(Color.Black),
        backgroundBrush = SolidColor(Color.White),
        quietZone = format.quietZone,
        rowHeightRatio = format.rowHeightRatio,
    )

/**
 * 一维码的绘制方式：把条空画进一块**左右各留 [quietZoneModules] 个模块**的区域里。
 *
 * 一维码的 painter 没有「静区」参数（默认画法会把条铺满整块画布），所以只能在这一层留白；
 * 好处是静区随渲染尺寸等比缩放，预览和导出的图都是准的 10 倍窄条宽。
 */
internal fun linearBarcodeBuilder(quietZoneModules: Int): BarcodePathBuilder = { size, code ->
    val slots = code.size + quietZoneModules * 2
    val module = if (slots > 0) size.width / slots else 0f
    Path().apply {
        code.forEachIndexed { index, dark ->
            if (dark) {
                val left = (index + quietZoneModules) * module
                addRect(Rect(left, 0f, left + module, size.height))
            }
        }
    }
}

/**
 * 导出 PNG 的像素尺寸：[longSide] 给长边，短边按 painter 的固有比例算出来。
 *
 * 不能一律导成正方形：PDF417 是 137×8 的长条、EAN-13 是 95 格的长条，塞进正方形画布里会被缩得
 * 很小、四周留一大片白（painter 自己按短边等比缩放居中），既难看也浪费像素。
 */
internal fun exportSizeOf(painter: Painter, longSide: Int): IntSize {
    val intrinsic = painter.intrinsicSize
    val longest = maxOf(intrinsic.width, intrinsic.height)
    if (longest <= 0f) return IntSize(longSide, longSide)
    val ratio = longSide / longest
    return IntSize(
        width = maxOf(1, (intrinsic.width * ratio).roundToInt()),
        height = maxOf(1, (intrinsic.height * ratio).roundToInt()),
    )
}

/**
 * 把 [painter] 栅格化成一张**白底**的 PNG。
 *
 * 不能直接用 `Painter.toByteArray`：一维码的 painter 只画条、不画底色（`BarcodePainter` 没有
 * 背景参数），那样导出的是一张透明底的图——打印或贴到深色页面上就废了。这里先铺白底再画；二维码
 * 的 painter 自带白底，多铺一层没有影响。
 */
internal fun renderPng(painter: Painter, width: Int, height: Int): ByteArray {
    val target = Size(width.toFloat(), height.toFloat())
    val bitmap = ImageBitmap(width, height)
    CanvasDrawScope().draw(
        density = Density(1f, 1f),
        layoutDirection = LayoutDirection.Ltr,
        canvas = Canvas(bitmap),
        size = target,
    ) {
        drawRect(color = Color.White)
        // `Painter.draw` 是「以 DrawScope 为扩展接收者的成员函数」，两个接收者都得就位才能调：
        // 用 `with` 把 painter 摆成调度接收者，DrawScope 由这个 lambda 自己充当。
        with(painter) { draw(target) }
    }
    return bitmap.toByteArray(ImageFormat.PNG)
}
