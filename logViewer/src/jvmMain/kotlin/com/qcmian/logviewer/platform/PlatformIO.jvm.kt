package com.qcmian.logviewer.platform

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.io.buffered

actual fun openPathSource(path: String): OpenedLogSource? = runCatching {
    val p = kotlinx.io.files.Path(path)
    val size = kotlinx.io.files.SystemFileSystem.metadataOrNull(p)?.size ?: return null
    OpenedLogSource(
        source = kotlinx.io.files.SystemFileSystem.source(p).buffered(),
        sizeBytes = size,
    )
}.getOrNull()

/** JVM 时间戳 */
actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun androidx.compose.ui.Modifier.nsResizeCursor(): androidx.compose.ui.Modifier =
    this.pointerHoverIcon(
        androidx.compose.ui.input.pointer.PointerIcon(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.N_RESIZE_CURSOR)),
    )

/** 桌面右键：Initial pass 观察按下即触发，携带相对组件位置（菜单跟随鼠标） */
actual fun Modifier.secondaryClick(onSecondary: (androidx.compose.ui.geometry.Offset) -> Unit): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.buttons.isSecondaryPressed) {
                onSecondary(event.changes.firstOrNull()?.position ?: androidx.compose.ui.geometry.Offset.Zero)
            }
        }
    }

/** 桌面拖动多选：主键按下拖过 slop 后，按下行 → 当前行区间选中（Initial pass，不干扰单击/右键） */
actual fun Modifier.dragSelect(
    state: com.qcmian.logviewer.state.ViewerState,
    listState: androidx.compose.foundation.lazy.LazyListState,
): Modifier = pointerInput(state) {
    awaitEachGesture {
        val first = awaitPointerEvent(PointerEventPass.Initial)
        if (!first.buttons.isPrimaryPressed) return@awaitEachGesture
        // 修饰键按住时走行点击语义（shift 连选/ctrl 点选），不进入拖选
        if (com.qcmian.logviewer.platform.KeyboardState.shift || com.qcmian.logviewer.platform.KeyboardState.ctrl) return@awaitEachGesture
        fun posAtY(y: Float): Int {
            val infos = listState.layoutInfo.visibleItemsInfo
            if (infos.isEmpty()) return -1
            val hit = infos.firstOrNull { y >= it.offset && y < it.offset + it.size }
                ?: if (y < infos.first().offset) infos.first() else infos.last()
            return hit.index
        }
        val downY = first.changes.firstOrNull()?.position?.y ?: return@awaitEachGesture
        val anchor = posAtY(downY)
        if (anchor < 0) return@awaitEachGesture
        var dragging = false
        while (true) {
            val e = awaitPointerEvent(PointerEventPass.Initial)
            val change = e.changes.firstOrNull() ?: break
            if (!change.pressed) break
            val y = change.position.y
            if (!dragging && kotlin.math.abs(y - downY) > viewConfiguration.touchSlop) {
                dragging = true
            }
            if (dragging) {
                val cur = posAtY(y)
                if (cur >= 0) state.selectRange(anchor, cur)
            }
        }
    }
}
