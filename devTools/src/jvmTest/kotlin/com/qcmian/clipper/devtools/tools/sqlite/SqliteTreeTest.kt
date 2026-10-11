package com.qcmian.clipper.devtools.tools.sqlite

import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 数据库对象树的组装逻辑。
 *
 * 有意的纯函数测试：树的形状（谁挂在谁下面、哪一层可展开）不依赖数据库，只依赖「有哪些对象、
 * 哪些子节点已加载」，因此这里直接喂数据断言，比通过界面去看靠得住。
 */
class SqliteTreeTest {

    @Test
    fun buildsDatabaseSchemaTableHierarchy() {
        val objects = listOf(
            SqliteObject("clips", SqliteObjectType.TABLE, "", "CREATE TABLE clips (id INTEGER)"),
            SqliteObject("recent", SqliteObjectType.VIEW, "", "CREATE VIEW recent AS SELECT * FROM clips"),
            SqliteObject("idx_body", SqliteObjectType.INDEX, "clips", "CREATE INDEX idx_body ON clips (body)"),
        )

        val root = buildSqliteTree("clipper.db", objects, emptyMap()).single()
        assertEquals("clipper.db", root.label)
        assertTrue(root.expandable)

        val schema = root.children.single()
        assertEquals("main", schema.label)
        // 只声明的对象类型才成组：这里是表 / 视图 / 索引三组，触发器组不该凭空出现。
        assertEquals(listOf("表 1", "视图 1", "索引 1"), schema.children.map { it.label })

        val tableNode = schema.children.first().children.single()
        assertEquals("clips", tableNode.label)
        assertTrue(tableNode.expandable)
        // 还没展开过，子节点为空——懒加载的第一层。
        assertTrue(tableNode.children.isEmpty())
    }

    @Test
    fun loadedChildrenAppearUnderTheObject() {
        val objects = listOf(SqliteObject("clips", SqliteObjectType.TABLE, "", null))
        val children = buildObjectChildren(
            parentKey = objectKey(objects.single()),
            columns = listOf(
                SqliteColumn("id", "INTEGER", notNull = false, primaryKey = true, defaultValue = null),
                SqliteColumn("body", "TEXT", notNull = true, primaryKey = false, defaultValue = null),
            ),
            foreignKeys = listOf(SqliteForeignKey(listOf("pid"), "parent", listOf("id"))),
            indexes = listOf(SqliteIndex("idx_body", unique = false, columns = listOf("body"))),
        )

        val tree = buildSqliteTree("clipper.db", objects, mapOf(objectKey(objects.single()) to children))
        val tableNode = tree.single().children.single().children.single().children.single()
        assertEquals("clips", tableNode.label)
        assertEquals(listOf("列 2", "键 1", "索引 1"), tableNode.children.map { it.label })

        val columns = tableNode.children.first().children
        assertEquals(listOf("id", "body"), columns.map { it.label })
        assertEquals(listOf("INTEGER", "TEXT"), columns.map { it.detail })
        // 图标按约束分：`id` 是主键（钥匙），`body` 落在 `idx_body` 索引里（放大镜）。
        assertEquals(ClipperIconKind.KEY, columns.first().icon)
        assertEquals(ClipperIconKind.SEARCH, columns.last().icon)
    }

    @Test
    fun columnIconsFollowConstraints() {
        val children = buildObjectChildren(
            parentKey = "obj:TABLE:clips",
            columns = listOf(
                SqliteColumn("id", "INTEGER", notNull = true, primaryKey = true, defaultValue = null),
                SqliteColumn("parentId", "INTEGER", notNull = false, primaryKey = false, defaultValue = null),
                SqliteColumn("body", "TEXT", notNull = false, primaryKey = false, defaultValue = null),
                SqliteColumn("plain", "TEXT", notNull = false, primaryKey = false, defaultValue = null),
            ),
            foreignKeys = listOf(SqliteForeignKey(listOf("parentId"), "clips", listOf("id"))),
            indexes = listOf(SqliteIndex("idx_body", unique = false, columns = listOf("body"))),
        )

        // 主键 > 外键 > 索引 > 普通；每一列都有图标（不像从前只有主键有，左缘因此会参差）。
        assertEquals(
            listOf(
                ClipperIconKind.KEY,
                ClipperIconKind.LINK,
                ClipperIconKind.SEARCH,
                ClipperIconKind.COLUMN,
            ),
            children.first().children.map { it.icon },
        )
    }

    @Test
    fun viewsHaveNoKeysOrIndexes() {
        val children = buildObjectChildren(
            parentKey = "obj:VIEW:recent",
            columns = listOf(SqliteColumn("id", "INTEGER", notNull = false, primaryKey = false, defaultValue = null)),
            foreignKeys = emptyList(),
            indexes = emptyList(),
        )
        assertEquals(listOf("列 1"), children.map { it.label })
    }

    @Test
    fun objectKeyRoundTrips() {
        val obj = SqliteObject("clips", SqliteObjectType.TABLE, "", null)
        assertEquals("obj:TABLE:clips", objectKey(obj))
        assertEquals(obj, sqliteObjectOf("obj:TABLE:clips", listOf(obj)))
        // 分组、列这类节点不是对象，还原不出来。
        assertNull(sqliteObjectOf("group:TABLE", listOf(obj)))
    }
}
