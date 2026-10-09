package com.qcmian.clipper.devtools.tools.cert

import com.qcmian.clipper.devtools.api.DataTypes
import com.qcmian.clipper.devtools.detect.CertDataTypeDetector
import com.qcmian.clipper.devtools.registry.DevToolsRegistry
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * 证书解析的**平台**那一半：PEM / 裸 Base64 / DER 文件 / PKCS#12 / JKS 与「混装的 PEM」各走一遍，
 * 外加「认不出来」时确实是那句话而不是异常。
 *
 * 夹具复用 `apksign/leaf.pem`（一份公开的自签证书，只有证书没有私钥）——同一个仓库里没必要为
 * 证书再养第二份夹具。逐格式的覆盖面在 `CertExampleFilesTest` 里。
 */
class CertParseTest {

    private val leafPem: String = resourceText("/apksign/leaf.pem")

    @Test
    fun `PEM 文本解析成一张证书，编出来的原文还能再解析回去`() {
        val document = okDocument(CertInput.Text(leafPem))
        assertEquals("PEM", document.formatLabel)
        assertEquals(1, document.certificates().size)
        assertTrue(document.certificates()[0].info.subject.contains("CN=demo"))

        val pem = document.certificates()[0].pem
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----"))
        assertTrue(pem.trimEnd().endsWith("-----END CERTIFICATE-----"))

        // 编出来的 PEM 是自洽的：拿它再解析一次仍是同一张证书。
        val again = okDocument(CertInput.Text(pem))
        assertEquals(document.certificates()[0].info.serial, again.certificates()[0].info.serial)
    }

    @Test
    fun `去掉 PEM 头的裸 Base64 也认`() {
        val bare = leafPem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("")

        val document = okDocument(CertInput.Text(bare))
        assertEquals("DER", document.formatLabel)
        assertEquals(1, document.certificates().size)
    }

    @Test
    fun `混装的 PEM 逐块解出来，顺序与文件里一致`() {
        // 证书 + 私钥装在同一份 `.pem` 里是常态（导出一次拿到两样），两样都该出现在结果里。
        val key = resourceText("/cert/private-pkcs8.pem")
        val text = leafPem + key

        val document = okDocument(CertInput.Text(text))
        assertEquals(2, document.items.size)
        assertIs<CertificateItem>(document.items[0])
        assertIs<KeyItem>(document.items[1])
    }

    @Test
    fun `认不出的 PEM 块报出它自己的 label，而不是闷着不说`() {
        val text = "-----BEGIN DH PARAMETERS-----\nQUJD\n-----END DH PARAMETERS-----\n"

        val failure = assertIs<CertParseResult.Failed>(parseCertInput(CertInput.Text(text)))
        assertTrue(failure.message.contains("DH PARAMETERS"), "要说清是哪个块不认")
    }

    @Test
    fun `DER 文件与 PKCS12、JKS 密钥库都读得出里面的证书`() {
        val der = Base64.getMimeDecoder().decode(
            leafPem.lineSequence().filterNot { it.startsWith("-----") }.joinToString(""),
        )

        val derDocument = okDocument(CertInput.File(tempFile(".der", der), password = null))
        assertEquals("DER", derDocument.formatLabel)
        assertEquals(1, derDocument.certificates().size)

        // PKCS#12 的证书也在口令保护之内（写在 MAC 保护的整体里），给了口令才读得到。
        val p12 = okDocument(CertInput.File(keyStoreFile("PKCS12"), password = "changeit"))
        assertEquals("PKCS#12", p12.formatLabel)
        assertEquals(1, p12.certificates().size)

        // JKS 的证书是明文存的，不给口令也读得出来。
        val jks = okDocument(CertInput.File(keyStoreFile("JKS"), password = null))
        assertEquals("JKS", jks.formatLabel)
        assertEquals(1, jks.certificates().size)
    }

    @Test
    fun `口令保护的密钥库先要口令，而不是直接说没有证书`() {
        val result = parseCertInput(CertInput.File(keyStoreFile("PKCS12"), password = null))

        val failure = assertIs<CertParseResult.Failed>(result)
        assertTrue(failure.needsPassword, "PKCS#12 没给口令时要提示填口令，界面据此露出那个输入框")
    }

    @Test
    fun `认不出来的输入给一句人话，而不是抛异常`() {
        val failure = assertIs<CertParseResult.Failed>(parseCertInput(CertInput.Text("这不是证书，只是一段话。")))
        assertTrue(failure.message.isNotBlank())
        assertFalse(failure.needsPassword)

        val missing = assertIs<CertParseResult.Failed>(
            parseCertInput(CertInput.File("/tmp/clipper-cert-does-not-exist-${System.nanoTime()}", null)),
        )
        assertFalse(missing.needsPassword)
    }

    private fun okDocument(input: CertInput): CertDocument =
        when (val result = parseCertInput(input)) {
            is CertParseResult.Ok -> result.document
            is CertParseResult.Failed -> error("解析失败：${result.message}")
        }

    private fun leafCertificate(): X509Certificate =
        requireNotNull(CertParseTest::class.java.getResourceAsStream("/apksign/leaf.pem")) {
            "夹具没了：jvmTest/resources/apksign/leaf.pem"
        }.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }

    /** 把一个证书条目写进 [type] 类型的密钥库，返回临时文件路径。 */
    private fun keyStoreFile(type: String): String {
        val file = File.createTempFile("clipper-cert-", if (type == "JKS") ".jks" else ".p12")
        file.deleteOnExit()
        val store = KeyStore.getInstance(type)
        store.load(null, null)
        store.setCertificateEntry("leaf", leafCertificate())
        file.outputStream().use { store.store(it, "changeit".toCharArray()) }
        return file.absolutePath
    }

    private fun tempFile(suffix: String, bytes: ByteArray): String {
        val file = File.createTempFile("clipper-cert-", suffix)
        file.deleteOnExit()
        file.writeBytes(bytes)
        return file.absolutePath
    }
}

/** 探测器与「推荐位」的接线：证书内容要能让证书工具排到前面。 */
class CertDataTypeDetectorTest {

    @Test
    fun `PEM 证书被认出，普通文本不被误判`() {
        val pem = resourceText("/apksign/leaf.pem")
        assertEquals(DataTypes.CERT, CertDataTypeDetector.typeName)
        assertTrue(CertDataTypeDetector.matches(pem))
        assertFalse(CertDataTypeDetector.matches("hello"))
        assertFalse(CertDataTypeDetector.matches("{\"a\":1}"))
    }

    @Test
    fun `粘贴一张证书时，证书工具排在推荐位第一`() {
        val registry = DevToolsRegistry.builtIn()
        val pem = resourceText("/apksign/leaf.pem")

        val ranked = registry.rankedTools(registry.detectTypes(pem))

        assertEquals("cert", ranked.first().metadata.id)
    }
}

/** 文档里那几张证书。 */
internal fun CertDocument.certificates(): List<CertificateItem> = items.filterIsInstance<CertificateItem>()

/** 读 jvmTest 资源里的一份夹具；没有就当场炸出来（比静默给空串好查）。 */
private fun resourceText(path: String): String =
    requireNotNull(CertDataTypeDetectorTest::class.java.getResourceAsStream(path)) { "夹具没了：$path" }
        .use { it.readBytes().decodeToString() }
