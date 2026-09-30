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
 * SQLite 对「已经处于事务中」的连接再收到 `BEGIN` 时的报错原文。
 *
 * 它是**唯一**能证明「连接上挂着一条没结束的事务」的信号（SQL 层面没有别的方式问出这件事），
 * 因此 [SqlCipherStatement] 用它来决定要不要回滚后重试。
 */
private const val LEFTOVER_TRANSACTION_ERROR = "within a transaction"

/**
 * SQLite 在「这条连接上还有语句在跑」时拒绝提交 / 打开保存点的报错原文。
 *
 * 两种情形都长这样：`cannot commit transaction - SQL statements in progress`、
 * `cannot open savepoint - SQL statements in progress`。见 [SqlCipherStatement.discardAbandonedStatement]
 * 里那个「执行完却给不出结果集」的例子——留一条这样的语句，整库就都写不进去。
 */
private const val STATEMENTS_IN_PROGRESS_ERROR = "SQL statements in progress"

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
 *   `ROLLBACK [TRANSACTION [TO SAVEPOINT x]]` / `END TRANSACTION`。连接因此把语句原样透传，
 *   **事务层级自己数**——见 [SqlTransactionKind] 与 [ConnectionState]。前提是**关掉驱动的自动提交
 *   探针**，见下一节。
 *
 * ## 为什么必须关掉驱动的「自动提交探针」
 *
 * `autoCommit = true` 时，驱动在**每条执行成功的语句之后**都会自己跑一次 `begin;` + `commit;`
 * （`DB.ensureAutoCommit`，它想借此判断「应用是不是自己开着事务」）。这个探针有三个致命问题：
 *
 * - **它开的那条事务不经过本驱动**，[ConnectionState] 的层级完全不知道它存在；
 * - **它的 `commit;` 很容易失败**：同一条连接上只要还有别的语句停在「读了一半」的位置，SQLite
 *   就会拒绝提交（`cannot commit transaction - SQL statements in progress`，与
 *   [ConnectionState.finishOutstanding] 对付的是同一个坑），于是那条事务真的留在了连接上；
 * - 这个失败还会**冒到语句调用方头上**：一条其实已经写成功的语句会被报成 `SQLITE_BUSY`。
 *
 * 之后整条连接就开始持续报 `cannot start a transaction within a transaction`——连接上确实有事务，
 * 而两边都以为没有（Room 只信自己的 `transactionStack`，本驱动的层级也没记过它），于是谁都不去
 * 收它；更隐蔽的是，我们自己的兜底 `ROLLBACK` 也会被同一个探针再污染一次，看起来像「回滚没生效」。
 *
 * 所以这里把 `autoCommit` 关掉：`ensureAutoCommit` 见 `autoCommit == false` 会直接返回，探针不再
 * 出现。Room 走的是裸 SQL，JDBC 那套 `commit()` / `rollback()` / `setSavepoint()` API 一个都不碰
 * （已全仓核对），因此也没人会依赖 `autoCommit == false` 时驱动「替你开着事务」的那套语义。
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
 *
 * ## 出事时的三条底线
 *
 * 多条语句挤在一条连接上，出错的代价会被放大：**一条语句坏掉、一条语句没跑完、或一次事务没结束，
 * 整库就再也写不进去**。所以三个方向都要兜住：
 *
 * - **语句坏掉要能重建**。JDBC 在语句执行失败时会顺手关掉它的指针，之后这条语句连绑参数都做不了
 *   （`statement is not executing`），而 Room 的缓存还拿着它。见 [SqlCipherStatement.stale]。
 * - **语句不能留在「跑了一半」上**。`execute()` 的返回值是按**预编译时的列数**算的，有些语句
 *   （`PRAGMA incremental_vacuum`）要到执行时才产出行——这时它说「没有结果集」，可底层语句已经停在
 *   `SQLITE_ROW` 上，而且我们拿不到句柄去收它。留着它，SQLite 会拒绝这条连接上的每一次提交与
 *   保存点（`SQL statements in progress`）。见 [SqlCipherStatement.discardAbandonedStatement]。
 * - **事务状态要么准、要么真的收掉**。本驱动的事务层级是 `inTransaction()` 的唯一答案，Room 靠它
 *   决定要不要在回收连接时补一次回滚。所以层级归零就必须真的把事务结束掉（
 *   [ConnectionState.abandonTransaction]），而事务语句撞上残留事务 / 没跑完的语句时，回滚或收掉
 *   之后重试一次（见 `SqlCipherStatement.executeOnce`）。
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
     * 事务**非正常收尾**时的兜底：把这条连接真的拉回「不在事务里」，并把层级归零。
     *
     * 只把层级清零是不够的，而且正是之前那个版本的坑：SQLite 在提交/回滚失败时**不一定**
     * 自己回滚（`COMMIT` 撞上 `SQLITE_BUSY` 就是典型——事务原样留着等重试）。于是连接上会
     * 长期挂着「层级 0 + 事务开着」这种自相矛盾的状态，而两边都不会来收拾它：
     *
     * - Room 不会开新事务——`PooledConnectionImpl.beginTransaction` 只信自己的 `transactionStack`，
     *   它已经把这层弹掉了；
     * - Room 也不会回收掉它——`markRecycled` 问的是本驱动的 `inTransaction()`，层级 0 让它以为
     *   没有事务要回滚。
     *
     * 结果是这条单连接再也开不了事务，下一次 `BEGIN` 直接报
     * `cannot start a transaction within a transaction`，写路径全线崩掉（见 [SqlCipherStatement] 的
     * `BEGIN` 自愈，它兜的就是这一步）。
     *
     * 实现上有两处必须绕开常规路径：
     *
     * - **不走语句缓存**，直接用裸 JDBC 语句发一句 `ROLLBACK`：此刻语句层可能已经不可信（失败
     *   的那条语句连指针都被驱动关掉了），兜底手段不能建立在它之上。
     * - **先收掉别的没收尾的语句**：SQLite 拒绝在「还有语句在跑」时回滚。
     *
     * 本来就没有事务在跑时，SQLite 会回一句 `no transaction is active`——属正常，忽略。
     */
    /**
     * 把这条连接上所有语句的底层对象都收干净（[SqlCipherStatement.discardForReuse]）。
     *
     * 用在「SQLite 说还有语句在跑，但 `finishOutstanding` 收不掉它」的时候：那种语句拿不到结果集
     * 句柄，只有把语句本身关掉（`sqlite3_finalize`）才能真正收尾。这批语句会在下次复用时重建，
     * 因此这条路径只在出错时走，正常路径一次都不碰。
     */
    fun discardStatements(except: SqlCipherStatement) {
        synchronized(lock) { live.toList() }.forEach { if (it !== except) it.discardForReuse() }
    }

    fun abandonTransaction(connection: Connection, except: SqlCipherStatement) {
        finishOutstanding(except)
        synchronized(lock) {
            depth = 0
            val outcome = runCatching { connection.createStatement().use { it.execute("ROLLBACK") } }
            // 结果进诊断轨迹：这条回滚是本驱动唯一能自证「连接已经被拉回无事务」的地方，
            // 它失败（或漏掉）时，轨迹上必须有痕迹。
            record { "放弃事务：直接回滚 -> ${outcome.exceptionOrNull()?.message ?: "成功"}" }
        }
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
        // 关掉驱动的自动提交探针——它是「连接上凭空多出一条事务」的唯一入口，见类注释那一节。
        translating {
            connection.autoCommit = false
            // `setAutoCommit(false)` 自己会顺手发一句 `begin;`，紧接着把那个空事务收掉。
            // 只能用裸语句：JDBC 的 `rollback()` 会在回滚之后再补一句 `begin;`，正好把那套
            // 「驱动替你开着事务」的语义又装回来。
            connection.createStatement().use { it.execute("ROLLBACK") }
        }
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
 *
 * 与原生的一条重要差别：JDBC 的 `PreparedStatement` **一次执行失败之后就被驱动废掉了**
 * （`DB.execute` 的兜底分支直接 `pointer.close()`），此后连 `clearParameters` 都抛
 * `statement is not executing`。Room 的语句缓存会长期持有本对象，所以这里必须能在复用前把它
 * 换掉——见 [stale] 与 [renewIfStale]。
 */
private class SqlCipherStatement(
    private val connection: Connection,
    private val sql: String,
    private val state: ConnectionState,
) : SQLiteStatement {

    private val transactionKind = classifyTransaction(sql)

    /** 会随 [stale] 被换掉，所以不是 `val`——见类注释。 */
    private var statement: PreparedStatement = connection.prepareStatement(sql)

    /**
     * 列名在**预编译时**抓一次并缓存。
     *
     * 不能每次现取：JDBC 的 `PreparedStatement.getMetaData()` 返回的其实就是它内部那个 `ResultSet`，
     * 而 `ResultSet.close()` 会把列信息清空——本驱动在 [reset] / 读完 / [close] 时都会关它，于是
     * 「复位之后再问列名」会直接抛 `inconsistent internal state`。原生驱动（`sqlite3_column_name`）
     * 在 prepare 之后随时都能问，缓存才能对齐这个语义。
     *
     * 重建语句时不必重取：同一条 SQL 的列信息是不变的。
     */
    private val columnNames: List<String> = runCatching {
        statement.metaData?.let { meta -> (1..meta.columnCount).map { meta.getColumnName(it) } }
    }.getOrNull().orEmpty()

    /**
     * 底层语句已被驱动废弃，下一次复用之前要重建。
     *
     * 只能由一次**失败**的执行置位（见 [discardAfterFailure]）。
     */
    private var stale = false

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
        // 语句已被驱动废弃时没有任何绑定可清——接下来会换上一条全新的语句，它本来就没有绑定。
        // 真去问旧语句只会得到 "statement is not executing"（见 [stale]）。
        if (closed || stale) return@translating
        statement.clearParameters()
    }

    override fun close() = translating {
        if (closed) return@translating
        closed = true
        state.record { "关闭 ${sql.replace('\n', ' ').take(70)}" }
        state.unregister(this)
        resultSet?.close()
        resultSet = null
        // 失败过的语句指针早已被驱动关掉，这里再关一次是安全的（`internalClose` 会先自查）。
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
        // 复位是**复用的起点**，也就是重建废弃语句唯一安全的时点：再晚一步（下一次执行前）
        // 调用方可能已经绑好参数，换语句会把那些绑定丢掉。
        renewIfStale()
    }

    /** 诊断用。 */
    fun describe(): String = buildString {
        append(sql.replace('\n', ' ').take(100))
        append(" | 类别=").append(transactionKind)
        append(", 已执行=").append(executed)
        append(", 有结果集=").append(resultSet != null)
        append(", 待重建=").append(stale)
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
            resultSet = executeOnce()
            executed = true
            transactionKind?.let(state::onTransaction)
            state.record { "执行 ${sql.replace('\n', ' ').take(70)} -> ${if (resultSet != null) "有行" else "无行"}" }
        }
        resultSet
    }

    /**
     * 执行一次；失败时按需自愈。失败一律以原始的 `SQLException` 抛出——外层的 [step] 负责翻译。
     *
     * 两种会自愈的情形，都是「连接上有一条语句/事务没结束」，而它们都会让整库写不进去：
     *
     * - **`BEGIN` 撞上一条没结束的事务**：Room 只在它自己的 `transactionStack` 为空时才发 `BEGIN`，
     *   所以收到那句错就说明连接的真实状态与本驱动的记账已经不一致，连接会一直卡在事务里，
     *   之后每次开事务都注定失败——也就是 `cannot start a transaction within a transaction`。
     * - **事务语句撞上「还有语句在跑」**（`SQL statements in progress`）：那条语句可能任何类型
     *   （见 [discardAbandonedStatement] 里 `PRAGMA incremental_vacuum` 的例子），而只要它挂着，
     *   提交与保存点就永远开不出来。这里把所有缓存语句收干净再重试一次——数据已经写在事务里，
     *   重试是安全的；不重试，这一次写就白丢了。
     *
     * 两种重试都必须用重建出来的语句：失败的那次执行已经让驱动关掉了旧语句的指针。事务控制语句
     * 从不带绑定参数，重建不会丢绑定。
     */
    private fun executeOnce(): ResultSet? {
        try {
            return executeStatement()
        } catch (t: SQLException) {
            discardAfterFailure()
            if (transactionKind == SqlTransactionKind.BEGIN && t.isLeftoverTransaction()) {
                state.record { "BEGIN 撞上残留事务：回滚后重试" }
                state.abandonTransaction(connection, this)
                renewIfStale()
                return retryOrReport { executeStatement() }
            }
            if (transactionKind != null && t.isStatementsInProgress()) {
                state.record { "事务语句撞上没收尾的语句：收掉它们后重试" }
                state.discardStatements(this)
                renewIfStale()
                return retryOrReport { executeStatement() }
            }
            // 先留现场**再**收拾：诊断要的正是「哪条语句还挂着」这个状态，
            // 而 `abandonTransaction` 会把它一并清掉。
            reportFailure(t)
            // 提交/回滚失败之后连接的真实状态已经不可知，而只把层级归零会让 Room 两边都不管它
            // （不开新事务、也不回收）——见 [ConnectionState.abandonTransaction]。必须真的拉回来。
            if (transactionKind == SqlTransactionKind.COMMIT ||
                transactionKind == SqlTransactionKind.ROLLBACK
            ) {
                state.abandonTransaction(connection, this)
            }
            throw t
        }
    }

    /** 重试一次；再失败就是「本该不可能发生」的场合，把现场打出来再抛。 */
    private fun retryOrReport(retry: () -> ResultSet?): ResultSet? =
        try {
            retry()
        } catch (again: SQLException) {
            discardAfterFailure()
            // 自愈没成功意味着写路径整个不可用了，所以这里**不受** `clipper.driver.debug` 约束。
            reportFailure(again, always = true)
            throw again
        }

    /** 执行一次，不碰任何状态（成功与否都由调用方决定怎么记账）。 */
    private fun executeStatement(): ResultSet? {
        if (statement.execute()) return statement.resultSet
        // `execute()` 的返回值是按**预编译时的列数**算的，而有些语句要到执行时才产出行：
        // `PRAGMA incremental_vacuum` 就是这样——它返回 false、`getResultSet()` 也给不出结果集，
        // 可底层语句已经停在 `SQLITE_ROW` 上。我们拿不到任何句柄去收它，于是它永远「在跑」，
        // SQLite 之后会拒绝这条连接上的每一次提交与保存点（`SQL statements in progress`），
        // 整库再也写不进去。所以这类语句执行完就立刻关掉，让它在下次复用时重建。
        if (sql.startsWith("PRAGMA", ignoreCase = true)) discardAbandonedStatement()
        return null
    }

    /**
     * 把「执行完了却给不出结果集」的语句直接收掉（`sqlite3_finalize`），下次复用会重建。
     *
     * 为什么必须是关掉而不是复位：驱动没有暴露 `sqlite3_reset`，能收尾的只有两条路——关结果集
     * （`CoreResultSet.close()` 只在 `open` 为真时才复位，而这里恰恰没被打开过）与关语句本身。
     * 代价只有一次重建，而留着它的代价是整库写不进去。
     */
    private fun discardAbandonedStatement() {
        stale = true
        runCatching { statement.close() }
    }

    /** 供 [ConnectionState.discardStatements] 从连接侧调用：收干净这条语句。 */
    fun discardForReuse() {
        runCatching { abort() }
        discardAbandonedStatement()
    }

    /**
     * 一次执行失败之后的收尾。
     *
     * [executed] 必须退回 `false`：这一次什么都没跑成，下一次调用还得真执行（原先把它置在
     * `execute()` 之前，失败之后那条语句就对外声称「已经执行过、没有结果集」，
     * `step()` 于是静默返回 `false`——一条写语句就这么无声无息地被跳过了）。
     *
     * [stale] 必须置位：驱动在失败时已经把语句的指针关掉（`DB.execute` 的兜底分支），这条语句
     * 再也不能用了，而 Room 的缓存还拿着它。重建推迟到下一次复用的起点（见 [abort]）。
     */
    private fun discardAfterFailure() {
        resultSet = null
        executed = false
        stale = true
    }

    /** 把废弃的底层语句换成一条新的（只有 [stale] 时才有动作）。 */
    private fun renewIfStale() {
        if (!stale) return
        runCatching { statement.close() }
        statement = connection.prepareStatement(sql)
        stale = false
    }

    /**
     * 诊断输出：默认只在 `-Dclipper.driver.debug=true` 时打，[always] 为真时无条件打。
     *
     * [always] 留给「本该不可能发生、但一旦发生就意味着写路径全废」的场合——那种现场必须留下来，
     * 否则下一次只看到一句 `cannot start a transaction within a transaction`，无从下手。
     */
    private fun reportFailure(t: SQLException, always: Boolean = false) {
        if (!always && !driverDebug) return
        println("[clipper-driver] 执行失败: ${sql.replace('\n', ' ').take(140)} -> ${t.message}")
        println("[clipper-driver] 该连接上活的语句：")
        println(state.describe())
        println("[clipper-driver] 最近动作：")
        println(state.historyDump())
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

/**
 * 这条报错是不是「连接上已经有一条没结束的事务」。
 *
 * 对嵌套 `BEGIN`，SQLite 的原话就是 `cannot start a transaction within a transaction`，驱动把它
 * 包成 `[SQLITE_ERROR] SQL error or missing database (cannot start a transaction within a transaction)`。
 * 判据只能是这段文本：SQL 层面没有别的办法问出「连接在不在事务里」。
 */
private fun SQLException.isLeftoverTransaction(): Boolean =
    message?.contains(LEFTOVER_TRANSACTION_ERROR) == true

/** 这条报错是不是「连接上还有语句在跑」。见 [STATEMENTS_IN_PROGRESS_ERROR]。 */
private fun SQLException.isStatementsInProgress(): Boolean =
    message?.contains(STATEMENTS_IN_PROGRESS_ERROR) == true
