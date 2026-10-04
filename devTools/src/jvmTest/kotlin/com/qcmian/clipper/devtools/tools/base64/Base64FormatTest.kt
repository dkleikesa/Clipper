package com.qcmian.clipper.devtools.tools.base64

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Base64 的格式逻辑：往返、URL 安全、Data URL、错误定位、UTF-8 判定、图片识别、体积换算。 */
class Base64FormatTest {

    @Test
    fun `text round trips through encode and decode`() {
        val bytes = "你好，Clipper!\n第二行".encodeToByteArray()
        val encoded = Base64Format.encode(bytes, urlSafe = false)
        assertEquals(
            "你好，Clipper!\n第二行",
            Base64Format.decode(encoded).getOrThrow().decodeToString(),
        )
    }

    @Test
    fun `standard encoding pads while url safe drops the padding`() {
        assertEquals("TWFu", Base64Format.encode("Man".encodeToByteArray(), urlSafe = false))
        assertEquals("TWE=", Base64Format.encode("Ma".encodeToByteArray(), urlSafe = false))
        assertEquals("TWE", Base64Format.encode("Ma".encodeToByteArray(), urlSafe = true))
        // 0xFB 0xFF 用到 62 / 63 号字符，正是两份字母表分岔的地方。
        val high = byteArrayOf(0xFB.toByte(), 0xFF.toByte())
        assertEquals("+/8=", Base64Format.encode(high, urlSafe = false))
        assertEquals("-_8", Base64Format.encode(high, urlSafe = true))
    }

    @Test
    fun `decoding tolerates whitespace and accepts both alphabets`() {
        assertEquals("Man", Base64Format.decode("TWFu").getOrThrow().decodeToString())
        assertEquals("Man", Base64Format.decode("T WF\nu\n").getOrThrow().decodeToString())
        // 未填充：长度 3（18 位）照样能解出两个字节。
        assertEquals("Ma", Base64Format.decode("TWE").getOrThrow().decodeToString())
        // URL 安全的 `-_` 在标准解码里也认，反之亦然。
        assertEquals(
            listOf(0xFB.toByte(), 0xFF.toByte()),
            Base64Format.decode("-_8").getOrThrow().toList(),
        )
        assertEquals(
            listOf(0xFB.toByte(), 0xFF.toByte()),
            Base64Format.decode("+/8=").getOrThrow().toList(),
        )
    }

    @Test
    fun `an invalid character fails with its position`() {
        val error = Base64Format.decode("TWFu!").exceptionOrNull() as Base64Error
        assertEquals(4, error.index)
        assertTrue(error.message!!.contains("不是合法"))
    }

    @Test
    fun `a dangling single character is rejected`() {
        val error = Base64Format.decode("TWFuA").exceptionOrNull() as Base64Error
        assertEquals(4, error.index)
        assertTrue(error.message!!.contains("1 个字符"))
    }

    @Test
    fun `content after the padding is rejected`() {
        val error = Base64Format.decode("TWE=A").exceptionOrNull() as Base64Error
        assertEquals(4, error.index)
        assertTrue(error.message!!.contains("填充符"))
    }

    @Test
    fun `a blank input decodes to nothing rather than failing`() {
        assertEquals(0, Base64Format.decode("  \n ").getOrThrow().size)
    }

    @Test
    fun `the error message carries a line and column`() {
        val error = Base64Format.decode("TWFu\nTWF\n!").exceptionOrNull() as Base64Error
        assertEquals("第 3 行 第 1 列：${error.message}", base64ErrorMessage("TWFu\nTWF\n!", error))
        val single = Base64Format.decode("!").exceptionOrNull() as Base64Error
        assertTrue(base64ErrorMessage("!", single).startsWith("第 1 列："))
    }

    @Test
    fun `a data url is unwrapped and positions shift with the prefix`() {
        val decoded = Base64Format.decode("data:text/plain;base64,TWFu").getOrThrow()
        assertEquals("Man", decoded.decodeToString())

        // 报错位置要算上被剥掉的前缀，用户看到的列号才对得上自己贴进来的那一整串。
        val error =
            Base64Format.decode("data:text/plain;base64,TWFu!").exceptionOrNull() as Base64Error
        assertEquals("data:text/plain;base64,".length + 4, error.index)
    }

