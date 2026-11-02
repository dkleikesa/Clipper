package com.qcmian.clipper.devtools.detect

import com.qcmian.clipper.devtools.api.DataTypes
import com.qcmian.clipper.devtools.tools.base64.Base64Format
import com.qcmian.clipper.devtools.tools.cert.CertFormat
import com.qcmian.clipper.devtools.tools.json.JsonFormat
import com.qcmian.clipper.devtools.tools.math.MathFormat
import com.qcmian.clipper.devtools.tools.sqlite.looksLikeSqlitePath
import com.qcmian.clipper.devtools.tools.timestamp.TimestampConvert
import com.qcmian.clipper.devtools.tools.xml.isWellFormedXml

/**
 * 一次数据类型探测：判断一段文本属于哪种格式。
 *
 * 与工具解耦——探测器只说「这是什么」，工具只说「我能吃什么」，主面板拿 [typeName] 把两边接起来
 * （见 `DevToolsRegistry.rankedTools`）。因此新增一种格式可以只加探测器、也可以只加工具。
 */
interface DataTypeDetector {
    /** 它识别的类型名（取值见 [DataTypes]）。 */
    val typeName: String

    /**
     * 具体程度。越大越具体，[detectTypes] 据此降序排列：`json` 排在 `text` 之前。
     *
     * 兜底类型（`text`）取 0，任何格式探测器都应当大于它。
     */
    val specificity: Int get() = 0

    /** [text] 是否属于本类型。实现必须**不抛异常**——抛出去会毁掉整次探测（面板就不推工具了）。 */
    fun matches(text: String): Boolean
}

/**
 * 探测 [text] 命中的所有类型，**最具体的在前**；最后通常跟着兜底的 [DataTypes.TEXT]。
 *
 * 同分时保持探测器声明的顺序（`sortedByDescending` 是稳定排序），结果因此可预期。
 */
fun Iterable<DataTypeDetector>.detectTypes(text: String): List<String> =
    rankDetectedTypes(filter { it.matches(text) }.map { it.specificity to it.typeName })

/**
 * 把一次探测的命中（类型名 + 具体程度）排成「最具体的在前」，同一个类型只留第一次。
 *
 * 文本探测器与 [FileTypeDetector] 的命中都走这里，排序规则因此只写一遍。
 */
internal fun rankDetectedTypes(hits: List<Pair<Int, String>>): List<String> =
    hits.sortedByDescending { it.first }.map { it.second }.distinct()

/**
 * 只按**文件路径**认的探测器。
 *
 * 与 [DataTypeDetector] 分工：那个看内容，这个看扩展名。APK、密钥库这类文件是二进制，读不出文本
 * （`devToolText` 对它们只会给出路径），扩展名是唯一还站得住的判据。做成独立接口而不是给
 * [DataTypeDetector] 加一个可空参数，是因为两者的输入根本不同——把路径喂给 JSON 解析器没有意义。
 */
interface FileTypeDetector {
    /** 它识别的类型名（取值见 [DataTypes]）。 */
    val typeName: String

    /** 具体程度，语义与 [DataTypeDetector.specificity] 相同。 */
    val specificity: Int

    /** [path] 是否属于本类型。与文本探测器一样，实现必须**不抛异常**。 */
    fun matches(path: String): Boolean
}

internal object JsonDataTypeDetector : DataTypeDetector {
    override val typeName: String = DataTypes.JSON
    override val specificity: Int = 60

    override fun matches(text: String): Boolean {
        val trimmed = text.trim()
        val head = trimmed.firstOrNull() ?: return false
        if (head != '{' && head != '[') return false
        return JsonFormat.isValid(trimmed)
    }
}

internal object XmlDataTypeDetector : DataTypeDetector {
    override val typeName: String = DataTypes.XML
    override val specificity: Int = 50

    override fun matches(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.firstOrNull() != '<') return false
        return isWellFormedXml(trimmed)
    }
}

internal object UrlDataTypeDetector : DataTypeDetector {
    override val typeName: String = DataTypes.URL
    override val specificity: Int = 40

    private val pattern = Regex("""^[a-zA-Z][a-zA-Z0-9+.\-]*://\S+$""")

    override fun matches(text: String): Boolean = pattern.matches(text.trim())
}

/**
 * Unix 时间戳：一串长度像时间戳的纯数字。
 *
 * 判据由 [TimestampConvert.isEpochNumber] 提供——探测与工具必须用同一把尺，否则会出现「按时间戳
 * 匹配了工具、工具却不认这串数字」的错位（与 JSON 探测器复用 `JsonFormat.isValid` 同一个道理）。
 */
