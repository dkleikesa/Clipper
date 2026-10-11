package com.qcmian.clipper.devtools.tools.sqlite

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException
import org.sqlite.SQLiteConfig

/**
 * SQLite 工具的平台实现：JDBC。
 *
 * 复用 `shared` 装的那颗驱动（`io.github.willena:sqlite-jdbc`，SQLite3MultipleCiphers 分支），
 * 因此**明文库与加密库走同一套代码**——加密靠连接构造时的 `PRAGMA cipher` / `PRAGMA key`
 * 注入，与 `shared` 的 `SqlCipherDriver` 完全一致（方案与密钥都必须给，只给 key 会用驱动的
 * 默认算法，与显式 sqlcipher 建出来的库对不上）。
 *
 * 这一层与 `shared` 的驱动**刻意不复用**那份实现：那个是 Room 的 `SQLiteDriver` 适配层，
 * 处理的是「语句缓存 + 事务分层」那一套（见 `SqlCipherDriver` 的长注释），而这里要的是
 * 「一把连接、顺着读几行」——把它拉过来只会背上一个用不上的状态机。
 */

/** 建库 / 开库一致的加密方案。必须与建库时相同，否则用对的口令也会报 `file is not a database`。 */
private const val SCHEME = "sqlcipher"

private const val JDBC_DRIVER_CLASS = "org.sqlite.JDBC"

/** 明文 SQLite 库的固定文件头（16 字节，含结尾的 NUL）。 */
private val PLAINTEXT_HEADER = "SQLite format 3\u0000".encodeToByteArray()

/** BLOB 在表格里最多预览多少字节；再多就不是「看一眼」，而是把界面拖死。 */
private const val BLOB_PREVIEW_BYTES = 64

internal actual fun openSqliteSession(
    path: String,
    passphrase: String?,
    readOnly: Boolean,
): SqliteSession {
    val file = File(path)
    if (!file.isFile) throw SqliteError("找不到文件：$path")

    // 文件头判断是不是加密库：标准头是明文，别的都是密文（随机盐值）。**开库之前**就要知道，
    // 因为加密库没口令根本打不开。
    val encrypted = !file.readHeader().contentEquals(PLAINTEXT_HEADER)
    if (encrypted && passphrase.isNullOrEmpty()) {
        throw SqliteError("这是一个加密库，需要口令才能打开", needsPassphrase = true)
    }

    Class.forName(JDBC_DRIVER_CLASS)
    val config = SQLiteConfig()
    if (encrypted) {
        config.setPragma(SQLiteConfig.Pragma.CIPHER, SCHEME)
        config.setPragma(SQLiteConfig.Pragma.KEY, requireNotNull(passphrase))
    }

    val connection = try {
        DriverManager.getConnection("jdbc:sqlite:$path", config.toProperties())
    } catch (e: SQLException) {
        throw SqliteError(
            message = if (encrypted) "口令不正确，或这不是一个加密库" else "打不开这个文件：${e.message}",
            needsPassphrase = encrypted,
        )
    }

    // `PRAGMA key` 即使口令错也「成功」——真正的失败要到第一次读页才出现。读一下 sqlite_master
    // 当探针，只有它成功才算口令正确（与 `verifyDatabasePassphrase` 同一套判据）。
    try {
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT count(*) FROM sqlite_master").use { it.next() }
        }
    } catch (e: SQLException) {
        connection.closeQuietly()
        throw SqliteError(
            message = if (encrypted) "口令不正确" else "这不是一个 SQLite 数据库文件",
            needsPassphrase = encrypted,
        )
    }

    if (readOnly) {
        // 只读用 SQL 层的 `query_only`：连接仍以读写模式打开（加密库没有别的打开姿势），
        // 但此后任何写语句都会被 SQLite 拒绝。这是工具默认的姿态——打开的是用户的真实库。
        runCatching { connection.createStatement().use { it.execute("PRAGMA query_only = ON") } }
    }

    return JdbcSqliteSession(connection, path, encrypted, readOnly)
}

/** 读文件开头这 16 字节；读不满（空文件、刚建的库）时返回空数组，按明文处理。 */
private fun File.readHeader(): ByteArray {
    val header = ByteArray(PLAINTEXT_HEADER.size)
    val read = inputStream().use { it.read(header) }
    return if (read < header.size) ByteArray(0) else header
}

