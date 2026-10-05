package com.qcmian.clipper.devtools.tools.url

import com.qcmian.clipper.core.util.decodeUtf8OrNull

/** 编解码的方向。与 Base64 工具一样**显式**给出，不靠猜。 */
internal enum class UrlMode(val title: String) {
    Encode("编码"),
    Decode("解码"),
}

/**
 * 编码 / 解码用哪一套字符集，决定这个工具是在编「一个参数」还是「一整条 URL」。
 *
 * [Strict] 是保守的**默认**：只留 RFC 3986 的 unreserved，编出来的结果在任何实现里都对得上。
 * 三档各自对着一个明确的出处：
 *  - [Strict] = RFC 3986 §2.3：只留 `A-Za-z0-9-._~`，与 OAuth / Ktor `encodeURLParameter` / Python `quote` 一致；
 *  - [Full] = `encodeURI`：在严格那套之上再留 `! * ' ( )` 与 URL 的结构字符（`; , / ? : @ & = + $ #`），用于整条 URL；
 *  - [Form] = `application/x-www-form-urlencoded`：只留字母数字与 `* - . _`，空格写成 `+`。
 *
 * [title] 是工具栏那三个 tab 上的短名——宽度跟着字走，再长会把整排挤紧。
 * [hint] 是鼠标悬停在 tab 上时弹出的说明。写法固定成「是什么规范 + 保留什么 + 空格怎么写」
 * 三句，后来加档时照着这个格式写，说明之间才不会有人详有人略。
 */
internal enum class UrlRules(val title: String, val hint: String) {
    Strict("严格", "RFC 3986标准整条转义，只保留字母、数字与 - . _ ~"),
    Full("URL", "保留URL骨架，只转义空格、中文这类不该出现的字符"),
    Form("表单", "表单专用转码，保留字母数字与 * - . _ 符号，空格写成 +"),
}

/**
 * 解码失败：[reason] 一句话，以及（可能有的）出错字符在**原始输入**里的下标。
 *
 * 与 `Base64Error` 同一套写法；差别在下标可为空——「解出来不是合法 UTF-8」这类错指不到某一个
 * 字符上，那时就不该硬编一个列号出来。[reason] 单独留一份是因为 `Throwable.message` 是可空的。
 */
internal class UrlError(val reason: String, val index: Int?) : Exception(reason)

/**
 * URL 百分号编码的**纯逻辑**。
 *
 * 标准库里没有百分号编码（`kotlin.io.encoding` 只有 Base64），所以编解码循环自己写。两处都按
 * 「先扫一遍、再一次性填结果」来写：先判断有没有活要干（没有就直接把原串还回去），再按精确长度
 * 开数组填满。这样常见输入（干净的 ASCII、本来就是文本的 URL）零分配，要真编码时也只走两趟。
 */
internal object UrlFormat {

    /**
     * 把 [text] 按 [rules] 做百分号编码；非 ASCII 字符按 **UTF-8** 逐字节转义。
     *
     * 逐字节处理是刻意的：`%E4%B8%AD` 这种一个汉字三个 `%XX` 的写法，正是百分号编码的定义。
     */
    fun encode(text: String, rules: UrlRules): String {
        val safe = safeTable(rules)
        val form = rules == UrlRules.Form

        // 快路径：整串都是「原样保留」的 ASCII 时一个字符都不用动——干净的 ASCII URL 走的正是
        // 这条，于是连 UTF-8 字节都不必取（省掉一整次 encodeToByteArray）。
        var clean = true
        for (char in text) {
            if (char.code >= safe.size || !safe[char.code]) {
                clean = false
                break
            }
        }
        if (clean) return text

        val bytes = text.encodeToByteArray()

        // 第一遍数出多少个字节要写成 `%XX`（占 3 个字符），其余字节各占 1 个字符（原样、或表单的
        // 空格写成 `+`）。据此**一次性**开好结果数组：只有两趟、一次分配，不再「攒进 List →
        // toByteArray → 转十六进制串 → 大写 → 逐两位 append」那几趟中间产物。
        var escaped = 0
        for (byte in bytes) {
            if (keptCode(byte.toInt() and 0xFF, safe, form) == ESCAPED) escaped++
        }

        val out = CharArray(escaped * 3 + (bytes.size - escaped))
        var index = 0
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            val kept = keptCode(value, safe, form)
            if (kept != ESCAPED) {
                out[index++] = kept.toChar()
            } else {
                out[index++] = '%'
                out[index++] = hexChar(value ushr 4)
                out[index++] = hexChar(value and 0x0F)
            }
        }
        return out.concatToString()
    }

    /** 把 [text] 按 [rules] 解码；`%XX` 不合法、或解出来不是 UTF-8 文本时返回失败。 */
    fun decode(text: String, rules: UrlRules): Result<String> {
        val form = rules == UrlRules.Form

        // 快路径：没有 `%`（也没有要当空格的 `+`）时原样返回——省掉整趟「收字节 → 校验 → 解码」。
        if (text.none { it == '%' || (form && it == '+') }) return Result.success(text)

        // 目标字节数事先不知道（一个字最多展开成 4 个字节，而 `%XX` 又是三收一），先按字符数开，
        // 不够再翻倍。用可增长的 ByteArray 而不是 `ArrayList<Byte>`：后者逐字节装箱、末尾还要
        // `toByteArray()` 再拷一遍。
        var buffer = ByteArray(text.length)
        var size = 0
        fun push(byte: Byte) {
            if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2 + 1)
            buffer[size++] = byte
        }

        var index = 0
        while (index < text.length) {
            when {
                text[index] == '%' -> {
                    val high = text.getOrNull(index + 1)?.digitToIntOrNull(16)
                    val low = text.getOrNull(index + 2)?.digitToIntOrNull(16)
                    if (high == null || low == null) {
                        return Result.failure(
                            UrlError("「%」后面要跟两位十六进制数字", firstBadDigit(text, index, high, low))
                        )
                    }
                    push(((high shl 4) or low).toByte())
                    index += 3
                }

                form && text[index] == '+' -> {
                    push(SPACE.toByte())
                    index++
                }

                else -> {
                    // 字面量一次吃一整段：纯 ASCII 逐字符直接推（不分配），含非 ASCII 才整段编码
                    // 一次——后者必须整段编码，否则会把一个代理对拆成两次无效的 UTF-8。
                    val start = index
                    while (index < text.length && text[index] != '%' &&
                        !(form && text[index] == '+')
                    ) {
                        index++
                    }
                    var ascii = true
                    for (i in start until index) {
                        if (text[i].code >= 0x80) {
                            ascii = false
                            break
                        }
                    }
                    if (ascii) {
                        for (i in start until index) push(text[i].code.toByte())
                    } else {
                        for (byte in text.substring(start, index).encodeToByteArray()) push(byte)
                    }
                }
            }
        }

        // `%XX` 是**字节**，一个多字节字符是连续几个 `%XX`，所以攒到最后才整体按 UTF-8 判与解；
        // 逐段解码会把一个字符拆成几段。数组刚好填满时不必再拷一份。
        val raw = if (size == buffer.size) buffer else buffer.copyOf(size)
        val decoded = decodeUtf8OrNull(raw)
            ?: return Result.failure(UrlError("解出来的字节不是合法的 UTF-8 文本", null))
        return Result.success(decoded)
    }
}

