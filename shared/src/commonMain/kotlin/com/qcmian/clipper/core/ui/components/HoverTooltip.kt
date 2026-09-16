package com.qcmian.clipper.core.ui.components

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** 悬停多久才弹出：与桌面系统 tooltip 的量级一致，鼠标扫过一排图标时不会一路闪。 */
private const val HoverTooltipDelayMillis = 500L

/**
 * 悬停提示：鼠标停在 [content] 上 [HoverTooltipDelayMillis] 后弹出气泡，移开即消失。
 *
 * 用于图标按钮——只有图标、没有文字的按钮，光看图形很难判断作用。纯文字控件不需要它。
 *
 * 基于 material3 的 `TooltipBox`（commonMain，各端都可用；Compose Desktop 专有的
 * `foundation.TooltipArea` 只能写在 jvmMain 里，commonMain 引用不到）。
 *
 * 刻意不用 `TooltipBox` 自带的悬停处理：它是**立即**弹出的（`BasicTooltip` 里对
 * `PointerEventType.Enter` 直接 `show()`），在一排按钮上扫过鼠标会挨个闪一下。这里关掉它
 * （`enableUserInput = false`），换成自己的悬停计时，行为与桌面系统一致。
 *
 * 气泡由 `Popup` 绘制，而 Compose Desktop 的 `Popup` 只能画在窗口内，因此紧贴窗口边缘的提示
 * 仍可能被裁掉一角；位置提供者会在锚点上下之间翻转、左右贴边，常规位置够用。
 *
 * @param text 提示文字。为空、或 [enabled] 为 `false` 时不做任何包裹，直接渲染 [content]。
 * @param positioning 气泡相对 [content] 的位置。默认在上方——图标按钮都很矮，上方总有空间。
 *   锚点本身很高时（例如全高的分隔条）必须换一个方向，否则气泡只能画到窗口外面去。
 */
@Composable
fun HoverTooltip(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    positioning: TooltipAnchorPosition = TooltipAnchorPosition.Above,
    content: @Composable () -> Unit,
) {
    if (!enabled || text.isEmpty()) {
        content()
        return
    }

    // isPersistent：悬停期间要一直留着。非持久态在 `show()` 内部带 1.5s 超时，鼠标不动也会自动消失。
    val state = rememberTooltipState(isPersistent = true)
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    LaunchedEffect(hovered) {
        if (hovered) {
            delay(HoverTooltipDelayMillis)
            // 挂起直到被 dismiss：鼠标移开（本效果随之取消）或另一个提示抢走互斥锁。
            state.show()
        } else {
            state.dismiss()
        }
    }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
            positioning = positioning,
            spacingBetweenTooltipAndAnchor = 6.dp,
        ),
        tooltip = { PlainTooltip { Text(text, fontSize = 12.sp) } },
        state = state,
        enableUserInput = false,
        modifier = modifier,
    ) {
        // `hoverable` 只观察指针进出，不消费事件，因此点击、拖拽都照旧传给 [content]。
        Box(Modifier.hoverable(interaction)) { content() }
    }
}