private fun Connection.closeQuietly() {
    runCatching { close() }
}

private class JdbcSqliteSession(
    private val connection: Connection,
    override val path: String,
    override val encrypted: Boolean,
    override val readOnly: Boolean,
) : SqliteSession {

    private var closed = false

    override fun objects(): List<SqliteObject> {
        // `sqlite_autoindex_*` 是 SQLite 为 UNIQUE / 主键自动建的索引：既没有建语句，名字也是自动
        // 生成的，列出来只会添乱。其余 `sqlite_*` 元数据表（`sqlite_sequence` 等）保留——它们是
        // 真实存在的表，能点开看数据。
        val sql = """
            SELECT type, name, tbl_name, sql FROM sqlite_master
            WHERE type IN ('table', 'view', 'index', 'trigger')
              AND name NOT LIKE 'sqlite_autoindex_%'
        """.trimIndent()
        val objects = queryText(sql).mapNotNull { row ->
            val type = objectTypeOf(row[0] ?: return@mapNotNull null) ?: return@mapNotNull null
            SqliteObject(
                name = row[1].orEmpty(),
                type = type,
                table = row[2].orEmpty(),
                sql = row[3],
            )
        }

        // `sqlite_master` **不列在它自己里**（那张表只记用户对象），所以上面那条查不到它；而它偏偏
        // 是看库结构时最该能点开的一张表——所有对象的定义都在里面。单独补一条：它的列由
        // `PRAGMA table_info` 给出，建表语句 SQLite 不对外提供，留 `null`。
        val master = SqliteObject("sqlite_master", SqliteObjectType.TABLE, "", null)
        val all = if (objects.any { it.name == master.name }) objects else objects + master

        // 分组顺序（表→视图→索引→触发器）由枚举顺序保证，组内按名字。
        return all.sortedWith(compareBy({ it.type.ordinal }, { it.name }))
    }

    override fun schema(name: String): SqliteSchema? {
        // `sqlite_master` 这类内部表**不在 `sqlite_master` 里**，查不到它们的元信息；但列信息照样
        // 能从 `PRAGMA table_info` 拿到，因此按「表」处理，只是没有建表语句可显示。
        val internal = name.startsWith("sqlite_")
        val meta = if (internal) {
            null
        } else {
            queryText(
                "SELECT type, sql FROM sqlite_master WHERE name = ? AND type IN ('table', 'view')",
                listOf(name),
            ).firstOrNull() ?: return null
        }
        val type = if (meta == null) SqliteObjectType.TABLE else objectTypeOf(meta[0].orEmpty()) ?: return null
        val ddl = meta?.get(1)
        val columns = queryText("PRAGMA table_info(${quoteIdentifier(name)})").map { row ->
            SqliteColumn(
                name = row[1].orEmpty(),
                declaredType = row[2].orEmpty(),
                notNull = (row[3]?.toIntOrNull() ?: 0) != 0,
                primaryKey = (row[5]?.toIntOrNull() ?: 0) != 0,
                defaultValue = row[4],
            )
        }
        return SqliteSchema(
            name = name,
            type = type,
            columns = columns,
            ddl = ddl,
            withoutRowId = ddl?.contains("WITHOUT ROWID", ignoreCase = true) == true,
        )
    }

    override fun count(table: String): Long =
        queryText("SELECT count(*) FROM ${quoteIdentifier(table)}").firstOrNull()?.firstOrNull()
            ?.toLongOrNull() ?: 0L

    override fun indexes(table: String): List<SqliteIndex> =
        // `origin` 有三种：`c`（CREATE INDEX 建的）、`u`（UNIQUE 约束自动建的）、`pk`（主键自动建的）。
        // `pk` 那条没有名字、用户也改不了，过滤掉；`u` 留着——它是唯一约束，看数据时有用。
        queryText("PRAGMA index_list(${quoteIdentifier(table)})").mapNotNull { row ->
            val name = row[1] ?: return@mapNotNull null
            if (row[3] == "pk") return@mapNotNull null
            SqliteIndex(
                name = name,
                unique = (row[2]?.toIntOrNull() ?: 0) != 0,
                columns = queryText("PRAGMA index_info(${quoteIdentifier(name)})").mapNotNull { it[2] },
            )
        }

    override fun foreignKeys(table: String): List<SqliteForeignKey> {
        // `PRAGMA foreign_key_list` 的列是 `id, seq, table, from, to, on_update, on_delete, match`。
        // 复合外键会拆成同一个 `id` 下的多行，因此先按 `id` 归组再合成一条。
        val rows = queryText("PRAGMA foreign_key_list(${quoteIdentifier(table)})")
        return rows.groupBy { it[0].orEmpty() }.values.map { group ->
            SqliteForeignKey(
                fromColumns = group.mapNotNull { it[3] },
                toTable = group.first()[2].orEmpty(),
                toColumns = group.mapNotNull { it[4] },
            )
        }
    }

    override fun page(table: String, limit: Int, offset: Int): SqliteRows =
        readRows("SELECT * FROM ${quoteIdentifier(table)} LIMIT ? OFFSET ?", listOf(limit, offset), limit)

    override fun query(sql: String, maxRows: Int): SqliteRows = readRows(sql, emptyList(), maxRows)

    override fun close() {
        if (closed) return
        closed = true
        connection.closeQuietly()
    }

    /** 跑一条**能给出结果集**的语句，全部读成字符串（供内部的结构查询用）。 */
    private fun queryText(sql: String, params: List<Any?> = emptyList()): List<List<String?>> =
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { resultSet -> drain(resultSet, Int.MAX_VALUE).rows }
        }

    /**
     * 跑任意一条语句并读结果。
     *
     * 用 `execute()` 而不是 `executeQuery()`：SQL 页签里既可能是一条 `SELECT` / `PRAGMA`，也可能
     * 被用户敲成一条写语句。写语句在只读连接上会被 SQLite 拒绝（异常照抛），成功时则给出受影响
     * 行数——两种结果都在同一个方法里交代，调用方不必先猜语句类型。
     */
    private fun readRows(sql: String, params: List<Any?>, maxRows: Int): SqliteRows =
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            if (statement.execute()) {
                statement.resultSet.use { drain(it, maxRows) }
            } else {
                SqliteRows(
                    columns = listOf("受影响行数"),
                    rows = listOf(listOf(statement.updateCount.toString())),
                    truncated = false,
                )
            }
        }

    private fun drain(resultSet: ResultSet, maxRows: Int): SqliteRows {
        val meta = resultSet.metaData
        val columnCount = meta.columnCount
        // 优先 `getColumnLabel`：带 `AS` 的查询有别名时，标签才是用户给的名字。
        val columns = (1..columnCount).map { index ->
            meta.getColumnLabel(index).ifBlank { meta.getColumnName(index) }
        }
        val rows = ArrayList<List<String?>>()
        var truncated = false
        while (resultSet.next()) {
            if (rows.size >= maxRows) {
                truncated = true
                break
            }
            rows += (1..columnCount).map { index -> formatValue(resultSet.getObject(index)) }
        }
        return SqliteRows(columns, rows, truncated)
    }
}

