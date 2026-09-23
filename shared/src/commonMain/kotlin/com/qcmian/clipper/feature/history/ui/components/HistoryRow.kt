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
import com.qcmian.clipper.core.domain.model.ClipImage
import com.qcmian.clipper.core.domain.model.ClipMeta
import com.qcmian.clipper.core.domain.model.isHexColor
import com.qcmian.clipper.core.domain.model.replacingUnsafeTitleScalars
import com.qcmian.clipper.core.domain.model.titleForDisplay
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
 * 判据只看 [ClipMeta.hasImage]，不看位图是否已经取回、能否解码：这一列随元数据一起来，
 * 因此「图片还在路上」「解码失败」都不会让行高在加载前后变来变去——那会让滑块长度、
 * 拖动落点与真实内容一起漂移。
 */
internal fun historyRowHeight(meta: ClipMeta, imageMaxHeight: Dp): Dp =
    if (meta.hasImage) imageMaxHeight + ImageRowPadding else Popup.itemHeight

/**
 *。一行要么是色块加标题，要么只有图片缩略图——
 * 绝不会同时出现标题与缩略图。
 *
 * 行高是**确定性**的，由 [historyRowHeight] 给出。窗口高度与滚动条都按同一函数推算整份内容的
 * 高度，因此两处都必须是固定高度（见 `ListItemRow` 与下方 `ContentScale.Inside`）。
 *
 * @param image 该条目的图片。字节在载荷里，由界面在行进入组合时按需取回（见
 *   `ClipboardUiAction.RequestImage`），因此它可能是 `null`——此时这一行会显示标题而不是缩略图，
 *   但行高仍然按图片行算（判据见 [historyRowHeight]）。
 * @param isSelected 在多选选中集里；整批候选都会被回车激活。
 * @param isCursor 光标所在的那一条，预览面板只跟它走（见 `ListItemRow`）。
 */
@Composable
fun HistoryRow(
    meta: ClipMeta,
    image: ClipImage?,
    ranges: List<IntRange>,
    shortcuts: List<KeyShortcut>,
    flags: ModifierFlags,
    isSelected: Boolean,
    isCursor: Boolean,
    highlight: HighlightMatch,
    showColorSwatch: Boolean,
    /** 标题里的空格 / 换行 / 制表符是否显示为 `·` / `⏎` / `⇥`。 */
    showSpecialSymbols: Boolean,
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
    val swatch = if (showColorSwatch && isHexColor(meta.title)) hexToColor(meta.title) else null
    val thumbnail = rememberImageBitmap(image)

    ListItemRow(
        isSelected = isSelected,
        isCursor = isCursor,
        shortcuts = shortcuts,
        flags = flags,
        // 行高与 `HistoryScreen` 推算窗口高度、滚动条内容总高时读的是同一个函数，因此不可能分叉。
        //
        // 判据只看 `meta.hasImage`（见 [historyRowHeight]），**不能**用 `thumbnail`：图片还没取回
        // 或解码失败时行内会退回标题文本，但行高仍留出图片槽位，只是多一段留白；若改用
        // `thumbnail`，那一行的真实高度就与滚动条前缀和差出一截，滑块长度、位置与拖动落点
        // 会一起漂移。
        height = historyRowHeight(meta, maxImageHeight),
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
            RowTitle(
                highlightedTitle(
                    displayTitle(meta, showSpecialSymbols),
                    ranges,
                    highlight,
                    isSelected,
                    colors,
                ),
            )
        }
    }
}

/**
 * 列表里那一行要渲染的标题。
 *
 * 库里存的是**原文**（见 `ClipItem.toMeta`），这里才做显示用的变形：
 *
 * - 图片文字识别的结果带真换行（「复制图片文字」要还原原文），单行展示时只压平成空格，
 *   不套 `·` / `⏎` / `⇥` ——那些符号会被当成识别内容的一部分。
 * - 其余标题按「特殊符号」偏好替换首尾空格与换行 / 制表符。
 *
 * `\n` 一律换成等长的空格，因此不会让搜索高亮的偏移错位——这里的每一步变换都**必须等长**
 * （`titleForDisplay` 与 `replacingUnsafeTitleScalars` 都是逐字符替换），删字符会让区间整体错位。
 */
private fun displayTitle(meta: ClipMeta, showSpecialSymbols: Boolean): String =
    if (meta.hasRecognizedText) {
        meta.title.replacingUnsafeTitleScalars().replace('\n', ' ')
    } else {
        meta.title.titleForDisplay(showSpecialSymbols).replace('\n', ' ')
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
        // 背景高亮要盖住整段文字，不能靠半透明叠色：行的底色本身就分「选中（primary 蓝）」与
        // 「未选中（surface）」两种，同一个 alpha 在两种底色上叠出两种深浅，其中一种必然偏淡、看不清。
        // 因此用不透明的成对配色：未选中「蓝底白字」，选中行反过来「白底蓝字」——两边都是实色对撞，
        // 且不依赖行的底色，深浅主题下都一样清楚。再加粗，让命中片段更跳。
        HighlightMatch.BACKGROUND -> SpanStyle(
            background = if (isSelected) colors.onPrimary else colors.primary,
            color = if (isSelected) colors.primary else colors.onPrimary,
            fontWeight = FontWeight.Bold,
        )
    }

    // 带属性的标题上限为 500 个字符，
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
