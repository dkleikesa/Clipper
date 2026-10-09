package com.qcmian.clipper.devtools.tools.apksign

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readAtMostTo
import kotlin.time.Instant

/**
 * Android 签名工具的**平台无关**那一半：数据模型、纯逻辑，外加三个 `expect`。
 *
 * 平台那一侧刻意只回答三件事——读一个密钥库、验一个 APK、签一个 APK（见 `ApkSign.jvm.kt`）。
 * 除此之外的一切都留在这里：指纹怎么排版、证书日期怎么显示、一段拖进来的字节到底是什么文件。
 * 它们是纯函数，因此 `jvmTest` 不必造一个真密钥库就能把它们钉住。
 *
 * 为什么整个工具只落在 JVM 上：签名要读 `java.security.KeyStore`、要重写 ZIP，验签与签名交给
 * AOSP 的 apksig（纯 Java，没有 KMP 制品）。桌面端已有 JVM；将来若需其他平台，补一个 `actual`
 * 报「本平台不支持」即可，界面层无需改动。
 */

/**
 * 三个签名方案。**v4 刻意不做**：它产出的 `APK Signature Scheme v4` 是给增量安装（`adb install
 * --incremental`）用的一个 `.idsig` 旁路文件，要额外给输出路径，且必须建立在 v2/v3 之上——
 * 多出来的复杂度与「签一个包发出去」这件事不成比例。
 */
internal enum class SignScheme(val title: String, val hint: String) {
    V1("v1", "JAR 签名（META-INF 里的三条记录）。Android 7.0 以下只认它"),
    V2("v2", "整包摘要（APK Signing Block）。Android 7.0+；v1 被剥掉后仍验得出篡改"),
    V3("v3", "在 v2 之上加了密钥轮换信息。Android 9+"),
}

/**
 * 一张证书上取出来的一项信息。
 *
 * 时间是**毫秒**而不是格式化好的字符串：格式化要用到本机时区，属于显示层的事，放在这一侧才能
 * 跟着 [formatCertDate] 一起被单测，而不是在平台实现里散着写两遍。
 */
internal class CertInfo(
    val subject: String,
    val issuer: String,
    val serial: String,
    val notBeforeMillis: Long,
    val notAfterMillis: Long,
    /** **这张证书自身**用的签名算法（谁给它盖的章），与里面公钥的类型无关。 */
    val signatureAlgorithm: String,
    /**
     * 证书里那把**公钥**的算法（`RSA` / `EC`）。
     *
     * 名字里带 `publicKey` 是刻意的：这几个字段读的都是**公钥**（见 `ApkSign.jvm.kt` 里的
     * `publicKey.algorithm`）。叫成笼统的 `keyAlgorithm` 会让人以为在说私钥——而私钥的属性在
     * 证书里根本看不到，只能在密码库里取出来才知道。
     */
    val publicKeyAlgorithm: String,
    /** 公钥位数（RSA 的模数长度 / EC 的阶）；认不出算法时为 0。 */
    val publicKeyBits: Int,
    /**
     * X.509 的版本号（`keytool` 的 `版本:` 那一行）。v3 给 `3`。
     */
    val version: Int,
    /**
     * 这张证书自己的几枚摘要。
     *
     * 挂在**证书**上而不是挂在条目上：`keytool -list -v` 的 `证书指纹:` 是**每一块里的**一份，链上
     * 有几张就有几组。挂到条目上就只能给叶证书那一份，链上其余几张的指纹会凭空消失。
     */
    val digests: CertDigests,
    /**
     * 「扩展」那一段的正文：`#1: ObjectId: …` 加扩展自己的正文，一段一条，末行后带一个换行。
     *
     * 这一段是**格式化好的文本**而不是结构化数据，因为它的正文只有 JDK 内部那几个扩展类会打
     * （`BasicConstraints:[`、`KeyIdentifier [`、`0000: A4 D4 …` 这类）；公共 API 拿得到的只有
     * `getExtensionValue` 给的那串 DER。自己照着规范重写一遍几十种扩展的打印器，是另一件事。
     * 来源与取法见 `ApkSign.jvm.kt` 的 `extensionsText`。
     *
     * 空串表示这张证书没有扩展——那时整段不打（`keytool` 也一样）。
     */
    val extensionsText: String,
)

/**
 * **证书**的两枚摘要，原始字节。
 *
 * 算的是**整张 X.509 证书的 DER**（`X509Certificate.getEncoded()`）——各家平台文档里写的那串指纹
 * （Google、微信、Firebase）指的就是它。
 *
 * 只留 `keytool` 会给的那两枚（`SHA1` / `SHA256`）：面板打印的就是它的样子，多算一枚没人看。
 *
 * 存字节而不是十六进制串：同一枚摘要在别处还要用别的写法（无分隔小写给微信那类），先钉死成一种
 * 写法，另一处就只能拿字符串再加工。
 *
 * 刻意**不是** data class：`ByteArray` 的 `equals` 是引用比较，套上 data class 只会给出一个
 * 「看起来能用、其实永远不等」的相等性。
 */
internal class CertDigests(val sha1: ByteArray, val sha256: ByteArray)

/** 密钥库里的一个条目。 */
internal class KeyStoreEntry(
    val alias: String,
    /** 私钥条目（能拿去签名）；`false` 是只放了证书的条目。 */
    val isKeyEntry: Boolean,
    /**
     * 证书链，**叶在前**；空表表示这个条目上没有证书（罕见，但 JKS 允许）。
     *
     * 存**整条**而不是只存叶证书：`java.security.KeyStore.getCertificate` 只给链上第一张，用它就
     * 等于把后面几张扔掉——`keytool` 报的 `证书链长度` / `证书[1]` `证书[2]` 就是这么来的，漏了它
     * 就答不上「这条链完不完整」。
     */
    val chain: List<CertInfo>,
    /**
     * 「创建日期」那一行，毫秒；取不到时为 `null`（那时整行不打，而不是编一个日期出来）。
     *
     * 它**不是**证书的生效时间：这是这个条目**被写进密钥库**的时刻，由密钥库自己记着。实测 JDK 的
     * `KeyStore.getCreationDate` 对 JKS 与 PKCS#12 都给得出，但那是**实现**给的，接口上并不保证
     * ——所以这里允许没有。
     *
     * 存毫秒而不是格式化好的字符串：`keytool` 那个 `2026年10月6日` 是**按本机 locale** 排的
     * （英文环境是 `Oct 6, 2026`），排版要在 JVM 那一侧做，见 [keytoolDayText]。
     */
    val creationDateMillis: Long?,
) {
    /** 配着私钥的那一张（链上的第一张）；没有证书时为 `null`。 */
    val certificate: CertInfo? get() = chain.firstOrNull()
}

