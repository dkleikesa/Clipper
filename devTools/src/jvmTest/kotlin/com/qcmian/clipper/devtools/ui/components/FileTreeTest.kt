package com.qcmian.clipper.devtools.ui.components

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 文件系统树适配器：列一层、目录在前、可展开标记正确。 */
class FileTreeTest {

    @Test
    fun listsDirectoriesBeforeFiles() {
        val root = File.createTempFile("clipper-tree", "").apply {
            delete()
            mkdirs()
        }
        try {
            File(root, "b.txt").writeText("hello")
            File(root, "a-dir").mkdirs()

            val nodes = directoryChildNodes(root.absolutePath)
            assertEquals(listOf("a-dir", "b.txt"), nodes.map { it.label })
            assertTrue(nodes.first().expandable)
            assertFalse(nodes.last().expandable)
            // 文件带一个大小注记，目录不带。
            assertTrue(nodes.last().detail.orEmpty().endsWith("B"))
            assertEquals(null, nodes.first().detail)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun missingDirectoryYieldsNothing() {
        assertTrue(directoryChildNodes("/definitely/not/a/real/clipper-dir").isEmpty())
    }
}
