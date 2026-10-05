package com.qcmian.clipper.devtools.tools.barcode

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import io.github.alexzhirkevich.qrose.matrix.pdf417.Pdf417ErrorCorrectionLevel
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
 * @param inputHint 这类码吃什么样的输入、**最多装多少**：编码失败时那句「要求：…」用它，结果区
 *   在**输入为空**时也用它（那时它答的是「该给什么」）。长度上限必须写在这里——各码制差别极大
 *   （EAN/UPC 只收固定位数且校验位要对、Code 39/93 封顶 80 字符、Code 128 没有硬上限但编码器会
 *   自己崩、二维码看字节容量），不写清楚用户只能靠报错去猜（每个上限都实测过，见
 *   `BarcodeFormatTest`）。
 *
 *   它**不**进输入框的占位提示，见 [inputExample]。
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
 * @param inputExample 输入框占位提示里的**示例**（「例如 …」后面那一截，取一个该码制真收的值）。
 *   占位提示只回答「这里填什么」，所以不放容量与字符集——那是 [inputHint] 的事，它出现在结果区
 *   与失败提示里。这条分工是**真机反馈纠正过来的**：原先占位符直接铺 [inputHint]，EAN-13 那条
 *   二十多字的规格在输入框里既长又答非所问（用户要的是「填什么」，不是「限多少」）。示例值都在
 *   `BarcodeFormatTest` 里验过真能编出来——它是给用户照抄的，不能是个会报错的值。
 * @param linearType 一维码的码制；`null` 表示二维码。
 * @param quietZone 二维码的静区，单位是码元个数（依各自规范取值）。
 * @param rowHeightRatio 二维码的行高与码元宽之比；只有堆叠式的 PDF417 不是 1（标准值 3）。
 */
