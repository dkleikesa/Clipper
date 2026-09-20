package com.qcmian.clipper.feature.history.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.ui.KeyShortcut
import com.qcmian.clipper.core.ui.ModifierFlags
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.core.ui.visibleShortcut

/**
 * 历史列表与页脚共用的一种紧凑行。它只显示前置配件、
 * 标题（或缩略图）以及快捷键提示——没有时间戳、置顶标记或删除按钮。
 */
@Composable
fun ListItemRow(
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    shortcuts: List<KeyShortcut> = emptyList(),
    flags: ModifierFlags = ModifierFlags(),
    /**
     * 行的**固定**高度。默认是文本行高 [Popup.itemHeight]；图片行必须把
     * `maxImageHeight + ImageRowPadding` 传进来，否则这里会把它压回文本行高，
     * 「图片最大高度」设置就永远不生效（见 `HistoryRow`）。
     */
    height: Dp = Popup.itemHeight,
    /** 来源应用图标，在 `showApplicationIcons` 开启时显示。 */
    appIcon: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onHover: (() -> Unit)? = null,
    accessory: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val contentColor = if (isSelected) colors.onPrimary else colors.onSurface
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val clickModifier = onClick?.let { Modifier.clickable(onClick = it) } ?: Modifier

    LaunchedEffect(hovered) {
        if (hovered) onHover?.invoke()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            // 行高必须**恒定**，不能只是下限：窗口高度与滚动条都按「文本行 = `Popup.itemHeight`、
            // 图片行 = `imageMaxHeight + ImageRowPadding`」推算整份内容的高度
            // （见 `HistoryScreen` 的 `rowHeight`），任何让行长高的内容都会让那两处算少——
            // 前置图标的上下内边距就曾经把行撑到 25dp。需要居中的配件一律靠
            // `verticalAlignment` 解决，不要靠内边距撑开行。
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(if (isSelected) colors.primary.copy(alpha = 0.8f) else Color.Transparent)
            .hoverable(interactionSource)
            .then(clickModifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (appIcon != null) {
            Box(Modifier.padding(start = 4.dp)) { appIcon() }
            Spacer(Modifier.width(5.dp))
        } else {
            Spacer(Modifier.width(10.dp))
        }

        if (accessory != null) {
            accessory()
            Spacer(Modifier.width(5.dp))
        }

        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Box(Modifier.weight(1f)) { content() }
            Spacer(Modifier.width(5.dp))
            ShortcutView(shortcuts, flags)
            Spacer(Modifier.width(10.dp))
        }
    }
}

/**
 * 行的标题，对应 `ListItemTitleView`（单行、中间截断）。
 *
 * 行高被固定下来，使该行保持 `Popup.itemHeight`，而不会继承 Material 默认高得多的行框。
 */
@Composable
fun RowTitle(text: AnnotatedString, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 13.sp,
        lineHeight = 16.sp,
        color = LocalContentColor.current,
        maxLines = 1,
        overflow = TextOverflow.MiddleEllipsis,
        modifier = modifier,
    )
}

@Composable
fun RowTitle(text: String, modifier: Modifier = Modifier) {
    RowTitle(AnnotatedString(text), modifier)
}

/** 按 `KeyboardShortcutView` 的方式渲染 `⌥⌘⌫`。 */
@Composable
private fun ShortcutView(shortcuts: List<KeyShortcut>, flags: ModifierFlags) {
    // 把「当前修饰键」的读取圈在这个小组件里，并用 derivedStateOf 只在匹配到的变体真正
    // 变化时才触发重组：按住 / 松开修饰键本身不再让整行（缩略图、标题）跟着重组。
    val shortcut by remember(shortcuts) {
        derivedStateOf { visibleShortcut(shortcuts, flags) }
    }
    val resolved = shortcut ?: return
    val color = LocalContentColor.current
    Row(
        modifier = Modifier.alpha(0.7f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (resolved.modifiers.isNotEmpty()) {
            Text(
                text = resolved.modifiers,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                color = color,
                maxLines = 1,
            )
        }
        Text(
            text = resolved.character,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = color,
            maxLines = 1,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(12.dp),
        )
    }
}
