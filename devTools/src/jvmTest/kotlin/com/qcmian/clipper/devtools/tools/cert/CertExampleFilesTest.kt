package com.qcmian.clipper.devtools.tools.cert

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 工具**支持的每一种格式**各配一份真文件（`jvmTest/resources/cert/`），逐个喂给解析器。
 *
 * 与 `CertParseTest` 的分工：那边用 `leaf.pem` 与现场造的密钥库钉**边界**（口令、往返、误判），
 * 这里钉**覆盖面**——每种格式都有一份「拿 openssl / keytool 生成的真货」，因此也是给用户试手的
 * 示例文件（用开发者工具的文件页打开即可）。
 *
 * 夹具由一条外部的 openssl / keytool 流水线生成（不是 JDK 自己写的），所以顺带守住了「别人生成的
 * 文件我们读不读得了」——PKCS#12 的算法选择、PKCS#7 的容器写法、SEC1 / PKCS#1 那几种老式 PEM
 * 写法，自己写自己读是测不出来的。
 */
class CertExampleFilesTest {

    // ---------------------------------------------------------------- 证书

    @Test
    fun `PEM 单张证书`() = assertCertificates("certificate.pem", "PEM", 1)

    @Test
    fun `PEM 多张证书按出现顺序全解出来`() = assertCertificates("chain.pem", "PEM", 2)

    @Test
    fun `裸 Base64 证书`() = assertCertificates("certificate.base64", "DER", 1)

    @Test
    fun `DER 二进制证书`() = assertCertificates("certificate.der", "DER", 1)

    @Test
    fun `PKCS7 证书链（DER）`() = assertCertificates("chain.p7b", "PKCS#7", 2)

    @Test
    fun `PKCS7 证书链（PEM 包装）`() = assertCertificates("chain-pkcs7.pem", "PEM", 2)

    @Test
    fun `PKCS12 密钥库给了口令就出证书`() = assertCertificates("keystore.p12", "PKCS#12", 2, password = "changeit")

    @Test
    fun `JKS 密钥库给了口令就出证书`() = assertCertificates("keystore.jks", "JKS", 2, password = "changeit")

    @Test
    fun `PKCS12 不给口令时提示填口令，而不是说没有证书`() {
        val failure = assertIs<CertParseResult.Failed>(
            parseCertInput(CertInput.File(fixturePath("keystore.p12"), password = null)),
        )
        assertTrue(failure.needsPassword, "要提示填口令，界面据此露出那个输入框")
    }

    // ---------------------------------------------------------------- 证书请求

    @Test
    fun `证书请求解出主体、公钥与自签名结论`() {
        val request = assertIs<RequestItem>(document("request.csr").items.single())

        assertTrue(request.info.subject.contains("demo.clipper.local"), "主体要认得出来")
        assertEquals("RSA", request.info.publicKeyAlgorithm)
        assertEquals(2048, request.info.publicKeyBits)
        assertTrue(request.info.selfSigned, "自己签自己的请求应当验得过")
        assertTrue(request.info.extensionsText.isNotEmpty(), "请求里带的扩展要排出来")
    }

    // ---------------------------------------------------------------- 吊销列表

    @Test
    fun `吊销列表解出颁发者与吊销条目`() {
        val crl = assertIs<CrlItem>(document("revoked.crl").items.single())

        assertTrue(crl.info.issuer.contains("Clipper Demo Root CA"))
        assertEquals(2, crl.info.version)
        assertEquals(1, crl.info.revoked.size)
        assertEquals("1000", crl.info.revoked.single().serial)
        assertFalse(crl.pem.isBlank(), "左栏得能原样显示这份 CRL")
    }

    // ---------------------------------------------------------------- 密钥

    @Test
    fun `私钥 PKCS8 解出算法与位数`() {
        val key = keyOf("private-pkcs8.pem")

        assertTrue(key.info.isPrivate)
        assertEquals("PKCS#8", key.info.encoding)
        assertEquals("RSA", key.info.algorithm)
        assertEquals(2048, key.info.bits)
        assertFalse(key.info.encrypted)
    }

