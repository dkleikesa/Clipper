package com.qcmian.clipper.core.data.source

import java.io.File
import java.sql.DriverManager
import java.sql.SQLException
import org.sqlite.SQLiteConfig

/** 明文 SQLite 库固定的文件头（16 字节，含结尾的 NUL）。 */
private val PLAINTEXT_HEADER = "SQLite format 3\u0000".encodeToByteArray()

private const val DATABASE_NAME = "clipper.db"

/** 应用数据库文件：`~/.clipper/clipper.db`。 */
internal fun clipperDatabaseFile(): File =
    File(File(System.getProperty("user.home") ?: ".", ".clipper"), DATABASE_NAME)

/**
 * 会话级数据库密钥。
 *
 * **只在内存里，绝不落盘**——因此每次启动都要让用户重新输入一次（见 [unlockClipperDatabase]
 * 与桌面端的解锁窗口）。驱动每次建连接都现读它（见 `SqlCipherDriver`），所以换钥之后新开的
 * 连接自动用新口令，不必重建 Room。
 */
internal object DatabaseKey : SessionKey {
    @Volatile
    private var passphrase: String? = null

    override val encrypted: Boolean get() = passphrase != null

    override fun update(passphrase: String?) {
        this.passphrase = passphrase
    }

    /** 当前口令；未加密时为 `null`。 */
    fun current(): String? = passphrase
}

/**
 * 换钥前后的文件备份。库用 TRUNCATE 日志模式，已提交的数据全在主库文件里，因此一次
 * 文件拷贝就是一份一致的快照——这也是这里敢直接 `copyTo` 的原因（WAL 下还要带上 `-wal`）。
 */
internal object DatabaseFileBackup : EncryptionBackup {
    private val backupFile: File
        get() = File(clipperDatabaseFile().parentFile, "$DATABASE_NAME.bak")

    override fun snapshot(): Boolean {
        val source = clipperDatabaseFile()
        if (!source.exists()) return false
        return runCatching {
            source.copyTo(backupFile, overwrite = true)
            true
        }.getOrDefault(false)
    }

    override fun restore() {
        // 拷贝成功后备份就与主库完全一致，没必要再留一份占着几百 MB。
        runCatching {
            if (backupFile.exists()) {
                backupFile.copyTo(clipperDatabaseFile(), overwrite = true)
                backupFile.delete()
            }
        }
    }

    override fun discard() {
        runCatching { backupFile.delete() }
    }
}

/**
 * 数据库文件是否是加密库。
 *
 * 判据是文件头：标准 SQLite 的前 16 字节固定为 `SQLite format 3\0`，加密库的同一段是随机
 * 盐值。**开库之前**就要知道答案（加密库没有口令根本打不开），因此它不能寄存在偏好设置里
 * ——那份设置本身就在库里。
 */
internal fun isDatabaseEncrypted(file: File): Boolean {
    if (!file.exists()) return false
    val header = ByteArray(PLAINTEXT_HEADER.size)
    val read = file.inputStream().use { it.read(header) }
    // 比一个文件头还短（新建的空库）按明文处理：Room 建库写出来的就是明文。
    if (read < header.size) return false
    return !header.contentEquals(PLAINTEXT_HEADER)
}

/**
 * 校验口令能否打开 [file]。
 *
 * `PRAGMA key` 即使口令错误也返回成功——真正的失败要到第一次读页才出现，因此这里紧跟一条
 * `SELECT count(*) FROM sqlite_master` 作为探针，只有它成功才算口令正确。
 */
internal fun verifyDatabasePassphrase(file: File, passphrase: String): Boolean {
    if (!file.exists()) return false
    val config = SQLiteConfig()
    config.setPragma(SQLiteConfig.Pragma.CIPHER, SQLCIPHER_SCHEME)
    config.setPragma(SQLiteConfig.Pragma.KEY, passphrase)
    return try {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}", config.toProperties())
            .use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT count(*) FROM sqlite_master").use { it.next() }
                }
            }
        true
    } catch (_: SQLException) {
        false
    }
}

// ---------------------------------------------------------------------------------
// 宿主可见的门面：桌面端在组合、建依赖图**之前**用它决定要不要先要口令。
// ---------------------------------------------------------------------------------

/** 数据库是否需要口令才能打开。 */
fun isClipperDatabaseLocked(): Boolean = isDatabaseEncrypted(clipperDatabaseFile())

/**
 * 解锁：校验口令，通过则记入**内存**供本次会话使用。
 *
 * @return 口令正确时为 `true`；调用方据此决定是继续启动还是提示重输。
 */
fun unlockClipperDatabase(passphrase: String): Boolean {
    if (!verifyDatabasePassphrase(clipperDatabaseFile(), passphrase)) return false
    DatabaseKey.update(passphrase)
    return true
}