/** 一个签名者。APK 通常只有一个；密钥轮换过的包才会有多个。 */
internal class SignerInfo(val certificate: CertInfo)

/** 读一个密钥库的结果。 */
internal sealed interface KeyStoreOutcome {
    /**
     * 读出来了。
     *
     * @param storeType **实际**认出来的容器类型（`JKS` / `PKCS12`），不是扩展名——扩展名可以随便起，
     *   界面上要据此提醒「这东西的名字和它本身对不上」。
     * @param readWithoutPassword 这一次是**没给口令**就读的。它决定了「读不到证书」该怎么解释
     *   （见 [certificateAvailabilityOf]）：JKS 的证书是明文存的，不给口令也读得出来；PKCS#12 则把
     *   证书和私钥一起塞进加密的 safe bag，不给口令连证书都取不出来——实测 `getCertificate` 直接
     *   返回 `null`。少了这一位，界面就会把「被锁住」说成「压根没有」。
     */
    class Ready(
        val storeType: String,
        /**
         * 实现这个密钥库的 JCA 提供方名（`keytool` 的 `密钥库提供方:`）。JDK 给的都是 `SUN`，
         * 装了别的提供方（如 BouncyCastle 的 `BC`）时才有变数。
         */
        val providerName: String,
        val readWithoutPassword: Boolean,
        val entries: List<KeyStoreEntry>,
    ) : KeyStoreOutcome

    /** 读不出来。[message] 是给用户看的一句人话，不是异常栈。 */
    class Failed(val message: String) : KeyStoreOutcome
}

/**
 * 一个条目上的证书此刻是什么状态。
 *
 * 做成枚举而不是在界面里就地判：三种状态**都要**说不同的话，而判据本身是纯的（看一眼条目 + 这次
 * 有没有给口令），留在这一侧才钉得住——界面上那三句话靠眼看是验不出来的。
 */
internal enum class CertificateAvailability {
    /** 读到了，正常显示。 */
    Visible,

    /**
     * 没读到，而且这次**没给口令**。
     *
     * 这就是 PKCS#12 留空时的样子：不是这个条目没有证书，而是它被加密了。两种情况对用户是两件事
     * ——一件是「填上口令再看」，另一件是「这个条目本来就只有私钥」——绝不能合起来说成「没有证书」。
     */
    NeedsPassword,

    /** 没读到，但这次给了口令：那这个条目上确实没有证书。 */
    Absent,
}

/**
 * 判一个条目上的证书现在是个什么状态。
 *
 * 「读不到」有**两种**可能，而界面上只能有一个说法，所以必须先分开：给了口令还读不到，才是真的
 * 没有；没给口令就下结论，等于把「没解锁」说成「不存在」。
 */
internal fun certificateAvailabilityOf(
    entry: KeyStoreEntry,
    readWithoutPassword: Boolean,
): CertificateAvailability = when {
    entry.certificate != null -> CertificateAvailability.Visible
    readWithoutPassword -> CertificateAvailability.NeedsPassword
    else -> CertificateAvailability.Absent
}

/** 验一个 APK 的结果。 */
internal sealed interface VerifyOutcome {
    /**
     * 验完了（无论通不通过，[verified] 说了算）。
     *
     * 「验完了但不通过」与 [Failed] 是两件事：前者说明这是个能读的 APK、只是签名对不上或被改过；
     * 后者说明这压根不是一个能读的 APK。用户该做的事完全不同。
     */
    class Ready(
        val verified: Boolean,
        /** 真正验过的方案。**没开的那几项不在这里**——`apksigner verify` 报的就是这一组。 */
        val schemes: Set<SignScheme>,
        val signers: List<SignerInfo>,
        val errors: List<String>,
        val warnings: List<String>,
        /**
         * 这个包的**密钥轮替链**（proof-of-rotation）；没轮替过时是空的，顺序是**原始证书在前、
         * 当前在末位**（apksig 的排法：每一代都为自己的下一代作证）。
         *
         * 与 [signers] 有重叠但各有各的用途：[signers] 只放「当前真正在签的那一张」（`keytool
         * -printcert -jarfile` 也只打它，结果区要跟那份原文逐字对齐），链上更早的那几张只在这里
         * 露出来——而各平台注册要的常常正是最早那张。
         */
        val rotation: List<CertInfo>,
    ) : VerifyOutcome

    /** 读不了这个文件（不是 ZIP、截断、不是 APK）。 */
    class Failed(val message: String) : VerifyOutcome
}

/**
 * 一次签名请求。
 *
 * 密码是 `CharArray` 而不是 `String`：`String` 不可擦除，用完只能等 GC，而这里装的是私钥的口令。
 * 调用方负责在请求结束之后把数组填零（见 `ApkSignDevTool` 的 `sign`）。
 */
internal class SignRequest(
    val apkPath: String,
    val outPath: String,
    val keystorePath: String,
    /** `null` = 按魔数认（见 [storeTypeFromMagic]）。 */
    val storeType: String?,
    val storePassword: CharArray,
    val keyAlias: String,
    val keyPassword: CharArray,
    val schemes: Set<SignScheme>,
    /**
     * 签名时当作包的 `minSdkVersion` 用；`null` = **让 apksig 自己去包里读**——工具走的就是这一条
     * （它读的是 `AndroidManifest.xml` 里那个值，与 `apksigner` 不指定 `--min-sdk-version` 时一样）。
     *
     * 摆在这里是给测试当入口（同 `GlobalHotKeyController` 的 `now`）：没有真包时，apksig 会停在
     * 「读不到 `AndroidManifest.xml`」上，而轮替那一路的分档要一个值才走得下去。
     */
    val minSdkVersion: Int? = null,
    /**
     * 密钥轮替：把包从 [RotationRequest] 里那把**当前密钥**接到上面这把新钥上；不做轮替时为 `null`。
     */
    val rotation: RotationRequest? = null,
)

/**
 * 密钥轮替里的「当前密钥」——这个包**现在**用的那把钥。
 *
 * 为什么轮替非要有它，而且非要有它的**私钥**：新钥要合法地接替旧钥，靠的是旧钥亲笔签下「旧 → 新」
 * 这一跳（apksig 的 proof-of-rotation 就是这么一段一段攒起来的）。没有旧私钥，这一步根本无从谈起
 * ——这是轮替最容易被误解的地方：它不是「换一把钥重新签一遍」。
 *
 * 它还多担一件事：**低版本那一层仍由它签**（v1 / v2 / 3.0），所以没升级到 Android 13 的设备看到的
 * 还是它们本来就认识的那把钥，照样能更新（见 `signApk` 里的说明）。
 */
