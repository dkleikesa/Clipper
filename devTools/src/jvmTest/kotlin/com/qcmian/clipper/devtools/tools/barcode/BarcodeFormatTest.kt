package com.qcmian.clipper.devtools.tools.barcode

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 条码工具依赖 qrose 的那条链路：**编码 → 矢量绘制 → 栅格化并编成 PNG**，十二种码制各走一遍。
 *
 * 为什么值得单测：这三步全在库里，工具侧只是接起来，真跑错了在界面上也只表现为「图没出来」——
 * 而成因可能差很远（码制选错、编码器抛错、一维码那边忘了铺白底、导出尺寸算错）。这里钉住：
 *
 *  - 每种码制都编得出来、导出得回一张解码得了的 PNG，且尺寸与算好的完全一致；
 *  - 形状确实分成两类（方形码导成正方形、一维码与 PDF417 导成横条）——这一条守的是「码制选项
 *    真的接到了对应编码器」，而不是所有选项都画同一种码；
 *  - 一维码两侧**真的留出了静区**（扫出来的最左侧那一列是白的）——一维码的 painter 本身不画
 *    静区也不画底色，这两件事都在我们这边补，写错了只看图是看不出来的；
 *  - 输入不合规时编码器会**抛错**（因此工具必须接住它，见 `BarcodeDevTool`）。
 */
class BarcodeFormatTest {

    /**
     * 每类码各给一份合法内容。
     *
     * 一维码对长度与字符集的要求差别很大（EAN 只收数字、ITF 要偶数位……），共用一段文本的话
     * 除了 Code 128 之外全都会抛错。
     */
    private fun sample(format: BarcodeFormat): String = when (format) {
        BarcodeFormat.Ean13 -> "400638133393" // 12 位，校验位由编码器补
        BarcodeFormat.Ean8 -> "9638507" // 7 位
        BarcodeFormat.UpcA -> "03600029145" // 11 位
        BarcodeFormat.UpcE -> "0123456" // 首位是号制 0
        BarcodeFormat.Itf -> "12345678" // 偶数位
        BarcodeFormat.Code39, BarcodeFormat.Code93 -> "ABC-1234"
        BarcodeFormat.Codabar -> "123456" // 起止符由编码器补
        else -> "https://example.com/clipper"
    }

    @Test
    fun `每种码制都能编出码并导出成 PNG`() {
        for (format in BarcodeFormat.entries) {
            val code = encodeBarcode(format, sample(format), QrErrorLevel.Medium)
            val size = exportSizeOf(code.painter, 512)
            val decoded = assertNotNull(
                renderPng(code.painter, size.width, size.height).decodeToImageBitmap(),
                "${format.title} 导出的应当是解码器认得的图片",
            )
            assertEquals(size.width, decoded.width, "${format.title} 导出宽度")
            assertEquals(size.height, decoded.height, "${format.title} 导出高度")
            assertTrue(code.sizeLabel.isNotBlank(), "${format.title} 应当能报出尺寸")
        }
    }

    @Test
    fun `方形码导出成正方形 横条码导出成横条`() {
        // 这些是方块。
        listOf(BarcodeFormat.Qr, BarcodeFormat.Aztec).forEach { format ->
            val size = exportSizeOf(encodeBarcode(format, sample(format), QrErrorLevel.Medium).painter, 512)
            assertEquals(size.width, size.height, "${format.title} 是方形的")
        }

        // PDF417 是堆叠的长条，一维码是单行长条——都不能被导出成正方形（那会把码缩得很小）。
        val bars = listOf(BarcodeFormat.Pdf417) + BarcodeFormat.entries.filter { it.linearType != null }
        bars.forEach { format ->
            val size = exportSizeOf(encodeBarcode(format, sample(format), QrErrorLevel.Medium).painter, 512)
            assertTrue(size.width > size.height, "${format.title} 是横条")
        }
    }