/**
 * 这个字节**原样输出**成哪个字符；要写成 `%XX` 时给 [ESCAPED]。
 *
 * 表单与其余两档的差别就落在第一行：空格在这里变成 `+`，其余规则里它不在保留集、会被转义。
 */
private fun keptCode(value: Int, safe: BooleanArray, form: Boolean): Int = when {
    value == SPACE && form -> '+'.code
    value < safe.size && safe[value] -> value
    else -> ESCAPED
}

/**
 * 半字节 → 大写十六进制字符。百分号编码按惯例写大写（`%E4%B8%AD`）。
 *
 * 这里不走 `toHexString()`：那个接口面向**整个 ByteArray**、会产出中间字符串，而这里是在热循环里
 * 往固定数组填单个字符，一个两位分支就是最直接的写法。
 */
private fun hexChar(nibble: Int): Char = if (nibble < 10) '0' + nibble else 'A' + nibble - 10

/**
 * 出错下标指到**第一个不合法的那一位**：`%G1` 该说第 2 列（`G`），而不是 `%` 所在的第 1 列；
 * 后面直接没了（`%` 或 `%2` 结尾）则只能指 `%`。
 */
private fun firstBadDigit(text: String, percent: Int, high: Int?, low: Int?): Int = if (high == null) {
    if (text.getOrNull(percent + 1) == null) percent else percent + 1
} else {
    if (text.getOrNull(percent + 2) == null) percent else percent + 2
}

/** 把解码错误写成带列号的人话；没有可指认的位置时原样返回（见 [UrlError.index]）。 */
internal fun urlErrorMessage(source: String, error: UrlError): String {
    val index = error.index?.coerceIn(0, source.length) ?: return error.reason
    var line = 1
    var column = 1
    for (i in 0 until index) {
        if (source[i] == '\n') {
            line++
            column = 1
        } else {
            column++
        }
    }
    return if (line == 1) "第 $column 列：${error.reason}" else "第 $line 行 第 $column 列：${error.reason}"
}

/**
 * 每档规则「原样保留」的 ASCII 字符表，下标即字节值。
 *
 * 用查表而不是 `char in safeString`：后者是 O(字符数) 的扫描，长输入上会退化成 O(n·m)。
 * 三张表都是启动时建一次的常量。
 */
private fun safeTable(rules: UrlRules): BooleanArray = when (rules) {
    UrlRules.Strict -> StrictSafe
    UrlRules.Full -> FullSafe
    UrlRules.Form -> FormSafe
}

/** 严格 RFC 3986 的保留集：只有 unreserved（字母数字与 `- . _ ~`）。 */
private val StrictSafe =
    asciiTable("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

/** `encodeURI` 的保留集：严格那套 + `! * ' ( )` + URL 的结构字符。 */
private val FullSafe = asciiTable(
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'();,/?:@&=+$#"
)

/** 表单的保留集：字母数字与 `* - . _`，空格另写作 `+`（见 [UrlFormat.encode]）。 */
private val FormSafe =
    asciiTable("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789*-._")

private fun asciiTable(safe: String): BooleanArray = BooleanArray(128) { it.toChar() in safe }

/** 空格的字节值。 */
private const val SPACE = 0x20

/** 需要写成 `%XX` 的标记；保留字符的原样输出值都落在 `0..127`，不会撞上它。 */
private const val ESCAPED = -1
