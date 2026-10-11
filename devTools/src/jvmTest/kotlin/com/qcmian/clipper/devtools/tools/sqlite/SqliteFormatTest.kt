package com.qcmian.clipper.devtools.tools.sqlite

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 纯逻辑的测试：SQL 转义、CSV、INSERT 导出、路径判定。
 *
 * 这些规则最容易写错（少转一个引号、CSV 里多一个逗号），又最不容易从界面上看出来，所以单测
 * 覆盖得细一些。它们不碰数据库，因此跑得很快。
 */
class SqliteFormatTest {

    @Test
    fun quotesIdentifiers() {
        assertEquals("\"clips\"", quoteIdentifier("clips"))
        // 关键字要能原样当标识符用。
        assertEquals("\"order\"", quoteIdentifier("order"))
        // 标识符里本来就有的双引号按 SQL 规则翻倍。
        assertEquals("\"a\"\"b\"", quoteIdentifier("a\"b"))
    }

    @Test
    fun csvLeavesPlainFieldsAlone() {
        val rows = SqliteRows(
            columns = listOf("name", "age"),
            rows = listOf(listOf("alice", "30")),
            truncated = false,
        )
        assertEquals("name,age\nalice,30\n", rows.toCsv())
    }

    @Test
    fun csvQuotesFieldsWithSpecials() {
        val rows = SqliteRows(
            columns = listOf("value"),
            rows = listOf(
                listOf("a,b"),
                listOf("say \"hi\""),
                listOf(""),
                listOf(null),
            ),
            truncated = false,
        )
        // 末尾那个换行来自 `appendLine`，去掉**它一个**后按行切开——空字段本身就是空行，
        // 既不能过滤（那会把它连同断言一起删掉），也不能 trimEnd（那会连空行前的分隔一起删）。
        val lines = rows.toCsv().removeSuffix("\n").split("\n")
        assertEquals(5, lines.size)
        assertEquals("value", lines[0])
        assertEquals("\"a,b\"", lines[1])
        assertEquals("\"say \"\"hi\"\"\"", lines[2])
        // 空串与 NULL 在 CSV 里同形——这是格式的限制，界面上另有 SQL 导出来补。
        assertEquals("", lines[3])
        assertEquals("", lines[4])
    }

    @Test
    fun csvKeepsNewlinesInsideQuotes() {
        val rows = SqliteRows(
            columns = listOf("value"),
            rows = listOf(listOf("line1\nline2")),
            truncated = false,
        )
        assertTrue(rows.toCsv().contains("\"line1\nline2\""))
    }

    @Test
    fun insertStatementsQuoteTableAndLiterals() {
        val rows = SqliteRows(
            columns = listOf("id", "body"),
            rows = listOf(listOf("1", "it's fine"), listOf("2", null)),
            truncated = false,
        )
        val sql = rows.toInsertStatements("clips")
        assertEquals(
            "INSERT INTO \"clips\" (\"id\", \"body\") VALUES ('1', 'it''s fine');\n" +
                "INSERT INTO \"clips\" (\"id\", \"body\") VALUES ('2', NULL);\n",
            sql,
        )
    }

    @Test
    fun detectsSqliteLikePaths() {
        assertTrue("/tmp/clipper.db".looksLikeSqlitePath())
        assertTrue("/tmp/data.SQLITE".looksLikeSqlitePath())
        assertTrue("notes.sqlite3".looksLikeSqlitePath())
        assertFalse("/tmp/photo.png".looksLikeSqlitePath())
        assertFalse("/tmp/database".looksLikeSqlitePath())
    }

    /** 表头与正文拆开写（导出整表要一页页往后追加），但合起来必须与 `toCsv` 一模一样。 */
    @Test
    fun csvHeaderAndBodyJoinBackIntoTheWhole() {
        val rows = SqliteRows(
            columns = listOf("a", "b"),
            rows = listOf(listOf("1", "x,y"), listOf(null, "")),
            truncated = false,
        )
        assertEquals("a,b", rows.toCsvHeaderLine())
        assertEquals("1,\"x,y\"\n,\n", rows.toCsvBody())
        assertEquals(rows.toCsvHeaderLine() + "\n" + rows.toCsvBody(), rows.toCsv())
    }

    /** 状态栏展示的那条：分页值内联，人一眼读得出在查第几页、每页多少行。 */
    @Test
    fun pageSqlInlinesPaging() {
        assertEquals(
            "SELECT * FROM \"clips\" LIMIT 200 OFFSET 400",
            sqlitePageSql(
                table = "clips",
                limit = 200,
                offset = 400,
                orderColumn = null,
                ascending = true,
                filter = null,
            ),
        )
    }

    @Test
    fun pageSqlCarriesOrderAndFilter() {
        assertEquals(
            "SELECT * FROM \"clips\" WHERE \"kind\" = 'text' ORDER BY \"created_at\" DESC LIMIT 200 OFFSET 0",
            sqlitePageSql(
                table = "clips",
                limit = 200,
                offset = 0,
                orderColumn = "created_at",
                ascending = false,
                filter = SqliteFilter("kind", "text"),
            ),
        )
    }

    /** 列名照转义、`NULL` 走 `IS NULL`——展示的语句与执行的那条得是同一个形状。 */
    @Test
    fun pageSqlQuotesIdentifiersAndNullFilter() {
        assertEquals(
            "SELECT * FROM \"a\"\"b\" WHERE \"col\" IS NULL LIMIT 50 OFFSET 100",
            sqlitePageSql(
                table = "a\"b",
                limit = 50,
                offset = 100,
                orderColumn = null,
                ascending = true,
                filter = SqliteFilter("col", null),
            ),
        )
    }
}
