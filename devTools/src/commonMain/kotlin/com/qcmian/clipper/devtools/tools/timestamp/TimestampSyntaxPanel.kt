package com.qcmian.clipper.devtools.tools.timestamp

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.qcmian.clipper.core.ui.components.VerticalScrollbar
import com.qcmian.clipper.core.ui.components.VerticalScrollbarWidth
import com.qcmian.clipper.core.ui.theme.hintColor
import com.qcmian.clipper.devtools.ui.components.DevToolScrollbarGap
import com.qcmian.clipper.devtools.ui.components.DevToolSectionDivider

/** 「含义」列的宽度：最长的一条（`偏移（带冒号）`）也放得下，各行因此左缘对齐。 */
private val MeaningWidth = 130.dp

/** 写法列的宽度：最长的写法（`yyyy` / `ZZZZZ`）与它右边的示例之间留出间距。 */
private val SpellingWidth = 78.dp

/**
 * 「占位符速查」：模板支持的全部写法，点一下接进格式框。
 *
 * 内容来自 [TimestampSyntax]，这里只管画。顶部那行提示固定在卡片里不随内容滚：它说的是「点了会
 * 落到哪儿」，滚走之后用户就只能猜了。
 *
 * 表头不能省：这一列全是等宽的符号，不写清楚它是「写法」，第一次看的人会当成示例的一种。
 *
 * @param target 点中的写法接进哪个框——只用在提示语里，界面上要写清楚，否则点了半天不知道落到哪。
 * @param onPick 点中一个写法。
 */
@Composable
internal fun TimestampSyntaxPanel(
    target: String,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(6.dp)
    val scroll = rememberScrollState()

    Column(
        modifier = modifier
            .clip(shape)
            .background(colors.onSurface.copy(alpha = 0.04f))
            .border(1.dp, colors.outline.copy(alpha = 0.6f), shape),
    ) {
        Text(
            text = "点一个写法，接进「$target」的末尾",
            fontSize = 11.sp,
            color = MaterialTheme.hintColor,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp, bottom = 6.dp),
        )
        DevToolSectionDivider()

        Box(Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll)
                    // 右端多让一条滚动条的宽度再加一点间隙，免得最长的那一行被滑块压住。
                    .padding(
                        start = 12.dp,
                        top = 6.dp,
                        bottom = 8.dp,
                        end = 12.dp + VerticalScrollbarWidth + DevToolScrollbarGap,
                    ),
            ) {
                FieldHeaderRow()
                TimestampSyntax.groups.forEach { group ->
                    GroupTitle(group.title)
                    group.fields.forEach { field -> FieldRow(field, onPick) }
                }
            }
            VerticalScrollbar(
                scrollState = scroll,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}

/** 表头：三列各是什么（含义 / 写法 / 示例），只在最上面写一次。 */
@Composable
private fun FieldHeaderRow() {
    Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 4.dp)) {
        HeaderCell("含义", Modifier.width(MeaningWidth))
        HeaderCell("写法", Modifier.width(SpellingWidth))
        HeaderCell("示例", Modifier.weight(1f))
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier) {
    Text(text = text, fontSize = 10.sp, color = MaterialTheme.hintColor, modifier = modifier)
}

/** 一组字段的小标题：比正文浅一档，只是把「年月日」与「时分秒」之间划出层次。 */
@Composable
private fun GroupTitle(title: String) {
    Text(
        text = title,
        fontSize = 10.sp,
        color = MaterialTheme.hintColor,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

/** 一行字段：含义 / 写法 / 示例；写法那一格可点。 */
@Composable
private fun FieldRow(field: TimestampSyntaxField, onPick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = field.meaning,
            fontSize = 12.sp,
            color = colors.onSurface,
            modifier = Modifier.width(MeaningWidth),
        )
        SpellingCell(field.spelling, SpellingWidth, colors.onSurface, onPick)
        Text(
            text = field.example,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.hintColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 可点的写法格：点一下把这个写法接进格式框。
 *
 * 悬停浮出主色底、光标变手型：表里其余格子都不可点，不给点反馈的话没人知道这里能按。
 */
@Composable
private fun SpellingCell(text: String, width: Dp, color: Color, onClick: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .width(width)
            .clip(RoundedCornerShape(4.dp))
            .background(if (hovered) colors.primary.copy(alpha = 0.12f) else Color.Transparent)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(interactionSource = interaction, indication = null) { onClick(text) },
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = if (hovered) colors.primary else color,
        )
    }
}
