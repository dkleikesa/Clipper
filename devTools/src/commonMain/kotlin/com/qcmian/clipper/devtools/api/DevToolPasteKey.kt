package com.qcmian.clipper.devtools.api

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * 窗口层接到的**粘贴**按键交给谁。
 *
 * 为什么不在输入区自己接：按键只发给**焦点路径**上的节点。文本框正拿着焦点时，输入区外层那圈
 * `onPreviewKeyEvent` 收得到 `⌘V`；可来源卡片（载入的文件 / 图片）一上来，文本框就没了，内容区
 * 里一个焦点节点都不剩——按键直接落空，「已经载入一张图，再粘个文件替换它」就是这么失效的。
 *
 * 这正是 `ClipperDevToolsWindow` 把 `⌘W` / `Esc` / `⌘B` 放在**窗口层**而不是内容根节点上的原因
 * （那里写得很清楚：靠焦点链会让按键时灵时不灵）。粘贴走同一条路：按键在窗口层接住，处理交给
 * **当前工具的输入区**——只有它知道剪贴板里的文件 / 图片该怎么安置。
 *
 * 输入区在组合时把处理函数登记进来（一屏只有一个输入区，后登记的胜出），窗口层问它「这次粘贴
 * 你接不接」：接了就把按键吞掉，不接就交回系统（普通文本粘贴照常走文本框）。
 */
class DevToolPasteKey {

    private var handler: (() -> Boolean)? = null

    /**
     * 问当前输入区接不接这次粘贴；接了返回 `true`（窗口层据此吞掉这次按键）。
     *
     * 没有人登记、或登记的那一份说「不接」时返回 `false`。
     */
    fun handle(): Boolean = handler?.invoke() == true

    /**
     * 输入区登记自己的处理函数；返回注销句柄。
     *
     * 注销时只认**自己登记的那一个**：换工具、卡片与文本框来回切都可能让新的一份先登记上，
     * 旧的那一份随后卸载时不能把新的顺手抹掉。
     */
    internal fun register(handler: () -> Boolean): () -> Unit {
        this.handler = handler
        return { if (this.handler === handler) this.handler = null }
    }
}

/**
 * 窗口层提供的粘贴入口。
 *
 * 没人提供时（离屏渲染、别处的窗口宿主）为 `null`，输入区退回自己那层按键拦截——那条要求输入区
 * 里有焦点节点，只够兜底。
 */
val LocalDevToolPasteKey = staticCompositionLocalOf<DevToolPasteKey?> { null }

/**
 * 这次按键是不是「粘贴」：`⌘V`（macOS）或 `⌃V`（其余平台）。
 *
 * 窗口层与输入区共用同一条判据，两处各写一遍早晚会分叉（一处认 `⌃V`、另一处不认）。
 */
fun KeyEvent.isPasteShortcut(): Boolean =
    type == KeyEventType.KeyDown &&
        key == Key.V &&
        (isMetaPressed || isCtrlPressed)
