package com.qcmian.clipper.devtools.tools.sqlite

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.sqlite.SQLiteConfig

/**
 * 引擎的集成测试：真的建一个 SQLite 文件，再用 [openSqliteSession] 打开它。
 *
 * 覆盖三条最容易出错、又最要紧的路径——**只读真的挡住写**、**加密库要口令**、**翻页不越界**。
 * 用的是与运行时同一颗驱动，因此连「加密方案要和建库一致」这种细节也一并守着。
 */
class SqliteEngineTest {

    @Test
    fun listsObjectsAndReadsSchema() {
        val file = createDatabase { connection ->
            connection.execute("CREATE TABLE clips (id INTEGER PRIMARY KEY, body TEXT NOT NULL, ratio REAL)")
            connection.execute("CREATE VIEW recent AS SELECT * FROM clips")
            connection.execute("CREATE INDEX idx_body ON clips (body)")
        }

        openSqliteSession(file.absolutePath, passphrase = null, readOnly = true).useSession { session ->
            val objects = session.objects()
            assertTrue(objects.any { it.name == "clips" && it.type == SqliteObjectType.TABLE })
            assertTrue(objects.any { it.name == "recent" && it.type == SqliteObjectType.VIEW })
            assertTrue(objects.any { it.name == "idx_body" && it.type == SqliteObjectType.INDEX })
            // `sqlite_master` 本身能看到、能点开（它不列在自己里，是引擎单独补上的）。
            assertTrue(objects.any { it.name == "sqlite_master" && it.type == SqliteObjectType.TABLE })
            // 但 SQLite 给主键自动建的 `sqlite_autoindex_*` 不该露面。
            assertTrue(objects.none { it.name.startsWith("sqlite_autoindex_") })

            val schema = assertNotNull(session.schema("clips"))
            assertEquals(listOf("id", "body", "ratio"), schema.columns.map { it.name })
            assertTrue(schema.columns.first { it.name == "id" }.primaryKey)
            assertTrue(schema.columns.first { it.name == "body" }.notNull)
            val ddl = assertNotNull(schema.ddl)
            assertTrue(ddl.contains("CREATE TABLE"))
        }
    }

    @Test
    fun queryReturnsAliasedColumnsAndNulls() {
        val file = createDatabase { connection ->
            connection.execute("CREATE TABLE t (a TEXT, b INTEGER)")
            connection.execute("INSERT INTO t (a, b) VALUES ('x', NULL)")
        }

        openSqliteSession(file.absolutePath, passphrase = null, readOnly = true).useSession { session ->
            val rows = session.query("SELECT a AS label, b FROM t", maxRows = 100)
            assertEquals(listOf("label", "b"), rows.columns)
            assertEquals(listOf("x", null), rows.rows.first())
        }
    }

    @Test
    fun pagingReadsConsecutiveWindows() {
        val file = createDatabase { connection ->
            connection.execute("CREATE TABLE numbers (n INTEGER)")
            connection.createStatement().use { statement ->
                (1..250).forEach { statement.execute("INSERT INTO numbers (n) VALUES ($it)") }
            }
        }

        openSqliteSession(file.absolutePath, passphrase = null, readOnly = true).useSession { session ->
            assertEquals(250, session.count("numbers"))
            assertEquals(SqlitePageSize, session.page("numbers", SqlitePageSize, 0).rows.size)
            // 第二页只剩 50 行——页大小 200。
            assertEquals(50, session.page("numbers", SqlitePageSize, SqlitePageSize).rows.size)
        }
    }

    @Test
    fun pagingPushesOrderAndFilterToSql() {
        val file = createDatabase { connection ->
            connection.execute("CREATE TABLE t (n INTEGER, label TEXT)")
            connection.execute("INSERT INTO t (n, label) VALUES (3, 'c'), (1, 'a'), (2, 'b'), (4, NULL)")
        }

        openSqliteSession(file.absolutePath, passphrase = null, readOnly = true).useSession { session ->
            // 排序是 SQL 的 ORDER BY，不是把这一页拿回来再排。
            assertEquals(
                listOf("1", "2", "3", "4"),
                session.page("t", 10, 0, orderColumn = "n", ascending = true).rows.map { it[0] },
            )
            assertEquals(
                listOf("4", "3", "2", "1"),
                session.page("t", 10, 0, orderColumn = "n", ascending = false).rows.map { it[0] },
            )

            // 等值筛选走 WHERE，计数也跟着筛。
            val filtered = session.page("t", 10, 0, filter = SqliteFilter("label", "b"))
            assertEquals(listOf("2"), filtered.rows.map { it[0] })
            assertEquals(1, session.count("t", SqliteFilter("label", "b")))

            // `value = null` 走 IS NULL（SQL 里没有 `= NULL`）。
            val nulls = session.page("t", 10, 0, filter = SqliteFilter("label", null))
            assertEquals(listOf("4"), nulls.rows.map { it[0] })
            assertEquals(1, session.count("t", SqliteFilter("label", null)))
        }
    }