    @Test
    fun `a data url without base64 is left untouched`() {
        val uri = Base64Format.dataUriPayload("data:text/plain,hello")
        assertEquals(0, uri.offset)
        assertEquals("data:text/plain,hello", uri.text)
    }

    @Test
    fun `utf8 detection rejects binary and accepts text`() {
        assertEquals("中文", Base64Format.utf8OrNull("中文".encodeToByteArray()))
        assertEquals("", Base64Format.utf8OrNull(ByteArray(0)))
        assertNull(Base64Format.utf8OrNull(byteArrayOf(0xFF.toByte(), 0xFE.toByte())))
        // 缺一个续字节的多字节序列也不能算文本。
        assertNull(Base64Format.utf8OrNull(byteArrayOf(0xC3.toByte())))
        // 过长编码（0xC0 0x80 表示 NUL）合法 UTF-8 里不存在。
        assertNull(Base64Format.utf8OrNull(byteArrayOf(0xC0.toByte(), 0x80.toByte())))
    }

    @Test
    fun `image formats are recognised by their magic bytes`() {
        assertEquals(ImageKind.Png, Base64Format.imageKindOf(magic(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals(ImageKind.Jpeg, Base64Format.imageKindOf(magic(0xFF, 0xD8, 0xFF)))
        assertEquals(ImageKind.Gif, Base64Format.imageKindOf("GIF89a".encodeToByteArray()))
        assertEquals(ImageKind.Webp, Base64Format.imageKindOf("RIFF????WEBPVP8 ".encodeToByteArray()))
        assertEquals(ImageKind.Bmp, Base64Format.imageKindOf("BM".encodeToByteArray()))
        // TIFF 的大小端两套魔数：macOS 剪贴板里的图片多半是它。
        assertEquals(ImageKind.Tiff, Base64Format.imageKindOf(magic(0x49, 0x49, 0x2A, 0x00)))
        assertEquals(ImageKind.Tiff, Base64Format.imageKindOf(magic(0x4D, 0x4D, 0x00, 0x2A)))
        assertNull(Base64Format.imageKindOf("hello".encodeToByteArray()))
        // RIFF 但后面不是 WEBP（例如 wav）不该被当成图片。
        assertNull(Base64Format.imageKindOf("RIFF????WAVEfmt ".encodeToByteArray()))
    }

    @Test
    fun `mime comes from the file header first, then the extension`() {
        // 文件头最准：扩展名写错了也拦得住。
        assertEquals("image/png", Base64Format.mimeTypeOf("photo.txt", ImageKind.Png))
        // 认不出文件头就退回扩展名，大小写不敏感、认路径。
        assertEquals("image/png", Base64Format.mimeTypeOf("photo.PNG", null))
        assertEquals("application/json", Base64Format.mimeTypeOf("/tmp/a/data.json", null))
        // 都不是就给中性的兜底类型。
        assertEquals("application/octet-stream", Base64Format.mimeTypeOf("mystery", null))
        assertEquals("application/octet-stream", Base64Format.mimeTypeOf("mystery.unknown", null))
        assertEquals("application/octet-stream", Base64Format.mimeTypeOf(null, null))
    }

    @Test
    fun `a data url produced from an image decodes back to the same bytes`() {
        val png = magic(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01, 0x02)
        val dataUrl = "data:${Base64Format.mimeTypeOf("shot.png", Base64Format.imageKindOf(png))}" +
            ";base64,${Base64Format.encode(png, urlSafe = false)}"
        assertEquals("data:image/png;base64,", dataUrl.substringBefore("iVBOR"))
        assertEquals(png.toList(), Base64Format.decode(dataUrl).getOrThrow().toList())
    }

    @Test
    fun `sizes are written in the largest readable unit`() {
        assertEquals("0 B", Base64Format.humanSize(0))
        assertEquals("512 B", Base64Format.humanSize(512))
        assertEquals("1.0 KB", Base64Format.humanSize(1024))
        assertEquals("1.5 KB", Base64Format.humanSize(1536))
        assertEquals("1.0 MB", Base64Format.humanSize(1024L * 1024))
    }
}

private fun magic(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
