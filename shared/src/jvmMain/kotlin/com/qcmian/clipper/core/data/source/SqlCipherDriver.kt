package com.qcmian.clipper.core.data.source

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteException
import androidx.sqlite.SQLiteStatement
import java.math.BigDecimal
import java.math.BigInteger
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types
import org.sqlite.SQLiteConfig

/** 打开 `-Dclipper.driver.debug=true` 时，语句执行失败会打印该连接上所有活语句，便于定位锁冲突。 */
private const val DEBUG_PROPERTY = "clipper.driver.debug"

/** 只在显式打开时才记录语句轨迹：默认路径上不能为此多造一个字符串。 */
private val driverDebug: Boolean by lazy { System.getProperty(DEBUG_PROPERTY) == "true" }

/**
 * 把 JDBC 的 [SQLException] 翻译成 [SQLiteException]。
 *
 * 驱动接口与 Room 的异常处理都按后者约定：Room 的失效跟踪器对刷新过程中的失败是
 * `catch (ex: SQLiteException) { 跳过本次刷新 }`，抛别的类型会直接掀掉那个协程。
 */
private inline fun <T> translating(block: () -> T): T =
    try {
        block()
    } catch (e: SQLException) {
        throw SQLiteException(e.message ?: "SQLite 执行失败").also { it.initCause(e) }
    }

/**
 * 基于 SQLCipher 的 [SQLiteDriver]。
 *
 * 之前用的是 androidx 的 `BundledSQLiteDriver`（自带 `libsqliteJni`），那颗 native 库就是普通的 SQLite，
 * 而且驱动接口上**没有任何注入密钥的入口**——所以「换库」不能在 native 层解决，只能在驱动这一层接管：
 * 底层换成带加密能力的 SQLite JDBC 驱动（`io.github.willena:sqlite-jdbc`，SQLite3MultipleCiphers 分支），
 * 并把密钥在**连接构造时**交给驱动。
 *
 * [passphrase] 为 `null` 时不传任何加密配置，落盘的依然是**标准 SQLite 明文库**（文件头是 `SQLite format 3`），
 * 与改造前格式一致。它是**每次建连接现读**的提供者而不是一个固定值：换钥（`PRAGMA rekey`）之后
 * 新开的连接必须用新口令，而 Room 的连接是从这里现取的（见 `DatabaseKey`）。
 *
 * ## 三处必须自己抹平的语义差
 *
 * - **密钥注入时机**：JDBC 上**不能**先建连接再发 `PRAGMA key`。驱动的连接构造函数自己会先执行一批
 *   PRAGMA，那一步已经去读库头了——对新建的空库碰巧还能成功（还没写过页），换成已加密的库就会以
 *   `file is not a database` 失败，**用正确密钥也一样**。只能通过 URL 参数或 [SQLiteConfig] 传。
 *
 * - **下标基准**：androidx 直接透传 SQLite C 接口的下标，于是同一套接口里两边**不一致**——
 *   `bindXxx` 是 **1 基**（等价 `sqlite3_bind_xxx`），`getXxx` / `isNull` / `getColumnName` 是 **0 基**
 *   （等价 `sqlite3_column_xxx`）。JDBC 两边都是 1 基，因此只在**读**的一侧做 `+1`。
 *
 * - **事务是 SQL 语句，不是驱动 API**：[SQLiteConnection] 上没有 begin/commit 方法，Room 直接发
 *   `BEGIN [DEFERRED|IMMEDIATE|EXCLUSIVE] TRANSACTION` / `SAVEPOINT` / `RELEASE` /
 *   `ROLLBACK [TRANSACTION [TO SAVEPOINT x]]` / `END TRANSACTION`。因此连接保持 `autoCommit = true`
 *   把语句原样透传，**事务层级自己数**——见 [SqlTransactionKind] 与 [ConnectionState]。
 *
 * ## 为什么要维护「活动语句」集合
 *
 * SQLite 在提交/回滚时，如果同一条连接上还有别的语句没有跑完（`sqlite3_step` 停在 `SQLITE_ROW`，
 * 既没走完也没 `reset`），会直接拒绝：
 *
 * ```
 * [SQLITE_BUSY] cannot commit transaction - SQL statements in progress
 * ```
 *
 * 原生驱动里这不成问题，语句一 `close` 就收尾了；但在 JDBC 上 `PreparedStatement` 是个长寿对象，
 * 它的 `ResultSet` 一直挂着底层语句，直到 `reset()` / `close()` 才让 SQLite 收尾。Room 的语句缓存
 * 会长期持有这些对象，于是提交时很容易撞上。所以这里在每次提交/回滚之前，**把同一连接上其它
 * 还没收尾的语句先收尾**（[ConnectionState.finishOutstanding]）。
 */