internal enum class BarcodeFormat(
    val title: String,
    /** 导出时的建议文件名。 */
    val fileName: String,
    val inputHint: String,
    val inputExample: String,
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
    // 对的，只是没声明；严格按 Latin-1 解的才难看。
    //
    // Aztec 这一路**本工具自己已经补上**：解码页认出这种乱码会修回 UTF-8（见 `repairTextEncoding`
    // ——拿自家编的 Aztec 回头解，踩的正是这个坑）。这里要说的是**别的**扫描器：认 UTF-8 的（手机
    // 基本都认）照旧没事，只认规范的会读出西欧字母。qrose 那份 Aztec 编码器是 ZXing 编码器的移植，
    // ZXing 自己也不发 ECI，所以这不是我们这层能调的参数。
    Qr("QR", "qrcode.png", "任意文本；上限 1273–2953 字节（随纠错等级；数字 3100–7100 位、字母 1900–4300 个、汉字 420–980 个）", inputExample = "https://example.com", quietZone = 4),
    // 这里原本还有 Data Matrix（工业与物流标签常用），被去掉了：qrose 的实现对非 ASCII 会走
    // Base256，而那里写的是 `c.code and 0xFF`——每个字符只留**低字节**。于是「中文」编出来的码
    // 扫回去是乱码，而且不报错、不提示（用 ZXing 往返验证过：`中文测试` → `-KÕ`）。要重新加回来，
    // 得先绕开这个坑（自己按 UTF-8 编码后走字节模式，或换一个编码器）。
    Aztec("Aztec", "aztec.png", "任意文本；上限 1872 字节（数字约 3700、字母约 3000、汉字约 620 个）", inputExample = "https://example.com", quietZone = 1),
    Pdf417("PDF417", "pdf417.png", "任意文本；上限 1032 字节（数字约 2500、字母约 1700、汉字约 340 个）", inputExample = "https://example.com", quietZone = 2, rowHeightRatio = 3f),

    // ---------------------------------------------------------------- 一维码（条空序列）
    // 「不设上限」的两个里，Code 128 其实有暗礁：编码器用递归走位，6000 字符还行，7000 就崩在
    // 栈溢出、8192 崩在校验和 Int 溢出（见 `BarcodeFormatTest` 钉住的稳妥值）。它没有长度检查，
    // 所以这句写的是**实测可用范围**而不是库的规则——而且这个边界随栈深度浮动（6000~8000 之间
    // 时好时坏），所以语气是「建议」而不是「上限」：5000 以内实测稳妥，再多不保证。
    Code128("Code 128", "code128.png", "任意 ASCII 文本；不设长度上限，建议 5000 字符以内（再多可能因编码器递归过深而失败）", inputExample = "https://example.com", linearType = BarcodeType.Code128),
    // Code 39/93 的 80 是**扩展后**的长度：字母表之外的字符（小写、冒号一类）得拆成两个字符编码，
    // 因此写小写实际只有 40 个额度——这就是「折半」的由来。
    Code39("Code 39", "code39.png", "大写字母、数字与 - . $ / + % 和空格；最多 80 字符（小写等字符走扩展模式，一个占两个额度）", inputExample = "ABC-1234", linearType = BarcodeType.Code39),
    Code93("Code 93", "code93.png", "大写字母、数字与 - . $ / + % 和空格；最多 80 字符（小写等字符走扩展模式，一个占两个额度）", inputExample = "ABC-1234", linearType = BarcodeType.Code93),
    // EAN/UPC 的后一档（13/12/8/8 位）不是「也能收」，而是**校验位必须算对**：算错报
    // `Contents do not pass checksum`，所以那句「或 N 位」后面得跟上这个条件。
    Ean13("EAN-13", "ean13.png", "12 位数字（自动补校验位）或 13 位（校验位须正确）", inputExample = "4006381333931", linearType = BarcodeType.EAN13),
    Ean8("EAN-8", "ean8.png", "7 位数字（自动补校验位）或 8 位（校验位须正确）", inputExample = "96385074", linearType = BarcodeType.EAN8),
    UpcA("UPC-A", "upca.png", "11 位数字（自动补校验位）或 12 位（校验位须正确）", inputExample = "036000291452", linearType = BarcodeType.UPCA),
    UpcE("UPC-E", "upce.png", "7 位数字（首位须为 0 或 1，自动补校验位）或 8 位（校验位须正确）", inputExample = "01234565", linearType = BarcodeType.UPCE),
    Itf("ITF", "itf.png", "偶数位数字，最多 80 位", inputExample = "12345678", linearType = BarcodeType.ITF),
    // 起止符：不写就自动补 A；自己写了就必须首尾成对（只写头一个会报 Invalid start/end guards）。
    // 允许的起止符是 A~D（个别读码器习惯写 T/N/*/E，这里也认，会映射到 A~D）。
    Codabar("Codabar", "codabar.png", "数字与 - $ : / . +；不设长度上限，起止符可省（自动补 A），写了须首尾成对", inputExample = "1234-5678", linearType = BarcodeType.Codabar),
}

/**
 * QR 可选的纠错等级。
 *
 * 三种二维码**各有一套刻度**（这里是四档固定等级，Aztec 是百分比，PDF417 是 0–8 级），所以界面
 * 上按码制换一组选项，而不是把它们拼成一个统一数值——那只会让人以为它们是同一种东西。
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

/**
 * Aztec 纠错百分比的取值范围。
 *
 * 与 QR 的「四档固定等级」不同，Aztec 的纠错本就是**百分比**（编码器的 `minEccPercent`）：符号里
 * 预留出这么一大块面积放纠错码字，规范建议至少 23% + 3 个码字，编码器默认 33%。它是连续量，
 * 所以界面上给的是滑杆而不是切几档——切成四档既够不到 23% 这种值，也看不出它本来是连续的。
 *
 * 下界取 5：再低就等于没有纠错（规范不推荐），但留着它，让「容量优先」这件事有得选；上界取 90：
 * 那会儿符号几乎全是纠错码字，稍长一点的内容就会装不下（编码器会抛 `Data too large`）。
 */
internal val AztecEcPercentRange = 5..90

/** 编码器自己的默认百分比。 */
internal const val DefaultAztecEcPercent = 33

/**
 * PDF417 的纠错档位：规范里的 0–8 级（每升一级，纠错码字翻倍），外加编码器的「自动」。
 *
 * 十档铺不进一条轨道，因此界面上用下拉——与「码制」同一个理由（见 `DevToolMenuButton`）。
 * 默认「自动」：编码器按数据长度挑推荐等级（≤40 码字用 2 级，≤160 用 3 级，依此类推）。
 */
