package com.qcmian.clipper.devtools.tools.hash

import kotlin.io.encoding.Base64
import kotlin.text.toHexString
import org.kotlincrypto.hash.md.MD5
import org.kotlincrypto.hash.sha1.SHA1
import org.kotlincrypto.hash.sha2.SHA224
import org.kotlincrypto.hash.sha2.SHA256
import org.kotlincrypto.hash.sha2.SHA384
import org.kotlincrypto.hash.sha2.SHA512
import org.kotlincrypto.hash.sha3.SHA3_224
import org.kotlincrypto.hash.sha3.SHA3_256
import org.kotlincrypto.hash.sha3.SHA3_384
import org.kotlincrypto.hash.sha3.SHA3_512

/** 一次摘要的结果：算法，以及它按当前编码方式写出来的串。 */
internal data class HashResult(val algorithm: HashAlgorithm, val value: String)

/**
 * 摘要的**纯逻辑**：算法调用与结果编码都在这里，界面只负责摆。
 *
 * 密码学算法（MD5 / SHA-1 / SHA-2 / SHA-3）交给 KotlinCrypto：这类原语自己写风险太大，而它是
 * KMP 实现，commonMain 直接用。CRC32 只是个查表校验和、且不在该库里，就地实现——二十来行、
 * 有标准向量可对拍，不值得为它再引一个依赖。
 *
 * 入参一律是**字节**：文本要 hash，先由调用方按 UTF-8 变成字节；文件则原样取字节，两类输入
 * 因此走同一条路（与 Base64 工具「输入输出都落在字节这一层」的取舍一致）。算出来的字节再由
 * [HashEncoding] 决定写成哪种字符串。
 */
internal object HashFormat {
    /** 界面与计算共用的算法次序：MD5 → … → SHA-512 → SHA3-224 → … → SHA3-512，CRC32 收尾。 */
    val algorithms: List<HashAlgorithm> = HashAlgorithm.entries

    /** 对 [bytes] 一次算出**全部**算法并按 [encoding] 编码，顺序与 [algorithms] 一致。 */
    fun digests(bytes: ByteArray, encoding: HashEncoding): List<HashResult> =
        algorithms.map { HashResult(it, digest(it, bytes, encoding)) }

    /** 单算一个算法并按 [encoding] 编码。 */
    fun digest(algorithm: HashAlgorithm, bytes: ByteArray, encoding: HashEncoding): String =
        encode(digestBytes(algorithm, bytes), encoding)

    /**
     * 拿一段**外部摘要**与本次算出的全部 [results] 对拍，返回第一条对上的；一条都对不上、或输入
     * 为空时给 `null`。
     *
     * 只比当前 [encoding] 写出来的那一列：结果列表里摆的是什么，就对什么——换一档编码，对拍的目标
     * 也跟着换。
     *
     * 十六进制**不分大小写**：`sha256sum` 印小写，但不少工具（GitHub 的校验值、Windows 的
     * `certutil`）复制出来是大写，而「复制粘贴来对拍」正是这个功能的主用法，不该为此判失败。
     * Base64 两档仍然**区分大小写**——它们的字母表里大小写是两个不同的符号，放宽了会平白对上一条
     * 并不相等的串。
     */
    fun match(input: String, results: List<HashResult>, encoding: HashEncoding): HashResult? {
        val needle = input.trim()
        if (needle.isEmpty()) return null
        val ignoreCase = encoding == HashEncoding.Hex
        return results.firstOrNull { result ->
            if (ignoreCase) result.value.equals(needle, ignoreCase = true) else result.value == needle
        }
    }

    /**
     * 摘要字节 → 显示串。
     *
     *  - [HashEncoding.Hex]：小写十六进制；
     *  - [HashEncoding.Base64]：RFC 4648 §4，带 `=` 填充；
     *  - [HashEncoding.Base64Url]：RFC 4648 §5，`-_` 字母表且**去掉填充**——与 Base64 工具的
     *    `urlSafe = true` 同一形态（JWT / `ssh-keygen` 指纹那一套）。
     */
    fun encode(digest: ByteArray, encoding: HashEncoding): String = when (encoding) {
        // 三种编码都用现成的：十六进制是 stdlib 的 `toHexString()`（小写），两种 Base64 是
        // stdlib 的 `Base64`——不自己写编码，只决定用哪一档。
        HashEncoding.Hex -> digest.toHexString()
        HashEncoding.Base64 -> Base64.Default.encode(digest)
        HashEncoding.Base64Url -> Base64UrlNoPadding.encode(digest)
    }

    private fun digestBytes(algorithm: HashAlgorithm, bytes: ByteArray): ByteArray = when (algorithm) {
        HashAlgorithm.MD5 -> MD5().digest(bytes)
        HashAlgorithm.SHA1 -> SHA1().digest(bytes)
        HashAlgorithm.SHA224 -> SHA224().digest(bytes)
        HashAlgorithm.SHA256 -> SHA256().digest(bytes)
        HashAlgorithm.SHA384 -> SHA384().digest(bytes)
        HashAlgorithm.SHA512 -> SHA512().digest(bytes)
        HashAlgorithm.SHA3_224 -> SHA3_224().digest(bytes)
        HashAlgorithm.SHA3_256 -> SHA3_256().digest(bytes)
        HashAlgorithm.SHA3_384 -> SHA3_384().digest(bytes)
        HashAlgorithm.SHA3_512 -> SHA3_512().digest(bytes)
        HashAlgorithm.CRC32 -> crc32(bytes)
    }
}

/**
 * URL 安全的 Base64，**去掉填充**。
 *
 * stdlib 预定义的那几个实例（`Default` / `UrlSafe`）都带 `=` 填充，这里显式改成无填充——JWT、
 * `ssh-keygen` 指纹里的 Base64URL 都是不带 `=` 的，与 Base64 工具 `urlSafe = true` 也一致。
 */
private val Base64UrlNoPadding = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/**
 * CRC-32（IEEE 802.3，反射多项式 `0xEDB88320`），返回 **4 字节大端**，与 `gzip` / `zlib` 的
 * `crc32` 一致：标准向量 `crc32("123456789") == 0xCBF43926`。
 *
 * 初值与终值都取全 1（异或 `0xFFFFFFFF`），这是该变体的定义；写成 `-1` 与 `inv()` 是同一件事，
 * 只是省去在 `Int` / `UInt` 之间反复转换。
 */
private fun crc32(bytes: ByteArray): ByteArray {
    var crc = -1 // 0xFFFFFFFF
    for (b in bytes) {
        crc = (crc ushr 8) xor Crc32Table[(crc xor b.toInt()) and 0xFF]
    }
    val value = crc.inv()
    return byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )
}

/** 反射 CRC-32 的查表：每个字节值走 8 轮后的余数。 */
private val Crc32Table = IntArray(256) { index ->
    var value = index
    repeat(8) {
        value = if (value and 1 != 0) 0xEDB88320.toInt() xor (value ushr 1) else value ushr 1
    }
    value
}
