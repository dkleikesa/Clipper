package com.qcmian.clipper.devtools.tools.cert

import com.qcmian.clipper.devtools.tools.apksign.CertDigests
import com.qcmian.clipper.devtools.tools.apksign.CertInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `CertFormat` 的纯逻辑：PEM 块抽取、证书判别、折行、四类条目的排版。
 *
 * 这些都**不碰平台**，所以能直接钉住；真正要吃 `java.security` / bouncycastle 的解析在
 * `CertParseTest` 与 `CertExampleFilesTest` 里。
 */
class CertFormatTest {

    private val leafPem: String = resourceText("/apksign/leaf.pem")

    @Test
    fun `PEM 块按成对的行抽出来，正文去掉所有空白与折行`() {
        val text = """
            一段无关的前言
            -----BEGIN CERTIFICATE-----
            QUJD
            REVG
            -----END CERTIFICATE-----
            后面还有别的东西
        """.trimIndent()

        val blocks = CertFormat.extractPemBlocks(text)
        assertEquals(1, blocks.size)
        assertEquals("CERTIFICATE", blocks[0].label)
        assertEquals("QUJDREVG", blocks[0].base64)
    }

    @Test
    fun `混装的块全都抽得出来，label 各是各的`() {
        val text = leafPem + "\n-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----\n" + leafPem

        val blocks = CertFormat.extractPemBlocks(text)
        assertEquals(3, blocks.size)
        assertEquals(listOf("CERTIFICATE", "PRIVATE KEY", "CERTIFICATE"), blocks.map { it.label })
        assertTrue(CertFormat.looksLikeCertificate(text))
    }

    @Test
    fun `没闭合的块直接丢掉，不当成半张证书`() {
        val text = "-----BEGIN CERTIFICATE-----\nQUJD\n"
        assertTrue(CertFormat.extractPemBlocks(text).isEmpty())
        assertFalse(CertFormat.looksLikeCertificate(text))
    }

    @Test
    fun `PEM 与裸 Base64 都认，普通文本不认`() {
        assertTrue(CertFormat.looksLikeCertificate(leafPem))

        val bare = leafPem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")
        assertTrue(CertFormat.looksLikeCertificate(bare))

        assertFalse(CertFormat.looksLikeCertificate("hello world"))
        assertFalse(CertFormat.looksLikeCertificate("{\"a\":1}"))
        // 太短的 Base64 不值得去解：`abcd` 也是合法 Base64，但那显然不是证书。
        assertFalse(CertFormat.looksLikeCertificate("YWJjZA=="))
    }

    @Test
    fun `折行按 64 列，最后一行不足也照排`() {
        assertEquals("abcd\nef", CertFormat.wrapBase64("abcdef", width = 4))
        assertEquals(64, CertFormat.wrapBase64("A".repeat(65)).lineSequence().first().length)
    }

    @Test
    fun `多张证书时按证书编号分块，原文逐张拼接`() {
        val document = CertDocument(
            formatLabel = "PEM",
            items = listOf(
                CertificateItem(certInfo(serial = "aa"), "pem-one"),
                CertificateItem(certInfo(serial = "bb"), "pem-two"),
            ),
        )

        val info = CertFormat.infoText(document)
        assertTrue(info.contains("证书[1]:"), "同类多张时才加编号")
        assertTrue(info.contains("证书[2]:"))
        assertTrue(info.contains("序列号: aa") && info.contains("序列号: bb"))

        assertEquals("pem-one\npem-two", CertFormat.pemText(document))
    }

    @Test
    fun `只有一张证书时信息直排，不套编号的壳`() {
        val document = CertDocument("PEM", listOf(CertificateItem(certInfo(serial = "aa"), "pem")))

        val info = CertFormat.infoText(document)
        assertFalse(info.contains("证书[1]:"))
        assertTrue(info.contains("序列号: aa"))
    }

    @Test
    fun `证书与密钥混装时各起一个标题，同类只有一张就不加编号`() {
        val document = CertDocument(
            "PEM",
            listOf(
                CertificateItem(certInfo(serial = "aa"), "pem-cert"),
                KeyItem(keyInfo(isPrivate = true), "pem-key"),
            ),
        )

        val info = CertFormat.infoText(document)
        assertTrue(info.contains("证书:"))
        assertTrue(info.contains("私钥:"))
        assertFalse(info.contains("证书[1]:"))
        assertFalse(info.contains("私钥[1]:"))
    }