internal enum class Pdf417ErrorLevel(
    val title: String,
    val level: Pdf417ErrorCorrectionLevel,
    private val codewords: Int,
) {
    Auto("自动", Pdf417ErrorCorrectionLevel.Auto, 0),
    Level0("0", Pdf417ErrorCorrectionLevel.Level0, 2),
    Level1("1", Pdf417ErrorCorrectionLevel.Level1, 4),
    Level2("2", Pdf417ErrorCorrectionLevel.Level2, 8),
    Level3("3", Pdf417ErrorCorrectionLevel.Level3, 16),
    Level4("4", Pdf417ErrorCorrectionLevel.Level4, 32),
    Level5("5", Pdf417ErrorCorrectionLevel.Level5, 64),
    Level6("6", Pdf417ErrorCorrectionLevel.Level6, 128),
    Level7("7", Pdf417ErrorCorrectionLevel.Level7, 256),
    Level8("8", Pdf417ErrorCorrectionLevel.Level8, 512),
    ;

    /**
     * 下拉里的行文字。
     *
     * 档位本身只是规范里的一个数字，所以把「这一档意味着多少纠错码字」一并写出来——分段控件那种
     * 悬停提示在下拉里没有位置（见 `DevToolMenuButton`），不写在这儿就没人看得见。
     */
    val menuLabel: String
        get() = if (level == Pdf417ErrorCorrectionLevel.Auto) {
            "自动（按数据长度推荐）"
        } else {
            "$title · $codewords 个纠错码字"
        }
}

/** 一次编码的结果：能直接画的 painter，以及给状态栏用的「多大」。 */
internal class EncodedCode(
    val painter: Painter,
    /** 二维码是「25×25」（模块数），一维码是「95 模块」（条空格数）。 */
    val sizeLabel: String,
    /**
     * 一维码下方该印的那串字符（人类可读文本）；二维码没有这一行，为 `null`。
     *
     * 见 [humanReadableOf]——它可能与用户输入不完全一样（EAN / UPC 少写的那位校验位）。
     */
    val humanReadable: String? = null,
)

/**
 * 把文本编成 [format] 的码；编不出来（长度 / 字符集不合该码制、内容超出容量）时抛异常。
 *
 * 纠错参数按码制分开传，因为三种二维码的刻度本来就不同（见 [QrErrorLevel]、[AztecEcPercentRange]、
 * [Pdf417ErrorLevel]）。后两个都取编码器自己的默认值，所以只关心 QR 纠错的调用方照旧传三个参数
 * 就行。
 *
 * 纯函数、不碰 Compose 状态：调用方负责放到后台线程，并接住异常（见 `BarcodeDevTool`）。
 */
internal fun encodeBarcode(
    format: BarcodeFormat,
    data: String,
    level: QrErrorLevel,
    aztecEcPercent: Int = DefaultAztecEcPercent,
    pdf417Ec: Pdf417ErrorLevel = Pdf417ErrorLevel.Auto,
): EncodedCode {
    val linear = format.linearType
    if (linear != null) {
        // 一维码：编码器直接给出条空序列，静区交给 builder（见 [linearBarcodeBuilder]）。
        val code = linear.encoder.encode(data)
        val painter = BarcodePainter(
            code = code,
            brush = SolidColor(Color.Black),
            builder = linearBarcodeBuilder(LinearQuietZoneModules),
        )
        return EncodedCode(painter, "${code.size} 模块", humanReadableOf(linear, data))
    }

    val matrix = when (format) {
        BarcodeFormat.Qr -> QroseEncoders.QR(level.level).encode(data)
        BarcodeFormat.Aztec -> QroseEncoders.Aztec(minEccPercent = aztecEcPercent).encode(data)
        BarcodeFormat.Pdf417 -> QroseEncoders.PDF417(errorCorrectionLevel = pdf417Ec.level).encode(data)
        else -> error("${format.title} 是一维码，不该走到这条路")
    }
    return EncodedCode(matrixPainter(format, matrix), "${matrix.width}×${matrix.height}")
}