internal class RotationRequest(
    val keystorePath: String,
    /** `null` = 按魔数认（见 [storeTypeFromMagic]）。 */
    val storeType: String?,
    val storePassword: CharArray,
    val keyAlias: String,
    val keyPassword: CharArray,
)

/** 签名的结果。 */
internal sealed interface SignOutcome {
    /**
     * 签好了。
     *
     * [lineage] 是这次轮替攒出来的**整条轮替链**（没轮替时为 `null`）：它已经写进包里，但**该单独再
     * 存一份**——「以后再接着轮替」用的就是它（见 `lineageFileNameOf`）。
     */
    class Done(val outPath: String, val lineage: ByteArray? = null) : SignOutcome

    class Failed(val message: String) : SignOutcome
}

/**
 * 轮替链存到哪：**紧挨着签出来的包**，名字在包的完整文件名后面接 `.lineage`
 * （`app-release-signed.apk.lineage`）。
 *
 * 与 v4 的 `.idsig` 同一套摆法（`app.apk` 配 `app.apk.idsig`）：看到其中一半，就知道另一半在哪。
 *
 * 它**不是签名时的输入**（链在签名那一刻由两把钥现造，见 `rotationLineage`），只是把「旧钥接过新钥」
 * 这件事单独留一份档：以后再要接着轮替，apksig 得先有整条老链（那个包还在的话，也能用
 * `SigningCertificateLineage.readFromApkFile` 从包里读回来）。所以两边各存一份，谁还在都行。
 */
internal fun lineageFileNameOf(signedApkPath: String): String = "$signedApkPath.lineage"

/**
 * 新建密钥库时能挑的密钥规格——只给 Android 生态里真在用的这四种。
 *
 *  - RSA 2048：存量主流，各平台通收；
 *  - RSA 4096：强度更高，单次签名耗时略增；
 *  - EC P-256 / P-384：更短的密钥、更快的签名，Android 7 起全支持。
 *
 * 刻意不给 DSA（已淘汰）和更短的 RSA（Play 不收），也不给 `Ed25519`：它要 Android 13 才普及的
 * v3.1 签名方案，签出来的包在旧设备上装不上——那是另一件事。
 */
internal enum class KeyAlgorithm(
    val title: String,
    /**
     * 选它意味着什么，**给不熟悉这几个缩写的人看**——显示在选项右边那一列（表单的说明列），给的是
     * 当前选中那一项的说法。
     *
     * 半句说**适用范围**、半句说它和别的差在哪：
     *
     *  - 适用范围就是那些版本号，两个 [signatureAlgorithm] 各对应一种；数字的出处是 apksig 自己的
     *    `SignatureAlgorithm` 表（`mJcaSigAlgMinSdkVersion` 一列）——AOSP 用它决定**能不能给这个
     *    包签名**，也就是「哪些 Android 版本验得了」：`SHA256withRSA` 是 `INITIAL_RELEASE`(API 1)，
     *    `SHA256withECDSA` 是 `HONEYCOMB`(API 11)。v2 / v3 那种带签名的块两边一样，都是 `N`(API
     *    24)，所以差别只在 v1（JAR 签名）那一层。
     *  - 另一半必须是别的差别：只写适用范围的话，`RSA 2048` 与 `RSA 4096` 会一模一样、
     *    `EC P-256` 与 `EC P-384` 也是——那一列就不再帮人做选择了。
     *
     * 短，是因为它只占一行、且一列要对齐：四个选项各挂一句长说明，说明列会被撑得很宽，窗口一窄
     * 就折行——而这一列的价值全在「从上往下对齐着扫」上。
     *
     * **不写 Play 收不收**：官方那张签名页只说有效期要过 2033-10-22 与口令强度，对算法一个字没有。
     * 没有出处的话不进这里——它会被当成事实读。
     */
    val advice: String,
    /** 交给 `KeyPairGenerator` 的算法名。 */
    val keyPairAlgorithm: String,
    /** RSA 的模数位数；EC 用 [curve]。 */
    val keySize: Int,
    /** EC 的曲线名（`ECGenParameterSpec`）；RSA 为 `null`。 */
    val curve: String?,
    /** **这张证书自己**用的签名算法（谁给它盖的章）。 */
    val signatureAlgorithm: String,
) {
    Rsa2048("RSA 2048", "Android 全版本；最常用", "RSA", 2048, null, "SHA256withRSA"),
    Rsa4096("RSA 4096", "Android 全版本；强度更高、更慢", "RSA", 4096, null, "SHA256withRSA"),
    EcP256("EC P-256", "Android 3.0 起；密钥短、签名快", "EC", 256, "secp256r1", "SHA256withECDSA"),
    EcP384("EC P-384", "Android 3.0 起；强度更高", "EC", 384, "secp384r1", "SHA384withECDSA"),
}

/**
 * 密钥库的容器。
 *
 * `PKCS#12` 排在前：它是行业标准（JDK 9 起为 `KeyStore` 的默认类型），而 `JKS` 只在对接旧工具时
 * 才需要——其私钥使用 `PBEWithMD5AndTripleDES` 加密，`keytool` 自身也会在输出末尾提示更换。
 *
 * [advice] 显示在**选项右边那一列**（即表单的说明列，见 `FormRow`），给的是**当前选中**那一项的
 * 说法：一条说**它是什么**，另一条说**什么时候才该选它**——后者才是真正要回答的问题（默认已经落
 * 在 PKCS#12 上，用户要做的是判断「我要不要改」）。
 *
 * 刻意不写「Android 推荐」这类表述：Android Studio 的「新建密钥库」对话框产出的就是 `.jks`，称
 * Android 推荐 PKCS#12 与用户实际看到的结果相反。推荐来自 **JDK / keytool**（JDK 9 起为 `KeyStore`
 * 的默认类型，且会打印迁移提示），而非 Android。`JKS` 的 3DES 细节不进入标签（过长），它会出现在
 * 两处：读取 JKS 时输出中的 `Warning:`，以及 `keytool` 的输出。
 */
internal enum class StoreFormat(
    val title: String,
    val advice: String,
    val keyStoreType: String,
    /** 文件名惯用的扩展名（不含点）。认与写用的是同一份，见 [storeFormatOfPath] / [withStoreExtension]。 */
    val extension: String,
) {
    Pkcs12("PKCS#12", "行业标准", "PKCS12", "p12"),
    Jks("JKS", "老格式，只在对接老工具时用", "JKS", "jks"),
}

/** 密钥库文件认得的扩展名。`pfx` 是 PKCS#12 在 Windows 那边的写法，`keystore` 是老项目里的惯用名。 */
private val KeyStoreExtensions = setOf("p12", "pfx", "jks", "keystore")

