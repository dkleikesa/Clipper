package com.qcmian.clipper.devtools.tools.sqlite

/**
 * SQLite 工具的数据模型：**纯数据 + 纯逻辑**，不含任何平台类型。
 *
 * 这一层刻意与 JDBC 无关：真正的驱动调用收在 `SqliteEngine`（`expect`）后面，模型只认
 * 「一个对象叫什么、一张表有哪些列、一次查询读出哪些行」。于是界面与格式化逻辑全部留在
 * commonMain、可以脱离数据库直接单测（见 `SqliteFormatTest`），平台实现也不必认识 Compose。
 */

/** `sqlite_master` 里的对象种类。顺序即侧边栏里的先后。 */
internal enum class SqliteObjectType(val label: String) {
    TABLE("表"),
    VIEW("视图"),
    INDEX("索引"),
    TRIGGER("触发器"),
}

/**
 * 数据库里的一个对象（一张表 / 一个视图 / 一条索引 / 一个触发器）。
 *
 * [sql] 是 `sqlite_master.sql` 的原文——`CREATE` 语句。**可以为 `null`**：SQLite 为
 * `UNIQUE` / 主键自动建出来的索引（`sqlite_autoindex_*`）没有建表语句。这类对象在本工具里
 * 一律过滤掉（见 [SqliteSession.objects]），但结构体本身仍按可空定义，免得哪天放开过滤时
 * 又要改这里。
 */
internal class SqliteObject(
    val name: String,
    val type: SqliteObjectType,
    /** 索引 / 触发器所属的表名；表与视图这里是空串。 */
    val table: String,
    /** 建这个对象时执行的那条语句；SQLite 内部对象没有，为 `null`。 */
    val sql: String?,
)

/** 一列。字段对应 `PRAGMA table_info` 的 `name` / `type` / `notnull` / `dflt_value` / `pk`。 */
internal class SqliteColumn(
    val name: String,
    /** 建表时声明的类型；SQLite 允许空（`CREATE TABLE t(a)`），此时为空串。 */
    val declaredType: String,
    val notNull: Boolean,
    val primaryKey: Boolean,
    val defaultValue: String?,
)

/**
 * 一张表 / 一个视图的结构。
 *
 * [withoutRowId] 只对表有意义：`WITHOUT ROWID` 表没有隐式的 `rowid` 列，按行定位要靠主键。
 * 第一版是只读浏览，用不到这个区别；先把它读出来，等做编辑时不必再加一次 `PRAGMA`。
 */
internal class SqliteSchema(
    val name: String,
    val type: SqliteObjectType,
    val columns: List<SqliteColumn>,
    val ddl: String?,
    val withoutRowId: Boolean,
)

/** 一条索引。[columns] 是它覆盖的列，按索引里的顺序。 */
internal class SqliteIndex(
    val name: String,
    val unique: Boolean,
    val columns: List<String>,
)

/** 一条外键：从 [fromColumns] 指到 [toTable] 的 [toColumns]。复合外键的多列合成一条。 */
internal class SqliteForeignKey(
    val fromColumns: List<String>,
    val toTable: String,
    val toColumns: List<String>,
)

/**
 * 一列上的等值筛选。
 *
 * 筛选**必须下推成 SQL**（`WHERE`），不能拉到内存里过滤：表可能有几十万行，而这里一次只取一页
 * ——在内存里筛「已加载的这一页」得到的结论是错的。
 *
 * [value] 为 `null` 表示「这一列是 NULL」：SQL 里没有 `= NULL` 这种写法，得走 `IS NULL`。
 */
internal class SqliteFilter(val column: String, val value: String?)

/**
 * 一次读取的结果。
 *
 * 每个格子已经是**显示文本**（`NULL` 为 `null`，其余见 `SqliteEngine` 的格式化），因此界面
 * 与导出不必再认识 JDBC 的值类型。行数由调用方给的上限约束，超出时 [truncated] 为真——
 * 大表不能一次全拉进内存。
 */
internal class SqliteRows(
    val columns: List<String>,
    val rows: List<List<String?>>,
    val truncated: Boolean,
)

/**
 * 打开数据库时给出的一句人话。参数正确但文件打不开（不是数据库、口令错）时用它。
 *
 * [needsPassphrase] 表示「再来一次，但这次带上口令」——界面据此把口令输入框露出来并聚焦，
 * 而不必去猜错误文案里有没有「口令」二字。
 */
internal class SqliteError(
    message: String,
    val needsPassphrase: Boolean = false,
) : Exception(message)

/**
 * 一个已经打开的 SQLite 连接。
 *
 * 生命周期与界面绑定：界面拿到一个实例后，换库 / 关窗都要 `close()`。实现方保证方法可重入
 * 之外**不保证线程安全**——调用方在后台线程上串行使用它（见 `SqliteDevTool` 里的状态机）。
 */
internal interface SqliteSession {

    /** 库文件路径，用于标题与状态栏。 */
    val path: String

    /** 库是不是加密库（文件头不是标准的 `SQLite format 3`）。 */
    val encrypted: Boolean

    /** 这条连接是不是只读的。 */
    val readOnly: Boolean

    /** 库里的对象，按「表 / 视图 / 索引 / 触发器」分组顺序排列，组内按名字。自动对象已过滤。 */
    fun objects(): List<SqliteObject>

    /** 一张表 / 一个视图的结构；[name] 不是表或视图时为 `null`。 */
    fun schema(name: String): SqliteSchema?

    /** 行数。[filter] 非空时只数命中的行。大表上这是全表扫描，调用方只该在需要精确计数时用。 */
    fun count(table: String, filter: SqliteFilter? = null): Long

    /**
     * 读一页数据。
     *
     * [orderColumn] / [ascending] 与 [filter] 都**下推给 SQLite**（`ORDER BY` / `WHERE`），而不是
     * 拉到内存里再排——大表上后者根本做不了，做出来也是错的（只排了当前这一页）。
     */
    fun page(
        table: String,
        limit: Int,
        offset: Int,
        orderColumn: String? = null,
        ascending: Boolean = true,
        filter: SqliteFilter? = null,
    ): SqliteRows

    /** 一张表上的索引（不含 SQLite 为主键自动建的那条——它没有名字，对用户也没有意义）。 */
    fun indexes(table: String): List<SqliteIndex>

    /** 一张表上的外键。 */
    fun foreignKeys(table: String): List<SqliteForeignKey>

    /**
     * 执行一条**查询**语句，最多读 [maxRows] 行。
     *
     * 读满 [maxRows] 就停下并把 [SqliteRows.truncated] 置真——不把整张结果集拉进内存。
     */
    fun query(sql: String, maxRows: Int): SqliteRows

    /** 让 JDBC 底层连接退出。重复调用应当无害。 */
    fun close()
}

/**
 * 打开一个 SQLite 文件。**平台相关**的那一步：JVM 上走带加密能力的 JDBC 驱动
 * （见 `SqliteEngine.jvm.kt`）。
 *
 * [readOnly] 为真时连接只读（写语句会失败），这是默认姿态——工具默认打开的是用户的真实库，
 * 误改的代价比「不能改」大得多。要编辑得由用户显式关掉只读开关，重新以读写打开一次。
 *
 * @param passphrase 加密库的口令；明文库传 `null`。口令错时抛 [SqliteError]。
 * @throws SqliteError 文件不存在、不是数据库、口令错、驱动加载失败。
 */
internal expect fun openSqliteSession(
    path: String,
    passphrase: String?,
    readOnly: Boolean,
): SqliteSession
