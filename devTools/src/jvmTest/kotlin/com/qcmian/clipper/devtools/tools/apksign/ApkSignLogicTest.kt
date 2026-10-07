package com.qcmian.clipper.devtools.tools.apksign

import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.security.auth.x500.X500Principal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * 签名工具里那几处**纯逻辑**的回归。
 *
 * 不去驱动真的 apksig：那要造一个带二进制 `AndroidManifest.xml` 的真 APK 当夹具，代价远大于它
 * 能守住的那点东西（签名算法本身是 AOSP 的实现，不是我们写的）。这里钉的是我们自己写的那几段——
 * 而它们恰好是最容易悄悄错的地方：十六进制排版（错一位就是一个看起来很像、但注册不过的指纹）、
 * 魔数分流（错了就把 APK 送去当密钥库开）、以及结果列表里那几行该不该出现。
 */
class ApkSignLogicTest {

    @Test
    fun `JKS 有固定魔数，其余一律按 PKCS#12 试`() {
        assertEquals("JKS", storeTypeFromMagic(byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFE.toByte(), 0xED.toByte())))
        // PKCS#12 是 DER：`30 82` 开头。它没有专属魔数，所以判据只能是「不是 JKS」。
        assertEquals("PKCS12", storeTypeFromMagic(byteArrayOf(0x30, 0x82.toByte(), 0x01, 0x02)))
        // 短到读不出魔数时也走 PKCS#12，由 `KeyStore.load` 去报错——那句话比这里能编的准确。
        assertEquals("PKCS12", storeTypeFromMagic(byteArrayOf(0x30)))
        assertEquals("PKCS12", storeTypeFromMagic(ByteArray(0)))
    }

    @Test
    fun `按开头几个字节分流密钥库与 APK`() {
        val jks = byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFE.toByte(), 0xED.toByte(), 0, 0, 0, 0)
        // PKCS#12 就是一段 DER：`30` 加长度。三种长度形式都得认，判据是「声明的总长 == 文件大小」。
        val shortForm = byteArrayOf(0x30, 0x65, 0x02, 0x01, 0x03, 0, 0, 0)          // 短形式：2 + 0x65 = 103
        val oneByteLength = byteArrayOf(0x30, 0x81.toByte(), 0x5A, 0x02, 0x01, 0x03, 0, 0) // 2 + 1 + 0x5A = 93
        val twoByteLength = byteArrayOf(0x30, 0x82.toByte(), 0x12, 0x34.toByte(), 0, 0, 0, 0) // 2 + 2 + 0x1234 = 4664
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0, 0, 0)

        assertEquals(SignInputKind.KeyStore, documentKindFromMagic(jks, jks.size.toLong()))
        assertEquals(SignInputKind.KeyStore, documentKindFromMagic(shortForm, 103))
        assertEquals(SignInputKind.KeyStore, documentKindFromMagic(oneByteLength, 93))
        assertEquals(SignInputKind.KeyStore, documentKindFromMagic(twoByteLength, 4664))
        assertEquals(SignInputKind.Apk, documentKindFromMagic(zip, zip.size.toLong()))

        // 声明的长度跟文件大小对不上，就不是一个完整的 DER 容器——JKS 那种「切开一半的文件」也在此列。
        assertNull(documentKindFromMagic(shortForm, 999))
        assertNull(documentKindFromMagic(twoByteLength, 8))

        // 认不出就说认不出：让调用方给一句「这既不是密钥库也不是 APK」，而不是猜一个塞进去。
        assertNull(documentKindFromMagic(byteArrayOf(0x7B, 0x22, 0x61, 0x22), 4))
        assertNull(documentKindFromMagic(ByteArray(0), 0))
        // `0x30` 正好是字符 `0`：一篇以「0」开头的文本不能因为这一个字节就被当成密钥库——它下一个
        // 字节是 ASCII，声明出来的长度几乎不可能正好等于文件大小。
        assertNull(documentKindFromMagic("0123456789".encodeToByteArray(), 10))
    }

    @Test
    fun `十六进制按写法排版，且不会把负字节写成八位`() {
        // `0xFF.toByte()` 直接转 Int 是 -1，`toString(16)` 会给出 `ffffffff`——指纹算错就是这么来的。
        assertEquals("FF", byteArrayOf(0xFF.toByte()).toHex())
        assertEquals("ff", byteArrayOf(0xFF.toByte()).toHex(upperCase = false))
        assertEquals("00", byteArrayOf(0x00).toHex())
        assertEquals("AA:BB", byteArrayOf(0xAA.toByte(), 0xBB.toByte()).toHex(separator = ":"))
        // 无分隔就是真的什么都不插：文档里写「无空格」，插了空格复制过去就校验不过。
        assertEquals("aabb", byteArrayOf(0xAA.toByte(), 0xBB.toByte()).toHex(upperCase = false))
        assertEquals("", ByteArray(0).toHex())
    }

    /**
     * **整份输出逐字对齐 `keytool -list -v`。**
     *
     * 这是这个工具最核心的一条：用户拿这份文本去跟 keytool 的输出对、去贴 bug 报告。所以这里断言
     * 的不是「包含某几段」，而是**整篇原文**——空行数、缩进、星号线、`扩展: ` 那个尾随空格，都在
     * 里面。少一个空行、多一个空格都算失败。
     */
    @Test
    fun `密钥库打印成 keytool -list -v 的原文`() {
        val leaf = certOf(
            atUtc(2046, 10, 1).toEpochMilliseconds(),
            subject = "CN=demo, O=Demo, C=CN",
            issuer = "CN=Demo Root CA, O=Demo, C=CN",
            serial = "8c504f5a6ce61e85",
            version = 3,
            digestSeed = 0xAB.toByte(),
            extensionsText = "#1: ObjectId: 2.5.29.19 Criticality=true\nBasicConstraints:[\n  CA:true\n]\n\n",
        )
        val entry = KeyStoreEntry(
            alias = "demo",
            isKeyEntry = true,
            chain = listOf(leaf),
            creationDateMillis = null,
        )

        // `creationDateMillis = null` 与两条时间戳走固定串：那三样由 JVM 排版，跟着本机 locale /
        // 时区走，钉不住；它们各自的排版由下面那条用例单独钉。
        //
        // 逐行写出来而不是用 `trimIndent()` 拼一个三引号串：这份原文里**空行的个数**本身就是要钉的
        // 东西，而三引号串里的空行看着都一样、数错了也看不出来。
        val expected = listOf(
            "密钥库类型: PKCS12",
            "密钥库提供方: SUN",
            "",
            "您的密钥库包含 1 个条目",
            "",
            "别名: demo",
            "条目类型: PrivateKeyEntry",
            "证书链长度: 1",
            "证书[1]:",
            "所有者: CN=demo, O=Demo, C=CN",
            "发布者: CN=Demo Root CA, O=Demo, C=CN",
            "序列号: 8c504f5a6ce61e85",
            "生效时间: ${keytoolTimestampText(leaf.notBeforeMillis)}," +
                " 失效时间: ${keytoolTimestampText(leaf.notAfterMillis)}",
            "证书指纹:",
            "\t SHA1: ${"AB:".repeat(19)}AB",
            "\t SHA256: ${"AB:".repeat(31)}AB",
            "签名算法名称: SHA256withRSA",
            "主体公共密钥算法: 2048 位 RSA 密钥",
            "版本: 3",
            "",
            // `扩展: ` 那个**尾随空格**是 keytool 原文，别顺手删——删了就跟它差一个字符。
            "扩展: ",
            "",
            "#1: ObjectId: 2.5.29.19 Criticality=true",
            "BasicConstraints:[",
            "  CA:true",
            "]",
            "",
            "",
            "",
            "*******************************************",
            "*******************************************",
            "",
            "",
        ).joinToString("\n") + "\n"

        assertEquals(expected, keytoolText("PKCS12", "SUN", listOf(entry), "/tmp/x.p12"))
    }

    /**
     * 「只放证书」的条目：没有 `证书链长度`、没有 `证书[i]:`，那一行位置**空一行**——这是 keytool
     * 原文里唯一一处「条目类型的差异体现在空行上」的地方，很容易顺手写成一样。
     */
    @Test
    fun `只放证书的条目不打证书链长度，且空一行`() {
        val certificate = certOf(
            atUtc(2050, 1, 1).toEpochMilliseconds(),
            subject = "CN=CA",
            version = 3,
            digestSeed = 0x01,
            extensionsText = "",
        )
        val entry = KeyStoreEntry(
            alias = "root",
            isKeyEntry = false,
            chain = listOf(certificate),
            creationDateMillis = null,
        )
        val text = keytoolText("PKCS12", "SUN", listOf(entry), "/tmp/x.p12")

        assertTrue(text.contains("条目类型: trustedCertEntry\n\n所有者: CN=CA"), text)
        assertFalse(text.contains("证书链长度"), text)
        assertFalse(text.contains("证书[1]:"), text)
        // 没有扩展的证书：整段不打，也不留一个光秃秃的 `扩展: `。
        assertFalse(text.contains("扩展:"), text)
    }

    /**
     * JKS 那一段提醒照搬 keytool 的原话（连命令行里的路径一起）。它不是客套：JKS 的私钥是拿
     * `PBEWithMD5AndTripleDES` 加密的，这是「该换掉这个文件」的真正理由。
     */
    @Test
    fun `JKS 末尾接上 keytool 那段迁移提醒`() {
        val text = keytoolText("JKS", "SUN", emptyList(), "/tmp/demo.jks")

        assertTrue(text.contains("Warning:\nJKS 密钥库使用专用格式。"), text)
        assertTrue(
            text.contains(
                "\"keytool -importkeystore -srckeystore /tmp/demo.jks" +
                    " -destkeystore /tmp/demo.jks -deststoretype pkcs12\"",
            ),
            text,
        )
        // 换成标准格式就不该再念这一句。
        assertFalse(keytoolText("PKCS12", "SUN", emptyList(), "/tmp/demo.p12").contains("Warning"), text)
    }

    @Test
    fun `证书日期按给它的时区排版`() {
        val noon = atUtc(2033, 10, 22).toEpochMilliseconds()
        assertEquals("2033-10-22", formatCertDate(noon, TimeZone.UTC))
        // 跨时区时同一个时刻可能落在不同的日子上——正因为如此，时区才是入参而不是内部读本机。
        assertEquals("2033-10-21", formatCertDate(noon, TimeZone.of("America/Los_Angeles")))
        // 月与日补零：`2033-1-2` 既不是 ISO 也不像日期。
        assertEquals("2026-01-02", formatCertDate(atUtc(2026, 1, 2).toEpochMilliseconds(), TimeZone.UTC))
    }

    @Test
    fun `另存为的名字带 signed 后缀且保留扩展名`() {
        assertEquals("app-release-signed.apk", signedFileNameOf("/tmp/build/app-release.apk"))
        assertEquals("app-signed.apk", signedFileNameOf("app.apk"))
        assertEquals("app-signed.apk", signedFileNameOf("app"))
        // 目录名里有点、文件名里没有：不能把目录那一段的「扩展名」当成文件的。
        assertEquals("app-signed.apk", signedFileNameOf("/tmp/a.b/app"))
    }

    @Test
    fun `读一个真文件的头几个字节来判类型`() {
        assertEquals(
            SignInputKind.KeyStore,
            documentKindOf(tempFileWith(0xFE, 0xED, 0xFE, 0xED, 0x00, 0x00, 0x00, 0x00, 0x01)),
        )
        assertEquals(
            SignInputKind.Apk,
            documentKindOf(tempFileWith(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08)),
        )
        // 比魔数还短的文件要读得完（读不满就是读不满，不能死循环），而且**判不出**就返回 `null`
        // ——半截的 JKS 魔数不足以说明它是密钥库。
        assertNull(documentKindOf(tempFileWith(0xFE, 0xED)))
        assertNull(documentKindOf(tempFileWith(0x41, 0x42, 0x43, 0x44)))
        // 不存在、以及是个目录：都返回 `null`，由调用方给一句提示，而不是抛异常出来。
        assertNull(documentKindOf("/tmp/clipper-apksign-does-not-exist-${System.nanoTime()}"))
        assertNull(documentKindOf(System.getProperty("java.io.tmpdir").orEmpty()))

        // 一个**真的** PKCS#12（JDK 自己写出来的）：这一类文件没有魔数，只有「DER 的长度对不对得上
        // 文件大小」这一条判据，所以必须拿真文件钉一次——手搓的字节测不出长度算错的那类毛病。
        assertEquals(SignInputKind.KeyStore, documentKindOf(realKeyStoreFile()))
    }

    @Test
    fun `没给口令时不说「没有证书」，说「要口令」`() {
        val withCertificate = entryOf(certOf(atUtc(2050, 1, 1).toEpochMilliseconds()))
        val withoutCertificate = entryOf(null)

        assertEquals(
            CertificateAvailability.Visible,
            certificateAvailabilityOf(withCertificate, readWithoutPassword = true),
        )
        assertEquals(
            CertificateAvailability.Visible,
            certificateAvailabilityOf(withCertificate, readWithoutPassword = false),
        )
        // 关键的一条：没给口令时读不到证书，**不能**下「没有证书」的结论。PKCS#12 把证书和私钥一起
        // 塞在加密的 safe bag 里，不给口令 `getCertificate` 直接给 `null`——照「读不到就是没有」去
        // 说，会对着一个好好的密钥库喊「这个条目没有证书」。
        assertEquals(
            CertificateAvailability.NeedsPassword,
            certificateAvailabilityOf(withoutCertificate, readWithoutPassword = true),
        )
        // 给了口令还读不到，那才是真的没有（JKS 允许一个条目上只放私钥）。
        assertEquals(
            CertificateAvailability.Absent,
            certificateAvailabilityOf(withoutCertificate, readWithoutPassword = false),
        )
    }

    /**
     * 读一个真密钥库时「这次有没有给口令」要如实带上；顺带钉住「填错口令会被 `load` 拦下来」——
     * 那正是留空与填错的区别：留空**跳过**校验，所以留空能开不代表口令对。
     */
    @Test
    fun `读密钥库时报出「有没有给口令」，填错口令则直接失败`() {
        val path = realKeyStoreFile()

        val withoutPassword = readKeyStore(path, null, null)
        assertTrue(withoutPassword is KeyStoreOutcome.Ready, "留空要能读（跳过校验）")
        assertTrue(withoutPassword.readWithoutPassword)

        val withPassword = readKeyStore(path, null, "secret".toCharArray())
        assertTrue(withPassword is KeyStoreOutcome.Ready)
        assertFalse(withPassword.readWithoutPassword)

        val wrongPassword = readKeyStore(path, null, "nope".toCharArray())
        assertTrue(wrongPassword is KeyStoreOutcome.Failed, "填错口令要在 load 这一步就被拦下来")
    }

    private fun entryOf(certificate: CertInfo?) = KeyStoreEntry(
        alias = "demo",
        isKeyEntry = true,
        chain = listOfNotNull(certificate),
        creationDateMillis = null,
    )

    /**
     * 「扩展」那一段是从 JDK 那份 `toString()` 里**切**出来的（见 `extensionsTextOf`），而这一段只有
     * 真证书才验得出来——它的正文是 JDK 内部那几个扩展类打的，手搓不出。夹具是一份公开的自签证书
     * （`apksign/leaf.pem`，**只有证书、没有私钥**）。
     *
     * 钉的是**切的边界**，不是 JDK 的排版本身：从 `#1: ObjectId:` 起、到扩展段收尾止，末尾不多带
     * `Algorithm:` / `Signature:` 那两段（它们是签名算法与签名值，不属于扩展）。
     */
    @Test
    fun `扩展段从 JDK 的 toString 里切得干净`() {
        val certificate = ApkSignLogicTest::class.java
            .getResourceAsStream("/apksign/leaf.pem")
            ?.use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        assertNotNull(certificate, "夹具没了：jvmTest/resources/apksign/leaf.pem")

        val file = File.createTempFile("clipper-apksign-", ".p12")
        file.deleteOnExit()
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        store.setCertificateEntry("leaf", certificate)
        file.outputStream().use { store.store(it, "secret".toCharArray()) }

        val ready = readKeyStore(file.absolutePath, null, "secret".toCharArray())
        assertTrue(ready is KeyStoreOutcome.Ready, "$ready")
        val extensions = ready.entries.single().chain.single().extensionsText

        assertTrue(extensions.startsWith("#1: ObjectId: "), extensions)
        // 这份证书就三条扩展：换成 `#N:` 的那三行都在。
        assertEquals(3, extensions.split("\n").count { it.startsWith("#") }, extensions)
        // 编号换过了：JDK 打的是 `[1]: `。
        assertFalse(extensions.contains("[1]:"), extensions)
        // 收尾干净，且末尾留一个空行（好与下面或星号线分开）。
        assertFalse(extensions.contains("Algorithm:"), extensions)
        assertFalse(extensions.contains("Signature:"), extensions)
        assertTrue(extensions.endsWith("]\n\n"), extensions)
    }

    /**
     * 别名按**字典序**排，不按读出来的顺序。`KeyStore.aliases()` 是哈希表的顺序（同一个密钥库两次
     * 读都可能不一样），而 keytool 打的是排过的——不排就没法跟它逐字对照（实测插入
     * `demo` / `zzz` / `aaa`，keytool 打 `aaa` / `demo` / `root` / `zzz`）。
     */
    @Test
    fun `条目按别名字典序排，而不是读出来的顺序`() {
        val entries = listOf("zzz", "demo", "aaa").map { alias ->
            KeyStoreEntry(
                alias = alias,
                isKeyEntry = false,
                chain = listOf(certOf(0, subject = "CN=$alias")),
                creationDateMillis = null,
            )
        }
        val aliases = keytoolText("PKCS12", "SUN", entries, "/tmp/x.p12")
            .split("\n")
            .filter { it.startsWith("别名: ") }
            .map { it.removePrefix("别名: ") }

        assertEquals(listOf("aaa", "demo", "zzz"), aliases)
    }

    /** 表单校验：只卡这一层判得了的那几项，其余交给真去建的那一步。 */
    @Test
    fun `表单校验挑出填错的项`() {
        fun problem(
            outPath: String = "/tmp/x.p12",
            alias: String = "key0",
            password: String = "123456",
            confirmPassword: String = "123456",
            subject: String = "CN=demo",
        ) = keyStoreRequestProblem(outPath, alias, password, confirmPassword, subject)

        assertNull(problem(), "填齐了就不该有话")
        assertEquals("先选一个保存位置", problem(outPath = "   "))
        assertEquals("别名不能为空", problem(alias = ""))
        // 口令的下限只有表单在守（见下面那条用例）。
        val shortPassword = problem(password = "12345", confirmPassword = "12345")
        assertNotNull(shortPassword)
        assertTrue(shortPassword.contains("至少 6"), shortPassword)
        // 打错一个字符的口令不可恢复，所以让用户敲两遍。
        assertEquals("两次口令不一样", problem(confirmPassword = "1234567"))
        // 主题要真能解析成 DN（用的是 JDK 自己那把尺子，见 `subjectProblem`）。
        val badSubject = problem(subject = "随便写点什么")
        assertNotNull(badSubject)
        assertTrue(badSubject.contains("CN="), badSubject)
        // 带**转义逗号**的合法写法不能被误杀——随手 `split(',')` 就会栽在这儿。
        assertNull(problem(subject = """CN=Smith\, John, O=Acme"""))
        // **属性名只能是已知的那几个**（实测：未知关键字会被 JDK 拒掉，`CN` 这种会规范化成大写）。
        assertNotNull(problem(subject = "FOO=bar"))
        assertEquals(
            "CN=小写属性名",
            X500Principal("cn=小写属性名").name,
            "属性名会被规范化成大写——所以别指望主题字符串原样存下来",
        )
        // 值本身几乎没约束：空的可以、`C` 不校验两位数（实测）。
        assertNull(problem(subject = "CN="))
        assertNull(problem(subject = "C=CHN"))
        // 逗号分隔的多个属性、以及直接写 OID 都行。
        assertNull(problem(subject = "CN=a, O=b, C=CN"))
        assertNull(problem(subject = "1.2.840.113549.1.9.1=a@b.com"))
    }

    /**
     * 六格证书项拼成 DN：顺序是**最具体的在前**，留空的项整个不写，值里的逗号要转义。
     *
     * 这三条正是「不要一个手写 DN 的框、要六格」的理由——语法由这里拼，用户只管填词。
     */
    @Test
    fun `证书六项拼成 DN：顺序、留空、转义`() {
        assertEquals(
            "CN=demo, OU=Android, O=Acme, L=Beijing, ST=Beijing, C=CN",
            subjectOf("demo", "Android", "Acme", "Beijing", "Beijing", "CN"),
        )
        // 留空的那几项**整个不写**（写一个 `O=` 会在证书里多出一条空属性）。
        assertEquals("CN=demo, C=CN", subjectOf("demo", "", "", "", "", "CN"))
        // 值里的逗号要转义，否则它会被当成属性之间的分隔符、DN 就散了。
        assertEquals("""CN=Smith\, John, O=Acme""", subjectOf("Smith, John", "", "Acme", "", "", ""))
        // 两端的空格裁掉（JDK 也会裁，但拼的时候就裁掉，比对起来才一致）。
        assertEquals("CN=demo", subjectOf("  demo  ", "", "", "", "", ""))
        // 六项全空：返回空串，由表单那条「至少要填一项」把关。
        assertEquals("", subjectOf("", "", "", "", "", ""))
    }

    /**
     * **容器与算法每个选项都必须带一句说明。**
     *
     * 这两行摆出来的意义全在那一句话上：光看 `PKCS#12` / `JKS`、`RSA 2048` / `EC P-256`，不熟的人
     * 根本选不出来。以后加选项时忘了写说明，这条会拦下来；说明写成把名字抄一遍也拦下来。
     */
    @Test
    fun `容器与算法的每个选项都有说明`() {
        (StoreFormat.entries.map { it.title to it.advice } +
            KeyAlgorithm.entries.map { it.title to it.advice }).forEach { (title, advice) ->
            assertTrue(advice.isNotBlank(), "$title 缺说明")
            assertFalse(title == advice, "$title 的说明只是把名字抄了一遍")
        }
    }

    /**
     * 有效期按**天**算（keytool 的 `-validity` 就是天），一年按 365 天、不做闰年精确。
     *
     * 顺带钉住 Play 那条线是真的会卡到：默认 30 年稳过，而 5 年不够——那句提示不是摆设。
     */
    @Test
    fun `有效期按天算，默认值稳稳晚于 Play 那条线`() {
        val now = atUtc(2026, 10, 7).toEpochMilliseconds()

        assertEquals(now + 30 * 365 * 86_400_000L, validityEndMillis(now, 30 * 365))
        assertTrue(validityEndMillis(now, DefaultValidityDays) > PlayKeyDeadlineMillis)
        assertTrue(validityEndMillis(now, 5 * 365) < PlayKeyDeadlineMillis)
    }

    /** 保存对话框预填的文件名跟着容器走：`.p12` / `.jks`。 */
    @Test
    fun `保存对话框预填的文件名跟着容器走`() {
        assertEquals("release-key.p12", defaultKeyStoreFileName(StoreFormat.Pkcs12))
        assertEquals("release-key.jks", defaultKeyStoreFileName(StoreFormat.Jks))
    }

    /**
     * 端到端：真建一个密钥库，再按普通密钥库读回来。**穿过的是真 JDK 与真 BouncyCastle**——证书
     * 到底合不合法、能不能被自己读出来，只有真跑一遍才算数。
     */
    @Test
    fun `新建的密钥库能读回来，是一张自签的叶证书`() {
        val path = createInto("key0", password = "123456", algorithm = KeyAlgorithm.EcP256)

        val ready = readKeyStore(path, null, "123456".toCharArray())
        assertTrue(ready is KeyStoreOutcome.Ready, "$ready")
        assertEquals(StoreFormat.Pkcs12.keyStoreType, ready.storeType)

        val entry = ready.entries.single()
        assertEquals("key0", entry.alias)
        assertTrue(entry.isKeyEntry)

        val certificate = entry.chain.single()
        assertEquals("CN=demo, O=Acme, C=CN", certificate.subject)
        // 自签：所有者与发布者是同一个。
        assertEquals(certificate.subject, certificate.issuer)
        assertTrue(certificate.notAfterMillis > PlayKeyDeadlineMillis, "${certificate.notAfterMillis}")
        // 摘要真算出来了：SHA-256 是 32 字节、SHA-1 是 20 字节。
        assertEquals(32, certificate.digests.sha256.size)
        assertEquals(20, certificate.digests.sha1.size)
        // 挑的是 EC P-256，证书里那把公钥就该是它。
        assertEquals("EC", certificate.publicKeyAlgorithm)
        assertEquals(256, certificate.publicKeyBits)
    }

    /** JKS 那一档也要能建（它是给老工具用的退路，但一样得通）。 */
    @Test
    fun `新建也能建 JKS，且魔数认得出来`() {
        val path = createInto("old", password = "123456", algorithm = KeyAlgorithm.Rsa2048, format = StoreFormat.Jks)

        val ready = readKeyStore(path, null, "123456".toCharArray())
        assertTrue(ready is KeyStoreOutcome.Ready, "$ready")
        assertEquals(StoreFormat.Jks.keyStoreType, ready.storeType)
        assertEquals(2048, ready.entries.single().chain.single().publicKeyBits)
    }

    /**
     * 目标位置已经有文件时**拒绝**，且一个字节都不动它。
     *
     * 系统保存对话框自己会问一次「要覆盖吗」，但这一页的路径框能手敲 / 粘贴，那条路不过对话框——
     * 而密钥库盖掉就找不回来。
     */
    @Test
    fun `不覆盖已存在的文件`() {
        val file = File.createTempFile("clipper-apksign-exists-", ".p12").also { it.deleteOnExit() }

        val outcome = createKeyStore(
            KeyStoreRequest(
                outPath = file.absolutePath,
                storeType = StoreFormat.Pkcs12.keyStoreType,
                alias = "key0",
                password = "123456".toCharArray(),
                algorithm = KeyAlgorithm.Rsa2048,
                validityDays = DefaultValidityDays,
                subject = "CN=demo",
            ),
        )

        assertTrue(outcome is CreateOutcome.Failed, "$outcome")
        assertTrue(outcome.message.contains("已经有文件"), outcome.message)
        // 没动过它：里面就算只装着一个空文件，也不该被这次调用清掉。
        assertEquals(0, file.length())
    }

    /**
     * **6 位下限只有表单在守**：`KeyStore.setKeyEntry` + `store` 用 3 位口令照样写得进去（实测）。
     *
     * 这一条是特意钉住的**事实**，不是期望的行为：它说明 [keyStoreRequestProblem] 那道卡不是多余
     * 的形式主义——去掉它，用户就能顺手建出一把口令是 `abc` 的签名密钥，而没有任何一层会拦。
     */
    @Test
    fun `口令的六位下限只有表单在卡，JDK 自己不卡`() {
        val path = File.createTempFile("clipper-apksign-new-", ".p12").also { it.deleteOnExit() }
        // 先删掉：要测的是「建一个新文件」，而目标已存在时会被上面那条防覆盖的闸拦下。
        path.delete()
        val shortPassword = "abc".toCharArray()

        val outcome = createKeyStore(
            KeyStoreRequest(
                outPath = path.absolutePath,
                storeType = StoreFormat.Pkcs12.keyStoreType,
                alias = "key0",
                password = shortPassword,
                algorithm = KeyAlgorithm.Rsa2048,
                validityDays = DefaultValidityDays,
                subject = "CN=demo",
            ),
        )
        assertTrue(outcome is CreateOutcome.Done, "JDK 不卡长度，这里应当真建出来了：$outcome")
        // 建出来还真能读回来——所以这一条错不了。
        val ready = readKeyStore(path.absolutePath, null, shortPassword)
        assertTrue(ready is KeyStoreOutcome.Ready, "$ready")
        // 而表单那一层是卡的。
        assertNotNull(
            keyStoreRequestProblem(
                outPath = path.absolutePath,
                alias = "key0",
                password = "abc",
                confirmPassword = "abc",
                subject = "CN=demo",
            ),
        )
    }

    /** 建一个新的密钥库到临时文件里，返回它的路径。三个新建用例共用。 */
    private fun createInto(
        alias: String,
        password: String,
        algorithm: KeyAlgorithm,
        format: StoreFormat = StoreFormat.Pkcs12,
    ): String {
        // 先建再删：`createTempFile` 会把文件造出来，而我们要测的是「建一个**新**文件」。
        val file = File.createTempFile("clipper-apksign-new-", ".tmp").also { it.deleteOnExit() }
        file.delete()
        val outcome = createKeyStore(
            KeyStoreRequest(
                outPath = file.absolutePath,
                storeType = format.keyStoreType,
                alias = alias,
                password = password.toCharArray(),
                algorithm = algorithm,
                validityDays = DefaultValidityDays,
                // 走**表单那条真路**：六项 → `subjectOf` → 证书。手工写一个 DN 字符串是测不出
                // 「用户填的那六格拼出来对不对」的。
                subject = subjectOf("demo", "", "Acme", "", "", "CN"),
            ),
        )
        assertTrue(outcome is CreateOutcome.Done, "$outcome")
        return file.absolutePath
    }

    /** 条目类型名照抄 JDK 的拼写；这两个词是查文档、搜报错时唯一能对上的关键词。 */
    @Test
    fun `条目类型名与 keytool 一致`() {
        assertEquals("PrivateKeyEntry", entryTypeName(isKeyEntry = true))
        assertEquals("trustedCertEntry", entryTypeName(isKeyEntry = false))
    }

    /**
     * **链上每一张都排一整块**，各带自己的 `证书指纹:` ——`keytool` 就是把每一张排成一张完整的表，
     * 少给哪一张，那一张的指纹（一个能拿去注册、能拿去 pin 的值）就没了。
     */
    @Test
    fun `链上每张证书各排一块，各带指纹`() {
        val leaf = certOf(
            atUtc(2046, 10, 1).toEpochMilliseconds(),
            subject = "CN=demo",
            issuer = "CN=Demo Root CA",
            serial = "aa11",
            digestSeed = 0x11,
        )
        val root = certOf(
            atUtc(2046, 10, 1).toEpochMilliseconds(),
            subject = "CN=Demo Root CA",
            serial = "bb22",
            digestSeed = 0x22,
        )
        val entry = KeyStoreEntry(
            alias = "demo",
            isKeyEntry = true,
            chain = listOf(leaf, root),
            creationDateMillis = null,
        )
        val text = keytoolText("PKCS12", "SUN", listOf(entry), "/tmp/x.p12")

        assertTrue(text.contains("证书链长度: 2"), text)
        // 两张的块头都在，且顺序是叶在前。
        assertEquals(2, text.split("\n").count { it.startsWith("证书[") }, text)
        assertTrue(text.indexOf("证书[1]:") < text.indexOf("证书[2]:"), text)
        // 每块里各有一份指纹，值分得开（叶那份全 `11`、根那份全 `22`）。
        assertTrue(text.contains("SHA1: ${"11:".repeat(19)}11"), text)
        assertTrue(text.contains("SHA1: ${"22:".repeat(19)}22"), text)
        // 两张的字段都真打出来了。
        assertTrue(text.contains("序列号: aa11"), text)
        assertTrue(text.contains("序列号: bb22"), text)
    }

    /**
     * APK 那一页仍走「标签 + 值」（见 `certificateRows`）：字段 + 两枚指纹，指纹标 `primary`。
     */
    @Test
    fun `APK 那一页的证书仍是逐行标签值，指纹标主色`() {
        val certificate = certOf(atUtc(2050, 1, 1).toEpochMilliseconds(), subject = "CN=demo", digestSeed = 0x11)
        val rows = certificateRows(certificate)

        assertEquals(certificate.subject, rows.single { it.label == "所有者" }.value)
        assertEquals("2048 位 RSA 密钥", rows.single { it.label == "主体公共密钥算法" }.value)
        val fingerprints = rows.filter { it.primary }
        assertEquals(listOf("SHA-256", "SHA-1"), fingerprints.map { it.label })
        assertTrue(fingerprints.all { it.value.startsWith("11:11") }, "$fingerprints")
    }

    /** UTC 的那一刻。测试里凡是「某天」都用它，免得跟着本机时区漂。 */
    private fun atUtc(year: Int, month: Int, day: Int) =
        LocalDateTime(year, month, day, 0, 0, 0).toInstant(TimeZone.UTC)

    /**
     * @param digestSeed 摘要的填充字节。用来让两张证书的指纹**长得不一样**——否则「哪一行属于谁」
     *   这类断言根本验不出来（两组都印同一串，谁都能对上）。
     * @param extensionsText 「扩展」那一段的正文，`keytool` 原文那一份（见 `CertInfo.extensionsText`）。
     */
    private fun certOf(
        notAfterMillis: Long,
        subject: String = "CN=test",
        issuer: String = subject,
        serial: String = "1",
        digestSeed: Byte = 0,
        version: Int = 3,
        extensionsText: String = "",
    ) = CertInfo(
        subject = subject,
        issuer = issuer,
        serial = serial,
        notBeforeMillis = 0L,
        notAfterMillis = notAfterMillis,
        signatureAlgorithm = "SHA256withRSA",
        publicKeyAlgorithm = "RSA",
        publicKeyBits = 2048,
        version = version,
        digests = CertDigests(
            sha1 = ByteArray(20) { digestSeed },
            sha256 = ByteArray(32) { digestSeed },
        ),
        extensionsText = extensionsText,
    )

    /** 写一个临时文件，内容就是要的那几个字节；返回它的路径。 */
    private fun tempFileWith(vararg bytes: Int): String {
        val file = File.createTempFile("clipper-apksign-", ".bin")
        file.deleteOnExit()
        file.writeBytes(ByteArray(bytes.size) { bytes[it].toByte() })
        return file.absolutePath
    }

    /**
     * 一个**真的** PKCS#12 文件：JDK 自己写出来的空密钥库。
     *
     * 空的那种只有百来字节，长度用的是短形式（实测 `30 65`）——手搓字节去测很容易正好绕开这一档，
     * 而真机上用户新建的空密钥库就是这个尺寸。
     */
    private fun realKeyStoreFile(): String {
        val file = File.createTempFile("clipper-apksign-", ".p12")
        file.deleteOnExit()
        val store = java.security.KeyStore.getInstance("PKCS12")
        store.load(null, null)
        file.outputStream().use { store.store(it, "secret".toCharArray()) }
        return file.absolutePath
    }
}
