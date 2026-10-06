package com.qcmian.clipper.devtools.tools.hex

/**
 * 每行显示多少个字节。
 *
 * 三档都是**2 的幂**：偏移量与十六进制列因此按固定的位宽对齐，眼睛能一列一列地读。16 是默认，
 * 也是 `hexdump -C` / `xxd` 的默认——绝大多数二进制文件都是按它排的，读的人早已习惯。
 */
internal enum class HexRowWidth(val bytesPerRow: Int, val title: String) {
    Eight(8, "每行 8"),
    Sixteen(16, "每行 16"),
    ThirtyTwo(32, "每行 32"),
}

/**
 * 一次排版的产物。
 *
 * [text] 是要显示的那份 dump（行与行之间一个 `\n`，末行不带换行）；[totalBytes] 是**整份输入**
 * 的字节数，[shownBytes] 是真正排进来的那一段——两者不等即被 [MaxDumpBytes] 截断。分开记是为了
 * 让状态栏能如实交代「共多少、排了多少」，而不是假装输入就这么大。
 */
internal class HexDump(
    val text: String,
    val totalBytes: Int,
    val shownBytes: Int,
    val rows: Int,
) {
    /** 输入比排出来的多——只排了前一段。 */
    val truncated: Boolean get() = shownBytes < totalBytes
}

/**
 * 二进制查看的格式逻辑：把一段字节排成「偏移量 + 十六进制 + 字符」三列。
 *
 * 版面照搬 `hexdump -C`（也即 `xxd` 那一族的通行写法），不另起一套：
 *
 * ```
 * 00000000  48 65 6c 6c 6f 00 00 00  00 00 00 00 00 00 00 00  |Hello...........|
 * 00000010  ...
 * ```
 *
 *  1. 左侧 8 位十六进制偏移量（这一行第一个字节在输入里的下标），随后两个空格；
 *  2. 每行 [HexRowWidth] 个字节，每个字节两位十六进制；[GroupSize] 个字节一小组，组间多空一格；
 *  3. 末尾是字符列：可打印 ASCII 原样显示，其余一律 `.`（`|` 只是把它框出来，方便定位）；
 *  4. 最后一行不足一行时，十六进制与字符列都用空格补齐，于是右侧的 `|` 始终对齐。
 *
 * 字符列刻意只认可打印 ASCII，不做 UTF-8 / GBK 的多字节解码：一个多字节字符跨好几格，逐字节
 * 对齐的版面就维持不住了（一个汉字该落在哪一格？）。要读中文串，看十六进制比看乱码列更可靠。
 */
internal object HexFormat {

    /** 每 8 字节在十六进制列里空一格，与 `hexdump -C` 的版面一致。 */
    private const val GroupSize = 8

    /** 偏移量列的位宽。 */
    private const val OffsetDigits = 8

    /**
     * 一次最多排多少字节。
     *
     * dump 出来是一整段文本，交回代码框时才按行虚拟化——因此**这一头**要设上限：它是先在内存里
     * 拼出来的。2 MiB 排成 16 字节一行约 13 万行、约一千万字符，仍在桌面端一次排版吃得下的
     * 量级；再大就该考虑分段或专门的虚拟列表了，不是这一版的范围。超出的部分不排，由状态栏
     * 如实说明「共多少、只排了前多少」。
     */
    const val MaxDumpBytes: Int = 2 * 1024 * 1024

    private const val LOWER = "0123456789abcdef"
    private const val UPPER = "0123456789ABCDEF"

    /**
     * 把 [bytes] 排成 dump。
     *
     * @param bytesPerRow 每行字节数（见 [HexRowWidth]），必须为正。
     * @param uppercase 十六进制用大写还是小写；偏移量跟着一起变，免得一列大写一列小写。
     * @param showCharColumn 是否画末尾的字符列。
     * @param maxBytes 一次最多排多少字节（见 [MaxDumpBytes]）。
     */
    fun dump(
        bytes: ByteArray,
        bytesPerRow: Int,
        uppercase: Boolean,
        showCharColumn: Boolean,
        maxBytes: Int = MaxDumpBytes,
    ): HexDump {
        require(bytesPerRow > 0) { "bytesPerRow 必须为正" }
        val shown = minOf(bytes.size, maxBytes)
        val rows = if (shown == 0) 0 else (shown + bytesPerRow - 1) / bytesPerRow
        val digits = if (uppercase) UPPER else LOWER
        // 每行大约：「偏移 + 两空格」10 字符，十六进制每字节 3 字符（外加组间空格），字符列紧随其后。
        // 先按上界开好容量，避免十几万次 append 一路扩容。
        val builder = StringBuilder(rows * (bytesPerRow * 4 + 40))

        for (row in 0 until rows) {
            val rowStart = row * bytesPerRow
            appendOffset(builder, rowStart, digits)
            builder.append("  ")

            for (i in 0 until bytesPerRow) {
                val index = rowStart + i
                if (index < shown) {
                    val value = bytes[index].toInt() and 0xFF
                    builder.append(digits[value ushr 4]).append(digits[value and 0xF])
                } else {
                    builder.append("  ")
                }
                builder.append(' ')
                if ((i + 1) % GroupSize == 0 && i != bytesPerRow - 1) builder.append(' ')
            }

            if (showCharColumn) {
                builder.append(" |")
                for (i in 0 until bytesPerRow) {
                    val index = rowStart + i
                    builder.append(if (index < shown) textChar(bytes[index]) else ' ')
                }
                builder.append('|')
            }

            if (row != rows - 1) builder.append('\n')
        }

        return HexDump(builder.toString(), bytes.size, shown, rows)
    }

    /**
     * 一个字节在字符列里显示成什么：可打印 ASCII（0x20–0x7E）原样，其余用 `.`。
     *
     * 判据与 `hexdump` / `xxd` 相同：0x7F（DEL）与 0x20 以下的控制字符一律不进字符列——它们多半
     * 是二进制噪声，画出来只会把可读的字符串冲散。
     */
    fun textChar(byte: Byte): Char {
        val value = byte.toInt() and 0xFF
        return if (value in 0x20..0x7E) value.toChar() else '.'
    }

    /** 把 [value] 写成 [OffsetDigits] 位、前导补零的十六进制。 */
    private fun appendOffset(builder: StringBuilder, value: Int, digits: String) {
        for (shift in (OffsetDigits - 1) * 4 downTo 0 step 4) {
            builder.append(digits[(value ushr shift) and 0xF])
        }
    }
}
