package com.qcmian.clipper.feature.history.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import kotlin.math.min
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
import com.qcmian.clipper.core.ui.ModifierFlags
import com.qcmian.clipper.core.ui.Popup
import com.qcmian.clipper.core.ui.components.rememberImageBitmap
import com.qcmian.clipper.core.ui.hexToColor

/**
 * 缩略图在 `maxImageHeight` 之上额外增加的垂直内边距（上下各一半）。
 *
 * 行高（`HistoryRow` 传给 `ListItemRow` 的 `height`）与图片槽位都由它推导，
 * `HistoryScreen` 推算窗口高度与滚动条时也读同一个常量，三处必须一致。
 */
internal val ImageRowPadding = 10.dp

/**
 * 一条记录的行高：文本行为 [Popup.itemHeight]，图片行为 [imageMaxHeight] 加 [ImageRowPadding]。
 *
 * 这是行高的**唯一依据**：列表渲染（[HistoryRow]）、窗口高度与滚动条的内容总高
 * （`HistoryScreen` 的 `rowHeight`）都调用它，三处因此不可能各自推算出一套数来。
 *
 * 判定只看 `item.image` 是否为 `null`，不看位图能否解码出来：位图解码失败时行内改显示
 * 标题，但内容总高仍按图片行推算——两者一旦用不同的判据，滑块长度与拖拽落点就会和真实
 * 内容对不上（滑块很短、一拖就跳到底）。
 */
internal fun historyRowHeight(item: ClipItem, imageMaxHeight: Dp): Dp =
    if (item.image != null) imageMaxHeight + ImageRowPadding else Popup.itemHeight

/**
 *。一行要么是色块加标题，要么只有图片缩略图——
 * 绝不会同时出现标题与缩略图。
 *
 * 行高是**确定性**的，由 [historyRowHeight] 给出。窗口高度与滚动条都按同一函数推算整份内容的
 * 高度，因此两处都必须是固定高度（见 `ListItemRow` 与下方 `ContentScale.Inside`）。
 */
@Composable
fun HistoryRow(
    item: ClipItem,
    ranges: List<IntRange>,
    shortcuts: List<KeyShortcut>,
    flags: ModifierFlags,
    isSelected: Boolean,
    highlight: HighlightMatch,
    showColorSwatch: Boolean,
    /** 图片行的高度上限，也是图片槽位的固定高度。 */
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
        shortcuts = shortcuts,
        flags = flags,
        // 行高与 `HistoryScreen` 推算窗口高度、滚动条内容总高时读的是同一个函数，因此不可能分叉。
        //
        // 判据只看 `item.image`（见 [historyRowHeight]），**不能**用 `thumbnail`：解码失败时
        // 行内会退回标题文本，但行高仍留出图片槽位，只是多一段留白；若改用 `thumbnail`，
        // 那一行的真实高度就与滚动条前缀和差出一截，滑块长度、位置与拖动落点会一起漂移。
        height = historyRowHeight(item, maxImageHeight),
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
            // 图片行的槽位高度必须恒定，否则「窗口高度」与「滚动条」都只能靠估算：
            // 它们用 `imageMaxHeight + 10dp` 推算每条的高度，小图按原始尺寸渲染时
            // 实际只有「真实高 + 10dp」，估算出来的内容总高会比真实值大一截。
            //
            // `Inside`：源比槽位大就等比缩小（完整可见、不裁切），比槽位小就保持原始
            // 尺寸居中——多出来的部分就是留白。`None` 不行：它完全不缩放，大图会被裁掉。
            Image(
                bitmap = thumbnail,
                contentDescription = null,
                contentScale = ContentScale.Inside,
                alignment = Alignment.Center,
                modifier = Modifier
                    .padding(vertical = ImageRowPadding / 2)
                    .height(maxImageHeight)
                    .clip(RoundedCornerShape(2.dp)),
            )
        } else {
            // 图片文字识别的标题是识别原文、带真换行（复制时要还原原文），单行展示时压平。
            // `\n` 换成等长的空格，因此不会让搜索高亮的偏移错位；文本条目的标题本来就没有换行。
            RowTitle(highlightedTitle(item.title.replace('\n', ' '), ranges, highlight, isSelected, colors))
        }
    }
}

@Composable
private fun ColorSwatch(color: Color) {
    // 半透明颜色直接画在行背景上几乎看不出透明度，
    // 因此先铺一层经典的白灰棋盘格，再把颜色（可能带 alpha）叠上去。
    val checkerLight = Color.White
    val checkerDark = Color(0xFFCCCCCC)
    Box(
        Modifier
            .size(12.dp)
            .clip(RoundedCornerShape(2.dp))
            .drawBehind {
                val step = 3.dp.toPx()
                var y = 0f
                var row = 0
                while (y < size.height) {
                    var x = 0f
                    var column = 0
                    while (x < size.width) {
                        drawRect(
                            color = if ((row + column) % 2 == 0) checkerLight else checkerDark,
                            topLeft = Offset(x, y),
                            size = Size(min(step, size.width - x), min(step, size.height - y)),
                        )
                        x += step
                        column++
                    }
                    y += step
                    row++
                }
            }
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