    @Test
    fun `一维码两侧留出了静区`() {
        val code = encodeBarcode(BarcodeFormat.Code128, "ABC-1234", QrErrorLevel.Medium)
        val size = exportSizeOf(code.painter, 512)
        val pixels = renderPng(code.painter, size.width, size.height).decodeToImageBitmap().toPixelMap()
        val row = size.height / 2

        // 最左侧必须是白底：一维码贴边时扫描器会把相邻的墨线当成码的一部分。这条同时验证了
        // 「导出时铺了白底」——`BarcodePainter` 自己不画底色，漏了这一步这里会是全透明。
        assertEquals(Color.White, pixels[0, row], "左侧应当留出静区")
        assertEquals(Color.White, pixels[size.width - 1, row], "右侧应当留出静区")
        // 中间总得有黑条，否则上面两条「都是白」的断言在「什么都没画」时也会通过。
        assertTrue(
            (0 until size.width).any { pixels[it, row].red < 0.5f },
            "条码中间应当有黑条",
        )
    }

    @Test
    fun `输入形状不合规时编码器抛错`() {
        // 一维码的输入要求很具体：这两条都是「形状不对」，编码器会抛错，工具接住后给提示。
        assertFailsWith<Exception> { encodeBarcode(BarcodeFormat.Ean13, "abc", QrErrorLevel.Medium) }
        assertFailsWith<Exception> { encodeBarcode(BarcodeFormat.Itf, "123", QrErrorLevel.Medium) }
    }

    @Test
    fun `文本超出容量时编码器抛错`() {
        // 二维码装不下这么多字符：工具必须接住这个异常并给出一句交代，
        // 而不是让它冒到组合里把窗口炸掉（见 `BarcodeDevTool` 的生成副作用）。
        val tooLong = "a".repeat(10_000)

        assertFailsWith<Exception> {
            encodeBarcode(BarcodeFormat.Qr, tooLong, QrErrorLevel.Low)
        }
    }

