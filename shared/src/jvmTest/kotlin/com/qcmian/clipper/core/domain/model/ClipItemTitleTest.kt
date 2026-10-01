package com.qcmian.clipper.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 标题的**隐性不变量**：落库前过滤 + 截断、渲染时等长替换、以及「标题」与「内容全文」两套
 * 派生规则的优先级分叉。
 *
 * 这些都没有可观察的行为面——写歪了不会报错：
 * - 渲染路径一旦删字符，搜索返回的高亮区间会整体错位，看起来只是「高亮串位」；
 * - 写入路径一旦漏过滤 / 漏截断，同一个字段的长度上限会随来源浮动，搜索范围也跟着变。
 */
class ClipItemTitleTest {

    @Test
    fun `toStoredTitle filters unsafe scalars before it truncates`() {
        // 占位符放在最前面：只有「先过滤、再截断」才能把后面的第 1000 个字符也带进来。
        val stored = ("\uFFFC" + "a".repeat(ClipItem.MAX_TITLE_LENGTH)).toStoredTitle()

        assertEquals(
            ClipItem.MAX_TITLE_LENGTH,
            stored.length,
            "先截断会把占位符留在前 1000 个里，过滤后只剩 999 个字符",
        )
        assertTrue(stored.all { it == 'a' }, "未过滤干净的占位符必须在写入时就被删掉，而不是渲染时")
    }

    @Test
    fun `toStoredTitle caps a plain long title at the shared limit`() {
        val stored = "x".repeat(ClipItem.MAX_TITLE_LENGTH + 200).toStoredTitle()
        assertEquals(ClipItem.MAX_TITLE_LENGTH, stored.length)
    }

    @Test
    fun `toStoredTitle rewrites the stored title through previewable text`() {
        val item = ClipItem(id = "i", text = "q".repeat(ClipItem.MAX_TITLE_LENGTH + 200))
        assertEquals(
            ClipItem.MAX_TITLE_LENGTH,
            item.toMeta().title.length,
            "落库标题必须走同一条 `previewableText.toStoredTitle()`，长度上限才只有一处",
        )
    }

    @Test
    fun `replacingUnsafeTitleScalars keeps the length while removingUnsafeTitleScalars does not`() {
        val input = "a\uFFFCb\uFFFC"

        val replaced = input.replacingUnsafeTitleScalars()
        assertEquals(input.length, replaced.length, "渲染路径只能等长替换，否则高亮区间会整体错位")
        assertEquals("a b ", replaced)

        assertEquals("ab", input.removingUnsafeTitleScalars(), "写入路径才允许改变长度")
    }

    @Test
    fun `titleForDisplay replaces in place and never changes the length`() {
        val samples = listOf(
            "  leading",
            "trailing   ",
            "both   ",
            "a\nb\tc",
            "\uFFFC  x  \n",
            "中文\uFFFC换行\n",
            "   ",
            "",
        )

        for (sample in samples) {
            assertEquals(
                sample.length,
                sample.titleForDisplay().length,
                "每个字符的替换都必须等长：${sample.replace("\n", "\\n")}",
            )
        }
    }

    @Test
    fun `titleForDisplay maps the special symbols without trimming`() {
        val input = "  hi  "
        assertEquals(
            input,
            input.titleForDisplay(showSpecialSymbols = false),
            "关闭特殊符号时也不裁剪首尾空白——它们在单行里本来也看不见，裁剪只会让高亮错位",
        )
        assertEquals("··hi··", input.titleForDisplay())
        assertEquals("a\u23ceb", "a\nb".titleForDisplay(), "换行替换成 ⏎")
        assertEquals("a\u21e5b", "a\tb".titleForDisplay(), "制表符替换成 ⇥")
    }

    @Test
    fun `titleForDisplay caps at the shared limit too`() {
        assertEquals(
            ClipItem.MAX_TITLE_LENGTH,
            "z".repeat(ClipItem.MAX_TITLE_LENGTH + 500).titleForDisplay().length,
        )
    }

    @Test
    fun `files win over text for both the title and the full preview`() {
        val files = listOf("/tmp/a.txt", "/tmp/b.txt")
        val item = ClipItem(id = "f", text = "ignored", files = files)

        assertEquals("/tmp/a.txt\n/tmp/b.txt", item.previewableText)
        assertEquals(
            "/tmp/a.txt\n/tmp/b.txt",
            deriveTitle(text = "ignored", files = files, title = "", richText = ""),
        )
    }

    @Test
    fun `deriveTitle and previewableText resolve the final fallback in opposite order`() {
        // 两套规则的最后一档正好相反：标题先信已落库的 title，全文先信附加表示。
        // 只有在「既没有正文也没有文件、而 title 与附加表示都已存在」时才看得出来。
        val html = "<p>hello <b>world</b></p>"
        val item = ClipItem(
            id = "x",
            title = "legacy title",
            text = null,
            files = emptyList(),
            contents = listOf(ClipboardContent("public.html", html.encodeToByteArray())),
        )

        assertEquals("hello world", item.previewableText, "全文那一档优先取附加表示里提取出的完整文字")
        assertEquals(
            "legacy title",
            deriveTitle(text = null, files = emptyList(), title = item.title, richText = "hello world"),
            "标题那一档优先用已落库的 title，只在它为空时才回退到附加表示",
        )
    }

    @Test
    fun `deriveTitle falls back to the rich text only when the stored title is blank`() {
        assertEquals(
            "from html",
            deriveTitle(text = null, files = emptyList(), title = "", richText = "from html"),
            "只写 HTML、不写纯文本的条目，标题必须能从附加表示里补出来",
        )
        assertNotEquals(
            deriveTitle(text = null, files = emptyList(), title = "kept", richText = "from html"),
            "from html",
        )
    }
}