/**
 * 路径的扩展名暗示的是哪一种容器；认不出来（没有扩展名、或别的扩展名）时 `null`。
 *
 * 只用在**用户刚从保存对话框里挑了个名字**那一处：他在那儿把名字写成 `.jks`，就是想要 JKS——面板
 * 跟着改过来，总比继续宣称 `PKCS#12`、再写一个内容与名字对不上的文件好。
 *
 * 这**不是**一条「扩展名决定容器」的规矩：容器始终由选项说了算，这里只是让选项追上用户已经做出的
 * 选择。路径框里手敲的名字走的是另一条路（那边不动）——对话框那一次是**明确的选择**，敲字不是。
 */
internal fun storeFormatOfPath(outPath: String): StoreFormat? =
    when (outPath.substringAfterLast('/').substringAfterLast('.', "").lowercase()) {
        "jks", "keystore" -> StoreFormat.Jks
        "p12", "pfx" -> StoreFormat.Pkcs12
        else -> null
    }

/**
 * 把路径的扩展名换成这个容器的扩展名。
 *
 * 换容器时使用（见 `KeyStoreForm.withFormat`）：容器由**选项**决定，路径的扩展名只是它的投影——
 * 切换容器而扩展名仍为另一个，会留下自相矛盾的状态。
 *
 * 三种情况分别处理：
 *  - 扩展名本就是密钥库的（[KeyStoreExtensions]）→ 替换；
 *  - 没有扩展名（`/tmp/mykey`）→ 补上（通常是用户删掉了默认名中的那一段）；
 *  - 是其他扩展名（`/tmp/mykey.txt`）→ **保持不变**：这是用户特意写的。
 *
 * 只看**最后一段**，所以 `/Users/a.b/mykey` 里那个点不算扩展名。
 */
internal fun withStoreExtension(outPath: String, format: StoreFormat): String {
    if (outPath.isBlank()) return outPath
    val name = outPath.substringAfterLast('/')
    val dot = name.lastIndexOf('.')
    // 没有扩展名（`mykey`），或者整段名字就是个 `.jks` 这样的隐藏文件：都当没有扩展名，补一个。
    if (dot <= 0) return "$outPath.${format.extension}"
    if (name.substring(dot + 1).lowercase() !in KeyStoreExtensions) return outPath
    // `take` 到那个点（含）为止，再接到新扩展名上。
    return outPath.take(outPath.length - name.length + dot + 1) + format.extension
}

/**
 * 一次新建请求。
 *
 * 口令用 `CharArray`（同 [SignRequest]：不可擦除的 `String` 装着私钥口令会一直躺在堆里等 GC），
 * 且 store 与 key **共用同一个**——`PKCS#12` 在格式上就要求两者相同，分开填两个只会让人以为
 * 可以不一样。
 */
internal class KeyStoreRequest(
    val outPath: String,
    /** 直接交给 `KeyStore.getInstance` 的类型名（见 [StoreFormat.keyStoreType]）。 */
    val storeType: String,
    val alias: String,
    val password: CharArray,
    val algorithm: KeyAlgorithm,
    val validityDays: Int,
    /** 主题，keytool 的 `-dname`：RFC 2253 的 `CN=…, O=…, C=…`。 */
    val subject: String,
    /**
     * 这一次要写的文件，用户**已经给过覆盖许可**。
     *
     * 许可是两处来的：系统保存对话框里那句「要覆盖吗」（用户答过了），或者上一次点「创建」拿到的是
     * [CreateOutcome.Exists]、这次点的是那个已变成「覆盖」的按钮。默认 `false`——路径框能手敲、能
     * 手粘，那条路没有人问过用户，而盖掉一个可能装着**再也拿不回来的私钥**的文件不能算数。
     */
    val overwrite: Boolean = false,
)

/** 新建的结果。 */
internal sealed interface CreateOutcome {
    class Done(val outPath: String) : CreateOutcome

    class Failed(val message: String) : CreateOutcome

    /**
     * 目标位置已经有文件，而这一次**没有人**给过覆盖许可。
     *
     * 它不是失败：**能**盖，只是要再问一句（那里面可能是一把再也拿不回来的私钥）。交给界面去问
     * ——面板上那个按钮这时候变成「覆盖」，再点一次就带着 [KeyStoreRequest.overwrite] 回来。
     *
     * 与 [Failed] 分开是有意的：两者在界面上要做的事完全不同（一个是「再点一次就行」，一个是「去
     * 改点什么」）。混成一句话让界面去认字符串，就等着哪天改一个字全坏掉。
     */
    class Exists(val outPath: String) : CreateOutcome
}

/** 新建一个密钥库，写到 [KeyStoreRequest.outPath]。 */
internal expect fun createKeyStore(request: KeyStoreRequest): CreateOutcome

/**
 * 读一个密钥库。
 *
 * [password] 为 `null` 表示「只读证书」：JKS 与 PKCS#12 都允许在不校验口令的情况下列出别名与
 * 证书（证书本身是公开的），所以「先看指纹再决定要不要输密码」这条路走得通。取私钥必须有口令，
 * 那是 [signApk] 的事。
 */
internal expect fun readKeyStore(path: String, storeType: String?, password: CharArray?): KeyStoreOutcome

/** 验一个 APK 的签名。等价于 `apksigner verify --print-certs`。 */
internal expect fun verifyApk(path: String): VerifyOutcome

/** 给一个 APK 签名，写到 [SignRequest.outPath]。**绝不改动输入那个文件。** */
internal expect fun signApk(request: SignRequest): SignOutcome

/**
 * 一个拖进来的文件是什么。按**内容**判，不看扩展名（密钥库叫 `.keystore` 也可能是 PKCS#12，
 * 而 APK 就是 ZIP）。
 */
internal enum class SignInputKind { KeyStore, Apk }

/** JKS 的魔数，`FE ED FE ED`（Sun 私有格式自己写的标识）。 */
private val JksMagic = byteArrayOf(0xFE.toByte(), 0xED.toByte(), 0xFE.toByte(), 0xED.toByte())

/** ZIP 的本地文件头，`PK\x03\x04`。APK 是 ZIP，所以认它就等于认出 APK。 */
private val ZipMagic = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

/**
 * 判容器类型：只有 JKS 有固定魔数。
 *
 * PKCS#12 没有魔数，它就是一段 DER，以 `30 82`（SEQUENCE + 两字节长度）开头——但**不能**反过来
 * 「以 `30 82` 开头就是 PKCS#12」以外还要求别的：`.p12` 之外还有 ASN.1 家族的一大堆东西长得一样。
 * 这里的判据无需覆盖全部情况：**不是 JKS 就按 PKCS#12 处理**，加载失败时由 `KeyStore.load` 报错，
 * 那一条信息比「魔数不匹配」更有参考价值。
 */