internal object TimestampDataTypeDetector : DataTypeDetector {
    override val typeName: String = DataTypes.TIMESTAMP
    override val specificity: Int = 45

    override fun matches(text: String): Boolean = TimestampConvert.isEpochNumber(text)
}

/**
 * 一段 Base64。
 *
 * 判据刻意**收得比较紧**：Base64 的字母表全是普通字母数字，放宽一点点，一长串英文单词或者一个
 * camelCase 标识符就会被认成 Base64（它们同样「长度够、字符合法、还解得开」）。四道关卡：
 *
 *  1. 长度 ≥ [MIN_LENGTH]——`test`、`abcd` 也是合法 Base64，但那显然不是「一段 Base64」；
 *  2. 不含空格 / 制表符——Base64 字母表里没有空格，折行只会用换行；带空格的多半是普通文本；
 *  3. 至少有一个数字或 `+ / = - _`——专门用来挡「一长串纯字母」（见上）；
 *  4. 真的解得开（含 URL 安全字母表与缺省填充）。
 *
 * 代价是极少数「恰好只有字母」的真实 Base64 不会被自动认出（长度 16 时约 3.6%，越长越低）。
 * 那只是不自动选中，手动点一下侧边栏照旧可用。
 */
internal object Base64DataTypeDetector : DataTypeDetector {
    override val typeName: String = DataTypes.BASE64
    override val specificity: Int = 55

    /** 短于这个长度就不认。 */
    private const val MIN_LENGTH = 16

    override fun matches(text: String): Boolean {
        val payload = Base64Format.dataUriPayload(text).text.trim()
        if (payload.length < MIN_LENGTH) return false
        if (payload.any { it == ' ' || it == '\t' }) return false
        if (payload.none { it.isDigit() || it in "+/=_-" }) return false
        return Base64Format.decode(payload).isSuccess
    }
}

/**
 * 一份证书类文件（证书 / 证书请求 / 吊销列表 / 密钥）。
 *
 * 判据交给 `CertFormat.looksLikeCertificate`，与证书工具（`tools/cert/`）共用同一把尺子：PEM 头
 * 是明写的（具体是哪一类由工具的解析器认），Base64 那一路则要解出 DER 的 `SEQUENCE + 长形式长度`
 * （`30 8x`）才认——不这么收，「一段普通的长 Base64」也会被当成证书（与 [Base64DataTypeDetector]
 * 收得紧是同一个理由）。
 *
 * 具体程度排在 [Base64DataTypeDetector] 之上：PEM 证书的正文本身也是一段合法 Base64，但「这是证书」
 * 比「这是 Base64」具体，推荐位该给证书工具。
 */
internal object CertDataTypeDetector : DataTypeDetector {
    override val typeName: String = DataTypes.CERT
    override val specificity: Int = 65

    override fun matches(text: String): Boolean = CertFormat.looksLikeCertificate(text)
}

/**
 * 数学表达式。
 *
 * 四道关卡，收得比 Base64 还紧——「打开任意一段文字就跳进计算器」是最讨厌的那种误判：
 *
 *  1. 单行、长度不超过 [MAX_LENGTH]：解析器是递归下降，长文本纯属白算；
 *  2. 至少有一个数字；
 *  3. 至少有一个**算式记号**（[SYMBOLS]、非开头的 `-`，或字母——函数 / 常量名）；
 *  4. 不是日期 / 版本号那类「长得像算式」的东西（`2024-01-01` 也算得出一个数），且真能算出结果。
 *
 * 第 4 条用的是工具自己的 [MathFormat.evaluate]，因此不会出现「按算式推荐了计算器、计算器却说语法
 * 错」的错位（与 JSON / 时间戳探测器同一条规矩）。
 */
internal object MathDataTypeDetector : DataTypeDetector {
    override val typeName: String = DataTypes.MATH
    override val specificity: Int = 35

    /** 再长就不像手写算式了。 */
    private const val MAX_LENGTH = 200

    /** 参与「像算式」判断的符号；`-` 单独判，开头的负号不算两个操作数之间的运算。 */
    private const val SYMBOLS = "+*/^%()"

    /** 日期、日期时间，以及用斜杠 / 点写的日期。 */
    private val dateOrVersionPattern =
        Regex("""^\d{1,4}[-/.]\d{1,2}[-/.]\d{1,4}([ T]\d{1,2}:\d{2}(:\d{2})?)?$""")