internal class SqlCipherDriver(
    private val passphrase: () -> String? = { null },
) : SQLiteDriver {

    init {
        // 注册到 DriverManager。显式加载一次，省掉首次连接时的服务发现。
        Class.forName(JDBC_DRIVER_CLASS)
    }

    override fun open(fileName: String): SQLiteConnection =
        translating { SqlCipherConnection(connect(fileName)) }

    private fun connect(fileName: String): Connection {
        val url = "jdbc:sqlite:$fileName"

        // 走 Properties 而不是 URL 查询串：密钥里的 `&` `=` 空格等字符不必再做转义。
        // 明文库也走同一个 config——见下面关掉 generated keys 的理由。
        val config = SQLiteConfig()

        // **必须关掉**：驱动默认 `jdbc.get_generated_keys=true`，于是每执行一条 INSERT，它都会
        // 自己 `createStatement()` 跑一句 `SELECT last_insert_rowid()`，并把那个结果集一直挂着
        // （`CoreStatement.updateGeneratedKeys`，直到下一次 `clearGeneratedKeys()` 才释放）。
        // 这条语句不是经 `prepare()` 建的，本驱动管不到它，可它同样占着 SQLite 的
        // `nVdbeActive`——于是整库重写（`VACUUM` / `PRAGMA rekey`）必然被拒：
        //
        //     cannot VACUUM - SQL statements in progress
        //
        // 实际后果是「只要复制过内容，设置页里就换不了密钥」。SQLite 层面没有别的收尾手段：
        // `sqlite3_reset` 也清不掉它，只有 `close()`（内部会 `clearGeneratedKeys()`）才行，
        // 而那条语句一直握在驱动手里。Room 用不到这一项（`androidx.sqlite.SQLiteStatement`
        // 上根本没有 generated keys），因此直接关掉，而不是每写一次就去找它收尾。
        config.setGetGeneratedKeys(false)

        // 现读一次口令：换钥之后这里必须给出新值，否则新开的连接会用旧口令去读新库头。
        val key = passphrase()
        if (key != null) {
            // 算法与密钥都要给，且建库与开库必须一致：只给 key 会用驱动的默认算法，
            // 与显式 sqlcipher 建出来的库对不上（同样报 file is not a database）。
            config.setPragma(SQLiteConfig.Pragma.CIPHER, SQLCIPHER_SCHEME)
            config.setPragma(SQLiteConfig.Pragma.KEY, key)
        }
        return DriverManager.getConnection(url, config.toProperties())
    }

    private companion object {
        const val JDBC_DRIVER_CLASS = "org.sqlite.JDBC"
    }
}

/** 一条连接上被 Room 当作事务控制的语句。 */
private enum class SqlTransactionKind {
    /** `BEGIN [DEFERRED|IMMEDIATE|EXCLUSIVE] TRANSACTION`：开启（或嵌套的保存点之外的）事务。 */
    BEGIN,
    SAVEPOINT,
    RELEASE,

    /** `COMMIT` / `END TRANSACTION`：结束整层事务。 */
    COMMIT,

    /** `ROLLBACK [TRANSACTION]`：回滚整层事务。 */
    ROLLBACK,

    /** `ROLLBACK TRANSACTION TO SAVEPOINT x`：只回到保存点，**事务仍然开着**。 */
    ROLLBACK_TO_SAVEPOINT,
}

