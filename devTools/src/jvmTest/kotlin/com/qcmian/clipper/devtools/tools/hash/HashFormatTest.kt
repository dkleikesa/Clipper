package com.qcmian.clipper.devtools.tools.hash

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 摘要逻辑：以各标准的**官方向量**对拍每一种算法。
 *
 * 这些不是「跑出来就这样」的回归值——它们来自 NIST / RFC 的公开测试向量与 `zlib` 的 CRC-32
 * 定义。对拍它们才拦得住「算法接错了」「字节序反了」「十六进制补零漏了」这类错，纯看界面
 * 是看不出来的（一排长得一模一样的随机字母，谁也认不出哪一行不对）。
 */
class HashFormatTest {

    private fun hex(algorithm: HashAlgorithm, text: String): String =
        HashFormat.digest(algorithm, text.encodeToByteArray(), HashEncoding.Hex)

    @Test
    fun `md5 matches the published vectors`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", hex(HashAlgorithm.MD5, ""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", hex(HashAlgorithm.MD5, "abc"))
        assertEquals(
            "9e107d9d372bb6826bd81d3542a419d6",
            hex(HashAlgorithm.MD5, "The quick brown fox jumps over the lazy dog"),
        )
    }

    @Test
    fun `sha1 matches the published vectors`() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", hex(HashAlgorithm.SHA1, ""))
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", hex(HashAlgorithm.SHA1, "abc"))
    }

    @Test
    fun `sha256 matches the published vectors`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hex(HashAlgorithm.SHA256, ""),
        )
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            hex(HashAlgorithm.SHA256, "abc"),
        )
    }

    @Test
    fun `sha384 matches the published vectors`() {
        assertEquals(
            "cb00753f45a35e8bb5a03d699ac65007272c32ab0eded1631a8b605a43ff5bed" +
                "8086072ba1e7cc2358baeca134c825a7",
            hex(HashAlgorithm.SHA384, "abc"),
        )
    }

    @Test
    fun `sha512 matches the published vectors`() {
        assertEquals(
            "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce" +
                "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e",
            hex(HashAlgorithm.SHA512, ""),
        )
        assertEquals(
            "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
            hex(HashAlgorithm.SHA512, "abc"),
        )
    }

    @Test
    fun `sha224 matches the published vectors`() {
        assertEquals(
            "d14a028c2a3a2bc9476102bb288234c415a2b01f828ea62ac5b3e42f",
            hex(HashAlgorithm.SHA224, ""),
        )
        assertEquals(
            "23097d223405d8228642a477bda255b32aadbce4bda0b3f7e36c9da7",
            hex(HashAlgorithm.SHA224, "abc"),
        )
    }

    @Test
    fun `sha3 224 matches the published vectors`() {
        assertEquals(
            "6b4e03423667dbb73b6e15454f0eb1abd4597f9a1b078e3f5b5a6bc7",
            hex(HashAlgorithm.SHA3_224, ""),
        )
        assertEquals(
            "e642824c3f8cf24ad09234ee7d3c766fc9a3a5168d0c94ad73b46fdf",
            hex(HashAlgorithm.SHA3_224, "abc"),
        )
    }

    @Test
    fun `sha3 256 matches the published vectors`() {
        assertEquals(
            "a7ffc6f8bf1ed76651c14756a061d662f580ff4de43b49fa82d80a4b80f8434a",
            hex(HashAlgorithm.SHA3_256, ""),
        )
        assertEquals(
            "3a985da74fe225b2045c172d6bd390bd855f086e3e9d525b46bfe24511431532",
            hex(HashAlgorithm.SHA3_256, "abc"),
        )
    }

    @Test
    fun `sha3 384 matches the published vectors`() {
        assertEquals(
            "0c63a75b845e4f7d01107d852e4c2485c51a50aaaa94fc61995e71bbee983a2a" +
                "c3713831264adb47fb6bd1e058d5f004",
            hex(HashAlgorithm.SHA3_384, ""),
        )
        assertEquals(
            "ec01498288516fc926459f58e2c6ad8df9b473cb0fc08c2596da7cf0e49be4b2" +
                "98d88cea927ac7f539f1edf228376d25",
            hex(HashAlgorithm.SHA3_384, "abc"),
        )
    }

    @Test
    fun `sha3 512 matches the published vectors`() {
        assertEquals(
            "a69f73cca23a9ac5c8b567dc185a756e97c982164fe25859e0d1dcc1475c80a6" +
                "15b2123af1f5f94c11e3e9402c3ac558f500199d95b6d3e301758586281dcd26",
            hex(HashAlgorithm.SHA3_512, ""),
        )
        assertEquals(
            "b751850b1a57168a5693cd924b6b096e08f621827444f70d884f5d0240d2712e" +
                "10e116e9192af3c91a7ec57647e3934057340b4cf408d5a56592f8274eec53f0",
            hex(HashAlgorithm.SHA3_512, "abc"),
        )
    }

    @Test
    fun `crc32 matches the zlib vector and pads to four bytes`() {
        // 标准校验向量；空输入必须是八个 0，而不是空串——补零漏了就会在这里露馅。
        assertEquals("cbf43926", hex(HashAlgorithm.CRC32, "123456789"))
        assertEquals("00000000", hex(HashAlgorithm.CRC32, ""))
        assertEquals(8, hex(HashAlgorithm.CRC32, "123456789").length)
    }

    @Test
    fun `every digest is twice its declared byte length`() {
        // 十六进制是每字节两位；长度对不上，说明某个算法返回了截断 / 补齐的值。
        HashFormat.algorithms.forEach { algorithm ->
            val digest = hex(algorithm, "abc")
            assertEquals(
                algorithm.digestBytes * 2,
                digest.length,
                "${algorithm.displayName} 摘要长度与声明的字节数不符",
            )
        }
    }

    @Test
    fun `digests returns every algorithm in declaration order`() {
        val results = HashFormat.digests("abc".encodeToByteArray(), HashEncoding.Hex)
        assertEquals(HashFormat.algorithms, results.map { it.algorithm })
        // 逐行与单算一致：界面各处复用的是同一份结果，不该出现第二套口径。
        results.forEach { assertEquals(hex(it.algorithm, "abc"), it.value) }
    }

    @Test
    fun `encodings match the published representations`() {
        // 十六进制是小写（stdlib `toHexString()` 的默认形态）。
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            HashFormat.digest(HashAlgorithm.SHA256, ByteArray(0), HashEncoding.Hex),
        )
        // MD5 空串的两种 Base64 写法都是公认值；URL 安全那个去掉了尾部 `=` 填充。
        assertEquals(
            "1B2M2Y8AsgTpgAmY7PhCfg==",
            HashFormat.digest(HashAlgorithm.MD5, ByteArray(0), HashEncoding.Base64),
        )
        assertEquals(
            "1B2M2Y8AsgTpgAmY7PhCfg",
            HashFormat.digest(HashAlgorithm.MD5, ByteArray(0), HashEncoding.Base64Url),
        )
    }

    @Test
    fun `the two base64 alphabets diverge on the last two symbols`() {
        // 0xFB 0xFF 用满字母表最后两位，正是标准与 URL 安全分岔的地方：`+ /` 对 `- _`。
        val high = byteArrayOf(0xFB.toByte(), 0xFF.toByte())
        assertEquals("+/8=", HashFormat.encode(high, HashEncoding.Base64))
        assertEquals("-_8", HashFormat.encode(high, HashEncoding.Base64Url))
    }

    @Test
    fun `a message crossing a block boundary is padded correctly`() {
        // FIPS 180-4 / 202、RFC 1321 的多块向量：56 字节，正好把填充推到下一个块。
        // 单一「abc」盖不住填充逻辑，这个长度才拦得住分块 / 长度位写错。
        val message = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"
        assertEquals("8215ef0796a20bcaaae116d3876c664a", hex(HashAlgorithm.MD5, message))
        assertEquals("84983e441c3bd26ebaae4aa1f95129e5e54670f1", hex(HashAlgorithm.SHA1, message))
        assertEquals(
            "75388b16512776cc5dba5da1fd890150b0c6455cb4f58b1952522525",
            hex(HashAlgorithm.SHA224, message),
        )
        assertEquals(
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            hex(HashAlgorithm.SHA256, message),
        )
        assertEquals(
            "41c0dba2a9d6240849100376a8235e2c82e1b9998a999e21db32dd97496d3376",
            hex(HashAlgorithm.SHA3_256, message),
        )
    }
}
