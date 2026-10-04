package com.qcmian.clipper.devtools.detect

import com.qcmian.clipper.devtools.api.DataTypes
import com.qcmian.clipper.devtools.tools.base64.Base64Format
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 类型探测。重点看住 Base64：它的字母表全是普通字母数字，「长度够 + 字符合法 + 解得开」这一套放到
 * 普通英文上照样成立，判据一松就会把一长串单词或 camelCase 标识符认成 Base64。
 */
class DataTypeDetectorTest {

    /** "Hello, World!" 的 Base64：20 个字符，带填充。 */
    private val hello = Base64Format.encode("Hello, World!".encodeToByteArray(), urlSafe = false)

    @Test
    fun `a base64 string is recognised`() {
        assertEquals("SGVsbG8sIFdvcmxkIQ==", hello)
        assertTrue(Base64DataTypeDetector.matches(hello))
        // 折行只会有换行；换个行照样认。
        assertTrue(Base64DataTypeDetector.matches(hello.substring(0, 10) + "\n" + hello.substring(10)))
        // 带 `data:` 头的整串也认——工具本来就吃得下它。
        assertTrue(Base64DataTypeDetector.matches("data:application/zip;base64,$hello"))
        // 首尾空白不算内容。
        assertTrue(Base64DataTypeDetector.matches("  $hello\n"))
    }

    @Test
    fun `url safe and unpadded base64 is recognised too`() {
        // 全 0xFF 的字节编出来正好是 62 / 63 号字符：标准是 `/`，URL 安全是 `_` 且不带填充。
        val bytes = ByteArray(20) { 0xFF.toByte() }
        val urlSafe = Base64Format.encode(bytes, urlSafe = true)
        assertFalse(urlSafe.contains('='))
        assertTrue(Base64DataTypeDetector.matches(urlSafe))
        assertTrue(Base64DataTypeDetector.matches(Base64Format.encode(bytes, urlSafe = false)))
    }

    @Test
    fun `ordinary text is not mistaken for base64`() {
        // 太短：`test`、`abcd` 本身也是合法 Base64。
        assertFalse(Base64DataTypeDetector.matches("test"))
        assertFalse(Base64DataTypeDetector.matches("SGVsbG8="))
        // 带空格：Base64 字母表里没有空格，折行只会用换行。
        assertFalse(Base64DataTypeDetector.matches("Hello world this is a test of the detector"))
        assertFalse(Base64DataTypeDetector.matches("iVBORw0KGgo AAAANSUhEUgAAAAE"))
        // 一长串纯字母 / camelCase 标识符：长度够、字符合法、也解得开，全靠「至少一个数字或号符」挡住。
        assertFalse(
            Base64DataTypeDetector.matches("internationalizationinternationalization")
        )
        assertFalse(Base64DataTypeDetector.matches("myVeryLongCamelCaseIdentifierName"))
        // 有非法字符时解码就失败了。
        assertFalse(Base64DataTypeDetector.matches("$hello!"))
        // 长度对不上：去掉填充后余 1 个字符，凑不出一个字节。
        assertFalse(Base64DataTypeDetector.matches("SGVsbG8sIFdvcmxkI"))
    }

    @Test
    fun `detection ranks the most specific type first`() {
        assertEquals(
            listOf(DataTypes.BASE64, DataTypes.TEXT),
            BuiltInDataTypeDetectors.detectTypes(hello),
        )
        // JSON 仍然排在 Base64 前面（前者更具体），两者也不会同时命中。
        val json = """{"a":1}"""
        assertTrue(DataTypes.JSON in BuiltInDataTypeDetectors.detectTypes(json))
        assertFalse(Base64DataTypeDetector.matches(json))
    }
}
