package com.qcmian.clipper.desktop.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import java.awt.Window

/**
 * 「按住自绘标题栏拖动整个窗口」的手势。
 *
 * 用「按下点 + 屏幕坐标」而不是累加窗口内的增量：笔触事件的坐标是**窗口内相对坐标**，而我们
 * 每挪一次窗口，同一个屏幕点对应的相对坐标就跟着变——增量要靠「窗口的移动晚于事件的投递」才
 * 凑得回来，一旦时序不同就会越拖越偏。屏幕坐标不受自己的移动影响，因此这里只记一次「按下时
 * 光标相对窗口左上角的偏移」，之后每次都按绝对位置摆放。
 *
 * 挂在标题栏里那个 `weight(1f)` 的容器上即可（见 `ClipperTitleBar` 的 [Modifier] 参数）——
 * 关闭按钮必须留在拖拽区之外，否则小幅移动会被判成拖动、点不中。
 */
@Composable
internal fun rememberTitleBarDragModifier(window: Window): Modifier = remember(window) {
    Modifier.pointerInput(window) {
        awaitEachGesture {
            // 找到「按下」的那一次事件，并记下光标相对窗口左上角的抓取偏移。
            //
            // 循环而不是直接取第一次事件：`awaitEachGesture` 只保证「上一轮手势已经结束」，
            // 块里的第一个事件可能只是一次悬停移动（没有任何按下的指针）。也不能先
            // `awaitFirstDown` 再回头找事件——`awtEventOrNull` 挂在 `PointerEvent` 上，
            // 而不是 `PointerInputChange` 上。
            var pressedId: PointerId? = null
            var grabX = 0
            var grabY = 0
            while (pressedId == null) {
                val event = awaitPointerEvent()
                val pressed = event.changes.firstOrNull { it.pressed } ?: continue
                // 非鼠标来源（触控 / 笔）没有 AWT 事件，也就没有屏幕坐标可用。
                val mouse = event.awtEventOrNull ?: return@awaitEachGesture
                val origin = window.locationOnScreen
                grabX = mouse.xOnScreen - origin.x
                grabY = mouse.yOnScreen - origin.y
                pressedId = pressed.id
            }
            // 循环退出时它必然非空；收进 `val` 是为了让后面的循环拿到一个非空类型。
            val draggingId = pressedId

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == draggingId } ?: break
                if (!change.pressed) break
                val mouse = event.awtEventOrNull ?: continue
                // 消费掉手势：否则它还会往下传给标题栏底下的东西。
                change.consume()
                window.setLocation(mouse.xOnScreen - grabX, mouse.yOnScreen - grabY)
            }
        }
    }
}