    @Test
    fun `私钥 PKCS1（老式 PEM 写法）也认`() {
        val key = keyOf("private-pkcs1.pem")

        assertEquals("PKCS#1", key.info.encoding)
        assertEquals("RSA", key.info.algorithm)
        assertEquals(2048, key.info.bits)
    }

    @Test
    fun `EC 私钥（SEC1）认得出编码与位数`() {
        val key = keyOf("private-ec.pem")

        assertEquals("SEC1", key.info.encoding)
        assertEquals("EC", key.info.algorithm)
        assertEquals(256, key.info.bits, "prime256v1 的阶是 256 位")
    }

    @Test
    fun `加密私钥不给口令时只报「加密了」，不编算法`() {
        val key = keyOf("private-encrypted.pem")

        assertTrue(key.info.encrypted)
        assertTrue(key.info.algorithm.isEmpty(), "没解密就不知道算法")
        assertEquals(0, key.info.bits)
        assertTrue(!key.info.encryptionAlgorithm.isNullOrBlank(), "用的是什么加密算法读得出来")
    }

    @Test
    fun `加密私钥给了口令就解出算法与位数`() {
        val key = keyOf("private-encrypted.pem", password = "changeit")

        assertFalse(key.info.encrypted, "解开之后就不再是「加密未解」那一档")
        assertEquals("RSA", key.info.algorithm)
        assertEquals(2048, key.info.bits)
    }

    @Test
    fun `加密私钥口令不对时说清是口令问题，并要求重填`() {
        val failure = assertIs<CertParseResult.Failed>(
            parseCertInput(CertInput.File(fixturePath("private-encrypted.pem"), password = "nope")),
        )
        assertTrue(failure.needsPassword)
        assertTrue(failure.message.contains("口令"), "要说清是口令不对：${failure.message}")
    }

    @Test
    fun `公钥（SPKI）解出算法与位数`() {
        val key = keyOf("public.pem")

        assertFalse(key.info.isPrivate)
        assertEquals("X.509 (SPKI)", key.info.encoding)
        assertEquals("RSA", key.info.algorithm)
        assertEquals(2048, key.info.bits)
    }

    @Test
    fun `公钥 PKCS1 也认`() {
        val key = keyOf("public-rsa.pem")

        assertEquals("PKCS#1", key.info.encoding)
        assertEquals("RSA", key.info.algorithm)
    }

    // ---------------------------------------------------------------- 帮手

    /** 解析一份夹具并核对：格式标签、证书张数、以及叶证书确实是 `demo.clipper.local` 那张。 */
    private fun assertCertificates(
        name: String,
        formatLabel: String,
        certificateCount: Int,
        password: String? = null,
    ) {
        val parsed = document(name, password)
        val certificates = parsed.certificates()

        assertEquals(formatLabel, parsed.formatLabel, "$name 的格式标签")
        assertEquals(certificateCount, certificates.size, "$name 里的证书张数")
        assertTrue(
            certificates.any { it.info.subject.contains("demo.clipper.local") },
            "$name 里应当有那张叶证书",
        )
        assertTrue(
            certificates.all { it.pem.startsWith("-----BEGIN CERTIFICATE-----") },
            "$name 的每张证书都要能编回 PEM 原文",
        )
    }

    private fun document(name: String, password: String? = null): CertDocument =
        when (val result = parseCertInput(CertInput.File(fixturePath(name), password))) {
            is CertParseResult.Ok -> result.document
            is CertParseResult.Failed -> error("$name 解析失败：${result.message}")
        }

    private fun keyOf(name: String, password: String? = null): KeyItem =
        assertIs<KeyItem>(document(name, password).items.single())

    /** 夹具的真文件路径：解析器吃的是路径，不是资源流。 */
    private fun fixturePath(name: String): String {
        val url = requireNotNull(CertExampleFilesTest::class.java.getResource("/cert/$name")) {
            "示例文件没了：jvmTest/resources/cert/$name"
        }
        return File(url.toURI()).absolutePath
    }
}