internal fun storeTypeFromMagic(head: ByteArray): String =
    if (head.size >= JksMagic.size && head.copyOf(JksMagic.size).contentEquals(JksMagic)) "JKS" else "PKCS12"

/**
 * 判一个文件是密钥库还是 APK。
 *
 * 只读开头几个字节，因此不必把文件整块读进来——拖进来的可能是个 200MB 的包。认不出来时返回
 * `null`，由调用方说一句「这不是密钥库也不是 APK」。
 *
 * @param fileSize 文件总字节数。判 DER 时要它：只看开头那两三个字节分不出「一个 DER 容器」与
 *   「一篇恰好以 `0` 开头的文本」，声明的长度对不对得上整个文件才是那条分界线（见 [derTotalLength]）。
 */
internal fun documentKindFromMagic(head: ByteArray, fileSize: Long): SignInputKind? {
    if (head.size >= JksMagic.size && head.copyOf(JksMagic.size).contentEquals(JksMagic)) {
        return SignInputKind.KeyStore
    }
    if (head.size >= ZipMagic.size && head.copyOf(ZipMagic.size).contentEquals(ZipMagic)) {
        return SignInputKind.Apk
    }
    // 剩下的一档是 PKCS#12。它没有自己的魔数，就是一段 DER：一个 SEQUENCE 从文件头铺到文件尾，
    // 所以判据用 DER 自己的定义——**声明的长度对不对得上整个文件**。
    //
    // 光看头两个字节不够，两头都会错：只认 `30 82` 会漏掉小密钥库（只有百来字节的那种用的是短形式
    // `30 65` 或一字节长形式 `30 81`，实测空 PKCS#12 就是 `30 65`）；而只看 `30` 后面随便跟个字节
    // 会把一篇以「0」开头的文本当成密钥库（`0x30` 正好是字符 `0`）。
    return if (derTotalLength(head) == fileSize) SignInputKind.KeyStore else null
}

/**
 * DER 对象头声明的**总长度**（含头部）；读不出来（不是 SEQUENCE、长度字节不完整、长度离谱）时
 * 返回 `null`。
 *
 * 三种长度形式都要认：
 *  - 短形式（`0x00`–`0x7F`）：这一个字节就是内容长度——空密钥库那种百来字节的文件走的是它；
 *  - 一字节长形式（`0x81`）：几乎所有的 `.p12` 都在这一档；
 *  - 二到四字节长形式（`0x82`–`0x84`）：文件超过 64KB 时才有（整个密钥库铺在一个 SEQUENCE 里）。
 *
 * `0x85` 及以上一律不认：那意味着长度字段本身就有五到八个字节，对应 4GB 以上的对象——这种「密钥库」
 * 不是本工具该接的，也没必要为它把判据放宽。
 */
private fun derTotalLength(head: ByteArray): Long? {
    if (head.size < 2 || head[0] != 0x30.toByte()) return null
    val first = head[1].toInt() and 0xFF
    return when {
        first < 0x80 -> (2 + first).toLong()

        first in 0x81..0x84 -> {
            val lengthBytes = first - 0x80
            if (head.size < 2 + lengthBytes) return null
            var content = 0L
            for (index in 0 until lengthBytes) {
                content = (content shl 8) or (head[2 + index].toLong() and 0xFF)
            }
            content + 2 + lengthBytes
        }

        else -> null
    }
}

/** 判文件类型时只看开头这几个字节：够覆盖上面三套魔数，也不至于把大文件拖进内存。 */
private const val MagicHeadBytes = 8

/**
 * 读一个文件的头几个字节并判它是什么；读不了（不存在、是目录、没权限）或认不出时返回 `null`。
 *
 * 走 kotlinx-io 而不是 `java.io`：读文件因此在 commonMain，这套判据能跟着一起被单测。
 */
internal fun documentKindOf(path: String): SignInputKind? = runCatching {
    val file = Path(path)
    val metadata = SystemFileSystem.metadataOrNull(file) ?: return@runCatching null
    if (!metadata.isRegularFile) return@runCatching null
    val source = SystemFileSystem.source(file).buffered()
    val head = try {
        val buffer = ByteArray(MagicHeadBytes)
        var read = 0
        while (read < buffer.size) {
            val count = source.readAtMostTo(buffer, read, buffer.size)
            if (count <= 0) break
            read += count
        }
        buffer.copyOf(read)
    } finally {
        source.close()
    }
    documentKindFromMagic(head, metadata.size)
}.getOrNull()

/** `keytool` 的条目分隔线：两条 43 个星号。 */
private const val KeytoolSeparator = "*******************************************"

/** 「证书指纹」下面那两行的缩进：一个制表符 + 一个空格（`keytool` 唯一的缩进处）。 */
private const val KeytoolFingerprintIndent = "\t "

/**
 * 把密钥库渲染成 `keytool -list -v` 的**原文**，逐行对齐。
 *
 * 为什么是出文本而不是结构化的行：这份东西的形状本身就是它的用途——用户拿它跟 `keytool` 的输出、
 * 跟别人的截图、跟一份 bug 报告对着看。而它里面有两处**只能**是文本：
 *
 *  - 「扩展」那一段是多行的（`BasicConstraints:[` / `CA:true` / `]`），塞进「一行一个标签」的
 *    列表里只能拆成几行空标签；
 *  - 段与段之间靠空行与星号线分隔，那是版式不是数据。
 *
 * 字段名与顺序**逐字**照 `keytool`（`密钥库类型` / `密钥库提供方` / `别名` / `创建日期` /
 * `条目类型` / `证书链长度` / 每张证书那一块的块头 / `所有者` / …）。这些标签是 keytool 的**中文**资源串，
 * 这里硬写中文：整个面板都是中文的，跟着系统语言变会让同一份输出在两种机器上长得不一样。
 *
 * @param storePath 只用来拼 JKS 那一段提醒里的命令行（`keytool -importkeystore -srckeystore …`）。
 */
