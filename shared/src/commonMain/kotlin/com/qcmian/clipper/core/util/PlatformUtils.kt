package com.qcmian.clipper.core.util

import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock

/** 自纪元以来的墙钟毫秒数。 */
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** 一个随机的、不易碰撞的标识符。 */
fun randomId(): String {
    val alphabet = "0123456789abcdef"
    return buildString(32) {
        repeat(32) { append(alphabet[Random.nextInt(alphabet.length)]) }
    }
}

/*
 * base64 的编解码一律交给标准库的 `kotlin.io.encoding.Base64`，不再手写循环。
 *
 * kotlinx-io 那个 `kotlinx.io.bytestring` 版本只是它在 `ByteString` 上的薄封装；本模块也不依赖
 * kotlinx-io，硬套过来还要把 `ByteArray` 再拷成一份 `ByteString`。标准库这一份按 4 个符号一组
 * 查表并位，超大输入不必逐字符 `append` 到 `StringBuilder`，分配也收敛到只剩结果本身。
 */

/** 标准字母表（`A-Za-z0-9+/`），编码带填充。 */
private val Base64Standard: Base64 = Base64.Default

/**
 * URL 安全字母表（`A-Za-z0-9-_`）且**不带填充**。
 *
 * 这套写法本来就是为「放进 URL 或文件名」准备的，那里的 `=` 要转义，去掉更省事。
 */
private val Base64UrlSafe: Base64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/** 标准字母表、填充可有可无：解码时两种写法都能吃下。 */
private val Base64Lenient: Base64 = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)

/**
 * base64 编码。
 *
 * [urlSafe] 为真时改用 URL 安全字母表（`-` / `_`）并去掉填充 `=`。
 */
fun encodeBase64(bytes: ByteArray, urlSafe: Boolean = false): String =
    if (bytes.isEmpty()) "" else (if (urlSafe) Base64UrlSafe else Base64Standard).encode(bytes)

/** 一次 base64 解码的结果：要么拿到字节，要么知道在哪个字符上、为什么失败。 */
sealed interface Base64Decode {
    class Success(val bytes: ByteArray) : Base64Decode

    /**
     * @param index 出问题的字符在**原始输入**里的下标（空白与填充都算在内）。
     * @param message 一句话说明，调用方据此拼出带行列的提示。
     */
    class Failure(val index: Int, val message: String) : Base64Decode
}

/**
 * base64 解码，失败时带出**出错字符的位置**。
 *
 * 比 [decodeBase64] 多两件事，都是为了「让工具能指着屏幕说哪错了」：
 *  - 标准与 URL 安全两份字母表**同时接受**——用户很少记得手里这份是哪种；
 *  - 失败给出下标与原因，而不是一个 `null`。
 *
 * 空白一律忽略：base64 常按 76 列折行，复制时也会带上换行。填充 `=` 可有可无。
 *
 * 成功那一路整段交给标准库，只有失败后才回头在**原始输入**上定位出错字符（见 [locateFailure]），
 * 因此错误提示不拖慢正常解码。
 */
fun decodeBase64Detailed(value: String): Base64Decode =
    decodeLenient(value)?.let { Base64Decode.Success(it) } ?: locateFailure(value)

/** 标准 base64 解码；输入不是合法 base64 时返回 `null`。 */
fun decodeBase64(value: String): ByteArray? = decodeLenient(value)

/**
 * 宽松解码：空白忽略、`+/` 与 `-_` 两种字母表都认、填充可有可无；解不出来时返回 `null`。
 *
 * 输入本来就「干净」（全是标准符号与填充、没有空白）时**直接在原串上解码**，不新建任何字符串；
 * 只有真的带了空白或 URL 安全字符，才按需规整出一份副本（见 [normalizeForDecoding]）。
 */
private fun decodeLenient(value: String): ByteArray? {
    if (value.isEmpty()) return ByteArray(0)
    val prepared = normalizeForDecoding(value) ?: value
    return try {
        Base64Lenient.decode(prepared)
    } catch (_: IllegalArgumentException) {
        // 字母表之外、填充不对、长度凑不出字节……都归到「解不出来」，由上层决定怎么提示。
        null
    }
}

/**
 * 需要时把输入规整成「标准字母表、无空白」的一份；本来就干净时返回 `null`，让调用方直接复用原串。
 *
 * 只映射字母表分岔的两个字符（`-` / `_` → `+` / `/`）并丢掉空白，其余字符原样保留——它们若真
 * 的非法，会由标准库报错，再由 [locateFailure] 定位到原始下标上。
 */
private fun normalizeForDecoding(value: String): String? {
    var dirty = false
    for (char in value) {
        if (char.isWhitespace() || char == '-' || char == '_') {
            dirty = true
            break
        }
    }
    if (!dirty) return null

    val builder = StringBuilder(value.length)
    for (char in value) {
        when (char) {
            '-', '+' -> builder.append('+')
            '_', '/' -> builder.append('/')
            else -> if (!char.isWhitespace()) builder.append(char)
        }
    }
    return builder.toString()
}

/**
 * 解码失败后，在**原始输入**上找出第一个出问题的字符并给出一句人话。
 *
 * 只在失败后跑一次，多扫一遍不心疼；而且它只对照字母表挑错、不重新解码，因此不会再走一遍
 * 手写解码循环。下标一律相对原始输入（空白 / 填充都计入），这样用户看到的列号才对得上自己贴进来
 * 的那一整串。
 */
private fun locateFailure(value: String): Base64Decode {
    var symbols = 0
    var lastSymbolIndex = -1
    var paddingSeen = false

    for (index in value.indices) {
        val char = value[index]
        if (char.isWhitespace()) continue
        if (char == '=') {
            paddingSeen = true
            continue
        }
        if (paddingSeen) return Base64Decode.Failure(index, "填充符 = 之后不该再有字符")
        if (!char.isBase64Symbol()) return Base64Decode.Failure(index, "「$char」不是合法的 Base64 字符")
        symbols++
        lastSymbolIndex = index
    }

    val index = if (lastSymbolIndex < 0) 0 else lastSymbolIndex
    return if (symbols % 4 == 1) {
        Base64Decode.Failure(index, "去掉填充后余下 1 个字符，凑不出一个字节")
    } else {
        // 字母表与长度都过得去，却仍被标准库拒掉：多半是填充位不为 0 之类的细节。
        Base64Decode.Failure(index, "不是合法的 Base64 编码")
    }
}

/** 这个字符是否属于**任一种** base64 字母表（标准 `+ /` 或 URL 安全 `- _`）。 */
private fun Char.isBase64Symbol(): Boolean =
    this in 'A'..'Z' ||
        this in 'a'..'z' ||
        this in '0'..'9' ||
        this == '+' || this == '/' || this == '-' || this == '_'
