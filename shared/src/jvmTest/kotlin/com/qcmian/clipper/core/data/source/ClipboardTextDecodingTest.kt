package com.qcmian.clipper.core.data.source

import com.qcmian.clipper.core.domain.model.ClipboardContent
import com.qcmian.clipper.core.domain.model.contentKeyOf
import java.nio.charset.Charset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 剪贴板文本的**解码口径**。
 *
 * 这几条都有同一个特点：实现改错了，代码读起来照样合理，而后果是「静默乱码」——不会抛异常、
 * 不会掉数据，只是内容变成看不懂的字符，或者同一份内容每次都算成新的（去重失效）。
 * 而它们原本**只**由需要真实系统粘贴板的用例间接覆盖，也就是在 CI 里等于没有覆盖。
 *
 * 用例里的字节是**实测样本**，不是自己编的：换一组"看起来等价"的字节就测不出问题了。
 */
class ClipboardTextDecodingTest {

    private val source = MacClipboardDataSource()

    @Test
    fun `没有 BOM 的小端 UTF-16 必须按小端解`() {
        // 实测：系统写出的 `public.utf16-plain-text` 是**无 BOM 的小端**，「你好」的字节是 60 4F 7D 59。
        // 把这里的判据改成「没有 BOM 就当大端」（代码同样合理、甚至更像常规做法），下面这行会得到
        // 「恀」之类的乱码，而 Finder 复制的文件名列表会整片变成乱码。
        val bytes = byteArrayOf(0x60, 0x4F, 0x7D, 0x59)

        assertEquals("你好", decodeUtf16(bytes))
        assertEquals("你好", decodeText("public.utf16-plain-text", bytes))
    }

    @Test
    fun `带 ff fe 的小端 BOM 按小端解`() {
        // 实测：Finder 复制文件时放的 `public.utf16-external-plain-text` 就是 ff fe 开头的小端。
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            "a.txt\nb.txt".toByteArray(Charsets.UTF_16LE)

        assertEquals("a.txt\nb.txt", decodeUtf16(bytes))
        assertEquals("a.txt\nb.txt", decodeText("public.utf16-external-plain-text", bytes))
    }

    @Test
    fun `带 fe ff 的大端 BOM 按大端解`() {
        // 大端字节序得靠 BOM 才认得出来：判据里 `bytes[0] == 0xFF` 这一半就是为它留的。
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + "中文".toByteArray(Charsets.UTF_16BE)

        assertEquals("中文", decodeUtf16(bytes))
    }

    @Test
    fun `多编码文本优先取 UTF-8，而不是排在最前面的那一份`() {
        // 系统给纯文本时**同时**写 UTF-16 与 UTF-8，而且常把 UTF-16 排在前面。取「第一条文本表示」
        // 是很自然的写法，但那意味着每次都要走自己那套解码；实现里显式先挑 UTF-8 正是为了绕开它。
        // 这里刻意让两份内容不同：只有真的按类型优先，才会拿到「正确的那一份」。
        val contents = listOf(
            ClipboardContent("public.utf16-external-plain-text", "错误的那一份".toByteArray(Charsets.UTF_16LE)),
            ClipboardContent("public.utf8-plain-text", "正确的那一份".encodeToByteArray()),
        )

        assertEquals("正确的那一份", source.readText(contents))
    }

    @Test
    fun `传统 Mac 编码的纯文本按 Big5 解`() {
        // 繁体中文环境下系统会给 `com.apple.traditional-mac-plain-text`。这条分支看起来像死代码
        // ——「现在谁还用传统编码」——删掉它不会报错，只会在那种环境下解出乱码。
        val text = "中文測試"
        val bytes = text.toByteArray(Charset.forName("Big5"))

        assertEquals(text, decodeText("com.apple.traditional-mac-plain-text", bytes))
        // 同一批字节按 UTF-8 解是另一回事（这正是不能统一走 UTF-8 的原因）。
        assertTrue(decodeText("public.utf8-plain-text", bytes) != text)
    }

    @Test
    fun `动态标记不会让同一份内容的摘要发生变化`() {
        // `dyn.*`（以及 OLE 源）是提供方**每次复制都不同**的内部标记。它一旦进入 `contentKeyOf`
        // 的输入，同一份内容每次复制都会算出新的摘要，`findIdByContentKey` 再也命不中——
        // 表现为「历史里同一段文字越来越多条」，不报错、不崩溃，只是去重从此失效。
        val plain = listOf(ClipboardContent("public.utf8-plain-text", "hello".encodeToByteArray()))
        val withDynamic = plain + ClipboardContent("dyn.abc123", byteArrayOf(1, 2, 3))

        assertEquals(
            contentKeyOf(plain),
            contentKeyOf(withDynamic.filter { source.keepAsContent(it.type) }),
            "带不带 dyn.* 必须算出同一个摘要，否则去重静默失效",
        )
    }
}
