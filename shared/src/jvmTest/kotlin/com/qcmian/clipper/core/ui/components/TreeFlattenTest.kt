package com.qcmian.clipper.core.ui.components

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `flattenTree` 的单测。
 *
 * 这一层出错在界面上只表现为「少了几行」或「缩进错一级」，肉眼很难发现，因此对着断言逐级核对：
 * 折叠藏起整枝、展开按序插回、缩进随层级递增。
 */
class TreeFlattenTest {

    @Test
    fun collapsedNodesHideTheirSubtrees() {
        val tree = listOf(node("a", node("a1"), node("a2")), node("b"))
        assertEquals(listOf("a", "b"), flattenTree(tree, emptySet()).map { it.node.key })
    }

    @Test
    fun expandingInsertsChildrenAfterTheParent() {
        val tree = listOf(node("a", node("a1", node("a1x")), node("a2")), node("b"))
        val rows = flattenTree(tree, setOf("a"))
        assertEquals(listOf("a", "a1", "a2", "b"), rows.map { it.node.key })
        assertEquals(listOf(0, 1, 1, 0), rows.map { it.depth })
    }

    @Test
    fun deeperExpansionRecurses() {
        val tree = listOf(node("a", node("a1", node("a1x"))))
        val rows = flattenTree(tree, setOf("a", "a1"))
        assertEquals(listOf("a", "a1", "a1x"), rows.map { it.node.key })
        assertEquals(listOf(0, 1, 2), rows.map { it.depth })
    }

    /** 展开了叶子（没有子节点）：不额外出行，也不该崩——懒加载中与真·叶子共用这一条。 */
    @Test
    fun expandingALeafAddsNothing() {
        val tree = listOf(node("a", node("a1")))
        assertEquals(listOf("a", "a1"), flattenTree(tree, setOf("a", "a1")).map { it.node.key })
    }

    private fun node(key: String, vararg children: TreeNode) =
        TreeNode(key = key, label = key, children = children.toList())
}
