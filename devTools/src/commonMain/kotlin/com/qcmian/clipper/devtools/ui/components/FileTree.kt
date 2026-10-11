package com.qcmian.clipper.devtools.ui.components

import com.qcmian.clipper.core.ui.components.TreeNode
import com.qcmian.clipper.core.ui.icons.ClipperIconKind
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * 文件系统侧的树适配器——`TreeView`「展示文件列表」这一半。
 *
 * 与数据库那一半（`SqliteTree.kt`）形状刻意一致：**只列一层**，目录节点标成可展开，展开时由
 * 调用方再对那个目录调一次本函数。于是文件树与数据库树共用同一套「展开即懒加载」的约定，
 * 控件（`core/ui/components/TreeView`）一行都不用为文件系统改动。
 *
 * 读目录会碰磁盘，因此调用方要放到后台线程上——这里不做线程调度，保持纯函数式的「给路径、
 * 还节点」，与控件那边的约定一致。
 */
internal fun directoryChildNodes(directory: String): List<TreeNode> {
    val children = runCatching { SystemFileSystem.list(Path(directory)) }.getOrNull().orEmpty()
    return children
        .mapNotNull { entry ->
            val metadata = runCatching { SystemFileSystem.metadataOrNull(entry) }.getOrNull()
                ?: return@mapNotNull null
            val name = entry.name
            if (name.isEmpty()) return@mapNotNull null
            val isDirectory = metadata.isDirectory
            TreeNode(
                key = entry.toString(),
                label = name,
                detail = if (isDirectory) null else humanSize(metadata.size),
                icon = if (isDirectory) ClipperIconKind.FOLDER else ClipperIconKind.TYPE_FILE,
                expandable = isDirectory,
            )
        }
        // 目录排在文件前面，各自按名字不分大小写排序——与系统文件浏览器一致。
        .sortedWith(compareByDescending<TreeNode> { it.expandable }.thenBy { it.label.lowercase() })
}

/** 把字节数写成人看的大小。文件树里只需要「大概多大」。 */
internal fun humanSize(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024L * 1024 -> "${bytes / 1024} KB"
    else -> "${bytes / (1024L * 1024)} MB"
}