/**
 * 一条连接的共享状态：事务层级 + 该连接上所有还活着的语句 + 一把串行锁。
 *
 * 加锁是因为 JDBC 驱动内部的 autocommit 探测（它会自己发 `begin;` / `commit;`）并不与外部语句
 * 执行互斥；把「执行语句」这一步串起来，可以避免两个语句同时处于执行中。
 */
private class ConnectionState {
    private val lock = Any()
    private val live = LinkedHashSet<SqlCipherStatement>()
    private var depth = 0

    fun inTransaction(): Boolean = synchronized(lock) { depth > 0 }

    fun <R> locked(block: () -> R): R = synchronized(lock) { block() }

    fun register(statement: SqlCipherStatement) {
        synchronized(lock) { live.add(statement) }
    }

    fun unregister(statement: SqlCipherStatement) {
        synchronized(lock) { live.remove(statement) }
    }

    /**
     * 把除 [except] 之外、还在使用中的语句全部收尾。
     *
     * Room 的语句缓存会长期持有语句对象，一个 `SELECT` 读过一行就搁下的情况并不罕见；留着它
     * 会让 SQLite 在提交时报「SQL statements in progress」。这里统一收尾，与 SQLite 客户端的
     * 正确用法一致：事务边界上不该有别的语句还在跑。
     */
    fun finishOutstanding(except: SqlCipherStatement) {
        synchronized(lock) { live.toList() }.forEach { if (it !== except) it.abort() }
    }

    /** 诊断用：列出该连接上仍然是「活的」的语句。 */
    fun describe(): String =
        synchronized(lock) { live.joinToString("\n") { "      - ${it.describe()}" } }.ifEmpty { "      (无)" }

    private val history = ArrayDeque<String>()

    /** 诊断用：记录最近的语句动作。默认关闭，且入参是 lambda——不开诊断就不构造字符串。 */
    fun record(entry: () -> String) {
        if (!driverDebug) return
        synchronized(lock) {
            history.addLast(entry())
            if (history.size > 60) history.removeFirst()
        }
    }

    /** 诊断用：最近的语句动作序列。 */
    fun historyDump(): String =
        synchronized(lock) { history.joinToString("\n") { "      · $it" } }.ifEmpty { "      (无)" }

    fun onTransaction(kind: SqlTransactionKind) {
        synchronized(lock) {
            when (kind) {
                SqlTransactionKind.BEGIN, SqlTransactionKind.SAVEPOINT -> depth++
                SqlTransactionKind.RELEASE -> if (depth > 0) depth--
                SqlTransactionKind.COMMIT, SqlTransactionKind.ROLLBACK -> depth = 0
                // 回到保存点不结束事务——把它当成结束会让 `inTransaction()` 说谎。
                SqlTransactionKind.ROLLBACK_TO_SAVEPOINT -> Unit
            }
        }
    }

    /**
     * 提交/回滚**失败**时把层级强制归零。
     *
     * 失败之后真实的事务状态已经不可知，但继续对外声称「在事务中」会升级成僵尸：
     * 连接每次被回收都会再发一条注定失败的 `ROLLBACK TRANSACTION`，而它抛出的异常发生在
     * Room 的 try/catch 之外（`PooledConnectionImpl.markRecycled`），会直接掀掉调用方协程。
     * 归零至少让状态与 Room 的 `transactionStack` 一致。
     */
    fun resetDepth() {
        synchronized(lock) { depth = 0 }
    }
}

/**
 * 一条 SQLCipher 连接。
 *
 * 密钥已在 [SqlCipherDriver.connect] 里随连接构造一并注入，这里只负责其余语义差异。
 */
private class SqlCipherConnection(
    private val connection: Connection,
) : SQLiteConnection {

    private val state = ConnectionState()

    init {
        // 不交给 JDBC 的隐式事务：Room 自己发 BEGIN/END，两套机制同时生效会互相打架。
        translating { connection.autoCommit = true }
    }

    override fun inTransaction(): Boolean = state.inTransaction()

    override fun prepare(sql: String): SQLiteStatement =
        translating { SqlCipherStatement(connection, sql, state) }

    override fun close() {
        connection.close()
    }
}