    @Test
    fun readOnlySessionRejectsWrites() {
        val file = createDatabase { connection -> connection.execute("CREATE TABLE t (a INTEGER)") }

        openSqliteSession(file.absolutePath, passphrase = null, readOnly = true).useSession { session ->
            assertFailsWith<SQLException> { session.query("INSERT INTO t (a) VALUES (1)", maxRows = 10) }
        }

        // 读写模式下同样的语句通过，并报出受影响行数。
        openSqliteSession(file.absolutePath, passphrase = null, readOnly = false).useSession { session ->
            val result = session.query("INSERT INTO t (a) VALUES (1)", maxRows = 10)
            assertEquals(listOf("受影响行数"), result.columns)
            assertEquals("1", result.rows.first().first())
        }
    }

    @Test
    fun encryptedDatabaseRequiresPassphrase() {
        val file = createEncryptedDatabase("secret") { connection ->
            connection.execute("CREATE TABLE clips (id INTEGER PRIMARY KEY)")
        }

        // 没给口令：报「这是加密库」，并把 needsPassphrase 置真，界面据此露出输入框。
        val missing = assertFailsWith<SqliteError> { openSqliteSession(file.absolutePath, null, true) }
        assertTrue(missing.needsPassphrase)

        // 口令错：同样要口令。
        val wrong = assertFailsWith<SqliteError> { openSqliteSession(file.absolutePath, "nope", true) }
        assertTrue(wrong.needsPassphrase)

        // 口令对：打得开，且认得出这是加密库。
        openSqliteSession(file.absolutePath, "secret", true).useSession { session ->
            assertTrue(session.encrypted)
            assertTrue(session.objects().any { it.name == "clips" })
        }
    }

    @Test
    fun plaintextDatabaseIsNotReportedAsEncrypted() {
        val file = createDatabase { connection -> connection.execute("CREATE TABLE t (a INTEGER)") }
        openSqliteSession(file.absolutePath, null, true).useSession { session ->
            assertTrue(!session.encrypted)
        }
    }

    @Test
    fun readsInternalTableSchemaAndData() {
        val file = createDatabase { connection -> connection.execute("CREATE TABLE t (a INTEGER)") }

        openSqliteSession(file.absolutePath, passphrase = null, readOnly = true).useSession { session ->
            // 内部表不在 `sqlite_master` 里，但列信息照常读得到。
            val schema = assertNotNull(session.schema("sqlite_master"))
            assertEquals(
                listOf("type", "name", "tbl_name", "rootpage", "sql"),
                schema.columns.map { it.name },
            )
            // 内部表没有建表语句；数据照常能读——至少能看到刚才建的那张表。
            assertNull(schema.ddl)
            assertTrue(session.page("sqlite_master", 100, 0).rows.any { row -> "t" in row })
        }
    }

    @Test
    fun missingFileIsReported() {
        val file = File.createTempFile("clipper-missing", ".db").apply { delete() }
        assertFailsWith<SqliteError> { openSqliteSession(file.absolutePath, null, true) }
    }

    // ------------------------------------------------------------------ 测试夹具

    /** 建一个明文库，跑 [block] 造数据，把文件还给调用方。 */
    private fun createDatabase(block: (Connection) -> Unit): File {
        val file = File.createTempFile("clipper-sqlite", ".db").apply { delete() }
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use(block)
        file.deleteOnExit()
        return file
    }

    /** 建一个 SQLCipher 加密库，方案与运行时一致（`sqlcipher`）。 */
    private fun createEncryptedDatabase(passphrase: String, block: (Connection) -> Unit): File {
        val file = File.createTempFile("clipper-sqlite-enc", ".db").apply { delete() }
        val config = SQLiteConfig().apply {
            setPragma(SQLiteConfig.Pragma.CIPHER, "sqlcipher")
            setPragma(SQLiteConfig.Pragma.KEY, passphrase)
        }
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}", config.toProperties()).use(block)
        file.deleteOnExit()
        return file
    }

    private fun Connection.execute(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    /** [SqliteSession] 不是 `AutoCloseable`（commonMain 里另有约定），测试里补一层 `use`。 */
    private inline fun <R> SqliteSession.useSession(block: (SqliteSession) -> R): R =
        try {
            block(this)
        } finally {
            close()
        }
}
