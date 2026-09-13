package com.qcmian.clipper.feature.history.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.qcmian.clipper.core.domain.model.ClipItem
import com.qcmian.clipper.core.domain.model.isHexColor
import com.qcmian.clipper.core.settings.HighlightMatch
import com.qcmian.clipper.core.ui.KeyShortcut
import com.qcmian.clipper.core.ui.components.rememberImageBitmap
import com.qcmian.clipper.core.ui.hexToColor

/**
 *。一行要么是色块加标题，要么只有图片缩略图——
 * 绝不会同时出现标题与缩略图。
 */
@Composable
fun HistoryRow(
    item: ClipItem,
    ranges: List<IntRange>,
    shortcut: KeyShortcut?,
    isSelected: Boolean,
    highlight: HighlightMatch,
    showColorSwatch: Boolean,
 /** 偏好。 */
    maxImageHeight: Dp,
    /** 来源应用图标的 base64 PNG；图标关闭或未知时为 `null`。 */
    appIconBase64: String?,
    onClick: () -> Unit,
    onHover: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val appIcon = rememberImageBitmap(appIconBase64)
    // 色块要求标题以十六进制颜色开头，且必须带 `#` 前缀，
    // 以免普通的三个字母的单词被误认成十六进制颜色。
    val swatch = if (showColorSwatch && isHexColor(item.title)) hexToColor(item.title) else null
    val thumbnail = rememberImageBitmap(item.image)

    ListItemRow(
        isSelected = isSelected,
        shortcut = shortcut,
        onClick = onClick,
        onHover = onHover,
        appIcon = appIcon?.let {
            {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                )
            }
        },
        accessory = if (swatch != null) ({ ColorSwatch(swatch) }) else null,
    ) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .padding(vertical = 5.dp)
                    .heightIn(max = maxImageHeight)
                    .clip(RoundedCornerShape(2.dp)),
            )
        } else {
            RowTitle(highlightedTitle(item.title, ranges, highlight, isSelected, colors))
        }
    }
}

@Composable
private fun ColorSwatch(color: Color) {
    Box(
        Modifier
            .size(12.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

/** 对应 `HistoryItemDecorator.highlight(_:_:)`。 */
private fun highlightedTitle(
    title: String,
    ranges: List<IntRange>,
    highlight: HighlightMatch,
    isSelected: Boolean,
    colors: ColorScheme,
): AnnotatedString {
    if (ranges.isEmpty()) return AnnotatedString(title)

    val style = when (highlight) {
        HighlightMatch.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
        HighlightMatch.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
        HighlightMatch.UNDERLINE -> SpanStyle(textDecoration = TextDecoration.Underline)
        HighlightMatch.BACKGROUND -> SpanStyle(
            background = if (isSelected) colors.onPrimary.copy(alpha = 0.30f) else colors.primary.copy(alpha = 0.30f),
            color = if (isSelected) colors.onPrimary else colors.onSurface,
        )
    }

    // 对应 `HistoryItemDecorator.highlight`：带属性的标题上限为 500 个字符，
    // 因此超出部分的偏移会被丢弃。
    val visible = title.take(HIGHLIGHT_LENGTH)
    return buildAnnotatedString {
        append(visible)
        ranges.forEach { range ->
            val start = range.first.coerceIn(0, visible.length)
            val end = (range.last + 1).coerceIn(start, visible.length)
            if (start < end) addStyle(style, start, end)
        }
    }
}

/** 高亮标题的长度上限。 */
private const val HIGHLIGHT_LENGTH = 500