    @Test
    fun `长度上限与提示里写的一致`() {
        // 上限是**写给用户看的文案**（`BarcodeFormat.inputHint` 的占位提示与失败提示第一行都用它），
        // 所以在这里钉住：库哪天变松或变紧，这条测试会先红，而不是让人从界面上发现文案在骗人。
        //
        // 二维码的容量按**字节**算（一个汉字 3 字节），且随纠错等级变：这里取写进提示的那个区间的两端。
        // 汉字是 3 字节，所以只能验到 3 的倍数上：984 个汉字 = 2952 字节（规范写的上限是 2953 字节，
        // 两者并不矛盾——多出来的那 1~2 字节落在不到一个汉字的位置）。
        assertTrue(fits(BarcodeFormat.Qr, "中".repeat(984), QrErrorLevel.Low), "QR 低纠错装得下 2952 字节")
        assertFalse(fits(BarcodeFormat.Qr, "中".repeat(985), QrErrorLevel.Low), "再多 3 字节就装不下")
        assertTrue(fits(BarcodeFormat.Qr, "中".repeat(424), QrErrorLevel.High), "QR 高纠错装得下 1272 字节")
        assertFalse(fits(BarcodeFormat.Qr, "中".repeat(425), QrErrorLevel.High), "再多 3 字节就装不下")

        assertTrue(fits(BarcodeFormat.Aztec, "中".repeat(624)), "Aztec：624 个汉字（1872 字节）")
        assertFalse(fits(BarcodeFormat.Aztec, "中".repeat(625)), "Aztec 到此为止")

        assertTrue(fits(BarcodeFormat.Pdf417, "中".repeat(344)), "PDF417：344 个汉字（1032 字节）")
        assertFalse(fits(BarcodeFormat.Pdf417, "中".repeat(345)), "PDF417 到此为止")

        // 同一个符号能装多少**字符**随内容变（数字 4 位/字符、字母 5 位、汉字 8 位/字节），
        // 提示里给的是各字符集的锚点，这里逐个钉住——它们是用户会拿来对照自己那段文本的数。
        assertTrue(fits(BarcodeFormat.Aztec, "1".repeat(3748)), "Aztec：3748 位数字")
        assertFalse(fits(BarcodeFormat.Aztec, "1".repeat(3749)), "Aztec 数字到此为止")
        assertTrue(fits(BarcodeFormat.Aztec, "A".repeat(3000)), "Aztec：3000 个字母")
        assertFalse(fits(BarcodeFormat.Aztec, "A".repeat(3001)), "Aztec 字母到此为止")
        assertTrue(fits(BarcodeFormat.Pdf417, "1".repeat(2528)), "PDF417：2528 位数字")
        assertFalse(fits(BarcodeFormat.Pdf417, "1".repeat(2529)), "PDF417 数字到此为止")
        assertTrue(fits(BarcodeFormat.Pdf417, "A".repeat(1726)), "PDF417：1726 个字母")
        assertFalse(fits(BarcodeFormat.Pdf417, "A".repeat(1727)), "PDF417 字母到此为止")
        assertTrue(fits(BarcodeFormat.Qr, "1".repeat(7089), QrErrorLevel.Low), "QR 低：7089 位数字")
        assertFalse(fits(BarcodeFormat.Qr, "1".repeat(7090), QrErrorLevel.Low), "QR 低数字到此为止")
        assertTrue(fits(BarcodeFormat.Qr, "1".repeat(3057), QrErrorLevel.High), "QR 高：3057 位数字")
        assertFalse(fits(BarcodeFormat.Qr, "1".repeat(3058), QrErrorLevel.High), "QR 高数字到此为止")
        assertTrue(fits(BarcodeFormat.Qr, "A".repeat(4296), QrErrorLevel.Low), "QR 低：4296 个字母")
        assertTrue(fits(BarcodeFormat.Qr, "A".repeat(1852), QrErrorLevel.High), "QR 高：1852 个字母")
        assertFalse(fits(BarcodeFormat.Qr, "A".repeat(4297), QrErrorLevel.Low), "QR 低字母到此为止")

        // 一维码按字符数封顶。Code 39/93 的 80 是**扩展后**的长度：小写要拆成两个字符，
        // 所以同一个上限在小写输入上只剩 40 个。
        listOf(BarcodeFormat.Code39, BarcodeFormat.Code93).forEach { format ->
            assertTrue(fits(format, "A".repeat(80)), "${format.title}：80 个大写")
            assertFalse(fits(format, "A".repeat(81)), "${format.title} 大写到此为止")
            assertTrue(fits(format, "a".repeat(40)), "${format.title}：40 个小写（一个占两个额度）")
            assertFalse(fits(format, "a".repeat(41)), "${format.title} 小写到此为止")
        }
        assertTrue(fits(BarcodeFormat.Itf, "1".repeat(80)), "ITF：80 位")
        assertFalse(fits(BarcodeFormat.Itf, "1".repeat(82)), "ITF 到此为止（偶数位）")
        assertFalse(fits(BarcodeFormat.Itf, "1".repeat(81)), "ITF 奇数位不合规")

        // Code 128 没有长度检查，但编码器递归 + 校验和用 Int 累加，边界随栈深度浮动。
        // 提示里建议 5000，这里就钉 5000（再多不保证，7000 实测会栈溢出）。
        assertTrue(fits(BarcodeFormat.Code128, "a".repeat(5_000)), "Code 128：5000 字符实测稳妥")
        assertFalse(fits(BarcodeFormat.Code128, "中".repeat(10)), "Code 128 只收 ASCII")

        // Codabar 也没长度检查；起止符不写就补 A，写了必须首尾成对。
        assertTrue(fits(BarcodeFormat.Codabar, "1".repeat(4_096)), "Codabar 不设上限")
        assertEquals(
            encodeBarcode(BarcodeFormat.Codabar, "A123456A", QrErrorLevel.Medium).sizeLabel,
            encodeBarcode(BarcodeFormat.Codabar, "123456", QrErrorLevel.Medium).sizeLabel,
            "起止符省掉时自动补的应当与手写 A…A 完全等价",
        )
        assertFalse(fits(BarcodeFormat.Codabar, "A123456"), "只写头的起止符不合规")
    }