internal fun keytoolText(
    storeType: String,
    providerName: String,
    entries: List<KeyStoreEntry>,
    storePath: String,
): String = buildString {
    appendLine("密钥库类型: $storeType")
    appendLine("密钥库提供方: $providerName")
    appendLine()
    appendLine("您的密钥库包含 ${entries.size} 个条目")
    appendLine()
    // 别名**按字典序**排：`KeyStore.aliases()` 给的是哈希表的顺序（同一个密钥库，两次读出来未必
    // 一样），而 keytool 打的是排过序的（实测：插入 demo / zzz / aaa，它打 aaa / demo / root / zzz）。
    // 不排的话，这份「原文」每次都可能换个样子，也就没法跟 keytool 对着看了。
    entries.sortedBy { it.alias }.forEach { entry ->
        append(entryKeytoolText(entry))
        // 条目末尾：**两个**空行 + 两条星号线 + 两个空行。数出来是三个空行在星号线之前，但头一个
        // 是「扩展」那一段自己的收尾（见 `CertInfo.extensionsText` 的末尾换行），不在这儿再补一次。
        appendLine()
        appendLine()
        appendLine(KeytoolSeparator)
        appendLine(KeytoolSeparator)
        appendLine()
        appendLine()
    }
    // JKS 这一段在 keytool 里是打到 **stderr** 上的提醒，终端里就接在星号线后面。原样搬过来：
    // 它是「这个文件该不该换」的结论，比任何一条明细都该被看见。
    //
    // 这一句不是客套：**JKS 会拿专有算法加密私钥**——JDK 的 `KeyProtector` 用
    // `PBEWithMD5AndTripleDES`（MD5 派生密钥 + 3DES，64 位分组），实测在 JKS 文件里能直接找到那个
    // OID（`1.3.6.1.4.1.42.2.17.1.1`，就在第一个条目的私钥加密信息里）。这是它该被换掉的真正理由，
    // 比「格式不通用」实在得多。
    if (storeType.equals("JKS", ignoreCase = true)) {
        appendLine()
        appendLine("Warning:")
        appendLine(
            "JKS 密钥库使用专用格式。建议使用 \"keytool -importkeystore -srckeystore $storePath" +
                " -destkeystore $storePath -deststoretype pkcs12\" 迁移到行业标准格式 PKCS12。",
        )
    }
}

private fun entryKeytoolText(entry: KeyStoreEntry): String = buildString {
    appendLine("别名: ${entry.alias}")
    entry.creationDateMillis?.let { appendLine("创建日期: ${keytoolDayText(it)}") }
    appendLine("条目类型: ${entryTypeName(entry.isKeyEntry)}")
    if (entry.chain.isEmpty()) return@buildString
    if (!entry.isKeyEntry) {
        // 只放证书的条目：keytool 在这里空一行，没有「证书链长度」，也没有「证书[i]:」。
        appendLine()
        append(certificateKeytoolText(entry.chain.first()))
        return@buildString
    }
    appendLine("证书链长度: ${entry.chain.size}")
    entry.chain.forEachIndexed { index, certificate ->
        appendLine("证书[${index + 1}]:")
        append(certificateKeytoolText(certificate))
    }
}

// `internal` 而不是 `private`：证书工具（`tools/cert/`）也用它来排「证书信息」那一段——两处
// 显示同一张证书时逐字一致，用户在一个工具里见过的排版在另一个里认得出来。
internal fun certificateKeytoolText(certificate: CertInfo): String = buildString {
    appendLine("所有者: ${certificate.subject}")
    appendLine("发布者: ${certificate.issuer}")
    appendLine("序列号: ${certificate.serial}")
    // 生效与失效**在同一行**上，各自带标签（keytool 就是这么打的：`生效时间: X, 失效时间: Y`）。
    appendLine(
        "生效时间: ${keytoolTimestampText(certificate.notBeforeMillis)}" +
            ", 失效时间: ${keytoolTimestampText(certificate.notAfterMillis)}",
    )
    appendLine("证书指纹:")
    appendLine("${KeytoolFingerprintIndent}SHA1: ${certificate.digests.sha1.toHex(separator = ":", upperCase = true)}")
    appendLine(
        "${KeytoolFingerprintIndent}SHA256: " +
            certificate.digests.sha256.toHex(separator = ":", upperCase = true),
    )
    appendLine("签名算法名称: ${certificate.signatureAlgorithm}")
    appendLine("主体公共密钥算法: ${publicKeyAlgorithmText(certificate)}")
    appendLine("版本: ${certificate.version}")
    if (certificate.extensionsText.isEmpty()) return@buildString
    // 注意 `扩展: ` 那个**尾随空格**：keytool 打的就是 `扩展: `，照抄（看不出但一比就差一个字符）。
    appendLine()
    appendLine("扩展: ")
    appendLine()
    append(certificate.extensionsText)
}

/**
 * 把 APK 的签名者渲染成 `keytool -printcert -jarfile` 的原文。
 *
 * 格式是**实测来的**（拿一个 `jarsigner` 签过的 JAR 跑那条命令）：`签名者 #1:` 之后空一行，接着是
 * `Certificate #1:`——那两个词是**英文**，JDK 没把它本地化，照抄；再往下就是与 `-list -v` **一字
 * 不差**的那套字段，因此和密钥库那一页共用 [certificateKeytoolText]。
 *
 * 这里**不含**验签结论（v1 / v2 / v3 验没验过、几个签名者）：`keytool` 那条命令本来就不报这些。混
 * 进来会拼出一段既不像 keytool、也不像 apksigner 的文本，而这份文本的全部价值就是**能跟命令行的
 * 输出对着看**。结论另有落点——面板顶上那一行。
 */
internal fun printcertText(signers: List<SignerInfo>): String = buildString {
    signers.forEachIndexed { index, signer ->
        // 多个签名者之间空一行：`keytool` 每个签名者一块，块与块之间就是一行空行。
        if (index > 0) appendLine()
        appendLine("签名者 #${index + 1}:")
        appendLine()
        appendLine("Certificate #1:")
        append(certificateKeytoolText(signer.certificate))
    }
    // 末尾这一行空行也是实测的：不补它，跟真输出 diff 就总是差最后一行。
    if (signers.isNotEmpty()) appendLine()
}

/** `2048 位 RSA 密钥`；认不出算法时只报算法名，不编一个「0 位」。 */
private fun publicKeyAlgorithmText(certificate: CertInfo): String =
    if (certificate.publicKeyBits > 0) {
        "${certificate.publicKeyBits} 位 ${certificate.publicKeyAlgorithm} 密钥"
    } else {
        certificate.publicKeyAlgorithm
    }

/** 轮替链上的一行：一种写法（`原始·SHA-256`）配那一串指纹。 */
internal class RotationFingerprint(val label: String, val value: String)

