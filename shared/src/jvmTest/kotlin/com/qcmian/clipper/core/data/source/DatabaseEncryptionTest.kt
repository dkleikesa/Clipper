package com.qcmian.clipper.core.data.source

import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.sqlite.SQLiteConfig

/**
 * 加密库的**识别与口令校验**：启动路径上「要不要先要口令」完全靠这两个函数决定。
 *
 * 两条都不是看代码能看明白的取舍：
 *
 * - `isDatabaseEncrypted` 只看文件头，因为**开库之前**就要有答案（加密库没口令根本打不开），
 *   所以它不能像别的东西那样存在偏好设置里——那份设置本身就在库里。
 * - `verifyDatabasePassphrase` 里那句 `SELECT ... FROM sqlite_master` 看起来是多余的探针，
 *   实际是**唯一**的判据：`PRAGMA key` 给了错口令也返回成功，真正的失败要到第一次读页才出现。
 */
class DatabaseEncryptionTest {

    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("clipper-encryption").toFile()
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `错误口令必须被识破`() {
        val file = File(directory, "clipper.db")
        createEncryptedDatabase(file, passphrase = "correct")

        assertTrue(verifyDatabasePassphrase(file, "correct"), "对口令应当放行")
        assertFalse(
            verifyDatabasePassphrase(file, "wrong"),
            "错口令必须被识破：`PRAGMA key` 对错口令同样返回成功，只有紧跟的那条读页探针能戳穿它",
        )
        assertFalse(
            verifyDatabasePassphrase(file, ""),
            "空口令绝不能打开加密库——那等于解锁界面直接放行，加密形同虚设",
        )
    }

    @Test
    fun `明文库上给了口令也验不过`() {
        // 调用方只在 `isClipperDatabaseLocked()` 为真时才校验口令，所以明文库验不过并不影响正常启动；
        // 但反过来说，若有人让它对明文库返回 true，解锁界面就会为一句根本不存在的口令放行。
        val file = File(directory, "plain.db")
        createPlaintextDatabase(file)

        assertFalse(verifyDatabasePassphrase(file, "anything"))
    }

    @Test
    fun `文件不存在时不当作加密库，也验不过口令`() {
        val missing = File(directory, "nothing.db")

        assertFalse(isDatabaseEncrypted(missing))
        assertFalse(verifyDatabasePassphrase(missing, "x"))
    }

    @Test
    fun `只有写满文件头的库才按加密判断`() {
        val plaintext = File(directory, "plain.db")
        createPlaintextDatabase(plaintext)
        val encrypted = File(directory, "encrypted.db")
        createEncryptedDatabase(encrypted, passphrase = "correct")
        // 比一个文件头还短：新建但还没写过页的库，Room 建出来的就是这种。
        val stub = File(directory, "stub.db").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        assertFalse(isDatabaseEncrypted(stub), "读不满一个文件头时按明文处理，而不是当成加密库")
        assertFalse(isDatabaseEncrypted(plaintext), "明文库的文件头是固定的 `SQLite format 3`")
        assertTrue(isDatabaseEncrypted(encrypted), "加密库的同一段是随机盐值")
    }

    // -----------------------------------------------------------------------------------
    // 造库：直接用 SQLCipher 驱动，参数与线上 `SqlCipherDriver` 一致
    // -----------------------------------------------------------------------------------

    private fun createEncryptedDatabase(file: File, passphrase: String) {
        val config = SQLiteConfig()
        config.setPragma(SQLiteConfig.Pragma.CIPHER, SQLCIPHER_SCHEME)
        config.setPragma(SQLiteConfig.Pragma.KEY, passphrase)
        createDatabase(file, config.toProperties())
    }

    private fun createPlaintextDatabase(file: File) = createDatabase(file, null)

    private fun createDatabase(file: File, properties: java.util.Properties?) {
        val url = "jdbc:sqlite:${file.absolutePath}"
        val connection = if (properties == null) {
            DriverManager.getConnection(url)
        } else {
            DriverManager.getConnection(url, properties)
        }
        connection.use { it.createStatement().use { statement -> statement.execute("CREATE TABLE t(a)") } }
    }
}