/** `sqlite_master.type` 的字面量 → 枚举；未知类型返回 `null`（调用方跳过）。 */
private fun objectTypeOf(raw: String): SqliteObjectType? = when (raw.lowercase()) {
    "table" -> SqliteObjectType.TABLE
    "view" -> SqliteObjectType.VIEW
    "index" -> SqliteObjectType.INDEX
    "trigger" -> SqliteObjectType.TRIGGER
    else -> null
}

/**
 * 把 JDBC 取出来的值写成显示文本：`NULL` → `null`，其余转字符串。
 *
 * BLOB 特殊处理成十六进制预览——直接把字节 `toString()` 会得到 `[B@1a2b3c` 这种没有任何信息
 * 的地址。截断到 [BLOB_PREVIEW_BYTES]，并在尾巴上标出真实大小。
 */
private fun formatValue(value: Any?): String? = when (value) {
    null -> null
    is ByteArray -> blobPreview(value)
    else -> value.toString()
}

private fun blobPreview(bytes: ByteArray): String {
    val shown = bytes.copyOf(minOf(bytes.size, BLOB_PREVIEW_BYTES))
    val hex = shown.joinToString(" ") { byte -> byte.toInt().and(0xFF).toString(16).padStart(2, '0') }
    return if (bytes.size > BLOB_PREVIEW_BYTES) "x'$hex' …（共 ${bytes.size} 字节）" else "x'$hex'"
}