/** EAN / UPC 允许少写校验位的那几位：少写时印出来的字符串要补到这么长。 */
private val ShortEanLengths = mapOf(
    BarcodeType.EAN13 to 12,
    BarcodeType.EAN8 to 7,
    BarcodeType.UPCA to 11,
    BarcodeType.UPCE to 7,
)

/**
 * 一维码下方印的那串字符：**与码里真正编进去的内容一致**。
 *
 * EAN / UPC 允许少写一位（校验位由编码器补出来），这时印出来的也得是补全后的那一串——否则人照着
 * 标签敲进去的号和扫出来的号对不上，这一行就白印了。
 *
 * 补法**不抄校验位算法**，而是问编码器：把末位逐个试 0–9，它只认正确的那一个。抄算法的代价是
 * 两处实现会慢慢漂开（UPC-E 还得先展开成 UPC-A），而试十次的开销在这个量级上可以忽略。
 */
private fun humanReadableOf(type: BarcodeType, data: String): String {
    val shortLength = ShortEanLengths[type] ?: return data
    if (data.length != shortLength || data.any { !it.isDigit() }) return data
    for (digit in '0'..'9') {
        val candidate = data + digit
        if (runCatching { type.encoder.encode(candidate) }.isSuccess) return candidate
    }
    return data
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

/** 码下方那行文字占条高的比例。整张图按长边缩放，文字因此跟着码一起缩，不会「码大字小」。 */
private const val HumanReadableHeightRatio = 0.3f

/** 文字块里留给字形的比例，剩下的是上下留白。 */
private const val HumanReadableTypeRatio = 0.68f

/**
 * 一维码 + 下方一行人类可读文本。
 *
 * 为什么要包一层 painter、而不是在结果区里叠一个 `Text`：**导出的 PNG 里也得有这一行**（它本来就
 * 是印给人看的），两条路必须画同一份东西——各画一遍早晚会漂开（字号、间距、留白都会不一样）。
 *
 * 位置约定：文字在**条的下方**、横向居中，宽度不越过码（含左右静区）——静区是给扫描器认边界的，
 * 文字侵进去就可能读不出来，而这一行本来只是给人看的。按规范，EAN / UPC 的数字该分成几组、护线
 * 该延伸到文字旁；这里先统一居中一行，要严格排布时再单独做。
 *
 * 字号随绘制尺寸等比算（[HumanReadableHeightRatio] / [HumanReadableTypeRatio]），预览与导出的
 * 观感因此一致；内容过长时收成一行并省略，不会挤掉条的高度。
 */
private class HumanReadablePainter(
    private val code: Painter,
    private val label: String,
    private val measurer: TextMeasurer,
) : Painter() {

    override val intrinsicSize: Size = code.intrinsicSize.let {
        Size(it.width, it.height * (1f + HumanReadableHeightRatio))
    }

    override fun DrawScope.onDraw() {
        val codeHeight = size.height / (1f + HumanReadableHeightRatio)
        // 码按「整宽 × 条高」画：静区在它内部让出来（见 [linearBarcodeBuilder]）。
        with(code) { draw(Size(size.width, codeHeight)) }

        val blockHeight = size.height - codeHeight
        val layout = measurer.measure(
            text = label,
            style = TextStyle(
                color = Color.Black,
                fontFamily = FontFamily.Monospace,
                fontSize = (blockHeight * HumanReadableTypeRatio).toSp(),
            ),
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
            maxLines = 1,
            constraints = Constraints(maxWidth = (size.width * 0.98f).roundToInt()),
        )
        drawText(
            textLayoutResult = layout,
            color = Color.Black,
            topLeft = Offset(
                x = (size.width - layout.size.width) / 2f,
                y = codeHeight + (blockHeight - layout.size.height) / 2f,
            ),
        )
    }
}

/**
 * 拿去画（预览）与导出（PNG）的那份 painter：一维码且要求印字时，套上下方那一行。
 *
 * 只有一维码有 [EncodedCode.humanReadable]——二维码把载荷印在下面没有意义，也不是惯例。
 */
internal fun EncodedCode.painterFor(printText: Boolean, measurer: TextMeasurer): Painter =
    if (printText && humanReadable != null) {
        HumanReadablePainter(painter, humanReadable, measurer)
    } else {
        painter
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
