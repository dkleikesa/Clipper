package com.qcmian.clipper.core.data.source

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteException
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [SqlCipherDriver] 的语句与事务记账。
 *
 * 这一层是 JDBC 与 Room 之间的语义适配器，出错方式都很隐蔽：**一条语句坏掉、或一次事务没
 * 结束，整库就再也写不进去**。所以这里只测那两条底线：
 *
 * - [SQLiteConnection.inTransaction] 必须跟着 `BEGIN` / `SAVEPOINT` / `RELEASE` / `END` /
 *   `ROLLBACK` 走。它是
 *   Room 判断「要不要在回收连接时补一次回滚」的**唯一**依据（`PooledConnectionImpl.markRecycled`），
 *   一旦说谎，剩下的残留事务就再也没人收。
 * - 失败的语句必须能重建。JDBC 在语句执行失败时会顺手关掉它的指针，之后连 `clearParameters`
 *   都抛 `statement is not executing`；Room 的语句缓存还拿着它，所以这条语句得能自己活过来。
 *
 * 用内存库：这里只关心语句状态机，不需要落盘。库名固定为 `:memory:` 的写法在 sqlite-jdbc 上就是
 * 当前连接私有的内存库——一个用例一个连接，互不干扰。
 *
 * 上层（Room / 数据源）看到的行为见 [com.qcmian.clipper.core.data.local.RoomClipStorageDataSourceTest]
 * 里那条「外部锁挡住写入之后写路径要能恢复」的回归。
 */
class SqlCipherDriverTest {

    @Test
    fun `inTransaction 跟随事务语句的层级`() {
        driver.open(MEMORY_DATABASE).use { connection ->
            assertFalse(connection.inTransaction(), "新连接不该在事务里")

            connection.execute("BEGIN IMMEDIATE TRANSACTION")
            assertTrue(connection.inTransaction())
            connection.execute("CREATE TABLE t(a)")
            connection.execute("INSERT INTO t(a) VALUES (1)")

            // 嵌套：保存点只加一层、释放只减一层，事务本身仍然开着。
            connection.execute("SAVEPOINT '1'")
            assertTrue(connection.inTransaction(), "保存点属于事务内部，不该被当成事务结束")
            connection.execute("RELEASE SAVEPOINT '1'")
            assertTrue(connection.inTransaction(), "释放保存点之后外层事务还在")

            connection.execute("END TRANSACTION")
            assertFalse(connection.inTransaction(), "提交之后必须回到「不在事务里」")

            // 回滚路径同样要归零，且数据真的没留下。
            connection.execute("BEGIN IMMEDIATE TRANSACTION")
            connection.execute("INSERT INTO t(a) VALUES (2)")
            assertTrue(connection.inTransaction())
            connection.execute("ROLLBACK TRANSACTION")
            assertFalse(connection.inTransaction())

            assertEquals(1L, connection.scalar("SELECT COUNT(*) FROM t"), "回滚过的那一行不该在库里")
        }
    }

    /**
     * 回归：连接上挂着一条**没结束的事务**时再开事务。
     *
     * SQLite 对这种情况的原话就是 `cannot start a transaction within a transaction`——线上正是
     * 以这条错误从 `saveSettings` 崩到进程级的未捕获处理器。它出现就说明连接的真实状态与驱动的
     * 记账已经不一致，而连接会一直卡在事务里：之后每次开事务都注定失败，整个写路径瘫掉。
     *
     * 这里用「同一条语句上再执行一次 `BEGIN`」来制造这个局面（Room 在事务里不会这么干，但错误
     * 与状态完全一样）。驱动应当把残留事务回滚掉再重试，而不是把它留给下一条语句。
     */
    @Test
    fun `BEGIN 撞上残留事务时先回滚再重试`() {
        driver.open(MEMORY_DATABASE).use { connection ->
            // 建表必须在事务之外：残留事务是要被回滚的，建表语句搭在它上面会跟着一起消失。
            connection.execute("CREATE TABLE t(a)")
            val begin = connection.prepare("BEGIN IMMEDIATE TRANSACTION")
            try {
                begin.step()
                assertTrue(connection.inTransaction())
                connection.execute("INSERT INTO t(a) VALUES (7)")

                // 复位之后再开一次事务：这一步在修复前直接抛
                // `cannot start a transaction within a transaction`。
                begin.reset()
                begin.step()

                assertTrue(connection.inTransaction(), "自愈之后事务必须是开着的，而不是把连接丢在坏状态里")
                assertEquals(
                    0L,
                    connection.scalar("SELECT COUNT(*) FROM t"),
                    "残留事务里的改动必须被回滚掉——自愈不能顺手把半个事务提交了",
                )

                connection.execute("ROLLBACK TRANSACTION")
                assertFalse(connection.inTransaction())
            } finally {
                begin.close()
            }
        }
    }

