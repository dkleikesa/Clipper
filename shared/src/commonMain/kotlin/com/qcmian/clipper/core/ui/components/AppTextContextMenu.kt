package com.qcmian.clipper.core.ui.components

import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.contextMenuOpenDetector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.LocalTextContextMenu
import androidx.compose.foundation.text.TextContextMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow

/**
 * 文本输入框（`BasicTextField`）的右键菜单：把平台默认那套换成本应用自己的长相。
 *
 * 桌面端 Compose 会给每个 `BasicTextField` 挂一份上下文菜单（[LocalTextContextMenu]），默认那份是
 * 平台自己画的——白底、与这套中性灰主题不是一家人，和主面板 / 代码框那两份也对不上。这里提供同一
 * 接口的另一个实现：菜单**内容**仍取自平台给的 [TextContextMenu.TextManager]（剪切 / 复制 / 粘贴 /
 * 全选，以及它们此刻可不可用），画出来的却是 [AppContextMenu]，于是三处同一副长相、同一份实现。
 *
 * 内容交给平台算，是为了不把「有没有选区」「剪贴板里有没有文本」「是不是已经全选」这些判断抄一遍：
 * 每一项是 `null` 就表示这一课压根无从谈起（平台那套此时不显示它），`enabled` 为假则画淡、点不动。
 *
 * 装在**主题那一层**（见 `ClipperTheme`）：四个窗口都从那里过，写一次就够，应用里所有文本输入框
 * 一起换掉。
 */
@OptIn(ExperimentalFoundationApi::class)
object AppTextContextMenu : TextContextMenu {

    @Composable
    override fun Area(
        textManager: TextContextMenu.TextManager,
        state: ContextMenuState,
        content: @Composable () -> Unit,
    ) {
        // 菜单开在哪：指针位置（相对这一块）＋ 这一块在窗口里的原点，与代码框那份同一套算法。
        var menuAt by remember { mutableStateOf<Offset?>(null) }
        var windowOrigin by remember { mutableStateOf(Offset.Zero) }

        Box(
            // 撑开与平台那份一致：它也是 `propagateMinConstraints`，少这一条会改掉输入框的测量约束。
            modifier = Modifier
                .onGloballyPositioned { windowOrigin = it.positionInWindow() }
                // 右键探测器用平台那一个：它认的正是「次级键按下」，并且会把这一次按下吃掉
                // （不然输入框自己还会顺手把光标挪过去）。
                .contextMenuOpenDetector { menuAt = it },
            propagateMinConstraints = true,
        ) {
            content()

            menuAt?.let { position ->
                val entries = textContextMenuEntries(textManager) { menuAt = null }
                if (entries.isEmpty()) {
                    // 四项都无从谈起（没有选区、剪贴板里没有文本、已经全选）：收掉，别弹一个空壳。
                    // 平台那份也是这么做的（`SideEffect { session.close() }`）。
                    SideEffect { menuAt = null }
                } else {
                    AppContextMenu(
                        at = position,
                        anchor = windowOrigin,
                        onDismiss = { menuAt = null },
                        entries = entries,
                    )
                }
            }
        }
    }
}

/**
 * 菜单那四行。
 *
 * 文案与键位提示写死中文：应用里没有第二套语言，其余菜单（主面板、代码框）也都是这么写的。键位与
 * `BasicTextField` 自己的一致（`⌘X` / `⌘C` / `⌘V` / `⌘A`）。
 */
@OptIn(ExperimentalFoundationApi::class)
private fun textContextMenuEntries(
    textManager: TextContextMenu.TextManager,
    onDone: () -> Unit,
): List<AppContextMenuEntry> {
    fun entry(
        title: String,
        shortcut: String,
        action: TextContextMenu.Action?,
    ): AppContextMenuAction? = action?.let {
        AppContextMenuAction(title = title, shortcut = shortcut, enabled = it.enabled) {
            onDone()
            it.execute()
        }
    }

    return listOfNotNull(
        entry("剪切", "⌘X", textManager.cut),
        entry("复制", "⌘C", textManager.copy),
        entry("粘贴", "⌘V", textManager.paste),
        entry("全选", "⌘A", textManager.selectAll),
    )
}