/**
 * 轮替链要摆出来的那几行：**一行一种写法**，标签里写清这张证书在链上的位置。
 *
 * 为什么要标「原始 / 当前」：这两张的指纹长得几乎一样，而各平台注册要的是哪一张并不统一（Google
 * Play 那份认原始那张，Firebase 这类填当前那张）。靠肉眼在结果区里数「第几块证书」去分辨，抄错一个
 * 字节就要重跑一趟——位置必须写在标签里。
 *
 * 两枚摘要都给：`keytool` 只给 SHA1 / SHA256，而各平台要的恰好在这两枚之间来回（老平台要 SHA-1，
 * 新的要 SHA-256）。
 *
 * 标签短到一行放得下（标签列只有 92dp）：`第2张·SHA-256` 已经贴着上限，所以中间那段也不加空格。
 *
 * 纯函数，所以 `jvmTest` 不必造一个真轮替过的包就能把「哪一行是谁」钉住。
 */
internal fun rotationFingerprints(chain: List<CertInfo>): List<RotationFingerprint> = buildList {
    chain.forEachIndexed { index, certificate ->
        val who = when (index) {
            0 -> "原始"
            chain.lastIndex -> "当前"
            else -> "第${index + 1}张"
        }
        add(RotationFingerprint("$who·SHA-1", certificate.digests.sha1.toHex(separator = ":")))
        add(RotationFingerprint("$who·SHA-256", certificate.digests.sha256.toHex(separator = ":")))
    }
}

private const val HexUpper = "0123456789ABCDEF"
private const val HexLower = "0123456789abcdef"

/**
 * 字节 → 十六进制。
 *
 * 每一字节取 `and 0xFF` 再拆高低半字节：直接把 `Byte` 转 `Int` 会在最高位是 1 时得到负数，
 * `toString(16)` 于是给出 `fffffffe` 这种八位的结果（指纹算错就是这么来的）。
 */
internal fun ByteArray.toHex(separator: String = "", upperCase: Boolean = true): String {
    val digits = if (upperCase) HexUpper else HexLower
    return joinToString(separator) { byte ->
        val value = byte.toInt() and 0xFF
        "${digits[value shr 4]}${digits[value and 0x0F]}"
    }
}

/**
 * `keytool` 报的条目类型名。
 *
 * 原样用 JDK 的拼写（`PrivateKeyEntry` 首字母大写、`trustedCertEntry` 小写）：这两个名字是
 * 查文档、搜报错时唯一能对上的关键词，换成自造的说法反而要多翻一次。
 */
internal fun entryTypeName(isKeyEntry: Boolean): String =
    if (isKeyEntry) "PrivateKeyEntry" else "trustedCertEntry"

/**
 * 毫秒 → `Tue Oct 06 22:30:52 CST 2026`，即 `keytool` 的「生效时间 / 失效时间」。
 *
 * 这个格式**只能**由 JVM 给：它就是 `java.util.Date.toString()`，末尾那个 `CST` 是本机时区的
 * **缩写**，`kotlinx-datetime` 拿不到（它只有偏移量）。所以要一个 `expect`——和
 * [readKeyStore] 一样，是「这件事只有平台做得了」，不是随手加的一层。
 */
internal expect fun keytoolTimestampText(millis: Long): String

/**
 * 毫秒 → `2026年10月6日`，即 `keytool` 的「创建日期」。
 *
 * 与 [keytoolTimestampText] 不同，这个是**跟着本机 locale** 走的（英文环境里 keytool 打的是
 * `Oct 6, 2026`）：两个都照抄，才能与用户机器上那份 keytool 输出对得上。
 */
internal expect fun keytoolDayText(millis: Long): String

/**
 * 毫秒 → `2033-10-22`。
 *
 * 默认按本机时区显示而不是 UTC：这里给用户看的是「这张证书哪天到期」，与 `keytool -list` 报的是
 * 同一件事，两者对不上反而要多解释一句。
 *
 * [zone] 做成入参而不是内部读本机时区（与时间戳工具同一条做法）：同一个毫秒在不同时区可能落在
 * 不同的日子上，不给它就不能钉住结果，测试只能断言「大概是某天」。
 */