    override fun matches(text: String): Boolean {
        val expression = text.trim()
        if (expression.isEmpty() || expression.length > MAX_LENGTH) return false
        if (expression.none { it.isDigit() }) return false
        val looksArithmetic = expression.any { it in SYMBOLS || it.isLetter() } ||
            expression.drop(1).contains('-')
        if (!looksArithmetic) return false
        if (dateOrVersionPattern.matches(expression)) return false
        return MathFormat.evaluate(expression).isSuccess
    }
}

/** APK：只看扩展名。文件里是什么，交给签名工具自己读（ZIP 头）。 */
internal object ApkFileTypeDetector : FileTypeDetector {
    override val typeName: String = DataTypes.APK
    override val specificity: Int = 75

    override fun matches(path: String): Boolean = path.endsWithAnyExtension(APK_EXTENSIONS)
}

/**
 * 密钥库：扩展名五花八门，这里只认常见的几种。容器到底是 PKCS#12 还是 JKS 由签名工具读文件头判
 * （`documentKindOf`），因此 `.p12` 这类两可的也一并归这里——认错了它会自己说读不出来。
 */
internal object KeyStoreFileTypeDetector : FileTypeDetector {
    override val typeName: String = DataTypes.KEYSTORE
    override val specificity: Int = 75

    override fun matches(path: String): Boolean = path.endsWithAnyExtension(KEYSTORE_EXTENSIONS)
}

/**
 * SQLite 数据库：判据交给 `looksLikeSqlitePath`，与工具「打开时该不该自动载入」共用同一把尺子
 * ——否则会出现「按扩展名推荐了工具、工具却不认这个文件」的错位（与 JSON / 时间戳同一条规矩）。
 */
internal object SqliteFileTypeDetector : FileTypeDetector {
    override val typeName: String = DataTypes.SQLITE
    override val specificity: Int = 75

    override fun matches(path: String): Boolean = path.looksLikeSqlitePath()
}

/**
 * 证书类**文件**：只有二进制的那几种才需要它——PEM 是可读文本，[CertDataTypeDetector] 按内容就认
 * 得出来。`.p12` / `.pfx` 留给 [KeyStoreFileTypeDetector]：开发机上这两样多半是签名用的密钥库，
 * 交给签名工具比交给证书查看更贴近实际用法。
 */
internal object CertFileTypeDetector : FileTypeDetector {
    override val typeName: String = DataTypes.CERT
    override val specificity: Int = 65

    override fun matches(path: String): Boolean = path.endsWithAnyExtension(CERT_EXTENSIONS)
}

private val APK_EXTENSIONS = setOf("apk")

private val KEYSTORE_EXTENSIONS = setOf("jks", "keystore", "ks", "bks", "p12", "pfx")

private val CERT_EXTENSIONS = setOf("der", "cer", "crt", "p7b", "p7c")

/**
 * 路径的文件名是否以 [extensions] 里的某个扩展名结尾（大小写不敏感）。
 *
 * 只看最后一段名字；以点开头的名字（`.apk`）不算——那不是扩展名，是个隐藏文件名。
 */
private fun String.endsWithAnyExtension(extensions: Set<String>): Boolean {
    val name = trim().substringAfterLast('/').substringAfterLast('\\')
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.lastIndex) return false
    return name.substring(dot + 1).lowercase() in extensions
}

/** 兜底：任何有内容的文本。始终排在最后（[specificity] 为 0）。 */
internal object TextDataTypeDetector : DataTypeDetector {
    override val typeName: String = DataTypes.TEXT

    override fun matches(text: String): Boolean = text.isNotBlank()
}

/**
 * 内置探测器集合。顺序不参与排序（排序只看 [DataTypeDetector.specificity]），
 * 但同分时按这里的声明顺序稳定排列。
 */
val BuiltInDataTypeDetectors: List<DataTypeDetector> = listOf(
    CertDataTypeDetector,
    JsonDataTypeDetector,
    XmlDataTypeDetector,
    TimestampDataTypeDetector,
    Base64DataTypeDetector,
    UrlDataTypeDetector,
    MathDataTypeDetector,
    TextDataTypeDetector,
)

/**
 * 内置的文件探测器集合。与 [BuiltInDataTypeDetectors] 同一份约定：顺序不参与排序。
 *
 * 只收「二进制、且扩展名足够说明问题」的几档（APK / 密钥库 / DER 证书 / SQLite 库）。图片没有
 * 放进来：剪贴板会给图片表示，不必看路径。
 */
val BuiltInFileTypeDetectors: List<FileTypeDetector> = listOf(
    ApkFileTypeDetector,
    KeyStoreFileTypeDetector,
    CertFileTypeDetector,
    SqliteFileTypeDetector,
)
