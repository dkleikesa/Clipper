package com.qcmian.clipper.devtools.tools.regex

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

/** 「含义」列的宽度：最长的几条（`任意次（含 0 次）`、`引用前面的组`）也放得下。 */
private val MeaningWidth = 96.dp

/** 「写法」列的宽度：最长的一条（`(?<name>...)`）也放得下，各行因此左缘对齐。 */
private val SpellingWidth = 100.dp

/**
 * 「速查」：正则的常用写法、四个开关的意思、替换模板的写法，点一下接进对应的输入框。
 *
 * 与时间戳工具那张速查同一副长相与同一套交互（见 `TimestampSyntaxPanel`），三处差别都是内容决定的：
 *
 *  - **开关那一组不可点**：它列的是布尔量，不是能拼进模式里的串——点了没法插。那一组的写法列因此
 *    不给悬停反馈、光标也不变手型，「这一格能按」的信号只出现在真能按的行上。
 *  - **每组自己说清落到哪**（组标题里就写着「接进「模式」」）：一张表管两个输入框，不像时间戳那样
 *    一页只管一个，所以去处得写在看得见的地方。
 *  - **入口有两个、面板只有一个**：模式行与替换为行各挂一枚「速查」，开的是同一张表——不然用户在
 *    替换页想查模板写法还得先回匹配页去点。
 *
 * 顶部那行提示固定在卡片里、不随内容滚：它说的是「点了会落到哪里」。
 *
 * @param groups 显示哪几组。由调用方按**当前看得见哪几个框**决定——在匹配页把模板那一组也画出来，
 *   用户点了「替换为」那个还没画出来的框，什么都不会发生。
 */
@Composable
internal fun RegexSyntaxPanel(
    groups: List<RegexSyntaxGroup>,
    onPick: (RegexSyntaxField, String) -> Unit,
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
            text = "点一个写法，接进对应的输入框末尾",
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
                HeaderRow()
                groups.forEach { group ->
                    GroupTitle(group.title)
                    group.rows.forEach { row -> SyntaxRow(row, group.target, onPick) }
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
private fun HeaderRow() {
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

/** 一组的小标题：它是什么、点了落到哪，都在这句里。 */
@Composable
private fun GroupTitle(title: String) {
    Text(
        text = title,
        fontSize = 10.sp,
        color = MaterialTheme.hintColor,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

/** 一行：含义 / 写法 / 示例。整组可点时才把写法那一格画成可点的样子。 */
@Composable
private fun SyntaxRow(
    row: RegexSyntaxRow,
    target: RegexSyntaxField?,
    onPick: (RegexSyntaxField, String) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = row.meaning,
            fontSize = 12.sp,
            color = colors.onSurface,
            modifier = Modifier.width(MeaningWidth),
        )
        if (target == null) {
            // 不可点的那一组（开关）：写法只是标出它的单字母，给同一种等宽字体就够了，
            // 不给悬停、不给手型——那两样都是「这里能按」的信号。
            Text(
                text = row.spelling,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = colors.onSurface,
                modifier = Modifier.width(SpellingWidth),
            )
        } else {
            SpellingCell(
                text = row.spelling,
                width = SpellingWidth,
                color = colors.onSurface,
                onClick = { onPick(target, row.spelling) },
            )
        }
        Text(
            text = row.example,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.hintColor,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 可点的写法格：点一下把这个写法接进输入框。
 *
 * 悬停浮出主色底、光标变手型：表里其余格子都不可点，不给点反馈的话没人知道这里能按。
 */
@Composable
private fun SpellingCell(text: String, width: Dp, color: Color, onClick: () -> Unit) {
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
            .clickable(interactionSource = interaction, indication = null) { onClick() },
    ) {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = if (hovered) colors.primary else color,
        )
    }
}