    /**
     * EAN / UPC 的第二档长度（13 / 12 / 8 / 8 位）要求**校验位正确**，不是照单全收。
     *
     * 这是最容易写错的一句描述：只写「或 13 位」会让人以为随手凑 13 位数字就行，实测会被
     * `Contents do not pass checksum` 顶回来。这里把两档各自的样本钉住：
     * 12 位不带校验位（自动补，永远成功）、13 位带正确校验位（成功）、13 位校验位写错（失败）。
     */
    @Test
    fun `EAN 与 UPC 的校验位长度`() {
        // 400638133393 的校验位是 1；03600029145 的是 2；9638507 的是 4；0123456 的是 5。
        listOf(
            Triple(BarcodeFormat.Ean13, "400638133393", "4006381333931"),
            Triple(BarcodeFormat.Ean8, "9638507", "96385074"),
            Triple(BarcodeFormat.UpcA, "03600029145", "036000291452"),
            Triple(BarcodeFormat.UpcE, "0123456", "01234565"),
        ).forEach { (format, withoutCheck, withCheck) ->
            // 故意写错的校验位得跟正确的那位不同，否则这一条等于什么都没验。
            val wrongCheck = if (withCheck.last() == '9') '8' else '9'
            assertTrue(fits(format, withoutCheck), "${format.title}：${withoutCheck.length} 位，校验位自动补")
            assertTrue(fits(format, withCheck), "${format.title}：${withCheck.length} 位且校验位正确")
            assertFalse(
                fits(format, withCheck.dropLast(1) + wrongCheck),
                "${format.title}：校验位写错应当报错",
            )
            assertFalse(fits(format, "abc"), "${format.title}：只收数字")
        }

        // UPC-E 的号制位只能是 0 或 1。
        assertFalse(fits(BarcodeFormat.UpcE, "2123456"), "UPC-E 首位必须 0 或 1")
    }

    /**
     * 输入框占位提示里的示例，必须真能编出码。
     *
     * 它是给用户照抄的值（占位提示写的是「例如 …」）：示例本身不合格的话，用户照着填一遍只会
     * 撞上「文本要求」那句报错，比不给示例还糟。
     */
    @Test
    fun `占位提示里的示例都编得出来`() {
        for (format in BarcodeFormat.entries) {
            assertTrue(
                fits(format, format.inputExample),
                "${format.title} 的示例「${format.inputExample}」应当编得出来",
            )
        }
    }

    /**
     * 纠错档位真的传到了编码器：纠错越高，留给数据的面积越少，同一段文本就会从「装得下」变成
     * 「装不下」。这比比对符号尺寸稳——尺寸的档位是离散的，容量才是纠错的直接后果。
     *
     * 取 3000 个字符是有讲究的：实测 Aztec 在这个长度上 10% 装得下、33% 与 50% 都装不下（分界在
     * 2500~2800 之间），所以它同时能验两个档位。
     */
    @Test
    fun `纠错档位影响容量`() {
        val text = "a".repeat(3000)
        assertTrue(
            fits(BarcodeFormat.Aztec, text, aztecEcPercent = 10),
            "Aztec 10% 纠错：3000 字符装得下",
        )
        assertFalse(
            fits(BarcodeFormat.Aztec, text, aztecEcPercent = DefaultAztecEcPercent),
            "Aztec 33%（默认）：同一段就装不下了",
        )
        assertFalse(
            fits(BarcodeFormat.Aztec, text, aztecEcPercent = 50),
            "Aztec 50% 纠错：更装不下",
        )

        val long = "a".repeat(900)
        assertTrue(
            fits(BarcodeFormat.Pdf417, long, pdf417Ec = Pdf417ErrorLevel.Level0),
            "PDF417 0 级：900 字符装得下",
        )
        assertFalse(
            fits(BarcodeFormat.Pdf417, long, pdf417Ec = Pdf417ErrorLevel.Level8),
            "PDF417 8 级：512 个纠错码字一挤，同一段就装不下了",
        )
    }

    /**
     * 一维码下方印的那串字符 = **码里真正编进去的内容**。
     *
     * EAN / UPC 允许少写校验位，印出来时得补全：否则人照着标签敲的号与扫出来的对不上，这一行就
     * 白印了。这里不只比长度，还把补出来的那一串**再编一次**——编码器只收校验位正确的输入，
     * 因此这一编同时证明了补上去的那位是对的。
     */
    @Test
    fun `一维码的可读文本会补全校验位`() {
        listOf(
            BarcodeFormat.Ean13 to "400638133393",
            BarcodeFormat.Ean8 to "9638507",
            BarcodeFormat.UpcA to "03600029145",
            BarcodeFormat.UpcE to "0123456",
        ).forEach { (format, short) ->
            val label = assertNotNull(
                encodeBarcode(format, short, QrErrorLevel.Medium).humanReadable,
                "${format.title} 一维码应当有可读文本",
            )
            assertEquals(short.length + 1, label.length, "${format.title} 印出来的该补上校验位")
            assertTrue(label.startsWith(short), "${format.title} 只该在末尾多一位")
            assertTrue(fits(format, label), "${format.title}：补出来的校验位必须被编码器接受")
        }

        // 已经是全长就原样印；非 EAN / UPC 的一维码也原样印。
        assertEquals(
            "4006381333931",
            encodeBarcode(BarcodeFormat.Ean13, "4006381333931", QrErrorLevel.Medium).humanReadable,
        )
        assertEquals(
            "ABC-1234",
            encodeBarcode(BarcodeFormat.Code39, "ABC-1234", QrErrorLevel.Medium).humanReadable,
        )
        // 二维码没有这一行：载荷印在码下面没有意义，也不是惯例。
        assertNull(encodeBarcode(BarcodeFormat.Qr, "hello", QrErrorLevel.Medium).humanReadable)
    }

