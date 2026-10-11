package com.qcmian.clipper.devtools.tools.sqlite

/**
 * SQLite 工具的纯逻辑：SQL 转义、分页常量、CSV 导出。
 *
 * 与 `SqliteModel` 同属 commonMain，因此这些规则能脱离数据库直接单测——它们是最容易写错、
 * 又最不容易从界面上看出来（少转一个引号、CSV 里多一个逗号）的部分。
 */

/**
 * 把标识符写成 SQL 里安全的形态：双引号包裹，内部的 `"` 翻倍。
 *
 * 表名 / 列名可能带空格、关键字（`order`）、甚至引号，直接拼进语句必然出错或注入。SQLite 的
 * 标准做法是双引号（`"order"`），内部双引号按 SQL 规则翻倍即可。
 */
internal fun quoteIdentifier(name: String): String =
    "\"" + name.replace("\"", "\"\"") + "\""

/**
 * `WHERE` 那一段。
 *
 * [inlineValue] 为真时把筛选值写成**字面量**（给人读），否则写成绑定参数 `?`（真正执行）。
 * 列名一律走 [quoteIdentifier]，所以带空格、带引号、甚至是关键字的列名都拼不坏；`IS NULL`
 * 单独一支，因为 SQL 里没有 `= NULL` 这种写法。
 */
internal fun sqliteFilterClause(filter: SqliteFilter?, inlineValue: Boolean = false): String {
    if (filter == null) return ""
    val column = quoteIdentifier(filter.column)
    return when {
        filter.value == null -> " WHERE $column IS NULL"
        inlineValue -> " WHERE $column = " + sqlLiteral(filter.value)
        else -> " WHERE $column = ?"
    }
}

/**
 * 「当前这一页」的查询语句，**给人读**：分页与筛选的值都内联成字面量。
 *
 * 真正执行的是绑定参数那一版（`LIMIT ? OFFSET ?`、`WHERE col = ?`），两者共用 [sqliteFilterClause]
 * 与同一套排序规则，结构必然一致，只在「值是否内联」上不同。状态栏要回答的是「我现在在查
 * 什么」——内联的写法一眼读得懂，`LIMIT ? OFFSET ?` 不行。
 */
internal fun sqlitePageSql(
    table: String,
    limit: Int,
    offset: Int,
    orderColumn: String?,
    ascending: Boolean,
    filter: SqliteFilter?,
): String = buildString {
    append("SELECT * FROM ").append(quoteIdentifier(table))
    append(sqliteFilterClause(filter, inlineValue = true))
    if (orderColumn != null) {
        append(" ORDER BY ").append(quoteIdentifier(orderColumn))
        // 方向是 SQL **语法**而不是值，绑不进参数——执行时也只按这两个常量拼。
        append(if (ascending) " ASC" else " DESC")
    }
    append(" LIMIT ").append(limit).append(" OFFSET ").append(offset)
}

/** SQLite 文件常见的扩展名。判定「剪贴板里的这个文件该不该自动打开」时用。 */
private val SQLITE_EXTENSIONS = listOf(".db", ".sqlite", ".sqlite3", ".db3")

/**
 * 路径看起来是个 SQLite 文件（按扩展名）。
 *
 * 只看扩展名而不读文件头：这个判断发生在**打开之前**（决定要不要自动载入），此时读文件头
 * 又要碰一次磁盘；而猜错的代价只是「没自动打开」，用户手动点一下即可。
 */
internal fun String.looksLikeSqlitePath(): Boolean {
    val name = substringAfterLast('/').lowercase()
    return SQLITE_EXTENSIONS.any { name.endsWith(it) }
}

/** 表数据一页读多少行。 */
internal const val SqlitePageSize = 200

/** SQL 查询一次最多读多少行（超出即截断）。 */
internal const val SqliteQueryRowLimit = 1_000

/**
 * 导出整表时一次读多少行。
 *
 * 比页面（[SqlitePageSize]）大一些：少跑几个来回；又远小于「一次读完整张表」——那一页会占掉
 * 可观的内存，而导出本来就是边读边写的，没必要囤。
 */
internal const val SqliteExportPageSize = 1_000

/**
 * 把结果导成 CSV（按 RFC 4180）。
 *
 * 两条规则：字段里出现逗号 / 双引号 / 换行时整格用双引号包起来，格内的双引号翻倍；`NULL`
 * 写成**空字段**（与空串同形——这是 CSV 本身的限制，导出成 SQL 才分得清，所以界面上另给
 * 「导出 SQL」这一路）。
 *
 * 统一用 `\n` 分行而不是 CRLF：Excel、Numbers、文本编辑器都认，而 CRLF 在文本编辑器里会
 * 多出一个不可见字符。
 */
internal fun SqliteRows.toCsv(): String = toCsvHeaderLine() + "\n" + toCsvBody()

/** CSV 的表头那一行（不含换行）。导出整表时它只写一次。 */
internal fun SqliteRows.toCsvHeaderLine(): String = columns.joinToString(",") { csvField(it) }

/**
 * CSV 的正文（不含表头），每行以 `\n` 结尾；空结果集得到空串。
 *
 * 与 [toCsv] 拆开是为了**导出整张表**：那要一页一页地读、一页一页地写，表头只能出现一次，
 * 而正文是按页往后追加的。
 */
internal fun SqliteRows.toCsvBody(): String = buildString {
    rows.forEach { row ->
        appendLine(row.joinToString(",") { csvField(it.orEmpty()) })
    }
}

private fun csvField(value: String): String {
    val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
    if (!needsQuotes) return value
    return "\"" + value.replace("\"", "\"\"") + "\""
}

/**
 * 把结果导成 `INSERT` 语句。首版只服务「把一张表的几行搬走」，因此不生成建表语句。
 *
 * `NULL` 写成关键字，文本里的单引号翻倍——这两条是 CSV 表达不了的，所以另给这一路。
 */
internal fun SqliteRows.toInsertStatements(table: String): String = buildString {
    val target = quoteIdentifier(table)
    rows.forEach { row ->
        append("INSERT INTO ").append(target).append(" (")
        append(columns.joinToString(", ") { quoteIdentifier(it) })
        append(") VALUES (")
        append(row.joinToString(", ") { sqlLiteral(it) })
        appendLine(");")
    }
}

/** 把一个显示值写成 SQL 字面量：`NULL` 走关键字，其余按文本处理（数字也会带上引号，SQLite 接受）。 */
private fun sqlLiteral(value: String?): String =
    if (value == null) "NULL" else "'" + value.replace("'", "''") + "'"