    /**
     * 回归：一次失败的执行不能把语句永久废掉。
     *
     * 驱动在失败时已经关掉了语句的指针（`DB.execute` 的兜底分支），而我们之前把「已执行」标在了
     * 执行之前，于是这条语句在 Room 的缓存里既声称执行过、又什么都干不了：要么静默跳过，
     * 要么抛出一句与真正原因无关的 `statement is not executing`（它在线上是以 suppressed 的
     * 形式挂在真正的错误下面的，正好把原因盖住）。
     *
     * 这里用「没有事务时回滚」造一次必然失败——它就长这样。
     */
    @Test
    fun `失败的语句会被重建而不是永久废掉`() {
        driver.open(MEMORY_DATABASE).use { connection ->
            val rollback = connection.prepare("ROLLBACK TRANSACTION")
            try {
                assertFailsWith<SQLiteException> { rollback.step() }
                // Room 复用缓存语句的顺序是 reset → clearBindings，两步都不能抛。
                rollback.reset()
                rollback.clearBindings()

                val second = assertFailsWith<SQLiteException> { rollback.step() }
                assertFalse(
                    second.message.orEmpty().contains("statement is not executing"),
                    "语句必须被重建，而不是带着被驱动关掉的指针继续用：${second.message}",
                )

                // 重建出来的语句得真的能用：开一个事务，再让它回滚。
                connection.execute("BEGIN IMMEDIATE TRANSACTION")
                rollback.reset()
                rollback.step()
                assertFalse(connection.inTransaction(), "重建之后的语句必须真的把事务回滚掉")
            } finally {
                rollback.close()
            }
        }
    }

    /**
     * 回归：**提交失败**之后连接必须还能开事务。
     *
     * 这是那条线上崩溃最可能的来路。SQLite 在提交失败时不一定会自己回滚：`COMMIT` 撞上
     * `SQLITE_BUSY` 就是典型——事务原样留着等重试。驱动当时只把事务层级归零，于是连接上留下
     * 「层级 0 + 事务开着」这种无人认领的状态：Room 不会开新事务（它只信自己的栈），也不会回收
     * 这条连接（`markRecycled` 问的是驱动的 [SQLiteConnection.inTransaction]）。写路径就此瘫掉，
     * 表现为 `cannot start a transaction within a transaction`。
     *
     * 提交失败是真造出来的：另开一条连接挂着一个**没读完的** `SELECT`，回滚日志模式下它持有
     * SHARED 锁，而提交需要 EXCLUSIVE。
     */
    @Test
    fun `提交失败之后连接还能开新事务`() = withFileDatabase { (connection, file) ->
        // 另开一条连接挂着一个没读完的 SELECT：回滚日志模式下它持有 SHARED 锁，而提交需要
        // EXCLUSIVE——提交失败因此是真造出来的，不是模拟的。
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { blocker ->
            val rows = blocker.createStatement().executeQuery("SELECT * FROM t")
            rows.next()

            connection.execute("BEGIN IMMEDIATE TRANSACTION")
            connection.execute("INSERT INTO t(a) VALUES (3)")
            assertFailsWith<SQLiteException> { connection.execute("END TRANSACTION") }

            rows.close()
        }

        assertFalse(connection.inTransaction(), "提交失败之后不该再对外声称在事务里")
        // 修复前这里就是那次崩溃：连接上还挂着提交失败的那个事务，SQLite 直接拒绝开新的。
        connection.execute("BEGIN IMMEDIATE TRANSACTION")
        connection.execute("ROLLBACK TRANSACTION")
        assertEquals(
            2L,
            connection.scalar("SELECT COUNT(*) FROM t"),
            "没提交成的那一次必须整体回滚，而不是留在连接上等下一次事务把它顺手带走",
        )
    }