    /**
     * 印字那一行得**真画进图里**：它本来就是印给人（以及导出的 PNG）看的，只在预览里叠个 `Text`
     * 是不够的。这里同时钉住位置约定——文字在条的下方，而条那一行的左右静区不受影响。
     */
    @Test
    fun `一维码印字会把文字画进图里`() {
        val code = encodeBarcode(BarcodeFormat.Code39, "ABC-1234", QrErrorLevel.Medium)
        val plain = code.painterFor(printText = false, TestMeasurer)
        val labeled = code.painterFor(printText = true, TestMeasurer)

        // 关掉时拿到的就是原来那份 painter，不多包一层。
        assertSame(code.painter, plain, "不印字时不该套那层 painter")
        // 开了之后整张图变高：下方给文字让出一块。
        assertTrue(
            labeled.intrinsicSize.height > plain.intrinsicSize.height,
            "印字要给文字让出一块高度",
        )

        val size = exportSizeOf(labeled, 512)
        val pixels = renderPng(labeled, size.width, size.height).decodeToImageBitmap().toPixelMap()
        val barRow = size.height / 3
        // 文字在下四分之一里（具体哪几行取决于字号，断言整个带子而不是某一行）。
        val labelBand = (size.height * 0.8f).toInt() until size.height

        assertTrue((0 until size.width).any { pixels[it, barRow].red < 0.5f }, "上半部分应当是条")
        assertTrue(
            labelBand.any { row -> (0 until size.width).any { pixels[it, row].red < 0.5f } },
            "下半部分应当印出了文字",
        )
        // 静区照旧：条那一行的两端仍是白的。
        assertEquals(Color.White, pixels[0, barRow], "左侧静区不该被文字碰到")
        assertEquals(Color.White, pixels[size.width - 1, barRow], "右侧静区同理")
    }

    /**
     * Aztec 纠错滑杆的**两端**都得是能用的取值。
     *
     * 滑杆与分段控件不同：整条轨道上每一格用户都拖得到，所以不能有「拖到某一段必然报错」的区间——
     * 那种轨道等于摆设。这里用最短的内容钉住两端（长内容在两端的表现本来就不一样，见上一条）。
     */
    @Test
    fun `Aztec 纠错滑杆两端的取值都编得出来`() {
        val text = "clipper"
        assertTrue(
            fits(BarcodeFormat.Aztec, text, aztecEcPercent = AztecEcPercentRange.first),
            "最小档（${AztecEcPercentRange.first}%）也该编得出来",
        )
        assertTrue(
            fits(BarcodeFormat.Aztec, text, aztecEcPercent = AztecEcPercentRange.last),
            "最大档（${AztecEcPercentRange.last}%）对短内容同样可用",
        )
    }

    private fun fits(
        format: BarcodeFormat,
        payload: String,
        level: QrErrorLevel = QrErrorLevel.Medium,
        aztecEcPercent: Int = DefaultAztecEcPercent,
        pdf417Ec: Pdf417ErrorLevel = Pdf417ErrorLevel.Auto,
    ): Boolean = runCatching { encodeBarcode(format, payload, level, aztecEcPercent, pdf417Ec) }.isSuccess

    private companion object {
        /**
         * 画文字要的度量器。测试里没有组合环境，按 1:1 密度直接建一个——字号是按**绘制尺寸**算的
         * （见 `HumanReadablePainter`），所以这里的密度取多少都不影响断言。
         */
        val TestMeasurer = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)
    }
}
