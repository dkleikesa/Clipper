package com.qcmian.clipper.devtools.detect

import com.qcmian.clipper.devtools.api.DataTypes
import com.qcmian.clipper.devtools.registry.DevToolsRegistry
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

    @Test
    fun `a math expression is recognised`() {
        listOf("1+2", "2^10", "(1+2)/3", "sqrt(2)*10", "10 % 3", "100-20", "2*pi", "1/3").forEach {
            assertTrue(MathDataTypeDetector.matches(it), it)
        }
        assertEquals(DataTypes.MATH, MathDataTypeDetector.typeName)
    }

    @Test
    fun `plain numbers dates and ordinary text are not mistaken for math`() {
        // 一个数不是算式：单看 `42` 无从知道用户想不想算它。
        assertFalse(MathDataTypeDetector.matches("42"))
        assertFalse(MathDataTypeDetector.matches("1700000000"))
        // 日期 / 版本号 / IP 也「算得出」一个数，但那不是用户要的东西。
        assertFalse(MathDataTypeDetector.matches("2024-01-01"))
        assertFalse(MathDataTypeDetector.matches("2024/01/01"))
        assertFalse(MathDataTypeDetector.matches("1.2.3"))
        assertFalse(MathDataTypeDetector.matches("192.168.1.1"))
        // 开头的负号不算运算，`-5` 仍然只是个数。
        assertFalse(MathDataTypeDetector.matches("-5"))
        // 普通文本与代码：有数字也有字母，但算不出结果。
        assertFalse(MathDataTypeDetector.matches("Hello 2024"))
        assertFalse(MathDataTypeDetector.matches("user1"))
        assertFalse(MathDataTypeDetector.matches("""{"a":1}"""))
        assertFalse(MathDataTypeDetector.matches("  "))
    }

    @Test
    fun `apk and keystore files are recognised by extension`() {
        assertTrue(ApkFileTypeDetector.matches("/tmp/out/app-release.apk"))
        assertTrue(ApkFileTypeDetector.matches("/tmp/out/APP.APK"))
        assertTrue(KeyStoreFileTypeDetector.matches("/Users/me/.android/debug.keystore"))
        assertTrue(KeyStoreFileTypeDetector.matches("/Users/me/release.JKS"))
        assertTrue(KeyStoreFileTypeDetector.matches("/Users/me/upload.p12"))

        // 名字里带 apk 的目录、备份名、没有扩展名的，都不算。
        assertFalse(ApkFileTypeDetector.matches("/Users/me/apk/app"))
        assertFalse(ApkFileTypeDetector.matches("/Users/me/app.apk.bak"))
        assertFalse(ApkFileTypeDetector.matches("/Users/me/report.txt"))
        assertFalse(KeyStoreFileTypeDetector.matches("/Users/me/notes.md"))

        // DER 证书是二进制，只能按扩展名认；`.p12` 归密钥库那一档。
        assertTrue(CertFileTypeDetector.matches("/Users/me/leaf.der"))
        assertTrue(CertFileTypeDetector.matches("/Users/me/chain.CER"))
        assertFalse(CertFileTypeDetector.matches("/tmp/x.p12"))
    }

    @Test
    fun `closing a file recommends the tool that eats it`() {
        val registry = DevToolsRegistry.builtIn()

        val apk = registry.detectTypes("", listOf("/tmp/out/app-release.apk"))
        assertEquals(listOf(DataTypes.APK), apk)
        assertEquals("apk-sign", registry.rankedTools(apk).first().metadata.id)

        val keyStore = registry.detectTypes("", listOf("/Users/me/debug.keystore"))
        assertEquals(listOf(DataTypes.KEYSTORE), keyStore)
        assertEquals("apk-sign", registry.rankedTools(keyStore).first().metadata.id)

        val math = registry.detectTypes("1+2*3")
        assertEquals(listOf(DataTypes.MATH, DataTypes.TEXT), math)
        assertEquals("math", registry.rankedTools(math).first().metadata.id)

        val der = registry.detectTypes("", listOf("/Users/me/leaf.der"))
        assertEquals(listOf(DataTypes.CERT), der)
        assertEquals("cert", registry.rankedTools(der).first().metadata.id)
    }
}