    /**
     * 回归：有语句「读了一半」时，驱动不能自己开出一条没人认领的事务。
     *
     * 这是线上那串崩溃的源头。`autoCommit = true` 时，sqlite-jdbc 在**每条执行成功的语句之后**
     * 都会自己跑一次 `begin;` + `commit;`（`DB.ensureAutoCommit`，它想借此判断「应用是不是自己
     * 开着事务」）。那条 `commit;` 只要撞上「还有语句停在读了一半的位置」，就会被 SQLite 拒绝：
     *
     * ```
     * [SQLITE_BUSY] cannot commit transaction - SQL statements in progress
     * ```
     *
     * 于是探针开的 `begin;` **留在了连接上**，而它不经过本驱动——事务层级从没记过它，Room 回收
     * 连接时也不会来收（`markRecycled` 问的是驱动的 `inTransaction()`）。连接上从此确实有事务，
     * 两边却都以为没有，之后每次 `BEGIN` 都报
     * `cannot start a transaction within a transaction`；连我们自己的兜底 `ROLLBACK` 也会被同一个
     * 探针再污染一次，看起来就像「回滚没生效」。
     *
     * 这里用一条**返回行、又改了库头**的语句把「读了一半」稳定造出来（`PRAGMA journal_mode`），
     * 它必须在默认日志模式下跑——那个 PRAGMA 只有在真的要改模式时才写库头。
     */
    @Test
    fun `半读语句不会再让驱动自己开出一条事务`() {
        val directory = Files.createTempDirectory("clipper-driver").toFile()
        try {
            val file = File(directory, "clipper.db")
            // 刻意不先设 TRUNCATE：下面那条 PRAGMA 需要真的改一次库头（即一次写）。
            driver.open(file.absolutePath).use { connection ->
                connection.execute("CREATE TABLE t(a)")

                val halfRead = connection.prepare("PRAGMA journal_mode = TRUNCATE")
                try {
                    assertTrue(halfRead.step(), "这条 PRAGMA 会返回一行：读了一半，锁握在手里")

                    // 修复前：这一句会被探针报成 SQLITE_BUSY，而且探针开的那个事务会留在连接上。
                    connection.execute("INSERT INTO t(a) VALUES (1)")
                } finally {
                    halfRead.close()
                }

                // 那条 PRAGMA 自己改过库头（一次写）又没收尾，把它连同上面那条 INSERT 一起回滚掉，
                // 让连接回到「没有事务」的起点。修复前这里不会成功——连接上还留着探针那条没人
                // 认领的事务（它甚至不属于本驱动的账）。
                runCatching { connection.execute("ROLLBACK TRANSACTION") }
                assertFalse(connection.inTransaction(), "收尾之后连接上不该还有事务")

                // 修复前：这里就是线上那次崩溃——`cannot start a transaction within a transaction`。
                val before = connection.scalar("SELECT COUNT(*) FROM t")
                connection.execute("BEGIN IMMEDIATE TRANSACTION")
                connection.execute("INSERT INTO t(a) VALUES (2)")
                connection.execute("END TRANSACTION")
                assertEquals(before + 1, connection.scalar("SELECT COUNT(*) FROM t"), "新事务必须能开、能提交")
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    /**
     * 回归：事务里留下「读了一半」的语句时，提交必须由驱动替它收尾。
     *
     * SQLite 会以 `cannot commit transaction - SQL statements in progress` 拒绝提交，而且**不会**
     * 因此自己回滚事务。驱动在每次提交/回滚之前先把别的语句收尾（`ConnectionState.finishOutstanding`），
     * 这条用例守的就是那一步：它一旦失效，提交就会失败并留下一条没人认领的事务，下一次写直接崩。
     */
    @Test
    fun `读了一半的语句不会挡住提交`() = withFileDatabase { (connection, _) ->
        connection.execute("BEGIN IMMEDIATE TRANSACTION")
        connection.execute("INSERT INTO t(a) VALUES (3)")
        val halfRead = connection.prepare("SELECT * FROM t")
        try {
            assertTrue(halfRead.step())
            connection.execute("END TRANSACTION")
        } finally {
            halfRead.close()
        }

        assertFalse(connection.inTransaction())
        assertEquals(3L, connection.scalar("SELECT COUNT(*) FROM t"), "提交必须真的成功")
    }

    /**
     * 回归：执行完却「给不出结果集」的语句不能留在执行中。
     *
     * `PRAGMA incremental_vacuum` 就是这种：`execute()` 按**预编译时的列数**返回 `false`，
     * `getResultSet()` 也给不出结果集，可底层语句已经停在 `SQLITE_ROW` 上。我们因此拿不到任何
     * 句柄去收它，它就一直「在跑」——SQLite 之后会拒绝这条连接上的每一次提交与保存点：
     *
     * ```
     * [SQLITE_BUSY] cannot commit transaction - SQL statements in progress
     * ```
     *
     * 线上就是这样：启动时 `reclaimFreePages()` 跑了这条 PRAGMA，之后所有写操作全废（剪贴板再也
     * 记不进去），而读一切正常——最难查的那种形态。这条用例守着「这类语句执行完立刻收掉」。
     */
    @Test
    fun `产出行却没有结果集的 PRAGMA 不能把连接搞脏`() {
        val directory = Files.createTempDirectory("clipper-driver").toFile()
        try {
            val file = File(directory, "clipper.db")
            driver.open(file.absolutePath).use { connection ->
                // 让 incremental_vacuum 真的有活干：先开增量回收，再删掉一批行腾出空闲页。
                // 这两步缺一不可——只有**确实回收了页**时这条 PRAGMA 才会产出行，
                // 也才会把语句留在 `SQLITE_ROW` 上（见类注释与线上那次）。
                connection.execute("PRAGMA auto_vacuum = INCREMENTAL")
                connection.execute("PRAGMA journal_mode = TRUNCATE")
                connection.execute("CREATE TABLE t(a)")
                repeat(2_000) { connection.execute("INSERT INTO t(a) VALUES ($it)") }
                connection.execute("DELETE FROM t")
                assertTrue(connection.scalar("PRAGMA auto_vacuum") == 2L, "前置条件：增量回收必须生效")
                assertTrue(connection.scalar("PRAGMA freelist_count") > 0L, "前置条件：必须有空闲页可回收")

                // 关键：Room 的语句缓存**不会 close** 语句，只在复用前 reset + clearBindings
                // （见 `BasePreparedStatementCache.CachedStatement`）。因此这条语句的对象会一直留着，
                // 也就没人帮它 `sqlite3_finalize`——泄漏正是这么活下来的。
                val vacuum = connection.prepare("PRAGMA incremental_vacuum")
                try {
                    vacuum.step()
                } finally {
                    vacuum.reset()
                    vacuum.clearBindings()
                }

                // 修复前：上面那条 PRAGMA 的 VM 还停在 `SQLITE_ROW` 上，下面这次提交必然失败；
                // 之后每次提交/开保存点都一样，整库写不进去。
                connection.execute("BEGIN IMMEDIATE TRANSACTION")
                connection.execute("INSERT INTO t(a) VALUES (1)")
                connection.execute("END TRANSACTION")
                assertEquals(1L, connection.scalar("SELECT COUNT(*) FROM t"), "提交必须真的成功")

                // 语句本身还得能继续用（下次复用会重建）。
                vacuum.close()
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun SQLiteConnection.execute(sql: String) {
        prepare(sql).use { it.step() }
    }

    private fun SQLiteConnection.scalar(sql: String): Long =
        prepare(sql).use { statement -> if (statement.step()) statement.getLong(0) else -1L }

    /** 见 [withFileDatabase]。 */
    private data class FileDatabase(val connection: SQLiteConnection, val file: File)

    /**
     * 开一个临时文件库：日志模式与线上一致（TRUNCATE），表里预置两行。
     *
     * 「读了一半」那两条用例**必须落盘**：`cannot commit transaction - SQL statements in progress`
     * 只在提交需要写回文件时才判得出来，内存库上看不到，用例会假通过。
     */
    private fun <R> withFileDatabase(block: (FileDatabase) -> R): R {
        val directory = Files.createTempDirectory("clipper-driver").toFile()
        try {
            val file = File(directory, "clipper.db")
            return driver.open(file.absolutePath).use { connection ->
                connection.execute("PRAGMA journal_mode = TRUNCATE")
                connection.execute("CREATE TABLE t(a)")
                connection.execute("INSERT INTO t(a) VALUES (1)")
                connection.execute("INSERT INTO t(a) VALUES (2)")
                block(FileDatabase(connection, file))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    private companion object {
        /** sqlite-jdbc 的「当前连接私有内存库」写法。 */
        const val MEMORY_DATABASE = ":memory:"

        val driver = SqlCipherDriver()
    }
}