    @Test
    fun `证书请求排出自签名结论与扩展`() {
        val document = CertDocument("PEM", listOf(RequestItem(requestInfo(), "pem")))

        val info = CertFormat.infoText(document)
        assertTrue(info.contains("主体: CN=demo.clipper.local"))
        assertTrue(info.contains("公钥算法: 2048 位 RSA 密钥"))
        assertTrue(info.contains("签名算法: SHA256withRSA"))
        assertTrue(info.contains("自签名验证: 通过"))
        assertTrue(info.contains("#1: 2.5.29.17"))
    }

    @Test
    fun `吊销列表把每条记录排成一行，没有下次更新就不打那一行`() {
        val document = CertDocument(
            "DER",
            listOf(
                CrlItem(
                    CrlInfo(
                        issuer = "CN=Clipper Demo Root CA",
                        thisUpdateMillis = 0L,
                        nextUpdateMillis = null,
                        signatureAlgorithm = "SHA256withRSA",
                        version = 2,
                        revoked = listOf(RevokedEntry(serial = "1000", revokedAtMillis = 0L, reason = "密钥泄露")),
                    ),
                    "pem",
                ),
            ),
        )

        val info = CertFormat.infoText(document)
        assertTrue(info.contains("颁发者: CN=Clipper Demo Root CA"))
        assertTrue(info.contains("签名算法: SHA256withRSA"))
        assertTrue(info.contains("吊销条目: 1"))
        assertTrue(info.contains("序列号: 1000") && info.contains("原因: 密钥泄露"))
        assertFalse(info.contains("下次更新:"))
    }

    @Test
    fun `加密且没解开的私钥不编算法与位数，只报编码与加密算法`() {
        val document = CertDocument(
            "PEM",
            listOf(
                KeyItem(
                    KeyInfo(
                        isPrivate = true,
                        algorithm = "",
                        bits = 0,
                        encoding = "PKCS#8",
                        encrypted = true,
                        encryptionAlgorithm = "PBES2",
                    ),
                    "pem",
                ),
            ),
        )

        val info = CertFormat.infoText(document)
        // 注意不能断言 `contains("算法:")`：`加密算法: …` 那一行也含这三个字，得按行头判。
        assertFalse(info.lineSequence().any { it.startsWith("算法:") }, "解不开就不该有算法那一行")
        assertTrue(info.contains("编码: PKCS#8"))
        assertTrue(info.contains("加密: 是"))
        assertTrue(info.contains("加密算法: PBES2"))
    }

    @Test
    fun `状态栏区分「全是证书」与「混装」`() {
        val certificates = CertDocument(
            "PEM",
            listOf(CertificateItem(certInfo("aa"), "a"), CertificateItem(certInfo("bb"), "b")),
        )
        assertEquals("PEM · 2 张证书", CertFormat.statusText(certificates))
        assertTrue(CertFormat.hasOnlyCertificates(certificates))

        val mixed = CertDocument(
            "PEM",
            listOf(CertificateItem(certInfo("aa"), "a"), RequestItem(requestInfo(), "b")),
        )
        assertEquals("PEM · 2 项（含 1 张证书）", CertFormat.statusText(mixed))
        assertFalse(CertFormat.hasOnlyCertificates(mixed))
    }

    /** 一个字段齐备的假 [CertInfo]：只做排版测试，不谈真实性。 */
    private fun certInfo(serial: String): CertInfo = CertInfo(
        subject = "CN=demo, O=Demo, C=CN",
        issuer = "CN=Demo Root CA, O=Demo, C=CN",
        serial = serial,
        notBeforeMillis = 0L,
        notAfterMillis = 0L,
        signatureAlgorithm = "SHA256withRSA",
        publicKeyAlgorithm = "RSA",
        publicKeyBits = 2048,
        version = 3,
        digests = CertDigests(sha1 = ByteArray(20), sha256 = ByteArray(32)),
        extensionsText = "",
    )

    private fun requestInfo(): RequestInfo = RequestInfo(
        subject = "CN=demo.clipper.local, O=Clipper Demo, C=CN",
        publicKeyAlgorithm = "RSA",
        publicKeyBits = 2048,
        signatureAlgorithm = "SHA256withRSA",
        selfSigned = true,
        extensionsText = "#1: 2.5.29.17 (critical=false)\n  DNS: demo.clipper.local\n",
    )

    private fun keyInfo(isPrivate: Boolean): KeyInfo = KeyInfo(
        isPrivate = isPrivate,
        algorithm = "RSA",
        bits = 2048,
        encoding = "PKCS#8",
        encrypted = false,
        encryptionAlgorithm = null,
    )
}

/** 读 jvmTest 资源里的一份夹具；没有就当场炸出来（比静默给空串好查）。 */
private fun resourceText(path: String): String =
    requireNotNull(CertFormatTest::class.java.getResourceAsStream(path)) { "夹具没了：$path" }
        .use { it.readBytes().decodeToString() }