internal fun formatCertDate(millis: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String {
    val local = Instant.fromEpochMilliseconds(millis).toLocalDateTime(zone)
    // commonMain 没有 `String.format`，补零只能自己来。
    return "${local.year.toString().padStart(4, '0')}-${pad(local.month.ordinal + 1)}-${pad(local.day)}"
}

private fun pad(value: Int): String = value.toString().padStart(2, '0')

/**
 * 口令长度的下限：6 位。
 *
 * 这是**约定**而不是 `KeyStore` 的限制——keytool 与 Android Studio 新建密钥库时都按 6 位卡，而
 * `KeyStore.setKeyEntry` + `store` 实测用 3 位口令照样写得进去（见 `ApkSignLogicTest` 里那条用例）。
 * 所以这一条只能由表单把关：不卡的话，用户能顺手建出一把口令是 `abc` 的**签名密钥**。
 */
internal const val MinPasswordLength = 6

/**
 * 新建时的默认有效期：25 年（Android Studio 那张「新建密钥库」对话框给的就是这个数）。
 *
 * 25 年后的到期日稳稳晚于 Play 那条 2033-10-22 的线，而「25」这个数用户一眼认得。
 */
internal const val DefaultValidityDays = 25 * 365

/**
 * 证书那几项 → 一个 DN 字符串。
 *
 * 顺序是**最具体的在前**（`CN, OU, O, L, ST, C`）：RFC 2253 的写法就是这样，`keytool -dname` 也认
 * 这个顺序。（顺带一提，这也正是上一版那个「主题」单框最容易写反的地方。）
 *
 * 留空的项**整个不写**，而不是写一个空值——`O=` 会在证书里真多出一条空属性。六项全空时返回空串，
 * 由表单把关（见 [keyStoreRequestProblem]）。
 *
 * 值里的 `,` `+` `\` `"` 一律**转义**：这几项是分开填的，值里带个逗号是常事（`Smith, John`），而
 * 不转义的话那个逗号会被当成属性之间的分隔符——DN 就散架了。转义由我们来做，用户不必知道有这回事。
 */
internal fun subjectOf(
    commonName: String,
    organizationalUnit: String,
    organization: String,
    locality: String,
    state: String,
    country: String,
): String = listOf(
    "CN" to commonName,
    "OU" to organizationalUnit,
    "O" to organization,
    "L" to locality,
    "ST" to state,
    "C" to country,
)
    .filter { it.second.isNotBlank() }
    .joinToString(", ") { (type, value) -> "$type=${escapeDnValue(value.trim())}" }

/** DN 值里的这几个字符要转义（RFC 2253 的 `\` 转义）。见 [subjectOf]。 */
private fun escapeDnValue(value: String): String = buildString(value.length) {
    value.forEach { character ->
        if (character in DnEscapedChars) append('\\')
        append(character)
    }
}

private val DnEscapedChars = charArrayOf('\\', ',', '+', '"', '<', '>', ';')

/**
 * Google Play 对上传密钥有效期的硬性下限：**2033-10-22**。
 *
 * 这条不是本工具的发明，是 Play 控制台上传新包时的检查项（到期早于这一天的密钥会被直接拒收）。
 * 用 UTC 的那一刻算：Play 的规则按 UTC 判，跟着本机时区变会让跨时区的机器给出不同结论。
 *
 * 只在**新建**那一页用（作为默认有效期与一句提示）：`keytool -list -v` 的输出里没有这一行，
 * 所以证书那一页不再打它。
 */
internal val PlayKeyDeadlineMillis: Long =
    LocalDateTime(2033, 10, 22, 0, 0, 0).toInstant(TimeZone.UTC).toEpochMilliseconds()

/**
 * 按「天」算出来的到期时刻。
 *
 * 一年按 365 天算，不做闰年精确：keytool 的 `-validity` 本来就是**天**，这里只是把「年」这个更好
 * 填的单位换成天。差一两天不改变任何结论，而按历法精确加 N 年会让人以为它算的是年份。
 */
internal fun validityEndMillis(nowMillis: Long, validityDays: Int): Long =
    nowMillis + validityDays * 86_400_000L

/**
 * 主题写得对不对；没问题时返回 `null`，否则是一句人话。
 *
 * 放到平台那侧是因为**判断它的那把尺子在 JDK 里**（`X500Principal`）——它认的就是 RFC 2253 那一套，
 * 也正是 `keytool -dname` 认的那一套。自己在这一层写正则只会写出假的规则：`CN=Smith\, John` 这种
 * 带转义逗号的合法写法，随手一个 `split(',')` 就会判成错。
 */
internal expect fun subjectProblem(subject: String): String?

/**
 * 表单填得对不对；没问题时返回 `null`。
 *
 * 只做**在这一层就能判定**的那几项：真正建不建得出来由 [createKeyStore] 说了算，它会带回一句
 * 人话。唯一一处借了平台的是主题（见 [subjectProblem]）——那是为了在点「创建」**之前**就把写错的
 * 主题指出来，而不是等它抛出来。
 */
internal fun keyStoreRequestProblem(
    outPath: String,
    alias: String,
    password: String,
    confirmPassword: String,
    subject: String,
): String? = when {
    outPath.isBlank() -> "请先选择保存位置"

    alias.isBlank() -> "别名不能为空"

    password.length < MinPasswordLength ->
        "密码至少 $MinPasswordLength 位（keytool 与 Android Studio 均按此要求）"

    // 打错一个字符的口令是**不可恢复**的（这张表写下去就是最终结果），所以让用户再敲一遍。
    password != confirmPassword -> "两次输入的密码不一致"

    subject.isBlank() -> "证书那几项至少要填一项"

    else -> subjectProblem(subject)
}

/**
 * 轮替这一栏还差什么；齐全时返回 `null`。纯函数，所以 `jvmTest` 不必造密钥库就能把每一条钉住。
 *
 * 话术里说的 `KeyStore` 就是页面上那一行的名字（轮替时它是「这个包现在用的」那一把，另一行叫
 * `新KeyStore`，见 `ApkSignDevTool` 里那个 `newKeyName`）。
 *
 * 判据里唯一不显然的是最后一条：**它必须是这个包现在签名用的那把**。选错了在签名那一刻**不会报
 * 任何错**——apksig 照样能造出一条从这把钥出发的链、签出一个处处合法的包——但那条链的起点不是设备
 * 认得的那张证书，于是升级会被平台拒掉，而且是在包发出去之后才知道。所以这里当面比一次。
 *
 * @param apkSigner 这个包**现在**的签名证书；包还没签名、或没读出来时为 `null`。
 * @param alreadyRotated 这个包**已经轮替过**（链上不止一代）。
 * @param currentEntry 选中的那把 `KeyStore` 条目；没读出来时为 `null`。
 * @param keystoreFailed 那把钥的 KeyStore 读不出来（具体原因在状态栏，这里只说这一件事）。
 */
internal fun rotationProblem(
    apkSigner: CertInfo?,
    alreadyRotated: Boolean,
    keystorePath: String?,
    keystoreFailed: Boolean,
    currentEntry: KeyStoreEntry?,
    password: String,
): String? = when {
    // 排在只比它轻的那些判据前面：包已经轮替过是**做什么都没用**的一条，先说了不必让人白填一遍。
    alreadyRotated -> "这个包已经轮替过一次，本工具只做第一次轮替"

    apkSigner == null -> "轮替要从一个已经签过名的包接过去——这个包现在没有签名"

    keystorePath == null -> "需要选 KeyStore——这个包现在用的那把"

    keystoreFailed -> "KeyStore 无法读取"

    currentEntry == null || !currentEntry.isKeyEntry -> "KeyStore 里没有可用于签名的私钥条目"

    password.isEmpty() -> "需要填写 KeyStore 的密码"

    !sameCertificate(apkSigner, currentEntry.certificate) -> "KeyStore 和这个包现在的签名对不上"

    else -> null
}

/**
 * 两份摘要指的是不是同一张证书。
 *
 * 比摘要而不是比证书本身：走到这一层时两边都只剩 [CertInfo]（读密钥库与验签各转了一次），手里没有
 * `X509Certificate` 可留。SHA-256 认错一张证书的概率可以当零。
 */
internal fun sameCertificate(a: CertInfo?, b: CertInfo?): Boolean =
    a != null && b != null && a.digests.sha256.contentEquals(b.digests.sha256)

/**
 * 新建时保存对话框里预填的文件名：跟着容器走——`PKCS#12` 给 `.p12`，`JKS` 给 `.jks`。
 *
 * 扩展名不影响能不能用（容器类型看魔数，不看名字），但它是「这个文件是什么」唯一一眼能看出来的
 * 那点信息，写错只是给以后添一次误解。
 */
internal fun defaultKeyStoreFileName(format: StoreFormat): String = "release-key.${format.extension}"

/**
 * 另存为时预填的文件名：`app-release.apk` → `app-release-signed.apk`。
 *
 * 保留原扩展名而不是硬写 `.apk`：这是「保存」对话框里的默认值，用户随时能改；把它钉成一个
 * 可能对不上的扩展名，只会在用户已经改了名字之后还留下一条误导。
 *
 * 原文件名没有扩展名（`app`）时按 `.apk` 补——这一页收的就是 APK。
 */
internal fun signedFileNameOf(path: String): String {
    val name = path.substringAfterLast('/').ifBlank { path }
    val dot = name.lastIndexOf('.')
    return if (dot <= 0) "$name-signed.apk" else "${name.substring(0, dot)}-signed${name.substring(dot)}"
}
