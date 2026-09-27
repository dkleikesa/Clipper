package com.qcmian.clipper.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.ui.icons.ClipperIcon
import com.qcmian.clipper.core.ui.icons.ClipperIconKind

/**
 * 无边框窗口自绘的标题栏：标题 + 关闭按钮。
 *
 * 应用的每个窗口都是无边框 + 透明的（见 `ClipperWindow` / `ClipperSettingsWindow`）：系统标题栏
 * 的底色由 AppKit 决定，既不跟主题走、也和本应用的配色对不上。于是标题、关闭按钮与可拖拽区
 * 都在我们手里，也就**必须**自己画一条。
 *
 * 做成共享组件而不是各窗口各抄一份：字号、按钮尺寸、左右内边距、拖拽区范围这些细节一旦分叉，
 * 几个窗口就会长得不像一家人。
 *
 * @param dragModifier 「按住拖动窗口」的手势，由宿主注入（共享代码里没有 `java.awt` 的窗口概念）。
 *   它只挂在标题那一段的 [Box] 上，**不覆盖关闭按钮**：按钮若落在拖拽区里，小幅移动就会被判成
 *   拖动，于是点不中。
 * @param closeTooltip 关闭按钮的悬停提示。各窗口含义不同（关窗 / 退出应用），因此由调用方给。
 */
@Composable
fun ClipperTitleBar(
    title: String,
    onClose: () -> Unit,
    closeTooltip: String,
    modifier: Modifier = Modifier,
    dragModifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TitleBarHeight)
            .background(colors.surface)
            .padding(start = 16.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .then(dragModifier),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = colors.onSurface,
            )
        }
        HoverTooltip(closeTooltip) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(colors.surfaceVariant.copy(alpha = 0.5f))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                ClipperIcon(ClipperIconKind.CLEAR, size = 13.dp, tint = colors.onSurfaceVariant)
            }
        }
    }
}

/** 标题栏高度：与 macOS 常规工具栏一致的量级，够放下 28dp 的关闭按钮又不占地方。 */
private val TitleBarHeight = 40.dp
