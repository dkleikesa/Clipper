package com.qcmian.clipper.devtools.tools.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * XML 排版的约定：缩进宽度可配、属性可按名排序、声明原样保留、失败走 [Result]。
 *
 * 属性排序是新加的一段（写起始标签时改写了事件搬运），错一位就是「标签名串到属性里」这种
 * 很难从界面上看出来的问题，所以钉在单测上。
 */
class XmlFormatTest {

    @Test
    fun `format indents nested elements by the chosen width`() {
        val out = XmlFormat.format("<a><b>1</b></a>", XmlIndent.Spaces(2)).getOrThrow()
        assertTrue(out.contains("\n  <b>1</b>"), out)
    }

    @Test
    fun `minify drops every line break`() {
        val out = XmlFormat.minify("<a>\n  <b>1</b>\n</a>").getOrThrow()
        assertFalse(out.contains("\n"), out)
    }

    @Test
    fun `attribute sorting reorders by local name`() {
        val out = XmlFormat.format("""<a z="1" b="2"/>""", sortAttributes = true).getOrThrow()
        assertTrue(out.indexOf("b=\"2\"") < out.indexOf("z=\"1\""), out)
    }

    @Test
    fun `attribute order is kept when not sorting`() {
        val out = XmlFormat.format("""<a z="1" b="2"/>""").getOrThrow()
        assertTrue(out.indexOf("z=\"1\"") < out.indexOf("b=\"2\""), out)
    }

    @Test
    fun `minify also sorts attributes when asked`() {
        val out = XmlFormat.minify("""<a z="1" b="2"/>""", sortAttributes = true).getOrThrow()
        assertTrue(out.indexOf("b=\"2\"") < out.indexOf("z=\"1\""), out)
    }

    @Test
    fun `sorting keeps the namespace declaration`() {
        val out = XmlFormat.format("""<r xmlns="urn:x" z="1" b="2"/>""", sortAttributes = true).getOrThrow()
        assertTrue(out.contains("xmlns=\"urn:x\""), out)
        assertTrue(out.indexOf("b=\"2\"") < out.indexOf("z=\"1\""), out)
    }

    @Test
    fun `comments are kept by default`() {
        val out = XmlFormat.format("<a><!-- note --><b/></a>").getOrThrow()
        assertTrue(out.contains("<!-- note -->"), out)
    }

    @Test
    fun `comments can be dropped`() {
        val out = XmlFormat.format("<a><!-- note --><b/></a>", keepComments = false).getOrThrow()
        assertFalse(out.contains("note"), out)
    }

    @Test
    fun `minify keeps comments by default`() {
        val out = XmlFormat.minify("<a><!-- note --><b/></a>").getOrThrow()
        assertTrue(out.contains("<!-- note -->"), out)
    }

    @Test
    fun `the declaration is kept verbatim`() {
        val out = XmlFormat.format("""<?xml version='1.1' encoding='UTF-8'?><a/>""").getOrThrow()
        assertTrue(out.startsWith("<?xml version='1.1' encoding='UTF-8'?>"), out)
    }

    @Test
    fun `malformed xml fails instead of throwing`() {
        assertTrue(XmlFormat.format("<a><b></a>").isFailure)
    }

    @Test
    fun `well-formedness check agrees with formatting`() {
        assertTrue(XmlFormat.isValid("<a><b>1</b></a>"))
        assertFalse(XmlFormat.isValid("<a><b></a>"))
    }

    @Test
    fun `indent options cover every level plus the tab`() {
        assertEquals(
            listOf(1, 2, 3, 4, 5, 6, 7, 8),
            XmlFormat.IndentOptions.filterIsInstance<XmlIndent.Spaces>().map { it.count },
        )
        assertTrue(XmlFormat.IndentOptions.last() is XmlIndent.Tab)
    }
}
