package com.qcmian.clipper.devtools.tools.sqlite

import com.qcmian.clipper.core.ui.components.TreeNode
import com.qcmian.clipper.core.ui.icons.ClipperIconKind

/**
 * 把数据库结构组装成通用树控件（`TreeView`）认得的一棵树。
 *
 * 形状照数据浏览器里常见的那副来：**库 → main → 表 / 视图 / 索引 / 触发器 → 列 / 键 / 索引**。
 * 表与视图底下那一层（列 / 键 / 索引）是**懒加载**的——展开哪张表才去查哪张表，因此这里只负责
 * 把**已经拿到的**子节点挂上去（`childrenCache`），取数据的事留给界面。
 *
 * 全是纯函数：喂进去「有哪些对象、哪些子节点已经拿到」，吐出来一棵 [TreeNode] 树。因此
 * 「折叠时藏了哪几枝、展开了插回哪里」这类容易出错又难看出问题的逻辑可以脱离数据库直接单测。
 */

/** 根节点的 key。 */
internal const val SQLITE_ROOT_KEY = "db"

/** `main` schema 节点的 key。 */
internal const val SQLITE_SCHEMA_KEY = "schema:main"

/** 默认展开的那几个节点：库、schema、表分组——「打开就想看表」是常见诉求。 */
internal val SqliteDefaultExpandedKeys: Set<String> = setOf(
    SQLITE_ROOT_KEY,
    SQLITE_SCHEMA_KEY,
    groupKey(SqliteObjectType.TABLE),
)

/** 「表 / 视图 / …」分组节点的 key。 */
internal fun groupKey(type: SqliteObjectType): String = "group:${type.name}"

/** 一个表 / 视图 / 索引 / 触发器节点的 key。表与视图据此还能还原回 [SqliteObject]。 */
internal fun objectKey(obj: SqliteObject): String = "obj:${obj.type.name}:${obj.name}"

/** 从一个节点 key 还原出它对应的对象；不是对象节点（分组、列…）时返回 `null`。 */
internal fun sqliteObjectOf(key: String, objects: List<SqliteObject>): SqliteObject? {
    if (!key.startsWith("obj:")) return null
    return objects.firstOrNull { objectKey(it) == key }
}

/**
 * 组一棵库的树。
 *
 * @param databaseName 根节点上显示的名字，通常是库文件名。
 * @param objects 库里的全部对象（来自 [SqliteSession.objects]）。
 * @param childrenCache 已加载的表 / 视图子节点，键是对象节点的 [objectKey]。
 */
internal fun buildSqliteTree(
    databaseName: String,
    objects: List<SqliteObject>,
    childrenCache: Map<String, List<TreeNode>>,
): List<TreeNode> {
    val grouped = objects.groupBy { it.type }
    val groups = SqliteObjectType.entries.mapNotNull { type ->
        val items = grouped[type].orEmpty()
        if (items.isEmpty()) return@mapNotNull null
        TreeNode(
            key = groupKey(type),
            label = "${type.label} ${items.size}",
            icon = ClipperIconKind.FOLDER,
            expandable = true,
            children = items.map { objectNode(it, childrenCache) },
        )
    }
    return listOf(
        TreeNode(
            key = SQLITE_ROOT_KEY,
            label = databaseName.ifBlank { "database" },
            icon = ClipperIconKind.DATABASE,
            expandable = true,
            children = listOf(
                TreeNode(
                    key = SQLITE_SCHEMA_KEY,
                    label = "main",
                    icon = ClipperIconKind.FOLDER,
                    expandable = true,
                    children = groups,
                ),
            ),
        ),
    )
}

/**
 * 组合一张表（或视图）展开后看到的子节点：列 / 键 / 索引三组，各自只在自己非空时出现。
 *
 * 三组都是**一次性**给全的（列、外键、索引在同一次查询里取完）：它们本来就来自同一次「展开
 * 这张表」的动作，分三次加载只会让展开的动作看起来一顿一顿的。
 */
internal fun buildObjectChildren(
    parentKey: String,
    columns: List<SqliteColumn>,
    foreignKeys: List<SqliteForeignKey>,
    indexes: List<SqliteIndex>,
): List<TreeNode> = buildList {
    if (columns.isNotEmpty()) {
        // 列的图标按**约束**分：主键 / 外键 / 索引 / 普通。一列可能同时命中多条（主键自带索引、
        // 外键列也常被建索引），按下述优先级只取一个——越具体的越靠前。
        val foreignKeyColumns = foreignKeys.flatMapTo(mutableSetOf()) { it.fromColumns }
        val indexedColumns = indexes.flatMapTo(mutableSetOf()) { it.columns }
        add(
            group(
                key = "$parentKey:columns",
                label = "列 ${columns.size}",
                children = columns.map { column ->
                    TreeNode(
                        key = "$parentKey:column:${column.name}",
                        label = column.name,
                        // 声明类型：SQLite 允许不写（`CREATE TABLE t(a)`），那种给一个占位符，
                        // 免得一行右边空着让人以为没读到。
                        detail = column.declaredType.ifBlank { "—" },
                        icon = when {
                            column.primaryKey -> ClipperIconKind.KEY
                            column.name in foreignKeyColumns -> ClipperIconKind.LINK
                            column.name in indexedColumns -> ClipperIconKind.SEARCH
                            else -> ClipperIconKind.COLUMN
                        },
                    )
                },
            )
        )
    }
    if (foreignKeys.isNotEmpty()) {
        add(
            group(
                key = "$parentKey:keys",
                label = "键 ${foreignKeys.size}",
                children = foreignKeys.map { key ->
                    TreeNode(
                        key = "$parentKey:key:${key.fromColumns.joinToString(",")}",
                        label = key.fromColumns.joinToString(", "),
                        detail = "→ ${key.toTable}",
                        icon = ClipperIconKind.KEY,
                    )
                },
            )
        )
    }
    if (indexes.isNotEmpty()) {
        add(
            group(
                key = "$parentKey:indexes",
                label = "索引 ${indexes.size}",
                children = indexes.map { index ->
                    TreeNode(
                        key = "$parentKey:index:${index.name}",
                        label = index.name,
                        // 唯一索引用 `UNIQUE` 标明；普通索列举出它覆盖的列。
                        detail = if (index.unique) "UNIQUE" else index.columns.joinToString(", "),
                        icon = ClipperIconKind.SEARCH,
                    )
                },
            )
        )
    }
}

private fun objectNode(obj: SqliteObject, cache: Map<String, List<TreeNode>>): TreeNode {
    val key = objectKey(obj)
    // 只有表与视图能再往下展开（列 / 键 / 索引）；索引与触发器是叶子。
    val expandable = obj.type == SqliteObjectType.TABLE || obj.type == SqliteObjectType.VIEW
    return TreeNode(
        key = key,
        label = obj.name,
        icon = when (obj.type) {
            SqliteObjectType.TABLE -> ClipperIconKind.TABLE
            SqliteObjectType.VIEW -> ClipperIconKind.TYPE_FILE
            SqliteObjectType.INDEX -> ClipperIconKind.SEARCH
            SqliteObjectType.TRIGGER -> ClipperIconKind.SWAP
        },
        expandable = expandable,
        children = cache[key].orEmpty(),
    )
}

/** 「列 / 键 / 索引」这类中间分组：同一个长相，只在参数上差一点。 */
private fun group(key: String, label: String, children: List<TreeNode>): TreeNode = TreeNode(
    key = key,
    label = label,
    icon = ClipperIconKind.FOLDER,
    expandable = true,
    children = children,
)