/**
 * 一条预编译语句。
 *
 * 生命周期与 SQLite 原生一致：预编译一次 → 绑定 → 执行 → 复位 → 再绑定。因此 [step] 只在第一次
 * 调用时真正执行，之后只推进结果集；[reset] 回到可再次执行的起点，且**不清除绑定**
 * （`sqlite3_reset` 的语义；清绑定是 [clearBindings] 的事）。
 */
private class SqlCipherStatement(
    connection: Connection,
    private val sql: String,
    private val state: ConnectionState,
) : SQLiteStatement {

    private val transactionKind = classifyTransaction(sql)

    private val statement: PreparedStatement = connection.prepareStatement(sql)

    /**
     * 列名在**预编译时**抓一次并缓存。
     *
     * 不能每次现取：JDBC 的 `PreparedStatement.getMetaData()` 返回的其实就是它内部那个 `ResultSet`，
     * 而 `ResultSet.close()` 会把列信息清空——本驱动在 [reset] / 读完 / [close] 时都会关它，于是
     * 「复位之后再问列名」会直接抛 `inconsistent internal state`。原生驱动（`sqlite3_column_name`）
     * 在 prepare 之后随时都能问，缓存才能对齐这个语义。
     */
    private val columnNames: List<String> = runCatching {
        statement.metaData?.let { meta -> (1..meta.columnCount).map { meta.getColumnName(it) } }
    }.getOrNull().orEmpty()

    private var resultSet: ResultSet? = null
    private var executed = false
    private var closed = false

    init {
        state.register(this)
    }

    override fun bindBlob(index: Int, value: ByteArray) = translating {
        statement.setBytes(index, value)
    }

    override fun bindDouble(index: Int, value: Double) = translating {
        statement.setDouble(index, value)
    }

    override fun bindLong(index: Int, value: Long) = translating {
        statement.setLong(index, value)
    }

    override fun bindText(index: Int, value: String) = translating {
        statement.setString(index, value)
    }

    override fun bindNull(index: Int) = translating {
        statement.setNull(index, Types.NULL)
    }

    override fun getBlob(index: Int): ByteArray =
        translating { requireResultSet().getBytes(column(index)) }

    override fun getDouble(index: Int): Double =
        translating { requireResultSet().getDouble(column(index)) }

    override fun getLong(index: Int): Long =
        translating { requireResultSet().getLong(column(index)) }

    override fun getText(index: Int): String =
        translating { requireResultSet().getString(column(index)) ?: "" }

    override fun isNull(index: Int): Boolean =
        translating { requireResultSet().getObject(column(index)) == null }

    override fun getColumnType(index: Int): Int =
        translating { columnTypeOf(requireResultSet().getObject(column(index))) }

    override fun getColumnCount(): Int = columnNames.size

    override fun getColumnName(index: Int): String = columnNames.getOrNull(index).orEmpty()

    override fun step(): Boolean = translating {
        val current = executeIfNeeded() ?: return@translating false
        val hasRow = current.next()
        if (!hasRow) {
            // 读完立刻收尾：否则底层语句会一直停在「执行中」，把后续的提交挡掉。
            current.close()
            resultSet = null
        }
        hasRow
    }

    override fun reset() = abort()

    override fun clearBindings() = translating {
        statement.clearParameters()
    }

    override fun close() = translating {
        if (closed) return@translating
        closed = true
        state.record { "关闭 ${sql.replace('\n', ' ').take(70)}" }
        state.unregister(this)
        resultSet?.close()
        resultSet = null
        statement.close()
    }

    /**
     * 让底层语句结束当前这一轮执行，但**保留对象本身**（Room 会复用）。
     * 与 [reset] 同义，额外供 [ConnectionState.finishOutstanding] 从连接侧调用。
     */
    fun abort() = translating {
        if (resultSet != null) {
            state.record { "复位 ${sql.replace('\n', ' ').take(70)}" }
            resultSet?.close()
        }
        resultSet = null
        executed = false
    }

    /** 诊断用。 */
    fun describe(): String = buildString {
        append(sql.replace('\n', ' ').take(100))
        append(" | 类别=").append(transactionKind)
        append(", 已执行=").append(executed)
        append(", 有结果集=").append(resultSet != null)
        append(", 已关闭=").append(closed)
    }

    private fun executeIfNeeded(): ResultSet? = state.locked {
        if (closed) throw SQLiteException("statement is closed")
        if (!executed) {
            // 提交/回滚之前先把同一连接上其它没收尾的语句收掉，否则 SQLite 会拒绝提交。
            if (transactionKind == SqlTransactionKind.COMMIT ||
                transactionKind == SqlTransactionKind.ROLLBACK
            ) {
                state.finishOutstanding(this)
            }
            executed = true
            resultSet = try {
                if (statement.execute()) statement.resultSet else null
            } catch (t: SQLException) {
                if (transactionKind == SqlTransactionKind.COMMIT ||
                    transactionKind == SqlTransactionKind.ROLLBACK
                ) {
                    state.resetDepth()
                }
                if (driverDebug) {
                    println("[clipper-driver] 执行失败: ${sql.replace('\n', ' ').take(140)}")
                    println("[clipper-driver] 该连接上活的语句：")
                    println(state.describe())
                    println("[clipper-driver] 最近动作：")
                    println(state.historyDump())
                }
                throw t
            }
            transactionKind?.let(state::onTransaction)
            state.record { "执行 ${sql.replace('\n', ' ').take(70)} -> ${if (resultSet != null) "有行" else "无行"}" }
        }
        resultSet
    }

    /** 取列是 0 基的（对应 `sqlite3_column_xxx`），JDBC 是 1 基。 */
    private fun column(index: Int): Int = index + 1

    private fun requireResultSet(): ResultSet =
        resultSet ?: throw SQLiteException("no row to read: step() must return true first")

    private companion object {
        /** 对应 SQLite C 接口的 `SQLITE_INTEGER` / `SQLITE_FLOAT` / `SQLITE_TEXT` / `SQLITE_BLOB` / `SQLITE_NULL`。 */
        const val COLUMN_TYPE_INTEGER = 1
        const val COLUMN_TYPE_FLOAT = 2
        const val COLUMN_TYPE_TEXT = 3
        const val COLUMN_TYPE_BLOB = 4
        const val COLUMN_TYPE_NULL = 5

        fun columnTypeOf(value: Any?): Int = when (value) {
            null -> COLUMN_TYPE_NULL
            is Int, is Long, is Short, is Byte, is Boolean, is BigInteger -> COLUMN_TYPE_INTEGER
            is Double, is Float, is BigDecimal -> COLUMN_TYPE_FLOAT
            is ByteArray -> COLUMN_TYPE_BLOB
            else -> COLUMN_TYPE_TEXT
        }
    }
}

/**
 * 判断一条语句是否（以及以何种方式）控制事务。
 *
 * 取首个字母序列作为语句类别，跳过前导空白与注释符。`ROLLBACK` 要看有没有 `TO`：
 * `ROLLBACK TRANSACTION TO SAVEPOINT 'x'` 只回到保存点，事务并没有结束。
 */
private fun classifyTransaction(sql: String): SqlTransactionKind? {
    var index = 0
    while (index < sql.length && !sql[index].isLetter()) index++
    val start = index
    while (index < sql.length && sql[index].isLetter()) index++
    return when (sql.substring(start, index).uppercase()) {
        "BEGIN" -> SqlTransactionKind.BEGIN
        "SAVEPOINT" -> SqlTransactionKind.SAVEPOINT
        "RELEASE" -> SqlTransactionKind.RELEASE
        "COMMIT", "END" -> SqlTransactionKind.COMMIT
        "ROLLBACK" ->
            if (sql.uppercase().contains(" TO ")) {
                SqlTransactionKind.ROLLBACK_TO_SAVEPOINT
            } else {
                SqlTransactionKind.ROLLBACK
            }
        else -> null
    }
}
