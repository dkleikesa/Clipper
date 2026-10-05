package com.qcmian.clipper.devtools.tools.url

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * URL 百分号编码的逻辑：三档规则的保留集、`+` 与空格的两种写法、UTF-8 多字节、以及出错定位。
 *
 * 期望值取自 RFC 3986、`encodeURI` 与 `application/x-www-form-urlencoded` 的公认行为，
 * 不是「跑出来就这样」的回归值。
 */
class UrlFormatTest {

    private fun encode(text: String, rules: UrlRules) = UrlFormat.encode(text, rules)

    private fun decode(text: String, rules: UrlRules) = UrlFormat.decode(text, rules).getOrThrow()

    @Test
    fun `strict keeps only the rfc3986 unreserved characters`() {
        // 只留字母数字与 `- . _ ~`；`! * ' ( )` 这些「有的实现编、有的不编」的字符一律转义——
        // 正因为不留它们，编出来的结果才能跟别的实现对得上。
        assertEquals("A-Z_a-z.0-9~", encode("A-Z_a-z.0-9~", UrlRules.Strict))
        assertEquals("a%21b%2Ac%27d%28e%29f~g", encode("a!b*c'd(e)f~g", UrlRules.Strict))
        assertEquals("a%20b", encode("a b", UrlRules.Strict))
        // URL 的语法字符当然也转义（但 `.` 是 unreserved，留着）。
        assertEquals("https%3A%2F%2Fa.com%2Fb", encode("https://a.com/b", UrlRules.Strict))
        // 编一个参数值时同理：`:` `/` `?` `&` `=` 都是数据，一律转义。
        assertEquals("a%2Fb%3Fc%3Dd%26e%3Df", encode("a/b?c=d&e=f", UrlRules.Strict))
    }

    @Test
    fun `strict decodes back what it encodes`() {
        assertEquals("a*b~c", decode("a%2Ab%7Ec", UrlRules.Strict))
        assertEquals("https://a.com/b", decode("https%3A%2F%2Fa.com%2Fb", UrlRules.Strict))
        // `+` 在严格档里是普通加号，不当作空格。
        assertEquals("a+b", decode("a+b", UrlRules.Strict))
    }

    @Test
    fun `full keeps the url structure characters`() {
        assertEquals("a/b?c=d&e=f", encode("a/b?c=d&e=f", UrlRules.Full))
        // 空格不在保留集里，Full 也照样转义（`encodeURI` 就是这么定义的）。
        assertEquals("a%20b", encode("a b", UrlRules.Full))
        // `+` 与 `! ~ * ' ( )` 是 URL 的保留字符，Full 原样留着。
        assertEquals("a+b", encode("a+b", UrlRules.Full))
        assertEquals("A-Z_a-z.0-9!~*'()", encode("A-Z_a-z.0-9!~*'()", UrlRules.Full))
    }

    @Test
    fun `form writes a space as plus`() {
        assertEquals("a+b", encode("a b", UrlRules.Form))
        // 表单与严格的保留集各有取舍：`*` 留着，`! ~ ' ( )` 转义。
        assertEquals("a%21b%7Ec", encode("a!b~c", UrlRules.Form))
    }

    @Test
    fun `multi byte characters are escaped byte by byte as utf8`() {
        // 「中」的 UTF-8 是三字节 E4 B8 AD，一个字符因此写成三个 %XX。
        assertEquals("%E4%B8%AD", encode("中", UrlRules.Strict))
        assertEquals("%E4%B8%AD%E6%96%87", encode("中文", UrlRules.Strict))
    }

    @Test
    fun `an emoji is escaped as its four utf8 bytes`() {
        // 代理对必须成对编码：逐字符编会编出两段无效的 UTF-8。
        assertEquals("%F0%9F%98%80", encode("😀", UrlRules.Strict))
    }

    @Test
    fun `literal non ascii text and emoji survive decoding`() {
        // 字面量里含非 ASCII 时走「整段编码」那一支（见 `UrlFormat.decode`）。
        assertEquals("中文", decode("中%E6%96%87", UrlRules.Strict))
        assertEquals("搜索 关键词", decode("搜索%20关键词", UrlRules.Strict))
        assertEquals("😀 ", decode("😀%20", UrlRules.Strict))
    }

    @Test
    fun `decoding restores text and tolerates either hex case`() {
        assertEquals("中", decode("%E4%B8%AD", UrlRules.Strict))
        assertEquals("中", decode("%e4%b8%ad", UrlRules.Strict))
        assertEquals("a b", decode("a%20b", UrlRules.Strict))
        assertEquals("100%", decode("100%25", UrlRules.Strict))
    }

    @Test
    fun `plus means space only under the form rules`() {
        assertEquals("a b", decode("a+b", UrlRules.Form))
        // 严格 / URL 规则里 `+` 是普通字符，不能当空格吞掉。
        assertEquals("a+b", decode("a+b", UrlRules.Strict))
        assertEquals("a+b", decode("a+b", UrlRules.Full))
    }

    @Test
    fun `a truncated or non hex escape fails at the offending position`() {
        assertEquals(1, (UrlFormat.decode("%G1", UrlRules.Strict).exceptionOrNull() as UrlError).index)
        assertEquals(2, (UrlFormat.decode("%2G", UrlRules.Strict).exceptionOrNull() as UrlError).index)
        // `%` 后直接没了，只能指到 `%` 自己。
        assertEquals(2, (UrlFormat.decode("ab%", UrlRules.Strict).exceptionOrNull() as UrlError).index)
        assertEquals(0, (UrlFormat.decode("%", UrlRules.Strict).exceptionOrNull() as UrlError).index)
        assertEquals(0, (UrlFormat.decode("%2", UrlRules.Strict).exceptionOrNull() as UrlError).index)
        assertTrue(UrlFormat.decode("%", UrlRules.Strict).isFailure)
    }

    @Test
    fun `bytes that are not utf8 text are rejected without a column`() {
        // `%FF` 是一个孤立的续字节，凑不出合法的 UTF-8——这时指不到哪一列，错误里就没有位置。
        val error = UrlFormat.decode("%FF", UrlRules.Strict).exceptionOrNull() as UrlError
        assertNull(error.index)
        // 没有位置时 `urlErrorMessage` 原样返回，不硬编一个「第 1 列」出来。
        assertEquals(error.reason, urlErrorMessage("%FF", error))
    }

    @Test
    fun `encoding then decoding round trips`() {
        val original = "搜索 关键词 & 中文 / 已编码 % 😀"
        UrlRules.entries.forEach { rules ->
            assertEquals(original, decode(encode(original, rules), rules))
        }
    }

    @Test
    fun `errors carry the column of the offending character`() {
        assertEquals("第 2 列：「%」后面要跟两位十六进制数字", urlErrorMessage("%G1", UrlError("「%」后面要跟两位十六进制数字", 1)))
    }
}
